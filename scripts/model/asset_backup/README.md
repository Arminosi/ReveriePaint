# 猫猫资产备份

- `anime_cat.current.glb` —— 当前进包的版本（含耳骨轴心修正 `fix_cat_ears.py` 与脊柱中段骨
  `add_spine_mid.py` 的改动，30 关节，sha256 `3746ac75…`）。
  改动骨骼/权重前务必从这里回滚：`cp scripts/model/asset_backup/anime_cat.current.glb app/src/main/assets/3d_models/anime_cat.glb`
- 重新生成/改造资产后请同时更新本备份，并在 commit 里写明改了什么、用 `Model3DTest`
  的"静止姿态零形变"契约(`world × inverseBind ≈ I`)与 `preview_cat_poses.py` 渲染核对过。
- 2026-10-27 `add_spine_mid.py`: 在 Spine 骨中点插入 `spine_mid`（关节索引追加到 29，既有
  `JOINTS_0` 索引全部不变），逆绑定矩阵 accessor 从 29 扩到 30，躯干权重按"到骨段距离"重新
  分配到 Hips/Spine/spine_mid/Chest 四根骨并做 12 轮网格平滑。核对结果：静止姿态逐顶点位移 0、
  `world × inverseBind` 误差 2.9e-7、权重和 1.0、同位置重复顶点的权重完全一致。
