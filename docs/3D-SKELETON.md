# 3D 骨骼与姿态子系统优化记录

> 面向后续维护者的实现说明。相关代码:
> `model/SkinnedModel3D.kt`(骨架/蒙皮)、`model/PosePresets.kt`(预设与关节角度)、
> `model/GlbParser.kt`(glTF/GLB 解析)、`core/PaintViewModel3D.kt`(状态与手势)、
> `core/gl3d/Canvas3DDrawer.kt`(视口硬件 Canvas 投影)、`ui/painting/panels/PoseAdjustmentPanel.kt`(面板)

## 1. 数据流

```
Model3DState (posePreset + jointAngles + 视角/光照)
        │  PosePresets.buildEulerMap()      语义键 + 通用后缀键 → 骨骼局部欧拉角
        ▼
SkinnedMesh3D.createDeformedMesh()         FK(拓扑序) → 蒙皮矩阵 → 逐顶点线性混合蒙皮
        │  groundShiftY (预设贴地量, 只由预设决定)
        ▼
Deformed3D(mesh, skeleton, worldJoints)
        ├─ Canvas3DDrawer.draw()      逐帧投影 (拖拽/旋转期间)
        └─ Gl3dRenderer.renderToBitmap()  烘焙/栅格化/线稿 (后台线程)
```

## 2. 本轮修掉的缺陷

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | 头部 / 躯干俯仰、偏转滑杆实际角度是显示值的 2 倍 | `head_pitch` 等键先被语义分支读走, 又被"通用后缀键"兜底分支按 `head` 再累加一次 | `PosePresets` 用 `*_SEMANTIC_KEYS` 把已消费的键排除 |
| 2 | 切换图层 / 模型后, 骨架画在了别的模型上 | 骨架与世界关节坐标是 `getMeshForState()` 的副作用, 命中缓存时不会刷新 | 形变结果改为 `Deformed3D(mesh, skeleton, worldJoints)` 一并返回, 由绘制方按当前图层取用 |
| 3 | 少数导入模型形变后整体缩小 / 顶点被吸向原点 | 蒙皮权重未归一化, 权重和 < 1 时线性混合结果不足量 | 逐顶点除以权重和 |
| 4 | 四肢"横向拖了没反应" | 横向拖拽写的是绕骨骼自身长度轴 (局部 Y) 的旋转 = 自转, 屏幕上几乎无位移 | 拖拽轴角色按绑定姿态几何挑选: 选"屏幕横向位移最大"的轴 |
| 5 | 拖头时手指向下、头却抬起 | 俯仰符号与手指方向相反 | 符号由"绕该轴旋转时探针在屏幕上的移动方向"决定, 手指向下 → 骨尖向下 |
| 6 | 关节重叠时总是选中先遍历到的那个 | 拾取只看屏幕距离 | 距离接近 (≤3px) 时优先选择离相机更近的关节 |
| 7 | 拖拽骨骼时掉帧 | ① 每帧新建 300KB 顶点数组 ② 40 份网格缓存可达 ~30MB ③ 9.5k 面插入排序每帧 ~2×10⁷ 次比较 | ① 逐帧路径复用 `DeformBuffer` ② 缓存改按浮点总量限额 LRU (≈8MB) ③ 深度分桶计数排序 O(n) |
| 8 | 首次显示 3D 图层卡顿 ~100ms+ | 600KB GLB 在主线程 (onDraw) 解析 | 创建/恢复 3D 图层时后台预解析 |
| 9 | 导入的 VRM 角色"缺件" | 只读 `meshes[0].primitives[0]` | 合并全部三角图元并整体平移顶点/关节索引 |
| 10 | 部分导入模型读成乱码 | 未支持 `bufferView.byteStride` (交错顶点缓冲) | accessor 读取按 stride 步进 |

## 3. 热路径零分配

`Skeleton3D` 在构造期一次性算好:

