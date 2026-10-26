# 保存性能再优化（.revp / 显式保存 + 自动保存）实施方案

Date: 2026-10-26
状态: 已评审通过，待执行（本文件是执行方的唯一依据）

---

## 0. 给执行方的开场说明

- 本文件是**已批准**的实施计划，按 **Phase 0 → 1 → 2 →（可选）3** 顺序执行；每完成一个 Phase 单独提交一次，保证可独立回退。
- 动手前先按 `AGENTS.md` §9 做影响评估：本改动集中在 **C++ IO 域**（`app/src/main/cpp/ReverieCoreIO.cpp` 为主）+ 少量 Kotlin 埋点/显示。
- **不要**顺手重构无关代码；不要改 `.revp` 容器条目名/顺序/图层 PNG 尺寸约定；不要动 KRA/PSD/PNG 导出与 KRA 导入路径。
- 改动前先 `git status` 确认工作区干净，逐 Phase 提交。
- Phase 2 是唯一有正确性风险的阶段，必须靠 `saveverify` 压测 + `debug.reverie.saveincr` 开关兜底，**验证通过前默认关闭**。
- Phase 3 只在 Phase 0 真机读数明确指向时才做。

---

## 1. 目标与成功判据

**目标**：缩短 `.revp` 保存墙钟（显式保存 `saveProject` 与周期自动保存 `autoSaveProject`），不改变容器格式兼容性，不降低崩溃安全性。

**成功判据**（真机同工程 A/B，取性能标尺第 3 行 / logcat profile 行读数，各 5 次取中位数）：

| 指标 | 目标 |
|---|---|
| 全量保存墙钟 | ≥ 15% 下降（Phase 1 完成后） |
| 重复保存（仅改动 1/N 层，N≥5） | ≥ 50% 下降（Phase 2 完成后） |
| 产物体积 | 不增大（预览降采样后反而变小） |
| 正确性 | 保存→重开逐层像素一致；debug `saveverify` 压测 0 处不一致 |
| 兼容性 | 旧版本可打开新文件、新版本可打开旧文件；KRA/PSD/PNG 导出不受影响 |
| 崩溃安全 | 保持 `*.tmp` + fsync + 原子 rename + EOCD 校验，等级不降 |

> 用户侧未提供标尺读数（"没测过 / 都慢"），因此 **Phase 0 先量化**，Phase 2/3 的取舍由读数决定。

---

## 2. 现状核实（代码事实，含依据）

保存链路：

```
PaintViewModelProjects.kt:184 (saveProject) / :265 (autoSaveProject) / :329 (emergencySaveOnCrash)
  → JNI saveRevp / saveRevpAsync        reverie_jni_io.cpp:52 / :72
  → ReverieCore::saveRevp               ReverieCoreIO.cpp:570
    ReverieCore::saveRevpAsync          ReverieCoreIO.cpp:810
  → 引擎线程快照（meta.json / layers.xml / 合成预览 / 图层与关键帧克隆 / 选区掩码）
  → writeRevpStore                      ReverieCoreIO.cpp:449
  → writeRevpPngJobs（并行编码 + 串行写 zip）ReverieCoreIO.cpp:333
  → 校验 EOCD + fsync + 原子 rename      ReverieCoreIO.cpp:511-559
```

**已到位、本次不得回退的优化**

- 流式工厂避免整幅常驻：`makeSnapshotLayerJob` / `makeKeyframePngJob`（`ReverieCoreIO.cpp:404` / `:427`）
- 并行编码 + 串行写入 + 逐块释放：`writeRevpPngJobs`（`:333`）
- PNG 档位 q=70：`kRevpPngQualityDefault`（`:285`）；Qt 侧映射见 `qpnghandler.cpp`（`compression = 100-quality`，再 `level = compression*9/91` ⇒ q70 = zlib level 2）
- PNG 条目关闭容器压缩：`:375`；已压缩资产直存：`isPrecompressedAsset`（`:389`）
- 校验 + fsync + 原子 rename：`:511-559`
- 异步保存：`:810`
- `PaintRecorder.serialize()` 零拷贝：`docs/RENDER-OPTIMIZATION.md §3.5`

