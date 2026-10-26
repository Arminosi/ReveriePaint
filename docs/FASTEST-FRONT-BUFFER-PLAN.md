# ReveriePaint 超低延迟前缓冲预览方案设计与实施规划 (Fastest Front-Buffer Plan)

> **目标分支**: `feat/latency-prediction` (工作树: `E:\gongju\ReveriePaint-lowlatency`)  
> **核心组件**: `androidx.graphics:graphics-core:1.0.4` (Apache-2.0, API 29+)  
> **设计定位**: 笔尖前沿孪生预览段 (Preview Segment) 单缓冲直出，将笔尖到墨迹感知延迟压至 4~8ms 硬件极限。

---

## 1. 核心物理事实与选型依据 (Android 低延迟图形管线架构)

基于 Android 低延迟图形架构规范 (`androidx.graphics:graphics-core`) 与操作系统窗口合成器机制：

### 1.1 Android 硬件前缓冲流水线机制
- **核心组件**: `androidx.graphics:graphics-core` 库中的 `CanvasFrontBufferedRenderer` (@RequiresApi(29))。
- **底层原理**:
  1. 通过 Android 10+ 的 `SurfaceControl` 创建层级控制树：宿主 SurfaceView -> `MultiBufferedSurfaceControl`（双/三缓冲）与 `FrontBufferedSurfaceControl`（单缓冲直出）；
  2. 单缓冲前缓冲层配置为 `setMaxBuffers(1)`，绕过 Choreographer 的垂直同步 (VSYNC) 周期排队与合成管线；
  3. 当手写笔通过 `requestUnbufferedDispatch(event)` 产生触摸位移时，UI 线程捕获最新几何段后，渲染器在独立的硬件渲染线程直接向单缓冲 Surface 绘制并呈现；
  4. 呈现延迟从传统的 `Input -> VSYNC -> RenderThread -> SurfaceFlinger -> Display`（共 3~4 帧，约 30~50ms）压缩至硬件直出的 4~8ms 极限。

### 1.2 核心物理边界厘清
- **真实像素合成 vs 笔尖前沿预览**:
  - 全屏真实图层合成：ReveriePaint 的核心是 Krita 图像引擎，笔画需要完整的图层树投影合并、笔刷纹理混合、选区裁切与 CPU/GPU 双缓冲同步，真墨物理管线耗时通常在 30~50ms。
  - **ReveriePaint 达到 4~8ms 硬件极限的途径是「笔尖前沿孪生预览段」**：当前笔尖与真实墨水前沿之间存在 1~2 帧的延迟空窗。通过利用前缓冲独立图层以单缓冲极速直出该微小前沿段，能在视觉和物理手感上彻底消灭笔尖与线条之间的断裂感，当 Krita 后台完成真墨渲染后无缝融合。

### 1.3 Canvas 版对口 API: `CanvasFrontBufferedRenderer`
对于轻量级 2D 笔尖矢量前瞻预览，`androidx.graphics:graphics-core` 提供了 `CanvasFrontBufferedRenderer`：
- 无需搭建庞大的 EGL/GLSL 渲染管道与上下文切换开销，直接通过硬件加速 `Canvas` 回调绘图；
- 缓冲格式配置为 `TRANSLUCENT`，背景完全透明透出底层真实画布；
- 生命周期与 SurfaceHolder 自动绑定，资源安全可控。

---

## 2. 架构路线权衡：为什么必须坚决裁撤 B2？

在方案推演过程中，曾考虑两种演进路径：

### 2.1 原方案 B2 路径（全画布 SurfaceView 化）—— **坚决放弃**
- **做法**: 将 `CanvasTouchView` 拆分，画布位图、棋盘格、像素网格移入 `SurfaceView`（`lockCanvas(dirtyRect)`），前缓冲层挂在画布之上。
- **为什么必须放弃？**
  1. **AGSL 致命冲突**: ReveriePaint 的实时液化（Liquify）依赖 Android 13+ 的 `RuntimeShader`（AGSL）。AGSL **强制要求硬件加速 Canvas**。而 `SurfaceHolder.lockCanvas()` 产出的是**软件 Canvas**，在软件 Canvas 上绘制 AGSL 会直接报非法异常崩溃或黑屏！
  2. **巨大重构成本与灾难性回归风险**: `CanvasTouchView` 拥有 5686 行复杂代码，承载缩放/平移/旋转手势矩阵、对称轴镜像、选区蚂蚁线、拾色放大镜、图钉等。整体迁移至少耗时数周，极易破坏现有稳定绘制状态。
  3. **遮挡误解澄清**: 原方案误以为"画布留在 Window 里会被前缓冲层遮挡"。实际上，透明 SurfaceView（`setZOrderOnTop(true)` 或 `setZOrderMediaOverlay(true)`）本身只有被绘制的笔尖线段有像素，其余全部是透明通道，底层 Window 画布天然完全透出！

