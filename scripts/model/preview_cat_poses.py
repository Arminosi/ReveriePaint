#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""猫猫 3D 姿态预览 / 校核工具

用与 App 完全一致的数学 (拓扑序 FK + 线性混合蒙皮 + 预设角度表 + 骨骼平移 + 贴地升降)
离线形变内置猫猫模型, 导出 OBJ 并用 Blender 渲染对照图, 同时给出"边拉伸度"指标 —— 姿态
调参时用它替代真机肉眼试错。

依赖: numpy, 可选 blender (在 PATH 中时自动渲染对照图)
用法:
    python3 scripts/model/preview_cat_poses.py                        # 全部预设 + 渲染
    python3 scripts/model/preview_cat_poses.py --presets sit,curl --views side,quarter
    python3 scripts/model/preview_cat_poses.py --no-render            # 只看数值 (贴地/拉伸)
    python3 scripts/model/preview_cat_poses.py --model build/3d-rig/anime_cat.spine_mid.glb

注意: 本脚本内的预设数值需要与 app/src/main/java/com/reverie/paint/model/PosePresets.kt 同步,
它只用于离线核对, 不参与 App 构建。
"""
import argparse
import json
import os
import shutil
import struct
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_GLB = os.path.join(ROOT, 'app/src/main/assets/3d_models/anime_cat.glb')

# ---------------------------------------------------------------------------
# 与 PosePresets 猫猫分支同步的预设表 (角度单位: 度; 平移单位: 世界单位)
#   p = pitch (绕骨骼局部 X), y = yaw (绕局部 Y), r = roll (绕局部 Z)
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 与 CatPosePresets.kt 完全一致的角度表 (骨骼名 -> (pitch, yaw, roll))
# 由 scripts/model/cat_pose_solver.py 生成; 改姿态请改求解器再重新生成, 不要手改这里
# ---------------------------------------------------------------------------
PRESETS = {
    'stand': {'tail_01': (8.0, 0.0, 0.0), 'tail_02': (10.0, 0.0, 0.0), 'tail_03': (8.0, 0.0, 0.0), 'tail_04': (5.0, 0.0, 0.0), 'tail_05': (2.0, 0.0, 0.0)},
    'sit': {'carpus_L': (8.0, 0.0, 0.0), 'carpus_R': (8.0, 0.0, 0.0), 'chest': (-4.0, 0.0, 0.0), 'elbow_L': (12.0, 0.0, 0.0), 'elbow_R': (12.0, 0.0, 0.0), 'head': (10.0, 0.0, 0.0), 'hip_L': (-64.0, 0.0, 0.0), 'hip_R': (-64.0, 0.0, 0.0), 'hock_L': (-100.0, 0.0, 0.0), 'hock_R': (-100.0, 0.0, 0.0), 'knee_L': (78.0, 0.0, 0.0), 'knee_R': (78.0, 0.0, 0.0), 'meta_L': (30.0, 0.0, 0.0), 'meta_R': (30.0, 0.0, 0.0), 'neck': (-6.0, 0.0, 0.0), 'root': (-10.0, 0.0, 0.0), 'scapula_L': (12.0, 0.0, 0.0), 'scapula_R': (12.0, 0.0, 0.0), 'shoulder_L': (14.0, 0.0, 0.0), 'shoulder_R': (14.0, 0.0, 0.0), 'spine_01': (-12.0, 0.0, 0.0), 'spine_02': (-12.0, 0.0, 0.0), 'spine_03': (-8.0, 0.0, 0.0), 'tail_01': (20.0, 0.0, 0.0), 'tail_02': (25.0, 0.0, 0.0), 'tail_03': (20.0, 0.0, 0.0), 'tail_04': (12.0, 0.0, 0.0), 'tail_05': (6.0, 0.0, 0.0)},
    'loaf': {'carpus_L': (-15.0, 0.0, 0.0), 'carpus_R': (-15.0, 0.0, 0.0), 'chest': (2.0, 0.0, 0.0), 'elbow_L': (-45.0, 0.0, 0.0), 'elbow_R': (-45.0, 0.0, 0.0), 'head': (4.0, 0.0, 0.0), 'hip_L': (-70.0, 0.0, 0.0), 'hip_R': (-70.0, 0.0, 0.0), 'hock_L': (-110.0, 0.0, 0.0), 'hock_R': (-110.0, 0.0, 0.0), 'knee_L': (85.0, 0.0, 0.0), 'knee_R': (85.0, 0.0, 0.0), 'meta_L': (35.0, 0.0, 0.0), 'meta_R': (35.0, 0.0, 0.0), 'neck': (-2.0, 0.0, 0.0), 'root': (-6.0, 0.0, 0.0), 'scapula_L': (-25.0, 0.0, 0.0), 'scapula_R': (-25.0, 0.0, 0.0), 'shoulder_L': (45.0, 0.0, 0.0), 'shoulder_R': (45.0, 0.0, 0.0), 'spine_01': (-6.0, 0.0, 0.0), 'spine_02': (-6.0, 0.0, 0.0), 'spine_03': (-4.0, 0.0, 0.0), 'tail_01': (22.0, 0.0, 0.0), 'tail_02': (28.0, 0.0, 0.0), 'tail_03': (22.0, 0.0, 0.0), 'tail_04': (14.0, 0.0, 0.0), 'tail_05': (6.0, 0.0, 0.0)},
    'stretch': {'carpus_L': (-10.0, 0.0, 0.0), 'carpus_R': (-10.0, 0.0, 0.0), 'chest': (-6.0, 0.0, 0.0), 'elbow_L': (-26.0, 0.0, 0.0), 'elbow_R': (-26.0, 0.0, 0.0), 'head': (12.0, 0.0, 0.0), 'hip_L': (-18.0, 0.0, 0.0), 'hip_R': (-18.0, 0.0, 0.0), 'hock_L': (-12.0, 0.0, 0.0), 'hock_R': (-12.0, 0.0, 0.0), 'knee_L': (22.0, 0.0, 0.0), 'knee_R': (22.0, 0.0, 0.0), 'meta_L': (6.0, 0.0, 0.0), 'meta_R': (6.0, 0.0, 0.0), 'neck': (-8.0, 0.0, 0.0), 'root': (6.0, 0.0, 0.0), 'scapula_L': (-24.0, 0.0, 0.0), 'scapula_R': (-24.0, 0.0, 0.0), 'shoulder_L': (28.0, 0.0, 0.0), 'shoulder_R': (28.0, 0.0, 0.0), 'spine_01': (6.0, 0.0, 0.0), 'spine_02': (6.0, 0.0, 0.0), 'spine_03': (4.0, 0.0, 0.0), 'tail_01': (16.0, 0.0, 0.0), 'tail_02': (22.0, 0.0, 0.0), 'tail_03': (16.0, 0.0, 0.0), 'tail_04': (8.0, 0.0, 0.0), 'tail_05': (3.0, 0.0, 0.0)},
    'walk': {'carpus_L': (6.0, 0.0, 0.0), 'carpus_R': (-4.0, 0.0, 0.0), 'chest': (-1.0, 0.0, 0.0), 'elbow_L': (-16.0, 0.0, 0.0), 'elbow_R': (12.0, 0.0, 0.0), 'head': (2.0, 0.0, 0.0), 'hip_L': (10.0, 0.0, 0.0), 'hip_R': (-8.0, 0.0, 0.0), 'hock_L': (8.0, 0.0, 0.0), 'hock_R': (-6.0, 0.0, 0.0), 'knee_L': (-12.0, 0.0, 0.0), 'knee_R': (10.0, 0.0, 0.0), 'meta_L': (-4.0, 0.0, 0.0), 'meta_R': (4.0, 0.0, 0.0), 'neck': (-2.0, 0.0, 0.0), 'root': (2.0, 0.0, 0.0), 'scapula_L': (-10.0, 0.0, 0.0), 'scapula_R': (8.0, 0.0, 0.0), 'shoulder_L': (14.0, 0.0, 0.0), 'shoulder_R': (-10.0, 0.0, 0.0), 'spine_01': (2.0, 0.0, 0.0), 'spine_02': (1.0, 0.0, 0.0), 'spine_03': (1.0, 0.0, 0.0), 'tail_01': (18.0, 0.0, 0.0), 'tail_02': (20.0, 0.0, 0.0), 'tail_03': (16.0, 0.0, 0.0), 'tail_04': (10.0, 0.0, 0.0), 'tail_05': (5.0, 0.0, 0.0)},
    'curl': {'carpus_L': (-20.0, 0.0, 0.0), 'carpus_R': (-20.0, 0.0, 0.0), 'chest': (6.0, 0.0, 0.0), 'elbow_L': (-55.0, 0.0, 0.0), 'elbow_R': (-55.0, 0.0, 0.0), 'head': (-18.0, 28.0, 0.0), 'hip_L': (-75.0, 0.0, 0.0), 'hip_R': (-75.0, 0.0, 0.0), 'hock_L': (-120.0, 0.0, 0.0), 'hock_R': (-120.0, 0.0, 0.0), 'knee_L': (95.0, 0.0, 0.0), 'knee_R': (95.0, 0.0, 0.0), 'meta_L': (40.0, 0.0, 0.0), 'meta_R': (40.0, 0.0, 0.0), 'neck': (8.0, 0.0, 0.0), 'root': (8.0, 0.0, 0.0), 'scapula_L': (-35.0, 0.0, 0.0), 'scapula_R': (-35.0, 0.0, 0.0), 'shoulder_L': (55.0, 0.0, 0.0), 'shoulder_R': (55.0, 0.0, 0.0), 'spine_01': (-8.0, 0.0, 0.0), 'spine_02': (-8.0, 0.0, 0.0), 'spine_03': (-4.0, 0.0, 0.0), 'tail_01': (30.0, 0.0, 0.0), 'tail_02': (35.0, 0.0, 0.0), 'tail_03': (28.0, 0.0, 0.0), 'tail_04': (18.0, 0.0, 0.0), 'tail_05': (8.0, 0.0, 0.0)},
    'pounce': {'carpus_L': (-12.0, 0.0, 0.0), 'carpus_R': (-12.0, 0.0, 0.0), 'chest': (-8.0, 0.0, 0.0), 'elbow_L': (-34.0, 0.0, 0.0), 'elbow_R': (-34.0, 0.0, 0.0), 'head': (8.0, 0.0, 0.0), 'hip_L': (-40.0, 0.0, 0.0), 'hip_R': (-40.0, 0.0, 0.0), 'hock_L': (-60.0, 0.0, 0.0), 'hock_R': (-60.0, 0.0, 0.0), 'knee_L': (52.0, 0.0, 0.0), 'knee_R': (52.0, 0.0, 0.0), 'meta_L': (20.0, 0.0, 0.0), 'meta_R': (20.0, 0.0, 0.0), 'neck': (-6.0, 0.0, 0.0), 'root': (10.0, 0.0, 0.0), 'scapula_L': (-20.0, 0.0, 0.0), 'scapula_R': (-20.0, 0.0, 0.0), 'shoulder_L': (34.0, 0.0, 0.0), 'shoulder_R': (34.0, 0.0, 0.0), 'spine_01': (8.0, 0.0, 0.0), 'spine_02': (6.0, 0.0, 0.0), 'spine_03': (2.0, 0.0, 0.0), 'tail_01': (12.0, 0.0, 0.0), 'tail_02': (16.0, 0.0, 0.0), 'tail_03': (12.0, 0.0, 0.0), 'tail_04': (8.0, 0.0, 0.0), 'tail_05': (4.0, 0.0, 0.0)},
    'rear': {'carpus_L': (-8.0, 0.0, 0.0), 'carpus_R': (-8.0, 0.0, 0.0), 'chest': (-2.0, 0.0, 0.0), 'elbow_L': (-18.0, 0.0, 0.0), 'elbow_R': (-18.0, 0.0, 0.0), 'head': (4.0, 0.0, 0.0), 'hip_L': (-8.0, 0.0, 0.0), 'hip_R': (-8.0, 0.0, 0.0), 'hock_L': (-8.0, 0.0, 0.0), 'hock_R': (-8.0, 0.0, 0.0), 'knee_L': (10.0, 0.0, 0.0), 'knee_R': (10.0, 0.0, 0.0), 'meta_L': (4.0, 0.0, 0.0), 'meta_R': (4.0, 0.0, 0.0), 'neck': (-6.0, 0.0, 0.0), 'root': (-8.0, 0.0, 0.0), 'scapula_L': (-30.0, 0.0, 0.0), 'scapula_R': (-30.0, 0.0, 0.0), 'shoulder_L': (-25.0, 0.0, 0.0), 'shoulder_R': (-25.0, 0.0, 0.0), 'spine_01': (-8.0, 0.0, 0.0), 'spine_02': (-6.0, 0.0, 0.0), 'spine_03': (-4.0, 0.0, 0.0), 'tail_01': (-10.0, 0.0, 0.0), 'tail_02': (-8.0, 0.0, 0.0), 'tail_03': (-4.0, 0.0, 0.0), 'tail_04': (-2.0, 0.0, 0.0), 'tail_05': (0.0, 0.0, 0.0)},
    'look': {'chest': (0.0, 0.0, 0.0), 'head': (0.0, 42.0, 0.0), 'neck': (4.0, 18.0, 0.0), 'root': (-2.0, 0.0, 0.0), 'spine_01': (-2.0, 0.0, 0.0), 'spine_02': (-1.0, 0.0, 0.0), 'spine_03': (0.0, 0.0, 0.0), 'tail_01': (14.0, 0.0, 0.0), 'tail_02': (18.0, 0.0, 0.0), 'tail_03': (20.0, 0.0, 0.0), 'tail_04': (16.0, 0.0, 0.0), 'tail_05': (10.0, 0.0, 0.0)},
}

# 美术标定的整体升降: 已清空 —— 根骨平移会被贴地精确抵消, 所有姿态走几何自动贴地
TUNED_SHIFT = {}

# 语义名 -> 骨骼名别名表 (兼容旧版骨骼命名)
ALIAS = {
    'Hips': ['Hips', 'hips'], 'Spine': ['Spine', 'spine'],
    'spine_mid': ['spine_mid', 'spinemid'],
    'Chest': ['Chest', 'chest'], 'Neck': ['Neck', 'neck'], 'Head': ['Head', 'head'],
    'l_front_shoulder': ['l_front_shoulder', 'frontleg'], 'r_front_shoulder': ['r_front_shoulder', 'R_frontleg'],
    'l_front_leg': ['leftFrontLeg', 'LeftFrontLeg', 'l_front_leg', 'frontleg0'],
    'r_front_leg': ['rightFrontLeg', 'RightFrontLeg', 'r_front_leg', 'R_frontleg0'],
    'l_front_paw': ['l_front_paw', 'frontleg1', 'frontleg2'],
    'r_front_paw': ['r_front_paw', 'R_frontleg1', 'R_frontleg2'],
    'l_hind_thigh': ['leftHindLeg', 'LeftHindLeg', 'l_hind_thigh', 'backleg'],
    'r_hind_thigh': ['rightHindLeg', 'RightHindLeg', 'r_hind_thigh', 'R_backleg'],
    'l_hind_shank': ['l_hind_shank', 'backleg0'], 'r_hind_shank': ['r_hind_shank', 'R_backleg0'],
    'l_hind_foot': ['l_hind_foot', 'backleg1', 'backleg2'], 'r_hind_foot': ['r_hind_foot', 'R_backleg1', 'R_backleg2'],
    'tail1': ['Tail1', 'tail1', 'tail'], 'tail2': ['Tail2', 'tail2', 'tailstart'],
    'tail3': ['Tail3', 'tail3'], 'tail4': ['Tail4', 'tail4'], 'tail5': ['tail5'],
}


def export_obj(path, verts, indices):
    """导出 OBJ (转换到 Blender 的 Z-up: x, -z, y)"""
    tris = indices.reshape(-1, 3)
    with open(path, 'w') as f:
        for p in verts:
            f.write('v %.6f %.6f %.6f\n' % (p[0], -p[2], p[1]))
        for t in tris:
            f.write('f %d %d %d\n' % (t[0] + 1, t[1] + 1, t[2] + 1))


def load_glb(path):
    data = open(path, 'rb').read()
    off = 12
    js = None
    bin_ = None
    while off < len(data):
        clen, ctype = struct.unpack_from('<II', data, off)
        off += 8
        chunk = data[off:off + clen]
        off += clen
        if ctype == 0x4E4F534A:
            js = json.loads(chunk)
        else:
            bin_ = chunk
    return js, bin_


def accessor(js, bin_, idx):
    a = js['accessors'][idx]
    bv = js['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[a['type']]
    ct = a['componentType']
    cs = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[ct]
    stride = bv.get('byteStride') or cs * n
    base = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    fmt = '<' + {5126: 'f', 5121: 'B', 5123: 'H', 5125: 'I', 5120: 'b', 5122: 'h'}[ct] * n
    out = np.zeros((a['count'], n))
    for k in range(a['count']):
        out[k] = struct.unpack_from(fmt, bin_, base + k * stride)
    return out


def node_local(n):
    if 'matrix' in n:
        return np.array(n['matrix']).reshape(4, 4).T
    T = np.eye(4)
    R = np.eye(4)
    S = np.eye(4)
    if 'translation' in n:
        T[:3, 3] = n['translation']
    if 'rotation' in n:
        x, y, z, w = n['rotation']
        R[:3, :3] = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    if 'scale' in n:
        S[0, 0], S[1, 1], S[2, 2] = n['scale']
    return T @ R @ S


def rot_m(deg, x, y, z):
    r = np.radians(deg)
    c = np.cos(r)
    s = np.sin(r)
    L = np.hypot(np.hypot(x, y), z)
    if L < 1e-9:
        return np.eye(4)
    nx, ny, nz = x / L, y / L, z / L
    nc = 1 - c
    m = np.eye(4)
    m[0, 0] = nx * nx * nc + c
    m[1, 0] = ny * nx * nc + nz * s
    m[2, 0] = nz * nx * nc - ny * s
    m[0, 1] = nx * ny * nc - nz * s
    m[1, 1] = ny * ny * nc + c
    m[2, 1] = nz * ny * nc + nx * s
    m[0, 2] = nx * nz * nc + ny * s
    m[1, 2] = ny * nz * nc - nx * s
    m[2, 2] = nz * nz * nc + c
    return m


def trans_m(t):
    m = np.eye(4)
    m[:3, 3] = t
    return m


class Rig:
    def __init__(self, path):
        js, bin_ = load_glb(path)
        skin = js['skins'][0]
        self.joint_nodes = skin['joints']
        nodes = js['nodes']
        self.names = [nodes[j].get('name', '?') for j in self.joint_nodes]
        self.index = {n: i for i, n in enumerate(self.names)}
        invbinds = accessor(js, bin_, skin['inverseBindMatrices'])

        parent = {}
        for i, n in enumerate(nodes):
            for c in n.get('children', []):
                parent[c] = i
        # 关节间父子关系 (跳过非关节祖先节点)
        self.pj = []
        for jn in self.joint_nodes:
            p = parent.get(jn)
            while p is not None and p not in self.joint_nodes:
                p = parent.get(p)
            self.pj.append(self.joint_nodes.index(p) if p is not None else -1)

        order = []
        state = [0] * len(self.joint_nodes)

        def dfs(i):
            if state[i]:
                return
            state[i] = 1
            if self.pj[i] >= 0:
                dfs(self.pj[i])
            state[i] = 2
            order.append(i)

        for i in range(len(self.joint_nodes)):
            dfs(i)
        self.order = order
        self.invbinds = np.array([invbinds[i].reshape(4, 4).T for i in range(len(self.joint_nodes))])
        self.locals = [node_local(nodes[j]) for j in self.joint_nodes]

        prim = js['meshes'][0]['primitives'][0]
        attrs = prim['attributes']
        self.P = accessor(js, bin_, attrs['POSITION'])
        self.J = accessor(js, bin_, attrs['JOINTS_0']).astype(int)
        self.W = accessor(js, bin_, attrs['WEIGHTS_0'])
        self.IDX = accessor(js, bin_, prim['indices']).astype(int).reshape(-1, 3)
        self.edges = np.unique(np.sort(np.vstack(
            [self.IDX[:, [0, 1]], self.IDX[:, [1, 2]], self.IDX[:, [2, 0]]]), 1), axis=0)
        self.rest_min = float(self.P[:, 1].min())

    def deformed(self, euler_deg, translations=None):
        """euler_deg: 骨骼名 -> (pitch, yaw, roll); translations: 骨骼名 -> (x,y,z) 局部平移"""
        world = [np.eye(4)] * len(self.names)
        for i in self.order:
            loc = self.locals[i]
            e = euler_deg.get(self.names[i])
            tr = translations.get(self.names[i]) if translations else None
            if tr is not None:
                loc = loc @ trans_m(tr)
            if e and any(e):
                # 与 Kotlin 一致: local * Ry * Rx * Rz
                loc = loc @ (rot_m(e[1], 0, 1, 0) @ rot_m(e[0], 1, 0, 0) @ rot_m(e[2], 0, 0, 1))
            world[i] = (world[self.pj[i]] @ loc) if self.pj[i] >= 0 else loc
        skin_mats = [world[i] @ self.invbinds[i] for i in range(len(world))]

        out = np.zeros_like(self.P)
        # 每根骨一组批量计算, 避免逐顶点 4 次小矩阵乘 (与 App 结果等价)
        for k in range(4):
            w = self.W[:, k:k + 1]
            sel = w[:, 0] > 1e-5
            if not sel.any():
                continue
            acc = np.zeros((len(self.P), 3))
            for ji in np.unique(self.J[sel, k]):
                rows = sel & (self.J[:, k] == ji)
                M = skin_mats[ji]
                acc[rows] = (M[:3, :3] @ self.P[rows].T).T + M[:3, 3]
            out += w * acc
        return out

    def stretch(self, verts):
        """边长拉伸度: 只统计足够长的边 (短于 0.008 的碎面/绒毛细节会把比例放大成噪声)"""
        L0 = np.linalg.norm(self.P[self.edges[:, 0]] - self.P[self.edges[:, 1]], axis=1)
        L1 = np.linalg.norm(verts[self.edges[:, 0]] - verts[self.edges[:, 1]], axis=1)
        good = L0 > 0.008
        r = L1[good] / L0[good]
        return np.percentile(r, 50), np.percentile(r, 99), r.max(), int((r > 1.25).sum())


def pose_map_dict(d):
    def g(k):
        v = d.get(k, 0.0)
        if isinstance(v, (list, tuple)):
            v = v[0]
        return float(v)
    tail_p = float(d.get('tailp', 35.0))
    tail_side = float(d.get('tailside', 8.0))
    vals = {
        'Hips': (g('hip'), 0, 0),
        'Spine': (g('spine'), 0, 0),
        'spine_mid': (g('smid'), 0, 0),
        'spine_mid': (g('smid'), 0, 0),
        'Chest': (g('chest'), 0, 0),
        'Neck': (g('neck'), 0, 0),
        'Head': (g('head'), g('headyaw'), 0),
        'l_front_shoulder': (g('flsh'), 0, 0), 'r_front_shoulder': (g('frsh'), 0, 0),
        'l_front_leg': (g('flleg'), 0, 0), 'r_front_leg': (g('frleg'), 0, 0),
        'l_front_paw': (g('flpaw'), 0, 0), 'r_front_paw': (g('frpaw'), 0, 0),
        'l_hind_thigh': (g('hlleg'), 0, 0), 'r_hind_thigh': (g('hrleg'), 0, 0),
        'l_hind_shank': (g('hlshank'), 0, 0), 'r_hind_shank': (g('hrshank'), 0, 0),
        'l_hind_foot': (g('hlfoot'), 0, 0), 'r_hind_foot': (g('hrfoot'), 0, 0),
        'tail1': (tail_p, 0, tail_side),
        'tail2': (26 + tail_p * 0.25, 0, tail_side * 0.8),
        'tail3': (20 + tail_p * 0.2, 0, tail_side * 0.6),
        'tail4': (14 + tail_p * 0.15, 0, tail_side * 0.4),
        'tail5': (10 + tail_p * 0.1, 0, tail_side * 0.2),
    }
    m = {}
    for key, (p, y, r) in vals.items():
        for nm in ALIAS.get(key, [key]):
            m[nm] = (p, y, r)
    return m


def pose_map(preset):
    return pose_map_dict(PRESETS[preset])


def pose_map_rig(preset, rig):
    """预设既支持语义键 (hip/spine/...), 也支持直接写骨骼名 (spine_01/tail_03/...)"""
    d = PRESETS[preset]
    m = pose_map_dict(d)
    for k, v in d.items():
        if k in rig.index:
            m[k] = (float(v[0]) if isinstance(v, (list, tuple)) else float(v), 0.0, 0.0)
    return m


def translations_for(preset):
    """预设自带的骨骼平移 (与 PosePresets.buildTranslations 一致): 猫猫 Hips 局部 Y ≈ 世界上方"""
    d = PRESETS[preset]
    out = {}
    if d.get('typh'):
        out['Hips'] = (0.0, float(d['typh']), 0.0)
    return out


RENDER_TEMPLATE = r"""
import bpy, math
from mathutils import Vector
OUT_DIR = {out_dir!r}
JOBS = {jobs!r}
GROUND_Z = {ground_z!r}

