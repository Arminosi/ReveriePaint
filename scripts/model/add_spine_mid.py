#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""给猫猫 GLB 插入脊柱中段骨骼 spine_mid, 让躯干能弯成真正的 C 形

背景: 原骨架躯干只有 Hips -> Spine -> Chest 两个旋转轴心, 且 Chest 的权重被肩骨抢走,
脊柱只能在"骨盆"和"肩部"两处折, 蜷缩只能靠程序化顶点弯曲实现 —— 那条路径会按 cos 缩放截面,
把躯干拉长变形 (实机反馈"模型拉伸得不成样子")。本脚本在 Spine 骨中点插入一根可俯仰的
spine_mid, 并用"到骨段距离"重新分配躯干权重, 躯干从此有三段可以逐段弯。

做的事:
  1) 在 Spine 与 Chest 之间插入节点 spine_mid (与 Spine 同朝向, 原点在 Spine 骨中点);
     Chest 改挂到 spine_mid 下, 全部绑定姿态世界矩阵保持不变 -> 静止姿态逐顶点零形变
  2) spine_mid 追加进 skins[0].joints 末尾 (索引 29), 既有 JOINTS_0 索引全部不受影响
  3) inverseBindMatrices accessor 扩到 30 条 (该 bufferView 正好在 BIN 末尾, 直接追加 64B)
  4) 重蒙皮: 只重分配已有的躯干权重 (Hips/Spine/Chest 之和保持不变), 其它骨骼权重逐字节不变;
     躯干四根骨按 1/(d+0.03)^3 分配 + 网格上做 12 轮平滑 + 同位置重复顶点取均值 (防裂缝)

用法:
    python3 scripts/model/add_spine_mid.py --out build/3d-rig/anime_cat.spine_mid.glb
    python3 scripts/model/add_spine_mid.py --out <glb> --report