### 2.2 推荐路径：轻量独立透明前缓冲预览层 (Overlay)
- **核心理念**: **零侵入！保持现有 5686 行 `CanvasTouchView`、Krita 引擎、AGSL 液化着色器完全不动**。
- 仅新增一个独立的透明 `FrontBufferPreviewOverlay`（封装 `SurfaceView` + `CanvasFrontBufferedRenderer`）。
- 仅在手写笔落笔期间（`DOWN`..`UP`）处于激活状态，单缓冲直出笔尖前伸几何段，提笔即提交并透明休眠。
- 遇异常或在不支持的系统上，全自动零成本回退到现有 in-window 预览。

---

## 3. 目标运行架构

```
┌────────────────────────────────────────────────────────┐
│ Window 顶层：Compose UI (工具栏 / 调色盘 / 对话框)        │
├────────────────────────────────────────────────────────┤
│ 前缓冲层：FrontBufferPreviewOverlay (SurfaceControl)     │  ← 4~8ms 单缓冲直出笔尖前沿段
├────────────────────────────────────────────────────────┤
│ Window 底层：CanvasTouchView (5686行，Krita位图+手势+AGSL)│  ← 正常硬件加速显示
└────────────────────────────────────────────────────────┘
```

### 运行时时序：
1. **落笔 (ACTION_DOWN)**:
   - 手写笔事件进入 `CanvasTouchView`；
   - 激活 `FrontBufferPreviewOverlay`，初始化 `CanvasFrontBufferedRenderer`。
2. **运笔 (ACTION_MOVE)**:
   - 触控采样照常送入后台 Krita CPU 攒批流水线；
   - UI 线程将"引擎已渲染前沿 $\to$ 当前触控预测点"打包为轻量几何对象；
   - 调用 `CanvasFrontBufferedRenderer.renderFrontBufferedLayer(param)`，直接在单缓冲 SurfaceControl 上清空上一小段并重画最新段（4~8ms 直出上屏）。
3. **抬笔 (ACTION_UP)**:
   - 调用 `commit()` 或 `cancel()` 清空前缓冲；
   - Krita 真实墨迹末帧上屏，前缓冲层静默透明休眠；
   - 无残影、无撕裂、无层级穿透。

---

## 4. 分期实施计划

### Phase B0 · 真机可行性探针 (Spike)
1. **引入依赖**: `app/build.gradle.kts` 添加 `androidx.graphics:graphics-core:1.0.4`；
2. **环境探活**: 新增 `FrontBufferProbe.kt`，检测 API 29+ 及系统 SurfaceControl 运行状况；
3. **沙盒覆盖层**: 新增 `FrontBufferPreviewOverlay.kt`，实现透明 SurfaceView 与 CanvasFrontBufferedRenderer 框架；
4. **验证门禁**:
   - 透明度测试（无黑底遮挡）；
   - 国产 ROM 兼容性测试（无黑屏/闪烁）；
   - 抬笔清空与无残影验证；
   - 240fps 慢动作比对延迟收益。

### Phase B1 · 真实前沿几何对接与主流程集成
- 将 `CanvasTouchView` 中的 `currentRenderedFrontier` 及触控点通过纯数据结构同步至 `FrontBufferPreviewOverlay`；
- 当前缓冲层激活时，短路现有 in-window 模拟线绘制（防双重重叠绘图）；
- 完善手势变换（缩放/平移/旋转）坐标映射。

---

## 5. 事故记录：华为鸿蒙机型的 `libgraphics-core.so` 空指针崩溃 (1.6.0)

### 5.1 现场

用户报告（1.6.0 正式版，华为机型，系统为鸿蒙 5.0 及以上；开发者本机不复现）的 native 崩溃日志：

