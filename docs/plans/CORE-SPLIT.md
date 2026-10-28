# 核心拆分：`libreverie_core.so` 状态所有权清单与接口草案

> 起因：krita fork 的补丁层越同步越多，且 Kotlin 侧持有大量与引擎重复的状态（Chinsaa：
> 「实现漂移和状态不统一」）。本文件回答两件事：**现在谁持有哪个状态**、**拆成独立核心库
> 之后接口长什么样、怎么迁移**。
> 状态：草案，待评审（2026-10-28）。

## 1. 现状量化

| 项 | 数量 | 说明 |
|---|---|---|
| Kotlin ViewModel | 25,506 行 / 32 个 partial 文件 | `PaintViewModel*.kt` |
| ViewModel `mutable*StateOf` 字段 | **约 558 个**（主文件 449、动画 61、同步 28、备份 14、故事板 6） | 其中相当一部分是引擎状态的镜像 |
| JNI 外部函数 | **336 个** | `ReverieCoreBridge.kt`，8 个 `reverie_jni_*.cpp` |
| 应用 C++ | 25,683 行 / 37 文件 | 已直接对 Krita API 编程（只用了 38 个 Krita 头） |
| 引擎侧影子属性 | **8 个** `reverie_*` 动态属性 | `reverie_is_fill` `reverie_is_stroke` `reverie_stroke_{size,color,pos,opacity}` `reverie_is_transparency_mask` `reverie_fill_color` |
| 图层镜像 | `var layers by mutableStateOf(listOf<LayerUiState>())` + 23 字段结构体 | `PaintViewModel.kt:6277` / `LayerUiState:4904` |

## 2. 状态所有权矩阵（按「谁是权威」分三类）

### A. 引擎权威、Kotlin 只读镜像（应改成快照，不允许 Kotlin 改）

| 子系统 | 引擎侧 | Kotlin 镜像 | 风险 |
|---|---|---|---|
| 图层树 | `KisNode` 树 | `layers: List<LayerUiState>`（index/id/name/visible/locked/alphaLocked/isGroup/nodeType/depth/colorLabel/clipped/alphaInherited…） | 中：结构变更（合并/栅格化）后靠 `refreshLayers()` 重拉，顺序与选中态偶发不一致 |
| 关键帧 / 帧数 | `KisNode` 时间轴通道 | 播放态 + `keyframeCount()` 查询 | 中：动画面板 61 个状态字段，帧增删要手动同步 |
| 选区 | `KisSelection` | 选区模式/羽化等 UI 参数 | 低：UI 参数本就该在 Kotlin |
| 撤销/重做可用性 | `KUndo2Stack` | 布尔镜像 | 低 |
| 图层缩略图 / 瓦片 | 瓦片缓存 | 512 显示瓦片 | 低（已有 COW 状态机） |

### B. Kotlin 权威、写穿到引擎（保留在 UI 侧，但要走命令接口）

| 子系统 | 现状 |
|---|---|
| 笔画参数（大小/不透明度/流量/间距…） | Kotlin 面板 → `setBrush*` 逐项写引擎；引擎侧 `KisPaintOpPreset` 也会被预设切换改写 |
| 工具模式、视图变换（缩放/旋转/平移） | Kotlin 持有，引擎只吃结果 |
| 滤镜/调整层参数 | 对话框在 Kotlin，应用时序列化成配置串传给引擎（`AdjustmentConfigCodec`） |
| 画布尺寸/DPI/背景色 | Kotlin 表单 → 建文档命令 |

### C. 双份（真正的漂移源，必须收敛成一份）

| 状态 | 现在存在哪两处 | 典型症状 |
|---|---|---|
| **描边/填充图层参数** | 引擎 `KisNode` 动态属性 8 个 `reverie_*` + Kotlin 侧按 key 解析 | 节点被 clone/rasterize 后属性丢失（代码里已有「QObject 动态属性不保证随 clone 复制」的补丁） |
| **笔刷预设** | 引擎 `KisPaintOpPreset` + Kotlin `BrushPreset(name,size)` | 预设改名/换引擎后两侧不一致 |
| **动画关键帧模型** | 引擎通道 + Kotlin `manualKeyframes`/帧表 | 自动建帧与手动建帧两条路径各自维护 |
| **剪切蒙版标记** | 引擎 `enableClippingLayer` + `LayerUiState.clipped` | fork 的 PSD 读回与 UI 镜像两条写入点 |
| **3D 参考层姿态** | 引擎图层（序列化进 .revp）+ Kotlin 面板状态 | 保存/恢复后姿态回读 |