**仍存在的可优化浪费（逐条已核对源码）**

1. **冗余整幅深拷贝**：`renderMergedQImage()` 两处 `.copy()`（`ReverieCoreIO.cpp:73`、`:75`）。Krita 侧 `KisPaintDevice::convertToQImage` 内部 `new quint8[]` → `colorSpace()->convertToQImage` → `delete[]`（`krita-source/libs/image/kis_paint_device.cc:1651-1677`），返回值已是独立缓冲，4K 画幅白白多 2 次 ~64MB memcpy。该方法被 `savePng` / `exportJpg` / `saveRevp` / `saveRevpAsync` / `saveKra` 共用。
2. **preview.png 按全分辨率编码**（`ReverieCoreIO.cpp:718-731`），而消费方只当缩略图/自愈兜底（`PaintViewModelProjects.kt:919`、`AutoSaveHistoryManager.kt:100`、`ReverieCoreIO.cpp:1733` 自愈路径、`TemplateManager.kt:85/225/312`）。
3. **图层/关键帧按整幅转换**（`:414`、`:441`，`convertToQImage(nullptr,0,0,docW,docH)`）：稀疏层（线稿/文字/效果层）与 4K 画幅要付满幅色彩转换 + 满幅临时缓冲（`readBytes` 到临时缓冲再转 QImage，两遍 64MB 量级流量）。
4. **快照与编码完全不重叠**：必须先攒满所有 job 才进 `writeRevpStore`（`:801`、`:1045`）。合成（`renderMergedQImage`）+ 图层 COW 克隆期间，2~4 个编码线程全闲。
5. **每次保存重编码全部图层**：没有"自上次保存以来该图层未改动"的判断，改 1 层也要重编码 N 层。
6. **recording 条目按 level-6 deflate**：`setCompressionEnabled(true)` ⇒ `Z_DEFAULT_COMPRESSION`（`krita-source/libs/store/KoQuaZipStore.cpp:95-104`）。
7. **自动保存后整份 `.revp` 再复制一次**到历史目录：`AutoSaveHistoryManager.kt:94` 的 `copyTo`，大文件下是纯 IO 抢占。

---

## 3. 实施计划（按可独立提交/独立回退的阶段）

### Phase 0 — 细分埋点（先量化，独立提交）

**改动文件**：`app/src/main/cpp/ReverieCoreIO.cpp`（`RevpStat` 枚举 / `publishRevpStats` / 各阶段计时）、`app/src/main/cpp/reverie_jni_io.cpp:91`、`app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt:947`、`.../core/PaintViewModel.kt:369`、`.../core/PerfTrace.kt:406/939`。

1. `RevpStat` **在末尾追加**新指标，前 8 个下标语义保持不变（HUD/文档契约不破）：
   `StatComposite`（合成/预览转换）、`StatLayerClone`（图层 COW 克隆 + 关键帧 `writeToDevice`）、`StatSelMask`（选区掩码读取）、`StatConvert`（readBytes + 色彩转换）、`StatPngDeflate`（QImage→PNG）、`StatZipWrite`（zip 写入，含资产/录制）、`StatClose`（zip finalize + fsync + rename）、`StatReusedEntries`、`StatReusedBytes`。
2. **顺手修越界隐患**：`reverie_jni_io.cpp:96-100` 写死 `qint64 stats[8]` / `NewLongArray(8)`，而 `revpSaveStats(stats)` 按 `RevpStatCount` 写出 —— 枚举一扩就越界。改为按 `RevpStatCount` 分配与填充。
3. 工作线程侧计时：`RevpPngJob` 增两个 `std::atomic<qint64>`（convert / deflate 纳秒），`encodeSlot` 写入，写盘线程汇总（不加锁）。
4. 保存结束输出一行 logcat（保存是低频操作，默认常开）：
   `RP_IO save profile: total=..ms snapshot(composite=..,clone=..,sel=..) convert=.. png=.. zip=.. close=.. entries=N(reused=..,reusedBytes=..) file=..MB`。
