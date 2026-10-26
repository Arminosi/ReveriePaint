# 猫猫资产备份

- `anime_cat.current.glb` —— 当前进包的版本（含耳骨轴心修正 `fix_cat_ears.py` 的改动）。
  改动骨骼/权重前务必从这里回滚：`cp scripts/model/asset_backup/anime_cat.current.glb app/src/main/assets/3d_models/anime_cat.glb`
- 重新生成/改造资产后请同时更新本备份，并在 commit 里写明改了什么、用 `Model3DTest`
  的"静止姿态零形变"契约(`world × inverseBind ≈ I`)与 `preview_cat_poses.py` 渲染核对过。