- `order`: 父先于子的拓扑序 (FK 每个关节只做一次正向矩阵乘, 无递归/无 visited 数组);
- `semanticKeys`: 关节 → 人形/动物标准键的反向映射 (多键冲突按字典序取首个, 跨会话确定);
- `bindWorld`: 绑定姿态世界矩阵 → 每根骨骼的世界轴、拖拽轴角色与符号;
- `DeformBuffer`: 顶点/法线/蒙皮矩阵/世界矩阵/关节世界坐标的复用缓冲。

`Canvas3DDrawer` 复用投影矩阵 (仅在宽高比或缩放变化时重算)、三角形排序缓冲、骨骼屏幕坐标数组;
骨架覆盖层不再每帧 new `ProjectedJoint` 列表。

## 4. 姿态贴地与"压低身体"的正确做法

**结论先行: 根骨 (Hips) 的平移不是自由度。** 平移根骨等于整只模型平移, 而贴地又会把整只模型
抬回原位 (`groundShift = restMinY - minY` 精确抵消), 视觉上"什么都没发生"。这正是早期
"整体升降"滑杆被否掉的原因 —— 要压低身体只能折叠**四肢与脊柱**的真实骨骼姿态。

现在的规则:

- `TUNED_PRESET_SHIFT` **已清空**, 所有姿态一律走几何自动贴地: 用预设本身形变一次, 把网格最低点
  对齐到绑定姿态的地面基准线 (`SkinnedMesh3D.restMinY`)。升降量只由「预设」决定, 拖关节滑杆时
  模型不会上下漂移。
- 预设自带的骨盆平移 (`buildTranslations` 里的 `Hips` 那几条) 已删除, 原因见上; 通用
  `<骨骼>_tx/_ty/_tz` 通道保留, 但只对**非根骨**有意义 (让某段骨骼相对父级错位)。
- 端坐 / 趴睡的姿态数值由 `scripts/model/cat_pose_solver.py` 用"世界仰角 + 两骨 IK"解出,
  不再手调 (手调必然调出"腿在空中划水 / 骨盆悬空")。

> 离线核对 (Blender + 与 Kotlin 逐位一致的形变复算, 地面取绑定姿态最低点): 9 个猫猫预设
> 贴地后 `minY` 全部等于 `restMinY = -0.5691`, 四爪/跗关节落在同一水平面。

## 5. 骨骼拖拽轴角色

对每根骨骼, 用绑定姿态推测两种拖拽该作用在哪根局部轴上:

1. 垂直拖拽固定走局部 X (俯仰), 与既有预设数值体系一致;
2. 水平拖拽在 {局部 Y, 局部 Z} 中选**能让骨头末端/朝向在屏幕上产生最大横向位移**的那根,
   沿骨轴 (Y) 的自转会被自动淘汰;
3. 语义键为 `head` / `neck` 时固定绕局部 Y (转头), 因为转头的轴恰好是骨轴;
4. 符号由旋转 ±1° 时探针的屏幕位移符号决定, 保证"手指往哪边, 骨头往哪边"。

探针取三个: 子关节偏移 (骨骼末端)、骨骼 +Z (朝向)、骨骼 +X (侧向), 取响应最大的一个。

## 6. 面板

除既有语义滑杆 (按模型硬编码的关节清单) 外, 新增「选中骨骼」三轴精确调节:
在画布骨骼模式点选任意关节后 (含导入的 GLB/VRM/PMX 任意骨骼名), 面板顶部出现
俯仰 / 偏转 / 翻滚 三根滑杆 + 单骨骼归零, 键名约定为 `<骨骼名>_p / _y / _r`。
关节角度统一存在 `Model3DState.jointAngles`, 与预设角度按「同键相加」合成。

## 7. 测试

`app/src/test/java/com/reverie/paint/model/`

