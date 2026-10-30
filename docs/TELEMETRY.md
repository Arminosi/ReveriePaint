# 崩溃遥测 (Crash Telemetry) 设计与数据契约

> 状态: v1 代码完成(端点未部署) · 最后更新 2026-10-30
> 相关文档: `docs/PRIVACY.md`(面向用户的隐私说明) · `AGENTS.md` §4(架构铁律) §12(安全与机密)
>
> 已完成: 客户端 `core/telemetry/` 全链路、设置开关、首启告知条、崩溃弹窗手动上报、
> Worker + D1 + 本地 dev-server、native 帧偏移改造、20 项单测、契约端到端验证。
> 待办: 部署 Worker 与 D1 并注入 `TELEMETRY_ENDPOINT`/`TELEMETRY_KEY`; `-PbuildNative` 真机回归 C++ 帧格式;
> 符号化流水线(见 §11)。

## 1. 目标与非目标

**目标**: 让"用户崩了"这件事在维护者这边有信息。具体只回答三个问题:

1. 崩在哪个功能上(native 崩溃尤其需要,因为信号段错误在 Kotlin 侧完全不可见)
2. 崩在什么设备/系统/版本/ABI 组合上(机型矩阵是当前最缺的一块)
3. 同一类崩溃有多少人在撞(去重签名 + 聚合计数,决定修复优先级)

**非目标**:

- 不做行为统计、漏斗、留存、DAU。这些一律不采(见 §4 禁止清单)
- 不做 A/B、不做远程配置、不做灰度开关下发
- 不上传任何画作内容、图像、缩略图、工程文件

## 2. 现状: 采集层已经存在,缺的只是"出口"

| 已有能力 | 位置 |
|---|---|
| Java 未捕获异常 → 完整报告(设备/内存/画布状态/面包屑/堆栈/logcat) | `core/CrashHandler.kt` `buildCrashReport` |
| Native `SIGSEGV/SIGABRT/SIGBUS` → 备用栈 + 寄存器 + `dladdr` 回溯 | `cpp/ReverieCrashHandler.cpp` |
| 面包屑环形缓冲(Java 100 条 / Native 静态数组,双写) | `core/Breadcrumbs.kt` + `addNativeBreadcrumb` |
| logcat 凭据脱敏 | `core/DiagnosticsManager.kt` `sanitize` |
| 日志轮转、复制/导出/分享、下次启动弹窗 | `core/CrashHandler.kt` + `ui/dialog/CrashReportDialog.kt` |
| 崩溃后抢救草稿、前缓冲按次安装隔离 | `emergencySaveOnCrash` / `evaluateFrontBufferQuarantine` |

所以 v1 **不重造捕获层**,只加一条从 `crash_logs/` 到一个 HTTP 端点的单向通路。

### 2.1 必须先补的两个缺口

**(a) native 帧当前不可符号化**

`ReverieCrashHandler.cpp` 输出的是 `pc` 绝对地址 + `dladdr` 的 `dli_sname`。stripped `.so` 里绝大多数帧没有符号,只剩一个被 ASLR 随机化的绝对地址,同一份代码每次崩溃都不一样,事后无法 `addr2line`。修复:每一帧都额外附带 `模块基址相对偏移`(`pc - dli_fbase`),符号名退化成可选尾注:

```
#03 pc 0x7f8a1c2b3c  +0x1a2b3c  /data/app/…/lib/arm64/libreverie_jni.so
#04 pc 0x7f8a9c2b90  +0x2f19c  /data/app/…/lib/arm64/libQt6Core_arm64-v8a.so  (QString::fromLatin1+20)
```

解析侧对新旧两种格式都兼容(`TelemetryPayload.NATIVE_FRAME_NEW/NATIVE_FRAME_OLD`),老日志里 `(符号+偏移)` 这种符号相对偏移不会被误当成模块偏移。

**验证方式(不需要真机)**:`ReverieCrashHandler.cpp` 只有 `<android/log.h>` 一个 Android 依赖,给它一个空桩就能编成本地可执行文件,在宿主机上制造真实段错误:

```bash
# 见 app/src/test/resources/telemetry/crash_native_host.log 这份真实产物
mkdir -p /tmp/ch/inc/android && printf '#pragma once\n#define ANDROID_LOG_FATAL 7\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int,const char*,const char*,...){return 0;}\n' > /tmp/ch/inc/android/log.h
g++ -std=c++17 -g -O0 -I/tmp/ch/inc app/src/main/cpp/ReverieCrashHandler.cpp 你的harness.cpp -o /tmp/ch/crashhost -ldl
/tmp/ch/crashhost            # 会 SIGSEGV 并写日志
addr2line -f -C -e /tmp/ch/crashhost 0x<帧偏移>
```

`addr2line` 能还出 `render_frame(int)` 即证明"模块+偏移"真的可符号化;两次运行绝对 `pc` 不同而偏移一致,即证明 ASLR 不再是障碍。

**(c) native 面包屑补上时间戳**

原先 native 侧写的是 `- Brush: Load preset [3]: <笔刷名>`,没有时间,无法与 logcat / Java 面包屑对齐时间线。现在写成 `- [14:40:54] Brush: …`(UTC,信号处理器里不做时区换算)。解析侧三种格式都吃:Java 的 `[HH:MM:SS.SSS] [Cat] msg`、新的 `[HH:MM:SS] Cat: msg`、旧的裸 `Cat: msg`。

**(b) 面包屑里混着用户内容**

现有 15 个调用点中 5 个插值了用户数据(笔刷预设名、画作名)。本地留存无妨(是用户自己点分享的),但**上传路径不得复用 `dump()` 的原文**,必须走 §5.3 的白名单规约。

## 3. 数据流

```
崩溃发生
  ├─ Java 崩溃   → CrashHandler 写 crash_java_<ts>.log
  └─ Native 信号 → ReverieCrashHandler 写 crash_native_<ts>.log
                        ↓ (下次启动, 或用户在崩溃弹窗点"上报这条")
TelemetryReporter.scan()  扫描 crash_logs/,跳过已上报水位
                        ↓
TelemetryPayload.build()  解析 → 白名单规约 → 脱敏 → 组装 JSON
                        ↓                        └─ CrashSignature.sign()
gzip → POST {TELEMETRY_ENDPOINT}/t/v1/crash
                        ↓
Cloudflare Worker         校验 key / 配额 / 去重 → D1
                        ↓
GET /t/v1/stats?days=7    按签名聚合的简表
```

ANR 走另一条支路:启动时读 `ActivityManager.getHistoricalProcessExitReasons()`(API 30+),取 `REASON_ANR` 且晚于上次水位的条目,`getTraceInputStream()` 作为堆栈。

## 4. 禁止清单(永不采集)

以下字段一旦出现在上报体里即为缺陷,应在代码评审中直接打回:

- 任何文件路径与文件名(`.revp` / `.png` / `.kra` / 导出目录),日志中一律规约为 `*.<ext>`
- 任何用户自命名的字符串: 笔刷预设名、图层名、画集名、工程名
- 参考图、缩略图、画布像素、选区、图层像素
- WebDAV 服务器地址、用户名、密码、Token、Cookie、`Authorization` 头
- 爱发电 / Mirror酱 / GitHub 相关凭据
- `ANDROID_ID`、IMEI、序列号、MAC、手机号、邮箱
- 精确位置(不请求任何定位权限,也不从系统服务读)
- 剪贴板内容、输入法内容、已安装应用列表
- 客户端 IP(服务端不落库;见 §7.3)

## 5. 数据契约 v1

### 5.1 外层信封

```jsonc
{
  "v": 1,                                  // 契约版本, 不兼容时 +1
  "kind": "java_crash",                    // java_crash | native_crash | anr
  "install": "9f2c1a3e-…",                 // 随机 UUID, 首次启动生成, 存 prefs
  "at": 1761700000,                        // 崩溃发生时间(秒)
  "sig": "b41c…",                          // 去重签名, 见 §6
  "app": { "ver": "1.6.1", "code": 48, "abi": "arm64-v8a" },
  "dev": { … },                            // §5.2
  "doc": { … },                            // §5.3
  "crumbs": [ … ],                         // §5.4
  "stack": "…",                            // 崩溃线程堆栈(已脱敏)
  "logcat": "…",                           // ≤100 行, 已脱敏
  "native": { … }                          // 仅 native_crash
}
```

### 5.2 `dev` 字段