5. Kotlin 侧：`pollSaveStats` 按 `s.size` 容错读取（保持"短数组直接返回"的老行为），`PerfTrace.saveStats()` 新参数**给默认值**（唯一调用点是 `pollSaveStats`；release 源集只有 `PerfHud/TilePerfLog/FbGeomLog`，`PerfTrace` 在 main，安全），HUD 追加显示细分与复用量。
6. 纯逻辑抽可测单元：把 stats 数组解码成数据类写成**顶层纯函数**（如 `SaveProfile.fromStats(LongArray?)`），补 `app/src/test/java` JUnit4 单测，覆盖：null / 长度 8 老数组 / 长度扩展 / 异常值。

**验收**：做一次保存即可从 HUD/logcat 拿到各段耗时；只有 8 个元素的旧行为仍正常显示。

**提交**：`perf(io): 保存阶段细分埋点并修正 revpSaveStats 数组越界`

---

### Phase 1 — 无条件低风险提速（独立提交，逐项可单独回退）

#### 1.1 去掉 `renderMergedQImage()` 两处 `.copy()`（`:73`、`:75`）
依据见 §2.1。顺手在函数注释写明"Krita 已返回独立缓冲，不得再加 copy"。该函数被 `savePng/exportJpg/saveRevp/saveRevpAsync/saveKra` 共用，改完需回归导出 PNG/JPG、保存 KRA。

#### 1.2 预览降采样
- 新增 `int revpPreviewMaxDim()`：Android 读 `debug.reverie.previewmax`，其它平台读 `REVERIE_PREVIEWMAX`，默认 **2048**，`<=0` 表示不限制。
- `comp` 长边超限时先 `scaled(w, h, Qt::KeepAspectRatio, Qt::SmoothTransformation)` 再编码为 `preview.png`；`thumbnail.png` 逻辑不变（仍由 `comp` 缩到 400）。
- **必须成对改自愈兜底**：`ReverieCoreIO.cpp:1733` 分支里，`fallbackImg` 尺寸不等于 `(w, h)` 时先缩放到画布尺寸（`KeepAspectRatio`）再 `convertFromQImage`，否则降采样预览会被写在左上角一小块 —— 这是本改动唯一的行为面变化，务必一起提交。

#### 1.3 稀疏图层按 bounds 转换（不改容器格式）
`makeSnapshotLayerJob:404` / `makeKeyframePngJob:427`：
- 引擎线程克隆后立即取 `exactBounds()`，与画布矩形 `intersected(QRect(0,0,docW,docH))` 后传进 `produce`；
- 空 → 1×1 透明 PNG（现状分支）；
- 等于画布 → 走现状整幅路径（不引入额外拷贝）；
- 稀疏 → 只 `convertToQImage(nullptr, b)` 转换该区域，再逐行 `memcpy` 贴进 `QImage(docW, docH, ARGB32_Premultiplied)`（先 `fill(Qt::transparent)`）；`part` 格式与之不符时先 `convertToFormat`。
- 条目名/顺序/整幅尺寸全部不变，旧版本照读。

#### 1.4 快照 ∥ 编码/写盘 流水线
把 `writeRevpStore` 拆成"条目顺序由生产者决定 + 专用写盘线程按序落盘"：
- 引擎线程克隆完一层就立刻投递编码（在飞上限 = 池线程数 × 2），生产者继续合成/克隆；
- 写盘线程按条目顺序 `store->open/write/close`，遇到未完成的任务则等待（条件变量/信号量 + 每条目完成标志）；
- `tmp` 写入 + EOCD 校验 + fsync + 原子 rename 全部仍在写盘线程末尾完成；
- 同步 `saveRevp` 在 join 后返回（保持"返回即写完"契约）；异步路径沿用现有线程结构；
- 严禁改变条目顺序（zip 条目顺序是兼容性契约的一部分）。
预期收益 ≈ 去掉 `min(快照, 编码+写盘)` 的串行损失（大画幅多图层约 20%）。

**Phase 1 验收**
- 同工程 A/B 墙钟下降 ≥15%；
- 产物 `unzip -l` 的条目名/顺序/尺寸与改动前一致（除 `preview.png` 尺寸/体积外）；`preview.png` 像素内容与画布一致；
- 保存后重开逐层一致；
- 导出 PNG/JPG/KRA 回归通过。