- `SkeletonPoseTest`: 角度只施加一次、后缀键合成、猫猫标定升降、人体自动贴地、拖拽轴角色与符号
  (猫头 / 猫前腿 / 人臂)、权重归一化、乱序关节的 FK 拓扑、复用缓冲与独立缓冲顶点一致;
- `GlbPrimitiveMergeTest`: 多图元合并、交错缓冲 (`byteStride`) 解码、合并后索引/关节索引平移、静态图元不动;
- `Model3DTest`: 既有资产契约 (关节名、朝向、静止姿态零形变)。

## 8. 离线工具链 (调姿态 / 改资产前必读)

三个脚本构成一条链路, 都是纯 Python (numpy), Blender 只在最后出图时用:

| 脚本 | 作用 |
|---|---|
| `scripts/model/add_spine_mid.py` | 给 GLB 插入脊柱中段骨 `spine_mid` 并重蒙皮躯干 (见 §9) |
| `scripts/model/cat_pose_solver.py` | 按"世界仰角 + 两骨 IK"反解逐骨局部俯仰, 输出可直接粘进 `PosePresets.kt` 的数值 |
| `scripts/model/preview_cat_poses.py` | 用与 App 逐位一致的数学 (FK + LBS + 预设表 + 贴地) 形变模型, 导出 OBJ 并用 Blender 出对照图, 同时打印"边拉伸度" |

```bash
# 1) 解姿态 (不上屏, 只打印数值与落点)
python3 scripts/model/cat_pose_solver.py --pose sit,curl
# 2) 出图核对 (side / quarter / front / top 四个机位, 建议至少看 side + quarter)
python3 scripts/model/preview_cat_poses.py --presets sit,curl --views side,quarter
# 3) 数值满意后同步两处: PosePresets.kt (App) 与本脚本 PRESETS (离线复算)
```

**踩过的坑 (务必守住)**:

1. **不要用"目标关节坐标"硬拟合躯干** —— 这套骨架 Chest 骨只有 0.17 长且静止时朝前下, Neck 骨却有
   0.41 长, 会把链条拉成不可能的形状。躯干用世界仰角描述 (仰角在链上线性可加), 四肢才用 IK。
2. **IK 有两个解, 必须挑"转动量最小"的那支**。另一支是反关节 (膝盖朝后上), 要求股骨翻 150°+,
   线性混合蒙皮直接塌成一团 —— 实机/渲染都表现为"髋部鼓一个包"。
3. **单骨局部角超过 ~75° 就会开始鬼畜** (颈、跖骨尤其明显)。`cat_pose_solver.py` 打印每根骨的
   局部角, 越界就说明目标姿态在这个骨架上做不到, 应该降低目标而不是硬掰。
4. **贴地取最低点, 所以"尾巴/耳朵先着地"会把整只猫顶起来**。核对时看 `preview_cat_poses.py`
   打印的"最低 1cm 内顶点所属骨", 必须是爪子/跗关节/腹部, 不能是耳朵或吻部。
5. **根骨平移等于整体平移** (见 §4), 不要指望它压低骨盆。

## 9. 猫猫耳骨轴心修正 (资产)

原 `anime_cat.glb` 的 `earend` / `R_earend` 轴心在 y=0.577, 而耳朵几何最高点只有 0.573 —— 拖耳骨等于
绕耳尖把耳朵当倒摆甩, 骨架覆盖层也像两根天线。`scripts/model/fix_cat_ears.py` 只改绑定姿态:
把轴心移到耳根 (y≈0.455, 即耳区下四分位质心), 骨轴沿"耳根→耳尖", 局部 X 拉回世界 +X 附近,
并重算两根骨的 inverseBind。

- 静止姿态逐顶点不变: `world × inverseBind` 误差 < 3e-8, `Model3DTest` 的"静止姿态零形变"契约成立;
- 骨骼名不变 (`earend` / `R_earend`), 既有工程与测试无需迁移;
- 之后拖耳骨 = 绕耳根摆动, 覆盖层那两根线缩回耳内。

