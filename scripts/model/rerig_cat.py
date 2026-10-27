#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""只重做猫猫的骨架与蒙皮, 网格原样保留 (用 Blender 的自动权重)

    blender -b --factory-startup --python scripts/model/rerig_cat.py -- \
        --in app/src/main/assets/3d_models/anime_cat.glb \
        --out build/3d-rig/v3/anime_cat.glb

为什么是"只换骨架": 原模型的网格与画风没问题, 出问题的是 Meshy 自带的那套骨架
(颈骨 0.41 长、胸骨 0.17 长且朝前下、股骨静止朝下) 以及按距离硬算的权重 —— 大折角下
髋/肩关节会撕开。本次:
  1) 骨架按猫的解剖重排: 躯干 5 段 (骨盆 + spine_01..03 + 胸), 颈 1 段, 头, 尾 6 段,
     前后肢各 5 段 (scapula/humerus/forearm/pastern/toe 与 hip/knee/hock/meta/toe);
  2) 蒙皮改用 Blender 的 Bone Heat 自动权重 (按骨体积扩散, 关节处过渡自然),
     再 limit total 4 + 归一化, 与 App 的 4 权重 LBS 对齐;
  3) 所有骨的局部 X 对齐世界 +X: App 的俯仰全部绕局部 X, 这样俯仰落在矢状面上。