**提交**：`perf(io): 消除保存整幅冗余拷贝并按 bounds 转换 / 预览降采样 / 快照与编码流水线`

---

### Phase 2 — 增量保存（主收益，prop 可开关，验证后置默认）

核心：**未改动的条目直接沿用上一次同路径保存产物中的字节**（不读设备、不转换、不 deflate），只重编码改动过的图层。

#### 2.1 变更判据（双信号，任一变化即视为改动）
1. `KisPaintDevice::sequenceNumber()` —— 已核实：`KisPaintDeviceCache::invalidate()` 递增，而 `setDirty()` 四个重载都调用它（`krita-source/libs/image/kis_paint_device.cc:1149-1175`、`kis_paint_device_cache.h:52-62`），头文件语义为"设备每次变更即递增"。符号在预编译库中存在（`llvm-nm -DC third_party/android-native-libs/libkritaimage.so | grep 'KisPaintDevice::sequenceNumber'`）。
2. **我方像素写点审计**：`writeBytes|convertFromQImage|writePlanarBytes|fastBitBlt|->fill(|->clear(` 合计约 98 处（`app/src/main/cpp/*.cpp`），逐一确认写入后调用了 `setDirty()`（或经 `KisPainter`）；无把握的点补 `setDirty()`（这类点本来也存在"画面不刷新"隐患，属顺带修复）。

#### 2.2 数据结构（仅进程内，随文档走）
`QHash<绝对路径, SavedEntryIndex>`，每项记录 `{条目名, 类型, 图层身份(node 指针 + 图层名 + 顺序索引), 快照时刻的 seq, 关键帧时间集合, 产出文件 size+mtime}`；保留最近 ≤4 条路径；`loadRevp` / 新建文档 / resize 时整体清空。

#### 2.3 复用规则
- `layer_%03d.png`：同索引 + 同图层名 + 同 node 指针 + 同 seq；
- `frame_%03d_%05d.png`：额外要求关键帧时间集合不变；
- `preview.png` / `thumbnail.png`：仅当"全部图层签名 + 动画时间 + 结构均未变"时复用（这类"空保存"可退化为近乎纯拷贝）；
- 其它条目（meta / layers.xml / 资产 / recording）永远重写。

#### 2.4 写入方式
同时打开旧文件（KoStore 读，`path`）与新 `*.tmp`（KoStore 写），命中复用的条目读出字节后原样写入（`setCompressionEnabled(false)`），总量计入 `StatReusedEntries/StatReusedBytes`。

#### 2.5 保守回退（任一不满足即退回该条目的全量编码，**绝不因为缓存让保存失败**）
旧文件不存在 / 打不开 / 条目缺失 / CRC 错 / 大小与记录不符 / 路径被外部改动（size+mtime 对比）/ 图层增删改序 / 首次保存 / 另存改名 / 调整层 / 描边层 / 3D 层 / 动画图层签名可疑。

#### 2.6 线程注意点
异步保存时引擎线程仍在绘制 —— seq 必须在**快照时刻**在引擎线程采集并随 job 传递，缓存也记录这份快照值，而不是写盘线程结束时的值。

#### 2.7 默认值策略
先 `debug.reverie.saveincr`（默认 0）上线；真机跑完 §2.9 压测矩阵且 `saveverify` 0 不一致后，再改默认为 1。

#### 2.8 安全网 `debug.reverie.saveverify`
开启后对每个"被复用"的条目，用当前设备重新转换成 QImage 与旧 PNG 解码后的像素逐字节比对，不一致即 logcat 报错并强制该次保存重编码（release 保留代码，仅由 prop 开启，平时零开销）。

#### 2.9 压测矩阵（真机必须全跑）
笔画 / 橡皮 / 临时橡皮、填充、图案填充、滤镜与滤镜图层、曲线/渐变调整层、变换、液化、选区与存储选区、图层增删/改名/改不透明度/改混合模式/改顺序/合并/克隆、组与剪切蒙版、动画关键帧（当前帧与切帧后）、洋葱皮、3D 图层、导入资产、保存→杀进程→重开逐层比对。