## 3. 目标边界：`libreverie_core.so`

### 3.1 三条铁律

1. **C ABI 只出不进 Krita 类型**：头文件里不出现 `QString`/`KisNodeSP`/模板，全部 opaque + POD。
2. **引擎是唯一权威**：写操作只能走 `rp_cmd_*`；不再允许「Kotlin 先改自己的 state 再通知引擎」的乐观更新。
3. **读走不可变快照**：UI 拿到的快照带单调 `rev`，引擎内部对象永不出界；快照释放由引用计数管理。

### 3.2 接口草案

```c
/* reverie_core.h —— 纯 C99, 不依赖 Qt/Krita 头 */
typedef struct rp_doc      rp_doc;       /* 不透明句柄, JNI 层当 jlong 搬 */
typedef struct rp_snapshot rp_snapshot;  /* 不可变只读快照, 引用计数 */

uint32_t rp_abi_version(void);                       /* 不匹配直接拒绝加载 */

/* 生命周期 */
rp_doc  *rp_doc_open(const char *revp_path);         /* 失败返回 NULL, 原因见 rp_last_error() */
void     rp_doc_close(rp_doc *doc);
int32_t  rp_doc_save(rp_doc *doc, const char *path); /* <0 错误码 */

/* 只读快照（图层树 / 帧表 / 选区 / 文档元信息） */
const rp_snapshot *rp_doc_snapshot(rp_doc *doc);     /* 原子取当前版本 */
void               rp_snapshot_retain(const rp_snapshot *);
void               rp_snapshot_release(const rp_snapshot *);
uint64_t           rp_snapshot_rev(const rp_snapshot *);

uint32_t                  rp_snap_layer_count(const rp_snapshot *);
const struct rp_layer    *rp_snap_layer(const rp_snapshot *, uint32_t index);
/* rp_layer 是 POD: id / parent / depth / flags(可见/锁定/剪切/继承alpha)/
   node_type / name[64] / stroke_params(结构体, 取代 8 个 reverie_* 属性) */

/* 写命令：参数是 POD 数组, 返回新的 rev 或负错误码 */
int32_t rp_cmd_stroke_begin (rp_doc *, const struct rp_stroke_ctx *, uint64_t *rev);
int32_t rp_cmd_stroke_extend(rp_doc *, const struct rp_point *, uint32_t count); /* 热路径: 零拷贝批量 */
int32_t rp_cmd_stroke_end   (rp_doc *);
int32_t rp_cmd_layer_add    (rp_doc *, const struct rp_layer_desc *, uint64_t *rev);
int32_t rp_cmd_layer_patch  (rp_doc *, uint64_t layer_id, const struct rp_layer_patch *, uint64_t *rev);
int32_t rp_cmd_selection_set(rp_doc *, const struct rp_selection_op *);
int32_t rp_cmd_undo / rp_cmd_redo (rp_doc *, uint64_t *rev);
```

JNI 层从此只做三件事：`jlong` ↔ 句柄、`DirectByteBuffer` ↔ POD 数组、快照结构体 ↔ Kotlin 值类。
Kotlin 侧表现层把 `LayerUiState` 换成「快照 + diff」：`rev` 变了才重建列表，单项用 id 比对。

### 3.3 为什么这能治「漂移」

- 状态只有一个物理位置（引擎），Kotlin 只能读；不存在「两边都改、谁后写谁赢」。
- 8 个 `reverie_*` 影子属性并进 `rp_layer.stroke_params` 结构体，序列化与内存一份 schema。
- 预设/关键帧等结构从引擎侧生成快照，Kotlin 不再自己拼。

## 4. 迁移顺序（每阶段都可独立发版，APK 始终可跑）

| 阶段 | 内容 | 产出 | 验收 |
|---|---|---|---|
| P0 | C ABI + `rp_doc_snapshot`（图层树/文档元信息先行） | `libreverie_core.so`、JNI 薄壳 | 快照生成 ≤1.5ms（4K/20 层）；图层列表与旧 `refreshLayers()` 逐字段 diff 为 0 |
| P1 | 写路径逐域替换：图层增删/属性 → 选区 → 笔画 | 每域一个 PR | 同一操作序列下，新旧路径像素哈希一致；笔画路径零新增分配、单帧 ≤8ms |
| P2 | 影子属性下线：`reverie_*` 8 个属性 → `rp_layer.stroke_params` | 删掉 C++/Kotlin 两侧的 key 解析 | 描边/填充层 clone、栅格化、保存重开后参数不丢 |
| P3 | 旧 JNI 域文件退役：`reverie_jni_<域>.cpp` 逐个删除 | 336 → 目标 <120 个函数 | 每删一个域跑一次契约测试；`docs` 更新 |