| 字段 | 来源 | 用途 |
|---|---|---|
| `mfr` / `model` / `dev` | `Build.MANUFACTURER/MODEL/DEVICE` | 机型矩阵,判断某 ROM/机型是否共性问题 |
| `sdk` / `rel` | `Build.VERSION.SDK_INT/RELEASE` | 版本差异 |
| `fp` | `Build.FINGERPRINT` | 区分同型号不同固件 |
| `ram_mb` | `ActivityManager.MemoryInfo.totalMem` | 低内存机型崩溃归因 |
| `harmony` | 现有 `detectHarmonyOsVersion()` | 鸿蒙/EMUI 兼容性 |

### 5.3 `doc` 字段(应用状态快照)

| 字段 | 来源 | 用途 |
|---|---|---|
| `w` / `h` | 当前文档宽高 | 大画布内存压力 |
| `layers` | 图层数 | 复杂度归因 |
| `page` | `Page` 枚举名 | 崩在哪个页面 |
| `tool` | 当前工具 id | 崩在哪个工具 |
| `stylus` | 手写笔型号标识 | 手写笔适配问题归因 |

> 这几项是"数值型上下文",不含任何用户内容。取舍记录: 曾考虑完全不上报数值以求最保守,但那会让绝大多数崩溃退化为"不知道在什么规模上崩的",归因效率减半,故保留并在 `docs/PRIVACY.md` 中逐条列明。
>
> 已知限制: `native_crash` 的 `doc` 目前是全 0 —— 原生信号处理器的状态快照在启动时一次性灌入(`seedNativeStateSnapshot`),里面只有设备/系统/前缓冲开关,没有画布尺寸与图层数。要补这段需要在文档变化时同步 JNI 状态,属于下一步工作。

### 5.4 `crumbs`(面包屑白名单)

最多保留**末 32 条**,每条只有 `{ "t": 相对毫秒偏移, "c": 类别, "e": 动作标签 }`,**不含消息尾巴**。

规约方式: 取 `category` 与人可读消息的"动词片段"做映射,命中下表才通过,未命中的调用点一律记 `{"c": <category>, "e": "other"}`。

| 原调用 | 上报的 `c/e` |
|---|---|
| `Brush` / `Load preset [..]: <名>` | `Brush` / `load_preset` |
| `Brush` / `Restore preset [..]: <名>` | `Brush` / `restore_preset` |
| `Save` / `saveProject ..` | `Save` / `save_project` |
| `IO` / `loadProject ..` | `IO` / `load_project` |
| `Layer` / `Add layer: <名>` | `Layer` / `add` |
| `Layer` / `Remove layer: <i>` | `Layer` / `remove` |
| `Layer` / `Set current layer: <i>` | `Layer` / `select` |
| `Tool` / `Switch tool to: <id>` | `Tool` / `switch` |
| `History` / `Undo` / `Redo` | `History` / `undo` `redo` |
| `Navigation` / `Current page: <page>` | `Navigation` / `page` |
| `FrontBuffer` / `quarantined …` | `FrontBuffer` / `quarantined` |

> 这张表是**白名单**:新增 `Breadcrumbs.record` 若没在表里登记,上报侧只会得到 `other`,不会静默带出用户数据。

### 5.5 `native` 字段

```jsonc
{ "signal": "SIGSEGV", "code": 1, "fault": "0x0",
  "frames": ["libreverie_jni.so+0x1a2b3c", "libQt6Core_arm64-v8a.so+0x2f19c"] }
```

- 只保留 `模块名+偏移`,不保留绝对 `pc` 与寄存器(绝对地址无诊断价值且是随机数)
- 最多 24 帧(48 帧截半,体积优先)

## 6. 签名与去重

```
sig = sha256(kind + "|" + app.code + "|" + 异常类名 + "|" + 前 3 个帧的 "模块+偏移")
```

- Java: 异常类名 + 首个 app 帧(`com.reverie.*`)
- Native: signal + 前 3 个非系统库帧
- 不含时间、不含地址、不含设备 → 同一处代码同一个错,签名稳定

服务端按 `sig` 聚合:"这一类崩了多少次 / 多少人 / 首末见到版本",这就是修复优先级的唯一依据。

## 7. 服务端(Cloudflare Worker + D1)

### 7.1 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/t/v1/crash` | 上报入口,`Content-Type: application/json`,`Content-Encoding: gzip` |
| `GET` | `/t/v1/stats?days=7` | 看板(`STATS_KEY` 校验):趋势/签名聚合/版本分布/机型分布;`&model=` `&ver=` 筛选,`&sig=` 下钻 |
| `GET` | `/t/v1/health` | 存活探针,回显 `disabled` 状态 |

