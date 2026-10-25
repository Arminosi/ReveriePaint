# 3D 资产开源许可与来源说明 (3D Model Credits & Licenses)

本项目内置的 3D 参考人偶与几何模型遵循开源及公共领域许可

## 1. 人体标准素模 (男 / 女)

- **模型来源**: Blender Studio 官方资产库 (Blender Studio Human Base Meshes - Male & Female Realistic)
- **项目主页**: https://download.blender.org/demo/asset-bundles/human-base-meshes/
- **资产许可**: Creative Commons Zero 1.0 Universal (CC0 1.0 Public Domain)
- **商用与修改**: 允许免版权、免署名用于商业及非商业用途，支持二次修改与格式转换
- **工程处理说明**: 由 ReveriePaint 团队进行解剖骨骼关节球心定位与无扭曲平滑蒙皮权重构建，专为速写起稿与动态人体透视参考设计

## 2. Asaro 头部切面素模

- **模型来源**: Blender Studio 官方基础几何库 (Blender Studio Human Base Meshes - Head Planar)
- **项目主页**: https://download.blender.org/demo/asset-bundles/human-base-meshes/
- **资产许可**: Creative Commons Zero 1.0 Universal (CC0 1.0 Public Domain)
- **工程处理说明**: 经典 Asaro 头部空间折面与打光向导，帮助推敲头颈转折与明暗交界线

## 3. 3D 写实家猫素模

- **模型来源**: Meshy AI 生成并由用户与 ReveriePaint 团队二次精细拓扑与解剖校准
- **资产许可**: CC0 1.0 Public Domain / GPL-3.0-or-later 自由衍生
- **工程处理说明**: 包含完整猫科面部细节、胡须轮廓、立耳、脊椎反弓与 21 节点解剖骨骼蒙皮，专为动物起稿动态与透视参考设计
- **管线与坐标系**: 由 `scripts/model/build_cat_glb.py` 从 177k 顶点的 Meshy 高模 (`previous_cat_model.glb`) 重建：先整体旋转 86° 让身体朝向 +Z，再以"鼻尖测地距离"为权重把原本扭向侧方约 105° 的头部回正并移回身体中线 (过渡带做 Taubin 平滑抑制折痕)，减面到 3897 顶点 / 7798 三角面后重绑骨骼。产物为 glTF 标准朝向 (Y 轴向上、+Z 为面部正前方、左右关于 x=0 对称、原点居中)；骨架为 Hips→Spine→Chest→Neck→Head 主干 + 四段尾骨 + 前肢 (肩胛/前肢/前掌) 与后肢 (大腿/小腿/跗骨/后掌) 三节链，权重为限 4 影响并归一化的骨骼热扩散解算结果

