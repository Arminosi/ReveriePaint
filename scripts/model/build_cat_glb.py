#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""从 Meshy 高模 previous_cat_model.glb 重建 3D 猫猫参考资产 (anime_cat.glb)

    blender -b --python scripts/model/build_cat_glb.py -- \
        --in "/path/previous_cat_model.glb" --out app/src/main/assets/3d_models/anime_cat.glb

原始资产是一只"身体朝向 -X、头扭向 +Z 约 105°"的猫。本脚本:
  1) 整体绕竖直轴旋转 86° 让身体朝向 +Z (glTF 标准正面)
  2) 以"鼻尖测地距离"作权重把头部回正 105° 并移回身体中线
     (过渡带内做 Taubin 平滑: 只松弛折痕, 不做体积收缩)
  3) 减面到 ~3.9k 顶点 / 7.8k 三角面 (与人体素模同级)
  4) 重建 21 节骨骼并做骨骼热扩散蒙皮 (限 4 影响 + 归一化)
  5) 导出无材质/无动画的 GLB

产物契约 (App 侧 GlbParser / Skeleton3D / PoseAdjustmentPanel 依赖):
  * Y 轴向上, +Z 为面部正前方, x 关于 0 对称, 原点居中
  * 21 关节: Hips→Spine→Chest→Neck→Head, tail1..4,
    前肢 l|r_front_shoulder→leg→paw, 后肢 l|r_hind_thigh→shank→foot
  * 骨骼局部 +Y 沿骨长, 局部 X = 世界 -X ⇒ App 的 pitch>0 = 前摆 / 抬头 / 尾巴上翘
  * 每顶点最多 4 骨骼影响, 权重和归一化为 1