## 11. 猫猫骨架重绑 (2026-10, 网格不变只换骨架)

网格保持原样 (Meshy 导出的猫, 画风与细节都不动), 只重做骨架与权重 —— 出问题的一直是骨架,
不是模型。脚本 `scripts/model/rerig_cat.py` (Blender 无头运行)。

**新骨架 36 关节**:

```
root → spine_01 → spine_02 → spine_03 → chest → neck → head → muzzle
                                              │       └ ear_L / ear_R
   ├ hip_L → knee_L → hock_L → meta_L → toe_hind_L
   ├ hip_R → ...
   ├ tail_01 ... tail_06
   └ (前肢挂在 chest)
       scapula_L → shoulder_L → elbow_L → carpus_L → toe_front_L
       scapula_R → ...
```

- 躯干 5 段: 蜷缩/弓背由 5 段分担, 单骨只转 8~20 度, 不再靠一根骨硬掰;
- 颈只有 1 段 (旧资产颈骨 0.41 长, 是"看着惊悚"的主因之一);
- 尾 6 段 (旧 5 段太长, 绕不成圈); 前肢补了 scapula / carpus, 后肢补了 meta;
- 脚尖有骨: `toe_front_L/R` 与 `toe_hind_L/R` 各自带 500~680 的权重和, 爪子可以单独调;
- 所有骨局部 X 对齐世界 +X (`align_roll`), App 的俯仰才落在矢状面上。

**蒙皮**: Blender 的 Bone Heat 自动权重在这个资产上整体求解失败 (9550 顶点全零,
报 "failed to find solution for one or more bones"), 因此用 `到骨段距离 1/(d+0.03)³ 取前 4
+ 22 轮网格平滑 + 颈部淡出 + 权重归一化`, 与旧资产同一套已验证算法。

**效果 (离线实测, 与 App 逐位一致)**:

| 姿态 | 轮廓高度 | 边长拉伸 p99 |
|---|---|---|
| 站姿 | 1.138 | 1.07 |
| 端坐 (后腿深折: 髋 -64 / 膝 +78 / 跗 -100) | 1.136 | 1.52 |
| 农民揣 | 0.925 | 1.63 |
| 蜷缩 | 0.897 | 1.76 |

旧资产同类姿态 p99 在 2.3 以上 (髋/肩关节被撕开), 所以"后腿没弯下去""看着惊悚"的本质是
**骨架结构 + 权重**, 不是模型。

**注册表**: 预设角度在 `model/CatPosePresets.kt` (9 个预设 × 骨骼 → [pitch,yaw,roll]),
由求解器生成; `PosePresets.buildCatPose` 现在只是"查表 + 语义滑杆叠加", 不再手写 if/when。
面板语义键: `cat_head_pitch` / `cat_head_yaw` (新骨架头骨朝前 → 转头绕局部 Z) / `cat_spine_arch`
(摊到脊柱 4 段) / 四肢各键摊到两段关节 / `cat_tail_pitch` / `cat_tail_yaw` (6 段递减联动)。

## 12. 脊柱中段骨 spine_mid (上一轮, 已被 §11 取代)

原骨架躯干只有 `Hips → Spine → Chest` 两个旋转轴心, 且 Chest 的权重被肩骨抢走, 蜷缩/趴伏只能
靠程序化顶点弯曲 (那条路径会按 cosθ 缩放截面, 实机表现为"躯干被拉长变形", 已废弃)。

`scripts/model/add_spine_mid.py` 对 GLB 做二进制手术:

- 在 `Spine` 骨中点 (Spine 骨长的 50%) 插入节点 `spine_mid`, 朝向与 Spine 相同, `Chest` 改挂到它下面;
  **全部绑定姿态世界矩阵保持不变**, 所以静止姿态逐顶点零形变;
