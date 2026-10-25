#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""从 Meshy 高模 .blend 重建 3D 猫猫参考资产 (app/src/main/assets/3d_models/anime_cat.glb)

    blender -b --python scripts/model/build_cat_glb.py -- \
        --in "/path/latest_cat_model.blend" --out app/src/main/assets/3d_models/anime_cat.glb

产物契约 (App 侧 GlbParser / Skeleton3D / PoseAdjustmentPanel 依赖):
  * glTF 2.0 GLB, 无材质无动画, POSITION/NORMAL/JOINTS_0/WEIGHTS_0 + indices
  * 朝向: Y 轴向上, +Z 为面部正前方 (等价 Blender 的 -Y), x 关于 0 对称, 原点居中
  * 骨架 21 关节: Hips/Spine/Chest/Neck/Head, tail1..tail4,
    前肢 l|r_front_shoulder → l|r_front_leg → l|r_front_paw,
    后肢 l|r_hind_thigh → l|r_hind_shank → l|r_hind_foot
  * 骨骼局部 +Y 沿骨长 (Blender/glTF 惯例), 局部 X = 世界 -X (尾巴为 +X),
    对应 App 的关节欧拉约定: pitch > 0 = 肢体前摆 / 躯干抬头 / 尾巴上翘
  * 每个顶点至多 4 个骨骼影响, 权重和归一化为 1

