#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""修复并重绑 3D 猫猫参考资产 (anime_cat.glb)

    blender -b --python scripts/model/build_cat_glb.py -- \
        --in app/src/main/assets/3d_models/anime_cat.glb \
        --out app/src/main/assets/3d_models/anime_cat.glb

Meshy 导出的这套骨骼有几处硬伤, 本脚本逐条修掉:
  1) 焊接重复顶点: 源网格是 652 个碎壳 (11547 条边界边), 直接热扩散求解会失败
  2) Spine / Neck 的骨起点分别在 z=+18.4 / z=+23.0, 远在模型之外 ——
     任何旋转都会把上半身甩飞; 改为贴到父骨末端
  3) Head 骨改为竖直: App 的 yaw 绕骨骼局部 Y 轴旋转, 骨头一歪, 转头就变侧翻
  4) headend 改名 muzzle: App 的 humanoid 启发式按"名字含 head"匹配,
     headend 会顶掉真正的 Head, 让头部旋转被乘两次
  5) 权重重算: 源资产 Head 一根骨独占 66% 权重、躯干没有脊柱权重;
     改为"到骨线段距离 1/d^3 取前 4 + 18 轮表面平滑", 并让 Neck 的影响在颅骨内淡出
  6) 居中到原点 (源模型整体悬在 y>0, 在 App 里会画到画布上方之外)
  7) 丢掉 glTF 里的 walk 动画轨道 (App 的 3D 参考层不消费动画)
"""
"""Repair the new cat's rig: apply transforms -> fix bone heads/axes -> re-skin -> centre -> export."""
import bpy, math, os, sys, argparse
import numpy as np
from mathutils import Vector, Matrix

_argv = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
_ap = argparse.ArgumentParser()
_ap.add_argument("--in", dest="src", required=True)
_ap.add_argument("--out", dest="out", required=True)
_a = _ap.parse_args(_argv)
SRC, OUT = _a.src, _a.out
MAXINF = 4

bpy.ops.wm.read_factory_settings(use_empty=True)
for o in list(bpy.data.objects):
    bpy.data.objects.remove(o, do_unlink=True)
bpy.ops.import_scene.gltf(filepath=SRC)
arm = [o for o in bpy.data.objects if o.type == 'ARMATURE'][0]
me = [o for o in bpy.data.objects if o.type == 'MESH' and o.parent is arm][0]
if arm.animation_data:
    arm.animation_data_clear()
for a in list(bpy.data.actions):
    bpy.data.actions.remove(a)

# weld the shattered source mesh
bpy.context.view_layer.objects.active = me
me.select_set(True)
bpy.ops.object.mode_set(mode='EDIT')
bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.mesh.remove_doubles(threshold=0.0008)
bpy.ops.object.mode_set(mode='OBJECT')
print("[weld] %d verts / %d tris" % (len(me.data.vertices), len(me.data.polygons)))

# bake the import rotation so armature space == world space (Blender Z-up)
bpy.ops.object.select_all(action='DESELECT')
arm.select_set(True); me.select_set(True)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
print("[apply] rotations baked; arm rot", tuple(round(v, 3) for v in arm.matrix_world.to_euler()))

# ---- rig repair (Blender space: +Z up, -Y forward) -------------------------
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode='EDIT')
eb = arm.data.edit_bones
print("[rig] Spine.head was", tuple(round(v, 3) for v in eb['Spine'].head),
      " Neck.head was", tuple(round(v, 3) for v in eb['Neck'].head))
eb['Spine'].head = eb['Hips'].tail.copy(); eb['Spine'].use_connect = True
eb['Neck'].head = eb['Chest'].tail.copy(); eb['Neck'].use_connect = True
# the app's "yaw" rotates about the bone local Y: the head bone must be vertical
hd = eb['Head']
hd.tail = hd.head + Vector((0.0, 0.0, 0.16))
# 'headend' would be caught by the app's humanoid heuristic (contains "head")
for nm in ('headend',):
    if nm in eb:
        eb[nm].name = 'muzzle'
print("[rig] Head bone", tuple(round(v, 3) for v in hd.head), "->", tuple(round(v, 3) for v in hd.tail))
bpy.ops.object.mode_set(mode='OBJECT')

# ---- custom skinning (distance to bone segments) ---------------------------
bones = [(b.name, np.array([*b.head_local]), np.array([*b.tail_local])) for b in arm.data.bones]
V = np.array([v.co[:] for v in me.data.vertices])          # local == world now
print("[skin] %d bones, mesh bbox %s %s" % (len(bones), V.min(0).round(3), V.max(0).round(3)))