- `spine_mid` 追加到 `skins[0].joints` **末尾** (索引 29), 既有 `JOINTS_0` 索引一个都不动;
- `inverseBindMatrices` accessor 从 29 条扩到 30 条 (该 bufferView 正好在 BIN 末尾, 直接追加 64B);
- 重蒙皮只重分配**已有的躯干权重** (Hips/Spine/Chest 之和保持不变), 其它骨骼权重逐字节不变;
  躯干四骨按 `1/(d+0.03)³` 分配 + 网格上 12 轮平滑 + 同位置重复顶点取均值 (防形变裂缝)。

核对结果: 静止姿态逐顶点位移 0; `world × inverseBind` 误差 2.9e-7; 权重和 1.0; 2308 组同位置
重复顶点权重完全一致; 30 关节, 资产 608808 B (sha256 `3746ac75…`)。回归测试
`SkeletonPoseTest.cat rig has a mid spine joint that actually bends the torso` 守住这条链路
(关节存在 + 权重归一 + `spine_mid` 旋转确实带动躯干顶点)。

面板上对应"脊柱中段"滑杆 (`cat_spine_mid`, ±30°), 用来补弓背/塌腰; 预设里端坐与趴睡已经用到它。

## 9. 仍可继续优化 (未做)

1. 视口每个三角面一次 `canvas.drawPath`, 猫猫 9.5k 面约 4.7k 次调用/帧 —— 可用 `drawVertices` 批成 1 次
   (代价: 轮廓失去抗锯齿, 且需要每帧构建 flat 顶点/颜色数组), 需真机量测再决定;
2. 双骨解析 IK (拖手/拖脚带动整条链) —— 需要在 `Skeleton3D` 上加链求解并处理翻滚轴的自由度;
3. 姿态切换过渡插值 (目前是瞬切);
4. 蒙皮权重在加载后做一次邻域平滑, 减少大角度下的蝴蝶结与塌陷。

## 13. UniRig 骨架 + 用户微调 + 重算蒙皮 (2026-10 末, 当前主线)

本节记录一次完整的"自动绑定 → 人工调点 → 重算蒙皮"闭环, 以及途中排掉的错误方向。
教训密度很高, 改资产前建议先读完。

### 13.1 资产现状

`app/src/main/assets/3d_models/anime_cat.glb` = **原网格 (未改一个顶点) + 43 关节骨架 + 重算权重**

| 项 | 值 |
|---|---|
| 关节 | 43 = UniRig 预测 40 + 手工增补 3 (`lumbar` / `forearm_L` / `forearm_R`) |
| 权重 | UniRig Colab 完整配置预测 (32768 采样点) + 顶点迁移 + 串扰清理 |
| 自检 | 权重和恒 1.0; `world × inverseBind = I` 偏差 0.0000; 左右串扰 0 |

关节命名: `root`, `lumbar`, `spine_01..06`, `head`, `ear_L/R`(+`ear_tip_*`),
`tail_01..08`, `scapula/shoulder/forearm/elbow/carpus/toe_front` ×L/R,
`hip/knee/hock/meta/toe_hind` ×L/R。**没有** `muzzle` / `chest` / `neck`。

### 13.2 完整流水线 (脚本都在 `build/rigless/`)

```
① 网页拖关节   joint-editor.html   (three.js 单文件, 浏览器里点球拖动)
                  ↓ 导出 joint_offsets.json (每个关节的世界偏移)
② 烘焙位置     bake_offsets.py     改局部 translation + 全部重算 IB
                  ↓ anime_cat_v1x.glb
③ 骨架转 npz   make_skeleton_npz.py   glTF Y-up → Blender Z-up, 并按拓扑序重排
                  ↓ predict_skeleton.npz (43 关节)
④ 算权重       Colab: unirig_skin_colab.ipynb (32768 采样点, T4)
                  ↓ predict_skin.npz
⑤ 迁移到顶点   apply_skin.py       KD 最近邻 12 点 + 4 轮边平滑
⑥ 清理串扰     clean_weights.py    过远剔除 + 左右分侧
                  ↓ 最终 GLB
```