保留期 180 天,由 `scheduled` 定时任务每日删除(`wrangler.toml` 的 `[triggers]`)。

**固定书签**:第一次用 `?key=<STATS_KEY>` 打开后,服务端种下 `rp_stats` Cookie 并 302 到不带 key 的干净 URL;
之后书签固定为 `?days=7` 即可。Cookie 里放的是 `sha256("revp-stats-v1|"+STATS_KEY)` 而不是 key 本身
(HttpOnly / Secure / SameSite=Lax),且每次访问滑动续期一年。这样既让链接固定,也不会把写入凭据带进浏览器历史。

### 7.2 D1 schema

```sql
CREATE TABLE crashes (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  sig       TEXT    NOT NULL,
  kind      TEXT    NOT NULL,
  at        INTEGER NOT NULL,   -- 崩溃时刻
  received  INTEGER NOT NULL,   -- 收件时刻
  app_code  INTEGER NOT NULL,
  app_ver   TEXT    NOT NULL,
  abi       TEXT,
  sdk       INTEGER,
  model     TEXT,
  install   TEXT,               -- 仅用于配额与去重, 不参与展示
  payload   TEXT    NOT NULL    -- 原始 JSON(已脱敏)
);
CREATE INDEX idx_crashes_sig  ON crashes(sig, at);
CREATE INDEX idx_crashes_inst ON crashes(install, received);
```

### 7.3 不记录 IP

Worker 不读 `CF-Connecting-IP`,不写任何 IP 或 IP 哈希。滥用防护靠 `install` 维度的配额(§8)。Zone 侧日志保留策略需单独确认(默认 Cloudflare 分析关闭即不落 IP 日志)。

### 7.4 已部署资源(2026-10-30)

| 项 | 值 |
|---|---|
| Worker | `reveriepaint-telemetry` |
| 对外端点 | `https://telemetry.lanrhyme.top`(**自定义域,必需**) |
| D1 | `reveriepaint_telemetry` · `2ac5fc7b-9816-410d-9267-9a55f455359d`(region WNAM) |
| 定时任务 | `17 3 * * *` 保留期清理 |
| 密钥 | `INGEST_KEY`(写入,客户端用 `-PtelemetryKey` / `TELEMETRY_KEY` 注入) · `STATS_KEY`(看板只读,只在本机) |

> **为什么必须走自定义域**: 真机实测 `*.workers.dev` 在国内移动网络下 DNS 被污染,连接直接吊死成
> `SocketTimeoutException`;换成 `telemetry.lanrhyme.top` 后同一份 APK 立刻 200。
> 一个推论: `wrangler.toml` 的 `routes` 必须写在任何 `[table]` 之前,否则会被解析成上一个 table 的字段而**静默不生效**。

### 7.5 看板

纯服务端渲染(无 JS、无外部资源、无构建步骤),配色与 App 的 Morandi 主题同源(`ui/theme/Theme.kt` 的
`bg/panel/accent/text/subText`)。

- **深色模式**:默认跟随系统(`prefers-color-scheme`),另有「自动 / 浅色 / 深色」手动切换,
  选择写进 `rp_theme` Cookie(一年),显式选择会压过系统偏好
- **图标**:内联 `art/icon.svg`(与应用图标同源),垫一层 `#0e0f16` —— 图标里的白色星芒是给深色底设计的,
  直接放在浅色卡片上会消失
- 内容:指标卡(上报/设备/签名)、每日趋势条形、按签名聚合(可下钻)、版本分布、机型分布(可点击筛选);
  签名详情页展示版本跨度、机型分布与最近 20 条原始样本

主题与「书签固定」共用同一套重定向:带 `?theme=` 或 `?key=` 的请求都会先落 Cookie,再 302 回干净 URL。

## 8. 配额、退避与 kill switch

| 机制 | 规则 |
|---|---|
| 每设备每日 | 最多 5 条,超出的直接丢弃(仍返回 200,客户端不重试) |
| 每设备每签名每日 | 最多 3 条 |
| 单条体积 | 典型 8–20 KB;硬上限 128 KB(gzip 后),超出先砍 logcat 再砍堆栈尾部 |
| 网络失败 | 退避 1h → 6h → 24h,累计 3 次放弃该条(不删除本地日志) |
| 服务端拒绝 | `401/403/410` → 客户端本机永久停用(标记 `server_disabled`) |
| 手动关停 | Worker 返回 `410 Gone` 即全局停用,不必发版 |