依赖: numpy
"""
import argparse
import json
import os
import struct
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_GLB = os.path.join(ROOT, 'app/src/main/assets/3d_models/anime_cat.glb')
NEW_BONE = 'spine_mid'
TORSO = ('Hips', 'Spine', NEW_BONE, 'Chest')
MAXINF = 4
SMOOTH_ROUNDS = 12


# ----------------------------------------------------------------------------
# GLB 读写
# ----------------------------------------------------------------------------
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
            bin_ = bytearray(chunk)
    if js is None or bin_ is None:
        raise SystemExit('不是有效的 GLB: ' + path)
    return js, bin_


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
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    open(path, 'wb').write(bytes(out))


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


def accessor_layout(js, idx):
    """返回 (缓冲区绝对起始偏移, 元素步长, 元素字节数)"""
    a = js['accessors'][idx]
    bv = js['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[a['type']]
    cs = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[a['componentType']]
    return (bv.get('byteOffset', 0) + a.get('byteOffset', 0),
            bv.get('byteStride') or cs * n, cs, n)


# ----------------------------------------------------------------------------
# 变换工具
# ----------------------------------------------------------------------------
def node_local(n):
    if 'matrix' in n:
        return np.array(n['matrix'], dtype=np.float64).reshape(4, 4).T
    M = np.eye(4)
    if 'translation' in n:
        M[:3, 3] = n['translation']
    R = np.eye(4)
    if 'rotation' in n:
        x, y, z, w = n['rotation']
        R[:3, :3] = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    S = np.eye(4)
    if 'scale' in n:
        S[0, 0], S[1, 1], S[2, 2] = n['scale']
    return M @ R @ S


def quat_from_mat(R):
    tr = R[0, 0] + R[1, 1] + R[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        w = 0.25 * s
        x = (R[2, 1] - R[1, 2]) / s
        y = (R[0, 2] - R[2, 0]) / s
        z = (R[1, 0] - R[0, 1]) / s
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        w = (R[2, 1] - R[1, 2]) / s
        x = 0.25 * s
        y = (R[0, 1] + R[1, 0]) / s
        z = (R[0, 2] + R[2, 0]) / s
    elif R[1, 1] > R[2, 2]:
        s = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        w = (R[0, 2] - R[2, 0]) / s
        x = (R[0, 1] + R[1, 0]) / s
        y = 0.25 * s
        z = (R[1, 2] + R[2, 1]) / s
    else:
        s = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        w = (R[1, 0] - R[0, 1]) / s
        x = (R[0, 2] + R[2, 0]) / s
        y = (R[1, 2] + R[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w], dtype=np.float64)
    return q / np.linalg.norm(q)


def seg_dist(P, A, B):
    AB = B - A
    L2 = float(AB @ AB)
    if L2 < 1e-12:
        return np.linalg.norm(P - A, axis=1)
    t = np.clip(((P - A) @ AB) / L2, 0, 1)
    return np.linalg.norm(P - (A + np.outer(t, AB)), axis=1)


# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--in', dest='src', default=DEFAULT_GLB)
    ap.add_argument('--out', dest='out', required=True)
    ap.add_argument('--fraction', type=float, default=0.5,
                    help='spine_mid 在 Spine 骨长上的位置 (0~1), 默认 0.5 = 中点')
    ap.add_argument('--report', action='store_true', help='打印重蒙皮后的权重分布')
    args = ap.parse_args()

    js, bin_ = load_glb(args.src)
    nodes = js['nodes']
    skin = js['skins'][0]
    joints = skin['joints']
    name2node = {n.get('name'): i for i, n in enumerate(nodes) if n.get('name')}
    if NEW_BONE in name2node:
        raise SystemExit('资产里已经有 %s, 不要重复插入' % NEW_BONE)

    for need in ('Hips', 'Spine', 'Chest', 'Neck'):
        if need not in name2node:
            raise SystemExit('找不到骨骼节点: ' + need)
    spine, chest, neck = name2node['Spine'], name2node['Chest'], name2node['Neck']

    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []):
            parent[c] = i

    def world_of(ni, cache={}):
        if ni in cache:
            return cache[ni]
        m = node_local(nodes[ni])
        p = parent.get(ni)
        m = (world_of(p) @ m) if p is not None else m
        cache[ni] = m
        return m

    W_spine = world_of(spine)
    W_chest = world_of(chest)
    S = W_spine[:3, 3]
    C = W_chest[:3, 3]
    M = S + (C - S) * args.fraction

    # ---- 1) 插入 spine_mid 节点: 与 Spine 同朝向, 原点抬到骨长中点 ----
    desired = W_spine.copy()
    desired[:3, 3] = M
    local_mid = np.linalg.inv(W_spine) @ desired
    new_idx = len(nodes)
    nodes.append({
        'name': NEW_BONE,
        'translation': [float(v) for v in local_mid[:3, 3]],
    })
    # 父(Spine) 的 children 里把 Chest 换成 spine_mid, spine_mid 收养 Chest
    ch = nodes[spine]['children']
    ch[ch.index(chest)] = new_idx
    nodes[new_idx]['children'] = [chest]
    parent[new_idx] = spine
    parent[chest] = new_idx
    name2node[NEW_BONE] = new_idx

    # Chest 的局部变换需要换成相对 spine_mid (世界矩阵保持不变)
    new_local_chest = np.linalg.inv(desired) @ W_chest
    cn = nodes[chest]
    cn.pop('matrix', None)
    cn['translation'] = [float(v) for v in new_local_chest[:3, 3]]
    cn['rotation'] = [float(v) for v in quat_from_mat(new_local_chest[:3, :3])]

    # ---- 2) 关节表追加到末尾 (既有 JOINTS_0 索引不变) ----
    joints.append(new_idx)
    new_joint_index = len(joints) - 1
    inv_bind = np.linalg.inv(desired)

    # ---- 3) 逆绑定矩阵 accessor 扩一条 ----
    ib_idx = skin['inverseBindMatrices']
    ib_acc = js['accessors'][ib_idx]
    ib_bv = js['bufferViews'][ib_acc['bufferView']]
    old_count = ib_acc['count']
    base, stride, cs, ncomp = accessor_layout(js, ib_idx)
    if cs != 4 or stride != 64:
        raise SystemExit('inverseBindMatrices 布局异常 (stride=%d cs=%d)' % (stride, cs))
    end = base + old_count * 64
    if end != len(bin_):
        raise SystemExit('逆绑定矩阵 bufferView 不在 BIN 末尾 (end=%d len=%d), 需要改写追加逻辑'
                         % (end, len(bin_)))
    if base != ib_bv.get('byteOffset', 0) + ib_acc.get('byteOffset', 0) or ib_acc.get('byteOffset', 0) != 0:
        raise SystemExit('inverseBindMatrices 带 byteOffset, 需要改写追加逻辑')
    bin_ += struct.pack('<16f', *[float(v) for v in inv_bind.T.reshape(-1)])
    ib_bv['byteLength'] = ib_bv.get('byteLength', 0) + 64
    ib_acc['count'] = old_count + 1
    js['buffers'][0]['byteLength'] = len(bin_)

    # ---- 4) 重蒙皮 (只重分配躯干权重) ----
    prim = js['meshes'][0]['primitives'][0]
    attrs = prim['attributes']
    P = accessor(js, bin_, attrs['POSITION'])
    J = accessor(js, bin_, attrs['JOINTS_0']).astype(np.int64)
    Wt = accessor(js, bin_, attrs['WEIGHTS_0'])
    IDX = accessor(js, bin_, prim['indices']).astype(np.int64).reshape(-1, 3)
    nv, nj = len(P), len(joints)

    joint_pos = np.array([world_of(joints[k])[:3, 3] for k in range(nj)])
    chain = [joints.index(name2node[b]) for b in TORSO]          # 取关节索引
    # 骨段: Hips->Spine, Spine->mid, mid->Chest, Chest->Neck
    seg_head = np.array([joint_pos[chain[0]], joint_pos[chain[1]],
                         joint_pos[chain[2]], joint_pos[chain[3]]])
    seg_tail = np.array([joint_pos[chain[1]], joint_pos[chain[2]],
                         joint_pos[chain[3]], joint_pos[joints.index(name2node['Neck'])]])

    dense = np.zeros((nv, nj))
    rows = np.arange(nv)
    for k in range(MAXINF):
        dense[rows, J[:, k]] += Wt[:, k]
    old_torso = dense[:, chain].sum(1)
    mask = old_torso > 1e-4
    print('[rig] 躯干权重顶点 %d / %d (%.1f%%)' % (int(mask.sum()), nv, 100.0 * mask.mean()))

    D = np.stack([seg_dist(P, seg_head[i], seg_tail[i]) for i in range(4)], 1)
    INFL = 1.0 / (D + 0.03) ** 3
    ratio = INFL / INFL.sum(1, keepdims=True)
    old_ratio = dense[:, chain] / np.maximum(old_torso, 1e-9)[:, None]
    # 过渡: 躯干权重很小的顶点几乎不参与重分配, 避免在边缘出现硬接缝
    s = np.clip(old_torso / 0.10, 0.0, 1.0)[:, None]
    ratio = s * ratio + (1.0 - s) * old_ratio

    # 沿网格边平滑 (只在躯干顶点之间传导, 非躯干顶点当锚点)
    e2 = np.vstack([IDX[:, [0, 1]], IDX[:, [1, 2]], IDX[:, [2, 0]]])
    e2 = np.vstack([e2, e2[:, ::-1]])
    keep = mask[e2[:, 0]] & mask[e2[:, 1]]
    e2 = e2[keep]
    deg = np.bincount(e2[:, 0], minlength=nv).astype(np.float64)
    deg[deg == 0] = 1
    for _ in range(SMOOTH_ROUNDS):
        acc = np.zeros_like(ratio)
        np.add.at(acc, e2[:, 0], ratio[e2[:, 1]])
        sm = acc / deg[:, None]
        upd = mask & (deg > 0)
        ratio[upd] = 0.6 * ratio[upd] + 0.4 * sm[upd]
        ratio /= np.maximum(ratio.sum(1, keepdims=True), 1e-9)

    # 同位置的重复顶点必须拿到同一组权重, 否则形变时会裂开
    key = {}
    groups = []
    for i in range(nv):
        k = (round(float(P[i, 0]), 5), round(float(P[i, 1]), 5), round(float(P[i, 2]), 5))
        if k in key:
            groups[key[k]].append(i)
        else:
            key[k] = len(groups)
            groups.append([i])
    dup = sum(len(g) - 1 for g in groups)
    for g in groups:
        if len(g) > 1 and mask[g[0]]:
            ratio[g] = ratio[g].mean(axis=0)
    print('[rig] 同位置重复顶点 %d 组 (已取均值防裂缝)' % sum(1 for g in groups if len(g) > 1))

    # 写回: 躯干四骨按新比例, 其余骨骼权重不动, 再压回 4 根骨。
    # 未被重蒙皮的顶点保持逐字节不变, 把改动面压到最小。
    for i in np.where(mask)[0]:
        dense[i, chain] = old_torso[i] * ratio[i]
    newJ = J.copy()
    newW = Wt.copy()
    for i in np.where(mask)[0]:
        w = dense[i]
        nz = np.nonzero(w > 1e-5)[0]
        if len(nz) > MAXINF:
            nz = nz[np.argsort(-w[nz], kind='stable')[:MAXINF]]
        nz = nz[np.argsort(-w[nz], kind='stable')]
        tot = w[nz].sum()
        if tot <= 1e-9:
            nz = np.array([int(np.argmax(w))])
            tot = w[nz].sum()
        newJ[i] = 0
        newW[i] = 0
        newJ[i, :len(nz)] = nz
        newW[i, :len(nz)] = w[nz] / tot

    j_base, j_stride, j_cs, _ = accessor_layout(js, attrs['JOINTS_0'])
    w_base, w_stride, w_cs, _ = accessor_layout(js, attrs['WEIGHTS_0'])
    for i in range(nv):
        e = j_base + i * j_stride
        if j_cs == 1:
            struct.pack_into('<4B', bin_, e, *[int(v) for v in newJ[i]])
        else:
            struct.pack_into('<4H', bin_, e, *[int(v) for v in newJ[i]])
        struct.pack_into('<4f', bin_, w_base + i * w_stride, *[float(v) for v in newW[i]])

    write_glb(args.out, js, bin_)
    print('[rig] spine_mid: 关节索引 %d, 世界原点 %s (Spine 骨长的 %.0f%%)'
          % (new_joint_index, np.round(M, 4), args.fraction * 100))
    print('[rig] Spine 骨长 %.4f -> 两段各 %.4f' % (np.linalg.norm(C - S), np.linalg.norm(C - S) * args.fraction))
    print('[out]', args.out, os.path.getsize(args.out), 'bytes')

    if args.report:
        print('\n[report] 躯干四骨的权重覆盖:')
        for bi, b in enumerate(TORSO):
            col = np.zeros(nv)
            for k in range(MAXINF):
                sel = newJ[:, k] == chain[bi]
                col[sel] = newW[sel, k]
            print('   %-10s 权重和 %8.2f  顶点数(>0.05) %5d  最大 %.2f'
                  % (b, col.sum(), int((col > 0.05).sum()), col.max()))
        print('[report] 每顶点最多影响骨骼数:', int((newW > 1e-5).sum(1).max()))


if __name__ == '__main__':
    main()
