#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""把猫猫 GLB 的耳骨(earend / R_earend)轴心从"耳尖上方"移到耳根, 并沿耳轴定向。

背景: 原资产的耳骨原点 y=0.577 在耳朵几何最高点(0.573)之上, 拖耳骨 = 绕耳尖把耳朵当倒摆甩,
骨架覆盖层也像两根天线。本脚本只改绑定姿态 (节点矩阵 + 逆绑定矩阵), 静止姿态逐顶点不变
(world * inverseBind 在绑定姿态恒为单位阵), 因此不影响任何既有姿态与画风。

用法: python3 scripts/model/fix_cat_ears.py [--dry-run]
依赖: numpy
"""
import json
import struct
import sys
import numpy as np

GLB = 'app/src/main/assets/3d_models/anime_cat.glb'
EAR_BONES = ('earend', 'R_earend')


def load(path):
    data = open(path, 'rb').read()
    off = 12; js = None; bin_ = None
    while off < len(data):
        clen, ctype = struct.unpack_from('<II', data, off); off += 8
        chunk = data[off:off + clen]; off += clen
        if ctype == 0x4E4F534A:
            js = json.loads(chunk)
        else:
            bin_ = bytearray(chunk)
    return js, bin_


def accessor(js, bin_, idx):
    a = js['accessors'][idx]; bv = js['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[a['type']]
    ct = a['componentType']; cnt = a['count']
    cs = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[ct]
    elem = cs * n; stride = bv.get('byteStride') or elem
    out = np.zeros((cnt, n))
    for k in range(cnt):
        base = bv.get('byteOffset', 0) + a.get('byteOffset', 0) + k * stride
        for c in range(n):
            p = base + c * cs
            if ct == 5126: out[k, c] = struct.unpack_from('<f', bin_, p)[0]
            elif ct == 5121: out[k, c] = bin_[p]
            elif ct == 5123: out[k, c] = struct.unpack_from('<H', bin_, p)[0]
            elif ct == 5125: out[k, c] = struct.unpack_from('<I', bin_, p)[0]
            elif ct == 5120: out[k, c] = struct.unpack_from('<b', bin_, p)[0]
            elif ct == 5122: out[k, c] = struct.unpack_from('<h', bin_, p)[0]
    return out


def node_local(n):
    if 'matrix' in n:
        return np.array(n['matrix'], dtype=np.float64).reshape(4, 4).T
    T = np.eye(4); R = np.eye(4); S = np.eye(4)
    if 'translation' in n: T[:3, 3] = n['translation']
    if 'rotation' in n:
        x, y, z, w = n['rotation']
        R[:3, :3] = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    if 'scale' in n: S[0, 0], S[1, 1], S[2, 2] = n['scale']
    return T @ R @ S


def write_glb(path, js, bin_):
    js_bytes = json.dumps(js, separators=(',', ':')).encode('utf-8')
    js_pad = (4 - len(js_bytes) % 4) % 4
    bin_pad = (4 - len(bin_) % 4) % 4
    total = 12 + 8 + len(js_bytes) + js_pad + 8 + len(bin_) + bin_pad
    out = bytearray()
    out += struct.pack('<III', 0x46546C67, 2, total)
    out += struct.pack('<II', len(js_bytes) + js_pad, 0x4E4F534A)
    out += js_bytes + b' ' * js_pad
    out += struct.pack('<II', len(bin_) + bin_pad, 0x004E4942)
    out += bytes(bin_) + b'\x00' * bin_pad
    open(path, 'wb').write(bytes(out))


def main():
    dry = '--dry-run' in sys.argv
    js, bin_ = load(GLB)
    nodes = js['nodes']
    skin = js['skins'][0]
    joints = skin['joints']
    names = [nodes[j].get('name') for j in joints]
    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []):
            parent[c] = i

    prim = js['meshes'][0]['primitives'][0]
    attrs = prim['attributes']
    P = accessor(js, bin_, attrs['POSITION'])
    JI = accessor(js, bin_, attrs['JOINTS_0']).astype(int)
    WT = accessor(js, bin_, attrs['WEIGHTS_0'])
    inv_acc = skin['inverseBindMatrices']
    inv_bvs = js['bufferViews'][js['accessors'][inv_acc]['bufferView']]
    inv_base = inv_bvs.get('byteOffset', 0) + js['accessors'][inv_acc].get('byteOffset', 0)

    def world_of(node_idx, stop_at):
        m = node_local(nodes[node_idx]); p = parent.get(node_idx)
        while p is not None and p != stop_at:
            m = node_local(nodes[p]) @ m
            p = parent.get(p)
        return m

    for ear in EAR_BONES:
        ji = joints.index(next(j for j in joints if nodes[j].get('name') == ear))
        node_idx = joints[ji]
        weights = np.zeros(len(P))
        for k in range(4):
            sel = JI[:, k] == ji
            weights[sel] += WT[sel, k]
        V = P[weights > 0.3]
        lo, hi = np.percentile(V[:, 1], [10, 99])
        ear_mask = (V[:, 1] > lo) & (V[:, 1] < hi)
        Ve = V[ear_mask]
        base = Ve[Ve[:, 1] <= np.percentile(Ve[:, 1], 25)].mean(axis=0)
        tip = Ve[np.argmax(Ve[:, 1])]
        d = tip - base
        d /= np.linalg.norm(d)
        wx = np.array([1.0, 0.0, 0.0])
        z0 = np.cross(wx, d)
        if np.linalg.norm(z0) < 1e-5:
            z0 = np.array([0.0, 0.0, 1.0])
        z0 /= np.linalg.norm(z0)
        x0 = np.cross(d, z0)
        x0 /= np.linalg.norm(x0)

        desired = np.eye(4)
        desired[:3, 0] = x0
        desired[:3, 1] = d          # 骨轴 = 耳根 -> 耳尖
        desired[:3, 2] = z0
        desired[:3, 3] = base

        head_idx = parent[node_idx]
        head_world = world_of(head_idx, None)
        local = np.linalg.inv(head_world) @ desired
        inv_bind = np.linalg.inv(desired)

        print(f'{ear}: 耳根={np.round(base,3)} 耳尖={np.round(tip,3)} 骨轴={np.round(d,2)} '
              f'旧原点={np.round(world_of(node_idx, None)[:3,3],3)} 长度={np.linalg.norm(tip-base):.3f}')

        if not dry:
            node = nodes[node_idx]
            node.pop('translation', None); node.pop('rotation', None); node.pop('scale', None)
            node['matrix'] = [float(v) for v in local.T.reshape(-1)]  # 列主序
            struct.pack_into('<16f', bin_, inv_base + ji * 64, *inv_bind.T.reshape(-1))

    if dry:
        print('(dry-run, 未写盘)')
        return
    write_glb(GLB, js, bin_)
    print('已写回', GLB)


if __name__ == '__main__':
    main()