## 9. 用户可见的开关与告知

- **默认开启**(崩溃现场是排障必需品,与 Firebase/Crashlytics 的行业惯例一致)
- 首次启动在主页顶部显示**一次性告知条**(非模态、不阻断),点"了解详情"进 `docs/PRIVACY.md` 对应页面
- 设置 → 隐私与诊断 → "崩溃报告自动上报 `SettingSwitchGroupItem`",关闭后: 清空待上报队列、删除 installId、不再请求网络
- 关闭状态持久化在 `SharedPreferences("reverie_telemetry")`
- **纯净构建**: `-Ptelemetry=false` 或环境变量留空 → `BuildConfig.TELEMETRY_ENDPOINT=""`,整条链路编译期短路为 no-op(便于第三方再分发或自建渠道)

## 10. 验证清单

1. `./gradlew :app:compileDebugKotlin`
2. `./gradlew :app:testDebugUnitTest` — 覆盖脱敏、白名单映射、签名稳定性、体积裁剪
3. C++ 无需真机: 用 §2.1 的宿主机 harness 编出可执行文件,制造真实段错误,再用 `addr2line` 反查帧偏移
4. 本地 mock: `PORT=18787 node telemetry/worker/dev-server.mjs`,`-PtelemetryEndpoint=http://<局域网IP>:18787` 构建,`adb logcat -s ReverieTelemetry` 看回执
5. **真机端到端**(实测通过的做法):
   ```bash
   adb -s <serial> shell pm clear com.reverie.paint      # 清掉退避状态与 installId
   adb -s <serial> shell am start -n com.reverie.paint/.MainActivity
   PID=$(adb -s <serial> shell pidof com.reverie.paint | tr -d '\r')
   adb -s <serial> shell run-as com.reverie.paint kill -11 $PID   # 真 native 崩溃
   adb -s <serial> shell am start -n com.reverie.paint/.MainActivity
   adb -s <serial> logcat -d | grep ReverieTelemetry              # 期望 uploaded … (200)
   ```
   然后 `wrangler d1 execute reveriepaint_telemetry --remote --command "SELECT …"` 核对落库字段。
6. C++ 改动(帧偏移)需 `-PbuildNative` 重新打进 APK 并确认包内 `libreverie_core.so` 与本地构建产物一致
   (`llvm-strip --strip-unneeded` 后比 md5),再按上面第 5 条跑一遍

## 11. 未做(后续版本再议)

- **符号化流水线**: 目前只保证帧可符号化,还差"CI 保存未 strip 的 `.so` → 用 `addr2line`/`llvm-symbolizer` 还原函数名"的后台任务
- 功能采用率埋点(需要用户显式 opt-in,与崩溃遥测不是同一个开关)
- 性能画像(帧率/笔画延迟矩阵),设计见 `docs/RENDER-OPTIMIZATION.md`,是否上报单独评审
- 多维度看板(现在只有一张聚合简表)
- `native_crash` 的 `doc` 字段(§5.3 已知限制)

## 12. 踩过的坑

按踩到的顺序记录,避免重犯:

1. **`(?U)` 在 Android 上会炸**。`TelemetryScrubber` 最初用 `(?U)[\w\-.]` 匹配中日韩文件名,JVM 单测全绿,
   真机上抛 `PatternSyntaxException` → `ExceptionInInitializerError` → 整条上报链静默失效。
   Android 的 `java.util.regex` 不支持 JDK 的 `UNICODE_CHARACTER_CLASS` 内联标志,改用 `[\p{L}\p{N}_\-.]`。
   **教训**: 涉及 `java.util.regex` 的代码,JVM 单测通过不等于真机通过。
2. **只打异常类名会掩盖根因**。第一版 `Log.w(TAG, "… failed: ${it.javaClass.simpleName}")` 把 `Caused by`
   吞掉了,真机上完全无法定位。现在统一打全栈。
3. **`wrangler.toml` 的顶层键必须写在任何 `[table]` 之前**。`routes` 写在 `[triggers]` 后面会被解析成
   `triggers.routes`,自定义域静默不创建。
4. **`*.workers.dev` 在国内不可用**。见 §7.4。
5. **退避状态会挡住联调**。失败一次就写 `nextAttemptAt = now + 1h`,重装 APK 也不会清(`-r` 保留数据),
   联调时必须 `pm clear`。