**提交**：`perf(io): 未改动图层复用上次保存的 PNG 条目实现增量保存`

---

### Phase 3 — 数据驱动后续（本次默认不启用，仅当 Phase 0 读数指向时才做）

1. **自定义 libpng 编码器 A/B**（若 deflate 段 ≥ 40% 总耗时）：`~/ReveriePaint-android/deps/include/png.h` 与 `-lpng16`（`CMakeLists.txt` 已 `target_link_libraries(... -lpng16 ...)`）均就绪，CMake 需补 `${DEPS_DIR}/include` 到 include 路径。Qt 固定 `png_set_compression_level` 且用默认 filter 启发式，自写编码器可试 `level 3 + PNG_FILTER_NONE/SUB`（绘画内容常见 2~3× 编码速度，体积 +10~25%）。用 `debug.reverie.pngenc=qt|libpng` 同工程 A/B，速度与体积都达标才切换。
2. 存储选区掩码条目按 `selection->projection()->sequenceNumber()` 复用（逻辑同 Phase 2）。
3. recording 条目改为不压缩（省 level-6 deflate，代价是文件大几 MB）—— 由 `StatZipWrite` 决定是否值得。
4. 自动保存历史 `copyTo`（`AutoSaveHistoryManager.kt:94`）改为"保存完成后再复制 + 空闲时执行 + 与主保存互斥"，降低保存后 IO 争抢。

---

## 4. 公开接口 / 契约 / 数据流变化

| 项 | 变化 | 兼容性 |
|---|---|---|
| JNI `revpSaveStats()` | 返回数组由 8 → `RevpStatCount`（前 8 个语义不变） | Kotlin 侧按 `s.size` 容错；JNI 与 Kotlin 同版本同步改 |
| `.revp` 容器 | 条目名/顺序/尺寸全部不变；`preview.png` 可能降采样（默认仅长边 >2048 时） | 旧版本可打开；仅"图层元数据丢失"的自愈路径分辨率下降，已配自愈缩放改动 |
| 新增 debug prop | `debug.reverie.previewmax`、`debug.reverie.savelog`、`debug.reverie.saveincr`、`debug.reverie.saveverify`、`debug.reverie.pngenc` | 仅诊断，无 UI 影响 |
| 数据流 | Phase 1.4 增加"写盘线程"；Phase 2 增加"路径→条目索引"缓存 | 全在 `ReverieCoreIO.cpp` 内部匿名命名空间，`ReverieCore.h` 公开接口不变 |

---

## 5. 边界与失败模式

- `comp.isNull()` / 空图层 / 全透明图层 / 内容超出画布：沿用现状分支，Phase 1.3 用 `intersected` 保险。
- 磁盘不足：Kotlin `estimateProjectSaveBytes/checkProjectDiskSpace` 不动；增量保存产物大小与全量一致，无需改估算。
- rename 失败（跨文件系统/权限）：保留现有 `::rename` → `QFile::rename` → `copy+remove` 三级回退。
- 旧文件损坏 / 被外部修改 / 条目缺失：Phase 2 直接降级为全量编码，保存结果不变。
- 保存期间进程被杀：与今天一致（`.tmp` 不会被误认为成品；meta.json/autosave 兜底不变）。
- 内存：Phase 1.3 把每层满幅临时缓冲缩小到 bounds 尺寸，峰值内存下降；Phase 1.4 在飞上限受控，不会同时物化超过 `2 × 池线程数` 张整幅图。
- 单实例引擎假设不变：缓存在 `ReverieCore` 成员里，随 `loadRevp/close` 清理。

---

## 6. 测试与验收

**机械验证**