def clear():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)

def look_at(cam, target):
    d = Vector(target) - cam.location
    cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()

scene = bpy.context.scene
scene.render.engine = 'BLENDER_WORKBENCH'
scene.display.shading.light = 'STUDIO'
scene.display.shading.color_type = 'OBJECT'
scene.display.shading.show_shadows = True
scene.display.shading.show_cavity = True
scene.render.resolution_x = 420
scene.render.resolution_y = 360
scene.world.color = (0.95, 0.95, 0.96)

import mathutils

def fit_camera(cam, objs, view):
    # 按物体包围盒自动构图: 换资产/换姿态都不用手调相机
    pts = []
    for o in objs:
        for c in o.bound_box:
            pts.append(o.matrix_world @ mathutils.Vector(c))
    lo = mathutils.Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = mathutils.Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    center = (lo + hi) / 2
    size = max(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
    dirs = {{
        'side':    mathutils.Vector((1.0, 0.0, 0.18)),
        'quarter': mathutils.Vector((0.75, -0.66, 0.30)),
        'front':   mathutils.Vector((0.0, -1.0, 0.16)),
        'top':     mathutils.Vector((0.02, -0.08, 1.0)),
    }}
    d = dirs[view].normalized()
    cam.location = center + d * (size * 2.15)
    look_at(cam, center)

for name, view in JOBS:
    clear()
    bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 0, GROUND_Z))
    bpy.context.object.color = (0.42, 0.44, 0.47, 1)
    bpy.ops.object.camera_add(location=(3.4, 0.0, 0.2))
    cam = bpy.context.object
    cam.data.lens = 55
    scene.camera = cam
    bpy.ops.wm.obj_import(filepath=OUT_DIR + '/' + name + '.obj', forward_axis='Y', up_axis='Z')
    obj = bpy.context.selected_objects[0]
    obj.color = (0.86, 0.84, 0.82, 1)
    for poly in obj.data.polygons:
        poly.use_smooth = False
    fit_camera(cam, [obj], view)
    scene.render.filepath = OUT_DIR + '/' + name + '_' + view + '.png'
    bpy.ops.render.render(write_still=True)