"""
import argparse
import heapq
import json
import math
import os
import struct
import sys

import bpy
import numpy as np
from mathutils import Vector

BODY_DEG = 86.0
HEAD_DEG = -113.0
D0, D1 = 0.36, 0.82
SMOOTH_ITERS = 60
RATIO = 0.022

ORDER = ["Hips", "Spine", "Chest", "Neck", "Head", "tail1", "tail2", "tail3", "tail4",
         "l_front_shoulder", "l_front_leg", "l_front_paw",
         "r_front_shoulder", "r_front_leg", "r_front_paw",
         "l_hind_thigh", "l_hind_shank", "l_hind_foot",
         "r_hind_thigh", "r_hind_shank", "r_hind_foot"]
PARENT = {"Spine": "Hips", "Chest": "Spine", "Neck": "Chest", "Head": "Neck",
          "tail1": "Hips", "tail2": "tail1", "tail3": "tail2", "tail4": "tail3",
          "l_front_shoulder": "Chest", "l_front_leg": "l_front_shoulder", "l_front_paw": "l_front_leg",
          "r_front_shoulder": "Chest", "r_front_leg": "r_front_shoulder", "r_front_paw": "r_front_leg",
          "l_hind_thigh": "Hips", "l_hind_shank": "l_hind_thigh", "l_hind_foot": "l_hind_shank",
          "r_hind_thigh": "Hips", "r_hind_shank": "r_hind_thigh", "r_hind_foot": "r_hind_shank"}
CONNECT = {"Spine", "Chest", "Neck", "Head", "tail2", "tail3", "tail4",
           "l_front_leg", "l_front_paw", "r_front_leg", "r_front_paw",
           "l_hind_shank", "l_hind_foot", "r_hind_shank", "r_hind_foot"}


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="out", required=True)
    ap.add_argument("--ratio", dest="ratio", type=float, default=RATIO)
    return ap.parse_args(argv)


def load_glb(path):
    d = open(path, 'rb').read()
    off, js, binc = 12, None, None
    while off < len(d):
        clen, ctype = struct.unpack('<II', d[off:off + 8])
        payload = d[off + 8:off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(payload.decode())
        elif ctype == 0x004E4942:
            binc = payload
        off += 8 + clen
    acc, bvs = js['accessors'], js['bufferViews']
    prim = js['meshes'][0]['primitives'][0]
    a = acc[prim['attributes']['POSITION']]
    bv = bvs[a['bufferView']]
    V = np.frombuffer(binc, dtype='<f4', count=a['count'] * 3,
                      offset=bv.get('byteOffset', 0) + a.get('byteOffset', 0)).reshape(-1, 3).astype(np.float64)
    ia = acc[prim['indices']]
    ibv = bvs[ia['bufferView']]
    dt = {5121: np.uint8, 5123: '<u2', 5125: '<u4'}[ia['componentType']]
    I = np.frombuffer(binc, dtype=dt, count=ia['count'],
                      offset=ibv.get('byteOffset', 0) + ia.get('byteOffset', 0)).astype(np.int64).reshape(-1, 3)
    return V, I


def geodesic(V, I, seed):
    adj = [[] for _ in range(len(V))]
    for i, j in np.vstack([I[:, [0, 1]], I[:, [1, 2]], I[:, [2, 0]]]):
        i, j = int(i), int(j)
        w = float(np.linalg.norm(V[i] - V[j]))
        adj[i].append((j, w))
        adj[j].append((i, w))
    dist = np.full(len(V), 1e9)
    dist[seed] = 0.0
    pq = [(0.0, seed)]
    while pq:
        dd, u = heapq.heappop(pq)
        if dd > dist[u]:
            continue
        for v, w in adj[u]:
            nd = dd + w
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(pq, (nd, v))
    return dist


def orient(V, I):
    """ cuerpo -> +Z, head -> straight forward, head centred on the body midline """
    t = math.radians(BODY_DEG)
    c, s = math.cos(t), math.sin(t)
    V = V @ np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]).T
    ymin, ymax = V[:, 1].min(), V[:, 1].max()
    H = ymax - ymin
    hc = V[V[:, 1] > ymin + 0.72 * H].mean(0)
    pivot = np.array([hc[0], hc[1] - 0.16, hc[2]])

    nose = int(np.argmax(V[:, 2]))
    dist = geodesic(V, I, nose)
    w = np.clip((D1 - dist) / (D1 - D0), 0.0, 1.0)
    w = w * w * (3 - 2 * w)

    th = math.radians(HEAD_DEG)
    V1 = V.copy()
    idx = np.nonzero(w > 1e-3)[0]
    for i in idx:
        p = V[i] - pivot
        ang = th * w[i]
        ca, sa = math.cos(ang), math.sin(ang)
        V1[i] = pivot + np.array([p[0] * ca + p[2] * sa, p[1], -p[0] * sa + p[2] * ca])
    # 横向对中: 以"头部对称面"为准 (质心会受耳朵/胡须影响, 不准); 迭代两次收敛
    from mathutils.kdtree import KDTree
    for _it in range(2):
        hc1 = V1[w > 0.9].mean(0)
        cloud = V1[(np.linalg.norm(V1 - hc1, axis=1) < 0.30) & (V1[:, 1] > ymin + 0.60 * H)]
        if len(cloud) > 4000:
            cloud = cloud[np.random.default_rng(0).choice(len(cloud), 4000, replace=False)]
        kd = KDTree(len(cloud))
        for i, p in enumerate(cloud):
            kd.insert(Vector(p), i)
        kd.balance()

        def mirror_err(cx):
            mir = cloud * np.array([-1, 1, 1])
            mir[:, 0] += 2 * cx
            return float(np.mean([kd.find(Vector(p))[2] for p in mir]))

        best_c = min(((mirror_err(c), c) for c in np.arange(hc1[0] - 0.25, hc1[0] + 0.25, 0.01)))
        V1[idx, 0] -= best_c[1] * w[idx]
        print("  [center] head symmetry plane x=%+.3f (err %.4f) -> shift %+.3f" % (best_c[1], best_c[0], -best_c[1]))
    print("[orient] head %.0f deg, band %.2f-%.2f" % (HEAD_DEG, D0, D1))

    edges = np.vstack([I[:, [0, 1]], I[:, [1, 2]], I[:, [2, 0]]])
    edges = np.vstack([edges, edges[:, ::-1]])
    deg = np.bincount(edges[:, 0], minlength=len(V1)).astype(np.float64)
    deg[deg == 0] = 1
    band = (w > 0.03) & (w < 0.97)
    lam, mu = 0.55, -0.58
    for _ in range(SMOOTH_ITERS):
        acc = np.zeros_like(V1)
        np.add.at(acc, edges[:, 0], V1[edges[:, 1]])
        V1[band] += lam * ((acc / deg[:, None]) - V1)[band]
        acc = np.zeros_like(V1)
        np.add.at(acc, edges[:, 0], V1[edges[:, 1]])
        V1[band] += mu * ((acc / deg[:, None]) - V1)[band]

    c = (V1.min(0) + V1.max(0)) / 2
    V1 -= np.array([c[0], 0.0, c[2]])
    return V1


def build_rig(V):
    ymin, ymax = V[:, 1].min(), V[:, 1].max()
    H = ymax - ymin
    J = {}

    def add(name, head, tail):
        J[name] = (np.array(head, float), np.array(tail, float))

    add("Hips", (0, -0.02, -0.33), (0, 0.02, -0.10))
    add("Spine", (0, 0.02, -0.10), (0, 0.05, 0.26))
    add("Chest", (0, 0.05, 0.26), (0, 0.20, 0.52))
    add("Neck", (0, 0.20, 0.52), (0, 0.37, 0.63))
    add("Head", (0, 0.37, 0.63), (0, 0.52, 0.67))

    tail = [np.array([0.0, 0.10, -0.40]), np.array([0.0, -0.06, -0.56]),
            np.array([0.0, -0.19, -0.72]), np.array([0.0, -0.16, -0.85]),
            np.array([0.0, -0.03, -0.94])]
    for i in range(4):
        add("tail%d" % (i + 1), tail[i], tail[i + 1])

    def paw(sx, sz):
        band = ymin + 0.05 * H
        sel = V[(np.sign(V[:, 0]) == sx) & (np.sign(V[:, 2]) == sz) & (V[:, 1] < band)]
        return np.array([np.median(sel[:, 0]), sel[:, 1].min(), np.median(sel[:, 2])])

    def centerline(sx, pz, rmax=0.20):
        sel = V[(np.sign(V[:, 0]) == sx) & (np.abs(V[:, 2] - pz) < 0.22) & (V[:, 1] < ymin + 0.50 * H)]
        pc = np.array([np.median(sel[sel[:, 1] < ymin + 0.06 * H][:, 0]),
                       np.median(sel[sel[:, 1] < ymin + 0.06 * H][:, 2])])
        keep = sel[np.linalg.norm(sel[:, [0, 2]] - pc, axis=1) < rmax]
        out = []
        for y0 in np.linspace(keep[:, 1].min(), keep[:, 1].max(), 10):
            s2 = keep[np.abs(keep[:, 1] - y0) < 0.035 * H]
            if len(s2) > 6:
                out.append(np.array([s2[:, 0].mean(), y0, s2[:, 2].mean()]))
        return np.array(out)

    def at_y(cl, y):
        if len(cl) == 0:
            return np.array([0.0, y, 0.0])
        c = cl[np.argsort(cl[:, 1])]
        if y <= c[0, 1]:
            return np.array([c[0, 0], y, c[0, 2]])
        if y >= c[-1, 1]:
            return np.array([c[-1, 0], y, c[-1, 2]])
        for i in range(len(c) - 1):
            if c[i, 1] <= y <= c[i + 1, 1]:
                t = (y - c[i, 1]) / (c[i + 1, 1] - c[i, 1])
                return c[i] * (1 - t) + c[i + 1] * t
        return c[-1]

    def leg(prefix, sx, pawp, hind):
        cl = centerline(sx, pawp[2])
        p_top = at_y(cl, 0.02 if hind else 0.10)
        p_mid = at_y(cl, -0.42)
        p_lo = at_y(cl, -0.62)
        toe = np.array([pawp[0], pawp[1] + 0.01, pawp[2] + 0.04])
        if hind:
            add(prefix + "_thigh", p_top, p_mid)
            add(prefix + "_shank", p_mid, p_lo)
            add(prefix + "_foot", p_lo, toe)
        else:
            add(prefix + "_shoulder", np.array([p_top[0] * 0.55, p_top[1] + 0.06, p_top[2] - 0.03]), p_top)
            add(prefix + "_leg", p_top, p_lo)
            add(prefix + "_paw", p_lo, toe)

    leg("l_front", +1, paw(+1, +1), False)
    leg("r_front", -1, paw(-1, +1), False)
    leg("l_hind", +1, paw(+1, -1), True)
    leg("r_hind", -1, paw(-1, -1), True)
    return J


def to_blender(p):
    """glTF (fwd +Z, up +Y) -> Blender (fwd -Y, up +Z)"""
    return (float(p[0]), float(-p[2]), float(p[1]))


def main():
    args = parse_args()
    V, I = load_glb(args.src)
    print("[load] %d verts / %d tris" % (len(V), len(I)))
    V = orient(V, I)
    J = build_rig(V)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    bv = np.stack([V[:, 0], -V[:, 2], V[:, 1]], 1)
    me = bpy.data.meshes.new("CAT")
    me.from_pydata([tuple(v) for v in bv], [], [tuple(int(x) for x in t) for t in I])
    me.validate()
    ob = bpy.data.objects.new("CAT", me)
    bpy.context.scene.collection.objects.link(ob)
    bpy.context.view_layer.objects.active = ob
    dec = ob.modifiers.new("dec", 'DECIMATE')
    dec.decimate_type = 'COLLAPSE'
    dec.ratio = args.ratio
    dec.use_collapse_triangulate = True
    bpy.ops.object.modifier_apply(modifier="dec")
    print("[decimate] %d verts / %d tris" % (len(ob.data.vertices), len(ob.data.polygons)))

    arm_data = bpy.data.armatures.new("CatArmature")
    arm = bpy.data.objects.new("CatArmature", arm_data)
    bpy.context.scene.collection.objects.link(arm)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.mode_set(mode='EDIT')
    for name in ORDER:
        h, t = J[name]
        eb = arm_data.edit_bones.new(name)
        eb.head = Vector(to_blender(h))
        eb.tail = Vector(to_blender(t))
        eb.align_roll(Vector((-1, 0, 0)).cross((eb.tail - eb.head).normalized()))
    for name in ORDER:
        p = PARENT.get(name)
        if p:
            eb = arm_data.edit_bones[name]
            eb.parent = arm_data.edit_bones[p]
            eb.use_connect = name in CONNECT
    bpy.ops.object.mode_set(mode='OBJECT')

    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.parent_set(type='ARMATURE_AUTO')

    names = [g.name for g in ob.vertex_groups]
    nV = len(ob.data.vertices)
    W = np.zeros((nV, len(names)))
    for v in ob.data.vertices:
        for ge in v.groups:
            W[v.index, ge.group] = ge.weight
    order4 = np.argsort(-W, axis=1)[:, :4]
    W4 = np.zeros_like(W)
    rows = np.arange(nV)
    for k in range(4):
        W4[rows, order4[:, k]] = W[rows, order4[:, k]]
    tot = W4.sum(1, keepdims=True)
    bad = tot[:, 0] < 1e-6
    print("[skin] unweighted %d" % int(bad.sum()))
    W4[~bad] /= tot[~bad]
    W4[bad] = 0.0
    for g in list(ob.vertex_groups):
        ob.vertex_groups.remove(g)
    groups = [ob.vertex_groups.new(name=n) for n in names]
    for i in range(nV):
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
    for p in ob.data.polygons:
        p.use_smooth = True

    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = ob
    bpy.ops.export_scene.gltf(
        filepath=os.path.abspath(args.out),
        export_format='GLB', use_selection=True, export_apply=False, export_yup=True,
        export_animations=False, export_skins=True, export_morph=False, export_materials='NONE',
        export_normals=True, export_tangents=False, export_texcoords=False, export_extras=False,
        export_cameras=False, export_lights=False, export_def_bones=False,
    )
    print("[done] %s (%d bytes)" % (args.out, os.path.getsize(args.out)))


if __name__ == "__main__":
    main()