本地 4 GB 显存也能跑 ④ 的低配版 (`inference_skin_lowmem.yaml`: num_samples 8192 / grid 128),
但质量与完整版基本持平 —— **瓶颈不在采样密度**。

### 13.3 七个坑 (按踩到的顺序)

1. **glTF 节点的 `matrix` 不能直接删**: 烘焙位置时若节点用 `matrix` 存变换, 只写
   `translation` 会把旋转一起丢掉 → 子树整体转向、头颈乱飞、前肢硬转折。
   必须先分解出 (T, R, S) 再只替换 T。
2. **子骨改挂父级后必须重写局部矩阵**: 否则整段偏移半个骨长 (IB 偏差 0.14)。
3. **改任意关节后要重算全部 IB**: 父骨一动, 所有后代的世界矩阵都变。
4. **VEC4 float 的字节步长是 16 不是 4**: 写 WEIGHTS_0 时用错会让权重互相覆盖 (和变 4.0)。
5. **UniRig 要求父索引 < 子索引**: 后插入的关节直接放末尾会报
   `new order does not form a tree`, 必须按拓扑序重排并记录索引映射。
6. **采样点权重迁移会左右串扰**: 最近邻在两腿内侧会跨边取点, 表现为"拉左腿右趾跟着动"
   (实测 29 个顶点位移最大 0.136)。用 `clean_weights.py` 的"过远剔除 + 左右分侧"解决。
7. **App 侧改关节位置时要区分两种语义**:
   - 姿态平移 (`_tx/_ty/_tz`, 进 `PosePresets.buildTranslations`) → 会带着顶点走, **网格变形**
   - 绑定位置偏移 (`Skeleton3D.withRestOffsets`) → 只挪旋转轴心, **静止网格不变**
   另外 `DeformKey`/形变缓存必须把偏移算进缓存键, 否则"拖了没反应"。

### 13.4 排掉的错误方向 (都有实测数据)

| 假设 | 结论 |
|---|---|
| 换更好的骨架能解决端坐/蜷缩 | ❌ 自搭 36 骨 / spine_mid / UniRig 三套骨架撞同一堵墙 |
| 换 DQS 蒙皮能解决 | ❌ 深折 1.70→1.70、2.19→2.27, 无改善 (拉伸来自权重跨度, 不是扭转塌陷) |
| 距离权重能替代 UniRig | ❌ p99 从 1.70 恶化到 53 |
| Blender Bone Heat 自动权重 | ❌ 该网格 100% 顶点拿不到权重 (`failed to find solution`) |
| 降采样能提升权重质量 | ❌ 32768 与 8192 采样点结果几乎相同 |

### 13.5 姿态指标 (边长拉伸 p99, 判据 ≤1.35 为佳)

| 预设 | p99 | | 预设 | p99 |
|---|---|---|---|---|
| 站 | 1.05 | | 端坐 | 1.30 |
| 农民揣 | 1.32 | | 蜷缩 | 1.27 |

**深折角是硬约束**: 猫的臀部是整块厚体积、股骨埋在体内, 折到接近水平必然互穿;
任何绑法/算法都只能规避。真正的"坐到底/团成球"需要改网格比例, 用户已否决改网格。

### 13.6 快速复跑

```bash
# 位置: 浏览器打开 build/rigless/joint-editor.html (python3 -m http.server 8765)
python3 build/rigless/bake_offsets.py      # 烘焙导出的 joint_offsets.json
python3 build/rigless/make_skeleton_npz.py # 生成 UniRig 输入 npz
# 权重: Colab 跑 unirig_skin_colab.ipynb → 下载 predict_skin.npz
SKIN=~/下载/predict_skin.npz DST=build/rigless/out.glb python3 build/rigless/apply_skin.py
SRC=build/rigless/out.glb DST=build/rigless/final.glb python3 build/rigless/clean_weights.py
```