```bash
./gradlew :app:compileDebugKotlin          # Kotlin 侧改动
./gradlew :app:testDebugUnitTest           # 新增 stats 解码单测
# C++ 编译: 本机环境齐备 (~/Qt6/6.6.3/android_arm64_v8a, ~/Projects/krita-source,
#   ~/Projects/krita-source/build-android, ~/ReveriePaint-android/deps,
#   且 app/.cxx/Release/*/arm64-v8a 已配置) → 在既有配置目录增量 ninja reverie_jni
#   完整包: ./scripts/build_native.sh
./gradlew :app:lintDebug                   # 大范围改动后
./gradlew assembleDebug -PbuildNative      # 出真机包
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**功能验收（真机）**

1. 保存/另存/自动保存/崩溃抢救四条路径各成功一次，重开工程逐层与画布比对一致（含描边层、组、调整层、动画帧、存储选区、3D 图层）。
2. 保存后 HUD/logcat profile 行数值合理：各段之和 ≈ total。
3. 产物审计：`unzip -l` 条目名/顺序与 1.6.0 产物一致；`preview.png` 像素内容与画布一致（降采样除外）。
4. 降级测试：把旧版本（1.6.0）写的 `.revp` 用新版本打开并保存，再回旧版本打开 —— 画面一致。
5. Phase 2：`saveverify` 全矩阵 0 不一致；`saveincr=0/1` 各跑一遍确认回退可用。
6. 断电/杀进程：保存中途 `adb shell am force-stop`，确认目标文件仍是上一版完整文件。

**性能验收**：同一工程、同一设备，改动前后各 5 次保存取中位数，对照 §1 表格。

**常用调试命令**

```bash
adb shell setprop debug.reverie.savelog 1        # 打开保存 profile 日志
adb shell setprop debug.reverie.previewmax 2048  # 预览长边上限（0 = 不限制）
adb shell setprop debug.reverie.saveincr 1       # 增量保存开关（验证后默认 1）
adb shell setprop debug.reverie.saveverify 1     # 复用条目像素自检（压测用）
adb shell setprop debug.reverie.pngq 70          # PNG 档位 A/B
adb logcat -s RP_IO Ravenna                      # 看保存 profile
```

---

## 7. 文档与提交

- `docs/RENDER-OPTIMIZATION.md` 新增一节「保存性能（.revp）」：现状链路、Phase 0 读数、每项改动的依据与实测、增量保存的判据与回退、`saveverify` 用法、以及"不得回退的既有优化"清单（防后人改回去）。
- `docs/PROGRESS.md` 勾选里程碑。
- 提交（Conventional Commits，中文，body 写根因 + 真机验证结果 + 回退开关）：
  1. `perf(io): 保存阶段细分埋点并修正 revpSaveStats 数组越界`
  2. `perf(io): 消除保存整幅冗余拷贝并按 bounds 转换 / 预览降采样 / 快照与编码流水线`
  3. `perf(io): 未改动图层复用上次保存的 PNG 条目实现增量保存`
  4. （可选）`perf(io): PNG 编码器 A/B 开关`

---

## 8. 假设与不做清单

1. 用户未提供标尺读数，Phase 2/3 的取舍与先后由 Phase 0 真机读数决定；若读数显示 `snapshot` 而非编码为大头，则先做 Phase 1.4 与"合成缓存"，Phase 2 顺延。
2. 本次范围 = `.revp` 保存（显式 + 自动 + 崩溃抢救）；PNG/PSD/KRA/MP4 导出与 KRA 导入不在范围内。
3. `.revp` 格式兼容性是硬约束（不改条目名/顺序/整幅尺寸），因此**不采用**"裁剪后的图层 PNG + 偏移"这类能再省体积的方案（当前加载器把 PNG 视为整幅画布对齐，改用裁剪会破坏向下兼容）。
4. 崩溃安全等级不降：不引入直接覆写目标文件的路径，保留 fsync + 原子 rename + EOCD 校验。
5. 增量保存的正确性依赖 Krita `sequenceNumber()` + 我方 ~98 处像素写点审计；审计不彻底的点一律补 `setDirty()`，并由 `saveverify` 压测兜底，默认开关在验证通过后才打开。
6. **不做**：不改 PNG 档位默认值（q70 已实测最优区）、不动自动保存的触发策略与历史保留策略（除 Phase 3.4 的可选项）、不引入新容器格式、不重写 `PaintRecorder` 事件格式。