坐标: 输入/输出都是 glTF 约定 (+Y 上, +Z 朝前); Blender 内部是 (x, -z, y)。
"""
import argparse
import json
import os
import sys

import bpy
import numpy as np
from mathutils import Vector

# 新骨架: 名字 -> (glTF 位置, 父骨)。位置按原网格的实际形态摆放 (躯干 z -0.32..0.45,
# 腿从 y≈0.07 落到 y≈-0.53, 尾巴从 z=-0.32 向后下方走到 z=-0.75)。
SPEC = []


ROUNDS = 22


def _j(name, pos, parent):
    SPEC.append((name, np.array(pos, dtype=float), parent))


_j('root',      (0.000,  0.150, -0.300), None)
_j('spine_01',  (0.000,  0.105, -0.190), 'root')
_j('spine_02',  (0.000,  0.075, -0.070), 'spine_01')
_j('spine_03',  (0.000,  0.085,  0.070), 'spine_02')
_j('chest',     (0.000,  0.115,  0.210), 'spine_03')
_j('neck',      (0.000,  0.040,  0.380), 'chest')
_j('head',      (0.000,  0.300,  0.520), 'neck')
_j('muzzle',    (0.000,  0.290,  0.690), 'head')
for s, x in (('_L', 0.070), ('_R', -0.070)):
    _j('ear' + s, (x, 0.430, 0.630), 'head')
for s, x in (('_L', 0.075), ('_R', -0.075)):
    _j('scapula' + s,    (x,  0.120,  0.440), 'chest')
    _j('shoulder' + s,   (x,  0.030,  0.500), 'scapula' + s)
    _j('elbow' + s,      (x, -0.120,  0.410), 'shoulder' + s)
    _j('carpus' + s,     (x, -0.310,  0.410), 'elbow' + s)
    _j('toe_front' + s,  (x, -0.500,  0.440), 'carpus' + s)
for s, x in (('_L', 0.075), ('_R', -0.075)):
    _j('hip' + s,        (x,  0.120, -0.140), 'root')
    _j('knee' + s,       (x, -0.020, -0.100), 'hip' + s)
    _j('hock' + s,       (x, -0.150, -0.260), 'knee' + s)
    _j('meta' + s,       (x, -0.370, -0.330), 'hock' + s)
    _j('toe_hind' + s,   (x, -0.520, -0.270), 'meta' + s)
TAIL = [(0.000, 0.150, -0.360), (0.000, 0.100, -0.460), (0.000, 0.020, -0.550),
        (0.000, -0.080, -0.610), (0.000, -0.170, -0.660), (0.000, -0.260, -0.700)]
for i, p in enumerate(TAIL):
    _j('tail_%02d' % (i + 1), p, 'root' if i == 0 else 'tail_%02d' % i)
# 末端骨: 用"子骨位置"当尾巴; 叶子骨的 tail 指向下游 (尾巴/耳朵/脚趾)
LEAF_TAIL = {
    'muzzle': (0.000, 0.300, 0.780),
    'ear_L': (0.090, 0.520, 0.780), 'ear_R': (-0.090, 0.520, 0.780),
    'toe_front_L': (0.075, -0.545, 0.470), 'toe_front_R': (-0.075, -0.545, 0.470),
    'toe_hind_L': (0.075, -0.560, -0.230), 'toe_hind_R': (-0.075, -0.560, -0.230),
    'tail_06': (0.000, -0.330, -0.720),
}


def to_blender(p):
    """glTF (x, y 上, z 前) -> Blender (x, -z, y)"""
    return Vector((float(p[0]), -float(p[2]), float(p[1])))


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument('--in', dest='src', required=True)
    ap.add_argument('--out', dest='out', required=True)
    ap.add_argument('--report', default=None)
    a = ap.parse_args(argv)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=a.src)
    meshes = [o for o in bpy.data.objects if o.type == 'MESH']
    arms = [o for o in bpy.data.objects if o.type == 'ARMATURE']
    print('[in] meshes:', [(o.name, len(o.data.vertices)) for o in meshes],
          'armatures:', [o.name for o in arms])
    if not meshes:
        sys.exit('没有网格')
    # 取顶点最多的那个网格当猫身, 其余网格对象 (可能是导入产生的空占位) 丢弃
    meshes.sort(key=lambda o: -len(o.data.vertices))
    mesh = meshes[0]
    for o in meshes[1:]:
        print('[in] 丢弃多余网格对象:', o.name, len(o.data.vertices))
        bpy.data.objects.remove(o, do_unlink=True)
    print('[in] mesh %s: %d 顶点 / %d 面' % (mesh.name, len(mesh.data.vertices),
                                              len(mesh.data.polygons)))
    # 丢掉旧骨架/旧权重/旧父子关系, 网格顶点位置一个都不动
    for o in arms:
        bpy.data.objects.remove(o, do_unlink=True)
    mesh.parent = None
    for md in list(mesh.modifiers):
        if md.type == 'ARMATURE':
            mesh.modifiers.remove(md)
    for g in list(mesh.vertex_groups):
        mesh.vertex_groups.remove(g)
    mesh.matrix_world = mesh.matrix_world.Identity(4)

    # ---- 新骨架 ----
    arm_data = bpy.data.armatures.new('CatRig')
    arm = bpy.data.objects.new('CatRig', arm_data)
    bpy.context.collection.objects.link(arm)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.mode_set(mode='EDIT')
    eb = arm_data.edit_bones
    for name, pos, parent in SPEC:
        b = eb.new(name)
        b.head = to_blender(pos)
        child = next((p for n, p, par in SPEC if par == name), None)
        if child is not None:
            b.tail = to_blender(next(p for n, p, par in SPEC if n == next(
                n2 for n2, p2, p3 in SPEC if p3 == name)))
        elif name in LEAF_TAIL:
            b.tail = to_blender(LEAF_TAIL[name])
        else:
            b.tail = b.head + Vector((0.0, 0.0, 0.03))
        if parent:
            b.parent = eb[parent]
        d = (b.tail - b.head)
        if d.length > 1e-6:
            d.normalize()
            z = Vector((1.0, 0.0, 0.0)).cross(d)
            if z.length > 1e-4:
                b.align_roll(z.normalized())
    bpy.ops.object.mode_set(mode='OBJECT')
    print('[rig] %d 关节' % len(arm_data.bones))

    # ---- 清理网格: 只焊接重复顶点 / 去掉孤立点, 表面形状不变 ----
    # 源资产是碎壳拼的 (12308 顶点里 2758 个位置重复), Bone Heat 在这种网格上会求解失败
    bpy.ops.object.select_all(action='DESELECT')
    mesh.select_set(True)
    bpy.context.view_layer.objects.active = mesh
    before = len(mesh.data.vertices)
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.mesh.remove_doubles(threshold=0.0005)
    bpy.ops.mesh.delete_loose()
    bpy.ops.mesh.normals_make_consistent(inside=False)
    bpy.ops.object.mode_set(mode='OBJECT')
    print('[clean] 顶点 %d -> %d, 面 %d' % (before, len(mesh.data.vertices),
                                          len(mesh.data.polygons)))

    # ---- 蒙皮权重 ----
    # Blender 的 Bone Heat 在这个资产上整体求解失败 (9550 顶点全零, 报 "failed to find solution"),
    # 因此用与旧资产同一套已验证算法: 到骨段距离 1/(d+0.03)^3 取前 4 + 网格上多轮平滑。
    # Blender 的自动权重只是"按骨体积扩散"的近似, 这里做的是它的离散版本, 但对碎壳/开放
    # 网格没有求解失败的风险。
    bones = [(b.name, np.array([*b.head_local]), np.array([*b.tail_local]))
             for b in arm.data.bones]
    V = np.array([v.co[:] for v in mesh.data.vertices])          # Blender 空间 (x, -z, y)
    P = np.stack([V[:, 0], -V[:, 2], V[:, 1]], axis=1)           # 换回 glTF 空间算距离
    BP = [(n, np.array([h[0], -h[2], h[1]]), np.array([t[0], -t[2], t[1]])) for n, h, t in bones]

    def seg_dist(A, B):
        AB = B - A
        L2 = float(AB @ AB)
        if L2 < 1e-12:
            return np.linalg.norm(P - A, axis=1)
        t = np.clip(((P - A) @ AB) / L2, 0, 1)
        return np.linalg.norm(P - (A + np.outer(t, AB)), axis=1)

    D = np.stack([seg_dist(h, t) for _, h, t in BP], 1)
    INFL = 1.0 / (D + 0.03) ** 3
    MAXINF = 4
    rows = np.arange(len(P))
    best = np.argsort(-INFL, axis=1)[:, :MAXINF]
    W = np.zeros_like(INFL)
    for k in range(MAXINF):
        W[rows, best[:, k]] = INFL[rows, best[:, k]]
    W /= W.sum(1, keepdims=True)

    edges = np.array([tuple(e.vertices) for e in mesh.data.edges], dtype=np.int64)
    e2 = np.vstack([edges, edges[:, ::-1]])
    deg = np.bincount(e2[:, 0], minlength=len(P)).astype(np.float64)
    deg[deg == 0] = 1
    for _ in range(ROUNDS):
        acc = np.zeros_like(W)
        np.add.at(acc, e2[:, 0], W[e2[:, 1]])
        W = 0.55 * W + 0.45 * (acc / deg[:, None])
        W /= W.sum(1, keepdims=True)

    # 颈不要驱动颅骨 (旧资产踩过: Head 一根骨独占 66%)
    name_i = {n: i for i, (n, h, t) in enumerate(BP)}
    if 'head' in name_i and 'neck' in name_i:
        hy = float(BP[name_i['head']][1][1])
        fade = np.clip((hy + 0.02 - P[:, 1]) / 0.30, 0.0, 1.0)
        W[:, name_i['neck']] *= fade
        W /= np.maximum(W.sum(1, keepdims=True), 1e-9)

    best = np.argsort(-W, axis=1)[:, :MAXINF]
    W4 = np.zeros_like(W)
    for k in range(MAXINF):
        W4[rows, best[:, k]] = W[rows, best[:, k]]
    ssum = W4.sum(1, keepdims=True)
    bad = ssum[:, 0] < 1e-6
    W4[~bad] /= ssum[~bad]
    if bad.any():
        closest = np.argmin(D, axis=1)
        for bi in np.where(bad)[0]:
            W4[bi, closest[bi]] = 1.0
    W4 /= np.maximum(W4.sum(1, keepdims=True), 1e-9)
    print('[skin] 平滑 %d 轮, 未加权恢复 %d' % (ROUNDS, int(bad.sum())))

    for g in list(mesh.vertex_groups):
        mesh.vertex_groups.remove(g)
    groups = [mesh.vertex_groups.new(name=n) for n, h, t in bones]
    for i in range(len(P)):
        nz = np.nonzero(W4[i] > 1e-4)[0]
        if len(nz) == 0:
            groups[int(np.argmin(D[i]))].add([i], 1.0, 'REPLACE')
        else:
            for j in nz:
                groups[j].add([i], float(W4[i, j]), 'REPLACE')
    md = mesh.modifiers.new('Armature', 'ARMATURE')
    md.object = arm
    mesh.parent = arm
    for name in ('root', 'chest', 'head', 'hip_L', 'knee_L', 'tail_03'):
        gi = groups[bones.index(next(bn for bn in bones if bn[0] == name))].index
        cnt = sum(1 for v in mesh.data.vertices for g in v.groups
                  if g.group == gi and g.weight > 0.05)
        print('   %-10s 影响顶点(>0.05) %d' % (name, cnt))

    if a.report:
        chain = {b.name: (b.children[0].name if len(b.children) == 1 else None)
                 for b in arm.data.bones}
        json.dump({'chain': chain}, open(a.report, 'w'), indent=1, ensure_ascii=False)
        print('[report]', a.report)

    bpy.ops.object.select_all(action='DESELECT')
    mesh.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = mesh
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=a.out, export_format='GLB', use_selection=True,
                              export_apply=False, export_yup=True, export_animations=False,
                              export_skins=True, export_morph=False, export_materials='NONE',
                              export_normals=True, export_tangents=False, export_texcoords=False,
                              export_extras=False, export_cameras=False, export_lights=False,
                              export_def_bones=False)
    print('[done]', a.out, os.path.getsize(a.out), 'bytes')


if __name__ == '__main__':
    main()