估算：P0 1~2 天；P1 每域 0.5~2 天（笔画最重）；P2/P3 各 1~2 天。总计 1~2 周，期间不分叉 UI。

### P0 进展（2026-10-28）

已落地（代码在应用侧，未改动 Krita fork）：

| 文件 | 内容 |
|---|---|
| `app/src/main/cpp/reverie_core.h` | C ABI：`rp_doc_get` / `rp_doc_snapshot` / `rp_snapshot_release` / `rp_snapshot_layer`，POD `rp_layer_snapshot`（152 字节定长，字段偏移写死在头文件里，Kotlin 按同偏移解析） |
| `app/src/main/cpp/ReverieCoreApi.cpp` | 快照实现：逐项复用引擎既有访问器（`layerId/layerName/layerVisible/…/getLayerStrokeParams`），与 Kotlin 旧路径同源，保证可逐字段比对 |
| `app/src/main/cpp/reverie_jni_snapshot.cpp` | `nativeSnapshotMode/Size/Fill` 三个 JNI 入口，快照写进 DirectByteBuffer，一次调用替代 n×20 次 JNI |
| `core/LayerSnapshot.kt` | 解析 + 逐字段 diff + 时序统计，日志 tag `ReverieSnapshot` |
| `PaintViewModel.syncLayersFromNative()` | 探针接入：`debug.reverie.snapshot=1` 只比对，`=2` 用快照结果替换旧路径；关闭时只多一次返回 0 的 JNI 调用 |

验收方式：`adb shell setprop debug.reverie.snapshot 1` 后打开任意工程，看
`ReverieSnapshot: rev=N layers=K equal (legacy=Xus snapshot=Yus)`；出现 `DIFF` 即列出字段差异。

**2026-10-28 真机实测（OPD2413 / Android 16，arm64-v8a）**

| 场景 | 结果 |
|---|---|
| 模式 1（只比对） | `rev=2 layers=2 equal (legacy=114~123us snapshot=122~204us)`，23 字段零差异 |
| 模式 2（用快照当面板数据） | 图层列表/缩略图/可见性/名称全部正常，日志同样 `equal` |
| 单测 | `SnapshotCodecTest` 3 项（字段偏移、solo 遮蔽、坏魔数/截断拒绝）全过；C 侧 `offsetof` 用 g++ 编出来核对过（sizeof=152，偏移与头文件注释一致） |

性能要诚实说：**小工程（2 层）两条路径都在 0.1~0.2ms，快照并不更快** —— 旧路径每层约
20 次 JNI（≈3µs/次），新路径一次调用但要拷 POD + 解析；收益随图层数线性放大
（20 层时旧路径约 1.4ms，新路径仍是「一次调用 + 20 条定长拷贝」）。P0 的真正价值不是这点
耗时，而是**图层状态从此只有一个生产者**（快照由引擎生成，Kotlin 只解析）。

稳态已优化为**一次 JNI 调用**：`nativeSnapshotFill` 容量不足时返回 `-needed`，Kotlin 扩容后
重试，避免每次同步都先查一次大小（`nativeSnapshotSize` 会完整建一次快照，不能放在热路径）。

## 5. 不做的事（避免过度设计）

- 不把 Krita 类型暴露到 C ABI，也不做「C++ 头文件包装层」（那只是换名字）
- 不一次性重写 336 个 JNI 函数；迁移按域推进，旧函数先标 deprecated 再删
- 不把 UI 状态（面板位置、手势、主题）搬进核心库——那本来就该在 Kotlin
- 不在 P0 动笔画热路径（先只读，写路径最后）

## 6. 与 Krita fork 补丁层的关系

- 本拆分**不要求** fork 再改 Krita：`libreverie_core.so` 就是现在的 `app/src/main/cpp/*`
  编成一个独立 so，只是换边界，不动引擎源码。
- 唯一关联：P2 的 `stroke_params` 结构体需要引擎侧序列化跟着改（`reverie_*` 属性
  → 结构体字段），会落到 fork 的 `0001` 补丁里；届时用 `scripts/check_krita_sync.sh`
  确认剪切蒙版等契约不受影响。
