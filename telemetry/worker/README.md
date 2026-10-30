# ReveriePaint 崩溃遥测接收端

Cloudflare Worker + D1。数据契约见 [docs/TELEMETRY.md](../../docs/TELEMETRY.md) §7。

## 本地联调(不需要 Cloudflare 账号)

```bash
node telemetry/worker/dev-server.mjs
curl -s http://127.0.0.1:8787/t/v1/health
curl -s 'http://127.0.0.1:8787/t/v1/stats?days=7' | head
```

数据库落在 `telemetry/worker/.dev.sqlite`(已 gitignore),删掉即重置。

## 部署

```bash
cd telemetry/worker
npx wrangler d1 create reveriepaint_telemetry      # 把返回的 database_id 填进 wrangler.toml
npx wrangler d1 execute reveriepaint_telemetry --file=./schema.sql --remote
npx wrangler secret put INGEST_KEY                  # 上报写入凭据, 生成的随机串写进 CI/客户端
npx wrangler secret put STATS_KEY                   # 看板只读凭据, 只给自己用, 不要进 APK
npx wrangler deploy
```

绑定自定义域(或直接用 `*.workers.dev`):

```
Route: telemetry.<你的域名>/t/*
```

客户端构建时注入端点与 key:

```bash
./gradlew assembleRelease -PtelemetryEndpoint=https://telemetry.example.com -PtelemetryKey=$INGEST_KEY
```

不注入 → `BuildConfig.TELEMETRY_ENDPOINT` 为空 → 客户端整条链路 no-op。

## 全局停用(kill switch)

```bash
npx wrangler deploy --var DISABLED:1
```

客户端收到 `410` 后会在本机永久停用上报(标记 `server_disabled`),恢复到 `0` 也不会自动打开 ——
防止服务端抖动时反复横跳,重启上报需要用户重新打开开关。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/t/v1/crash` | 上报入口。`Content-Encoding: gzip` 可选,`x-rp-key` 校验 |
| `GET` | `/t/v1/stats?days=7&key=…` | 看板(`key` 用 **STATS_KEY**)。支持 `&model=` `&ver=` 筛选、`&sig=` 下钻 |
| `GET` | `/t/v1/health` | 存活探针,回显 `disabled` 状态 |

## 看板

`/t/v1/stats` 是服务端渲染的只读页面(无 JS、无外部资源):每日趋势条形、按签名聚合(次数/设备数/末见版本)、
版本分布、机型分布;点机型或版本下钻筛选,点签名看最近 20 条原始样本。
`STATS_KEY` 与 `INGEST_KEY` 必须分开 —— 看板 key 会进浏览器历史与 Referer, 不能是能写入的凭据。
未配置 `STATS_KEY` 时会退回 `INGEST_KEY`(仅为兼容旧部署)。

## 配额

| 维度 | 上限 | 超限行为 |
|---|---|---|
| 单条体积 | 128 KB(gzip 前) | `413` |
| 每设备每日 | 5 条 | 回 `200 {dropped:"install_daily_cap"}`,客户端记成功不再重试 |
| 每设备每签名每日 | 3 条 | 回 `200 {dropped:"sig_daily_cap"}` |

## 隐私

不读 `CF-Connecting-IP`,不写 IP 或 IP 哈希;`install` 字段只用于配额与去重,不出现在 `stats` 页面上
(页面只展示聚合数与机型分布)。Zone 默认分析建议保持关闭。