def seg_dist(P, A, B):
    AB = B - A
    L2 = float(AB @ AB)
    if L2 < 1e-12:
        return np.linalg.norm(P - A, axis=1)
    t = np.clip(((P - A) @ AB) / L2, 0, 1)
    return np.linalg.norm(P - (A + np.outer(t, AB)), axis=1)


D = np.stack([seg_dist(V, h, t) for _, h, t in bones], 1)
INFL = 1.0 / (D + 0.03) ** 3
best = np.argsort(-INFL, axis=1)[:, :MAXINF]
rows = np.arange(len(V))
W = np.zeros_like(INFL)
for k in range(MAXINF):
    W[rows, best[:, k]] = INFL[rows, best[:, k]]
W /= W.sum(1, keepdims=True)

# smooth weights over the mesh graph
edges = np.array([tuple(e.vertices) for e in me.data.edges], dtype=np.int64)
e2 = np.vstack([edges, edges[:, ::-1]])
deg = np.bincount(e2[:, 0], minlength=len(V)).astype(np.float64)
deg[deg == 0] = 1
for _ in range(18):
    acc = np.zeros_like(W)
    np.add.at(acc, e2[:, 0], W[e2[:, 1]])
    W = 0.55 * W + 0.45 * (acc / deg[:, None])
    W /= W.sum(1, keepdims=True)

# the neck must not drive the skull
neck_i = [i for i, (n, h, t) in enumerate(bones) if n == 'Neck'][0]
head_i = [i for i, (n, h, t) in enumerate(bones) if n == 'Head'][0]
yj = float(bones[head_i][1][2])          # head joint height (Blender Z)
fade = np.clip((yj + 0.02 - V[:, 2]) / 0.30, 0.0, 1.0)
W[:, neck_i] *= fade
W /= np.maximum(W.sum(1, keepdims=True), 1e-9)

best = np.argsort(-W, axis=1)[:, :MAXINF]
W4 = np.zeros_like(W)
for k in range(MAXINF):
    W4[rows, best[:, k]] = W[rows, best[:, k]]
s = W4.sum(1, keepdims=True)
bad = s[:, 0] < 1e-6
W4[~bad] /= s[~bad]
W4[bad] = 0
print("[skin] unweighted %d" % int(bad.sum()))
for j, (n, h, t) in enumerate(bones):
    w = W4[:, j]
    if w.sum() > 0.5:
        print("   %-18s verts>0.05 %5d sum %7.1f max %.2f" % (n, int((w > 0.05).sum()), w.sum(), w.max()))

for g in list(me.vertex_groups):
    me.vertex_groups.remove(g)
groups = [me.vertex_groups.new(name=n) for n, h, t in bones]
for i in range(len(V)):
    for j in np.nonzero(W4[i] > 1e-4)[0]:
        groups[j].add([i], float(W4[i, j]), 'REPLACE')

# ---- centre on the origin --------------------------------------------------
ctr = (V.min(0) + V.max(0)) / 2
me.data.transform(Matrix.Translation(-Vector(ctr.tolist())))
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode='EDIT')
for eb2 in arm.data.edit_bones:
    eb2.head = eb2.head - Vector(ctr.tolist())
    eb2.tail = eb2.tail - Vector(ctr.tolist())
bpy.ops.object.mode_set(mode='OBJECT')
print("[centre] shift", ctr.round(3))

if not any(m.type == 'ARMATURE' for m in me.modifiers):
    md = me.modifiers.new("Armature", 'ARMATURE')
    md.object = arm
for p in me.data.polygons:
    p.use_smooth = True
for pb in arm.pose.bones:
    pb.rotation_mode = 'QUATERNION'
    pb.rotation_quaternion = (1, 0, 0, 0)
    pb.location = (0, 0, 0)
for o in (me, arm):
    o.location = (0, 0, 0)
    o.rotation_euler = (0, 0, 0)
    o.scale = (1, 1, 1)
Vf = np.array([v.co[:] for v in me.data.vertices])
print("[final] bbox", Vf.min(0).round(3), Vf.max(0).round(3))

bpy.ops.object.select_all(action='DESELECT')
me.select_set(True); arm.select_set(True)
bpy.context.view_layer.objects.active = me
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', use_selection=True, export_apply=False,
                          export_yup=True, export_animations=False, export_skins=True, export_morph=False,
                          export_materials='NONE', export_normals=True, export_tangents=False,
                          export_texcoords=False, export_extras=False, export_cameras=False,
                          export_lights=False, export_def_bones=False)
print("[done]", OUT, os.path.getsize(OUT))