print('PREVIEW DONE')
"""


def render(out_dir, jobs, ground_z):
    if shutil.which('blender') is None:
        print('[preview] blender not found, skip rendering')
        return
    script = out_dir + '/_render.py'
    with open(script, 'w') as f:
        f.write(RENDER_TEMPLATE.format(out_dir=out_dir, jobs=jobs, ground_z=ground_z))
    subprocess.run(['blender', '-b', '--factory-startup', '-P', script], check=False)
    print('[preview] images in', out_dir)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default=DEFAULT_GLB)
    ap.add_argument('--presets', default=','.join(PRESETS.keys()))
    ap.add_argument('--views', default='side')
    ap.add_argument('--out', default=os.path.join(ROOT, 'build/3d-pose-review'))
    ap.add_argument('--no-render', action='store_true')
    ap.add_argument('--poses-json', help='用 cat_pose_solver.py --emit 输出的姿态覆盖预设表')
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if args.poses_json:
        with open(args.poses_json) as f:
            for k, v in json.load(f).items():
                PRESETS[k] = v
        print('[poses] 覆盖自', args.poses_json)
    if not os.path.exists(args.model):
        sys.exit('missing asset: ' + args.model)
    rig = Rig(args.model)
    print('[rig] %s: %d joints, has spine_mid = %s' %
          (os.path.basename(args.model), len(rig.names), 'spine_mid' in rig.index))
    print('[rig] rest minY =', round(rig.rest_min, 4))

    jobs = []
    for preset in args.presets.split(','):
        eul = pose_map_rig(preset, rig)
        tr = translations_for(preset)
        raw = rig.deformed(eul, tr)
        auto = rig.rest_min - float(raw[:, 1].min())
        shift = TUNED_SHIFT.get(preset, auto)
        # 贴地升降作用在形变结果上 (与 createDeformedMesh 的 groundShiftY 一致)
        verts = raw.copy()
        verts[:, 1] += shift
        p50, p99, pmax, bad = rig.stretch(verts)
        print('  %-8s shift=%+.4f minY=%+.4f  stretch p50 %.3f p99 %.3f max %.3f (>1.25: %d)'
              % (preset, shift, float(verts[:, 1].min()), p50, p99, pmax, bad))
        name = preset
        export_obj(os.path.join(out_dir, name + '.obj'), verts, rig.IDX)
        for view in args.views.split(','):
            jobs.append((name, view))

    print('[preview] objs in', out_dir)
    if not args.no_render:
        render(out_dir, jobs, rig.rest_min)


if __name__ == '__main__':
    main()