```
Signal: SIGSEGV (code=1)   Fault Address: 0x0
Crashed TID: 15840 (进程名 "m.reverie.paint" → 系统创建的 native 线程)
#04 pc …/lib/arm64/libgraphics-core.so     ← 故障帧 (与寄存器 pc 完全一致)
#05 #06   libgraphics-core.so
#07       /system/lib64/libandroid.so      (ASurfaceTransaction* NDK 封装)
#08       /system/lib64/libgui.so  android::NdkDelegatorController::ThreadLoop
Breadcrumbs: HOME → 载入笔刷 → PAINTING (刚进画布)
```

**判定**：崩在 `androidx.graphics:graphics-core` 的原生库，由系统 `libgui` 的
`NdkDelegatorController` 线程派发「SurfaceControl 事务已上屏」回调时，对空指针做了解引用。
与我们自己的 Krita/JNI 引擎无关；也不在 UI 或渲染线程上，Kotlin 侧无法 try/catch。

**为什么开发机不复现**：该回调由系统合成器派发，各 ROM 的 `ASurfaceTransaction` 实现差异很大；
鸿蒙的 Android 兼容层（日志里 `/apex/com.android.art` 说明确有完整 ART 在跑）是最接近"非常规实现"的一类。

**排障过程中发现的两处日志缺陷（已修）**：
1. `ReverieCrashHandler` 只在 `dladdr` 拿到符号名时才打印偏移，stripped 库（如 libgraphics-core.so）
   只剩裸地址，无法符号化；
2. 原生报告没有设备/系统信息，`Breadcrumbs.updateState` 从未被调用，状态快照一直是空的。

### 5.2 处置

| 措施 | 位置 | 说明 |
|---|---|---|
| 默认关闭前缓冲预测 | `PaintViewModel.frontBufferPredictionEnabled` | 新装/未存过设置 → 关；老用户已存的值不动 |
| 崩溃后自动隔离 | `CrashHandler.evaluateFrontBufferQuarantine` + `FrontBufferProbe.setCrashQuarantined` | 上次崩溃栈里出现 `libgraphics-core.so` → 本次安装关闭前缓冲；绑定 `lastUpdateTime`，发新版自动重试。逃生门 `debug.reverie.fb_force=1` |
| 崩溃日志补环境 | `CrashHandler.seedNativeStateSnapshot` | 机型/系统/HarmonyOS 属性/前缓冲开关与库版本写入 native 报告 |
| 升级组件 | `graphics-core:1.0.4 → 1.1.0-beta01` | 见 5.3 |

### 5.3 为什么升 1.1.0-beta01

上游 1.1.0-beta01（2026-10-07）的 Bug Fixes 与本次崩溃现象直接对应：

- *Fixed a race condition during `GLFrameBufferRenderer` teardown by releasing `SurfaceControl` on the GL thread.*
  → 1.0.4 在调用线程释放 SurfaceControl，与在途事务的完成回调赛跑；
- *Fixed memory leaks by unparenting `SurfaceControl` instances and clearing references on `release()`.*
  → 清了引用，系统回调不会再打到已释放的对象；
- *Fixed redundant draws and render stalls when surfaces are recreated.*
  → 对应"进出画布重建 surface"这条触发路径。

接入注意：
- 该版本把自身 `minSdk` 抬到 24（1.0.4 为 21），项目 `minSdk` 仍为 23，因此
  `AndroidManifest.xml` 用 `<uses-sdk tools:overrideLibrary="androidx.graphics.core" />` 放行
  —— 只有 API 29+ 才会加载这个库，API 23 设备走不到；若哪天全局引用，必须把 minSdk 抬到 24；
- `CanvasFrontBufferedRenderer` 公开 API 与 1.0.4 一致（`renderFrontBufferedLayer` / `commit` /
  `release` / `update` / `Callback`），`PreservedBufferContentsVerifier` 的反射目标也未变；
- 库版本单点定义在 `app/build.gradle.kts` 的 `graphicsCoreVersion`，并注入为
  `BuildConfig.GRAPHICS_CORE_VERSION`，日志与崩溃报告都会带上，便于确认测试包版本。

### 5.4 验证与回滚

- 单独出 beta 包（`com.reverie.paint.beta`，与正式版共存）验证：反复进出画布、画笔画、
  切换工具/图层、前后台切换、多笔快速起落；
- 通过后把 `frontBufferPredictionEnabled` 默认值改回 `true`；
- 若 1.1.0-beta01 仍复现：先看崩溃报告状态快照里的 `graphicsCore=` 与 `HarmonyOS/EMUI=`，
  再决定是否对鸿蒙类设备做机型级降级；隔离机制可保证不再反复崩。