注意: 无头环境必须用 bpy.data / bmesh 而不是依赖 GUI 上下文的 bpy.ops;
这里只在 modifier_apply / parent_set(ARMATURE_AUTO) / export_scene.gltf 处用 ops,
这三个在 blender -b 下可用 (已验证 Blender 5.2.3)。
"""
import argparse
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Vector


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default="", help="高模 .blend (留空则用当前场景)")
    ap.add_argument("--out", dest="out", default="/tmp/anime_cat.glb")
    ap.add_argument("--ratio", dest="ratio", type=float, default=0.022, help="Decimate 塌陷比例")
    return ap.parse_args(argv)


# (name, parent, head, tail, local_x_axis, connect) —— Blender 坐标 (Z 向上, 猫面向 -Y)
JOINTS = [
    ("Hips",   None,    (0, 0.22, 0.10),  (0, 0.00, 0.11),  (-1, 0, 0), False),
    ("Spine",  "Hips",  (0, 0.00, 0.11),  (0, -0.30, 0.14), (-1, 0, 0), True),
    ("Chest",  "Spine", (0, -0.30, 0.14), (0, -0.48, 0.22), (-1, 0, 0), True),
    ("Neck",   "Chest", (0, -0.48, 0.22), (0, -0.62, 0.40), (-1, 0, 0), True),
    ("Head",   "Neck",  (0, -0.62, 0.40), (0, -0.66, 0.58), (-1, 0, 0), True),

    ("tail1",  "Hips",  (0, 0.34, 0.16),  (0, 0.50, 0.03),  (+1, 0, 0), False),
    ("tail2",  "tail1", (0, 0.50, 0.03),  (0, 0.66, -0.17), (+1, 0, 0), True),
    ("tail3",  "tail2", (0, 0.66, -0.17), (0, 0.80, -0.22), (+1, 0, 0), True),
    ("tail4",  "tail3", (0, 0.80, -0.22), (0, 0.94, -0.11), (+1, 0, 0), True),

    ("l_front_shoulder", "Chest", (0.05, -0.40, 0.22), (0.10, -0.52, 0.06), (-1, 0, 0), False),
    ("l_front_leg", "l_front_shoulder", (0.10, -0.52, 0.06), (0.095, -0.49, -0.50), (-1, 0, 0), True),
    ("l_front_paw", "l_front_leg", (0.095, -0.49, -0.50), (0.107, -0.60, -0.655), (-1, 0, 0), True),
    ("r_front_shoulder", "Chest", (-0.05, -0.40, 0.22), (-0.10, -0.52, 0.06), (-1, 0, 0), False),
    ("r_front_leg", "r_front_shoulder", (-0.10, -0.52, 0.06), (-0.095, -0.49, -0.50), (-1, 0, 0), True),
    ("r_front_paw", "r_front_leg", (-0.095, -0.49, -0.50), (-0.107, -0.594, -0.655), (-1, 0, 0), True),

    # 后肢为交错站姿: 左后掌 y=+0.244, 右后掌 y=+0.330
    ("l_hind_thigh", "Hips", (0.11, 0.24, 0.10), (0.105, 0.30, -0.22), (-1, 0, 0), False),
    ("l_hind_shank", "l_hind_thigh", (0.105, 0.30, -0.22), (0.10, 0.40, -0.44), (-1, 0, 0), True),
    ("l_hind_foot", "l_hind_shank", (0.10, 0.40, -0.44), (0.127, 0.244, -0.655), (-1, 0, 0), True),
    ("r_hind_thigh", "Hips", (-0.11, 0.28, 0.10), (-0.105, 0.34, -0.22), (-1, 0, 0), False),
    ("r_hind_shank", "r_hind_thigh", (-0.105, 0.34, -0.22), (-0.10, 0.42, -0.44), (-1, 0, 0), True),
    ("r_hind_foot", "r_hind_shank", (-0.10, 0.42, -0.44), (-0.134, 0.33, -0.655), (-1, 0, 0), True),
]


def main():
    args = parse_args()
    if args.src:
        bpy.ops.wm.open_mainfile(filepath=os.path.abspath(args.src))

    meshes = [o for o in bpy.data.objects if o.type == 'MESH']
    assert len(meshes) == 1, f"expected exactly one mesh in source, got {[o.name for o in meshes]}"
    src = meshes[0]
    for o in list(bpy.data.objects):
        if o.name != src.name:
            bpy.data.objects.remove(o, do_unlink=True)

    # ---- 1. 减面 ------------------------------------------------------
    ob = src.copy()
    ob.data = src.data.copy()
    ob.name = "CAT_LOW"
    ob.data.name = "CAT_LOW_mesh"
    bpy.context.scene.collection.objects.link(ob)
    bpy.context.view_layer.objects.active = ob
    dec = ob.modifiers.new("dec", 'DECIMATE')
    dec.decimate_type = 'COLLAPSE'
    dec.ratio = args.ratio
    dec.use_collapse_triangulate = True
    bpy.ops.object.modifier_apply(modifier="dec")
    print(f"[cat] decimated: {len(ob.data.vertices)} verts / {len(ob.data.polygons)} tris")

    # ---- 2. 骨架 ------------------------------------------------------
    arm_data = bpy.data.armatures.new("CatArmature")
    arm = bpy.data.objects.new("CatArmature", arm_data)
    bpy.context.scene.collection.objects.link(arm)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.mode_set(mode='EDIT')
    for name, parent, head, tail, xax, _conn in JOINTS:
        eb = arm_data.edit_bones.new(name)
        eb.head = Vector(head)
        eb.tail = Vector(tail)
        # 目标: 局部 X = xax; 局部 Z = X × Y (右手系), 用 align_roll 指定 Z
        eb.align_roll(Vector(xax).normalized().cross((Vector(tail) - Vector(head)).normalized()))
    for name, parent, _h, _t, _x, conn in JOINTS:
        if parent:
            eb = arm_data.edit_bones[name]
            eb.parent = arm_data.edit_bones[parent]
            eb.use_connect = conn
    bpy.ops.object.mode_set(mode='OBJECT')

    # ---- 3. 蒙皮 + 权重收敛 -------------------------------------------
    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.parent_set(type='ARMATURE_AUTO')

    names = [g.name for g in ob.vertex_groups]
    n_verts = len(ob.data.vertices)
    W = np.zeros((n_verts, len(names)))
    for v in ob.data.vertices:
        for ge in v.groups:
            W[v.index, ge.group] = ge.weight
    order = np.argsort(-W, axis=1)[:, :4]
    W4 = np.zeros_like(W)
    rows = np.arange(n_verts)
    for k in range(4):
        W4[rows, order[:, k]] = W[rows, order[:, k]]
    total = W4.sum(1, keepdims=True)
    unweighted = total[:, 0] < 1e-6
    print(f"[cat] unweighted verts: {int(unweighted.sum())}")
    W4[~unweighted] /= total[~unweighted]
    W4[unweighted] = 0.0
    for g in list(ob.vertex_groups):
        ob.vertex_groups.remove(g)
    groups = [ob.vertex_groups.new(name=n) for n in names]
    for i in range(n_verts):
        for j in np.nonzero(W4[i] > 1e-5)[0]:
            groups[j].add([i], float(W4[i, j]), 'REPLACE')

    for pb in arm.pose.bones:
        pb.rotation_mode = 'QUATERNION'
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.location = (0, 0, 0)
    for o in (ob, arm):
        o.location = (0, 0, 0)
        o.rotation_euler = (0, 0, 0)
        o.scale = (1, 1, 1)

    V = np.array([v.co[:] for v in ob.data.vertices])
    print(f"[cat] bbox blender {V.min(0).round(3)} .. {V.max(0).round(3)}")
    print(f"[cat] x symmetry center {round(float((V[:, 0].min() + V[:, 0].max()) / 2), 4)}")

    # ---- 4. 导出 ------------------------------------------------------
    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = ob
    bpy.ops.export_scene.gltf(
        filepath=os.path.abspath(args.out),
        export_format='GLB',
        use_selection=True,
        export_apply=False,
        export_yup=True,
        export_animations=False,
        export_skins=True,
        export_morph=False,
        export_materials='NONE',
        export_normals=True,
        export_tangents=False,
        export_texcoords=False,
        export_extras=False,
        export_cameras=False,
        export_lights=False,
        export_def_bones=False,
    )
    print(f"[cat] exported {args.out} ({os.path.getsize(args.out)} bytes)")


if __name__ == "__main__":
    main()
