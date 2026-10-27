#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""猫猫姿态求解器 (v2 骨架): 按"世界仰角"反解 App 需要的逐骨局部俯仰角

原理: 这套骨架所有骨的局部 X 轴都等于世界 +X, 所以绕局部 X 的俯仰在整条链上**线性可加**:
    某骨的世界仰角 = 静止仰角 - 祖先俯仰之和 - 自身俯仰
于是只要给出"我想让每根骨在世界里指向多少度", 局部角可以直接算出来, 不需要迭代拟合。
    仰角定义: 0 = 水平朝前, +90 = 竖直向上, -90 = 竖直向下。

姿态用"人能读懂"的量描述: 脊柱各段仰角、颈/头仰角、四肢各段仰角、尾巴各段仰角;
脚掌落地位置由 `ground` 偏移统一控制, 最后由 App 的贴地把最低点对到地面基准线。

用法:
    python3 scripts/model/cat_pose_solver.py --pose sit,curl          # 打印
    python3 scripts/model/cat_pose_solver.py --pose sit --emit x.json  # 给预览/App 用
依赖: numpy
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preview_cat_poses import DEFAULT_GLB, Rig, pose_map_dict, rot_m  # noqa: E402

# 骨链: 骨 -> 链上的下一根骨 (仰角要用链方向算; 不能用 joint 的第一个子节点,
# 否则 Hips 的仰角会按"骨盆→大腿"算, 完全是另一个方向)
CHAIN = {
    'root': 'spine_01', 'spine_01': 'spine_02', 'spine_02': 'spine_03',
    'spine_03': 'chest', 'chest': 'neck', 'neck': 'head', 'head': 'muzzle',
    'scapula_L': 'shoulder_L', 'shoulder_L': 'elbow_L', 'elbow_L': 'carpus_L',
    'carpus_L': 'toe_front_L',
    'scapula_R': 'shoulder_R', 'shoulder_R': 'elbow_R', 'elbow_R': 'carpus_R',
    'carpus_R': 'toe_front_R',
    'hip_L': 'knee_L', 'knee_L': 'hock_L', 'hock_L': 'meta_L', 'meta_L': 'toe_hind_L',
    'hip_R': 'knee_R', 'knee_R': 'hock_R', 'hock_R': 'meta_R', 'meta_R': 'toe_hind_R',
    'tail_01': 'tail_02', 'tail_02': 'tail_03', 'tail_03': 'tail_04',
    'tail_04': 'tail_05', 'tail_05': 'tail_06',
}

# ---------------------------------------------------------------------------
# 姿态配方 (角度均为世界仰角, 单位度)
#   spine:  root / spine_01 / spine_02 / spine_03 / chest 五根骨的仰角
#   neck, head:  颈与头的仰角
#   front: scapula / shoulder / elbow / carpus / toe  前肢各段
#   hind:  hip / knee / hock / meta / toe            后肢各段
#   tail:  6 段尾巴的仰角 (从尾根到尾尖)
# ---------------------------------------------------------------------------
RECIPES = {
    # 端坐: 骨盆坐到地上, 脊柱立起, 前腿竖直支撑, 后腿折进体侧, 尾巴绕到前面
    'sit': dict(
        spine=(-50, 10, 40, 62, 68),
        neck=62, head=8,
        front=(-70, -88, -90, -88, -20),
        hind=(-25, -150, 0, 0, 0),
        tail=(4, 16, 20, 14, 6, 0),
    ),
    # 农民揣 (趴卧): 全身压低, 前腿折进胸下, 后腿收在体侧, 头抬起平视
    'loaf': dict(
        spine=(-84, -4, 2, 6, 10),
        neck=66, head=4,
        front=(-96, -50, -44, -40, -34),
        hind=(-38, -142, -6, -4, 0),
        tail=(6, 18, 24, 18, 10, 4),
    ),
    # 蜷缩入睡: 脊柱卷成 C (胸腔下压、骨盆上翘), 头收到前爪之间, 尾巴绕体一周
    'curl': dict(
        spine=(-80, -10, 12, 34, 48),
        neck=26, head=-30,
        front=(-102, -40, -32, -28, -22),
        hind=(-34, -140, -12, -8, -4),
        tail=(8, 26, 40, 38, 30, 20),
    ),
    # 站姿: 全部保持静止仰角, 只有尾巴略微上扬
    'stand': dict(spine=None, neck=None, head=None, front=None, hind=None,
                  tail=(0, 0, 0, 0, 0, 0)),
}


# ---------------------------------------------------------------------------
def fk_positions(rig, euler):
    world = [np.eye(4)] * len(rig.names)
    for i in rig.order:
        loc = rig.locals[i]
        e = euler.get(rig.names[i])
        if e and any(e):
            loc = loc @ (rot_m(e[1], 0, 1, 0) @ rot_m(e[0], 1, 0, 0) @ rot_m(e[2], 0, 0, 1))
        world[i] = (world[rig.pj[i]] @ loc) if rig.pj[i] >= 0 else loc
    return {nm: world[i][:3, 3] for i, nm in enumerate(rig.names)}


def rest_elevations(rig):
    pos = fk_positions(rig, {})
    out = {}
    for nm, nxt in CHAIN.items():
        if nm in pos and nxt in pos:
            d = pos[nxt] - pos[nm]
            out[nm] = float(np.degrees(np.arctan2(d[1], d[2])))
    return out


def wrap(deg):
    return (deg + 180.0) % 360.0 - 180.0


def solve(rig, recipe):
    """按配方给出逐骨局部俯仰 (键 = 骨骼名, 值 = [pitch, yaw, roll])

    配方里的脊柱/四肢是**局部角** (与 App 存储一致): 局部角才是蒙皮承受的量, 世界仰角只是结果。
    早期版本按"世界仰角"编写, 结果相邻两段差 60-75 度, 髋部/肩部直接被撕开。
    """
    if recipe.get('local'):
        out = {}
        for bone, pitch in recipe['local'].items():
            if abs(pitch) > 1e-9 and bone in rig.index:
                out[bone] = [float(pitch), 0.0, 0.0]
        for i, delta in enumerate(recipe.get('tail', ())):
            bone = 'tail_%02d' % (i + 1)
            if bone in rig.index and abs(delta) > 1e-9:
                out[bone] = [float(delta), 0.0, 0.0]
        return out
    re = rest_elevations(rig)
    want = {}
    spine_bones = ('root', 'spine_01', 'spine_02', 'spine_03', 'chest')
    if recipe.get('spine'):
        for bone, elev in zip(spine_bones, recipe['spine']):
            want[bone] = elev
    for key, bone in (('neck', 'neck'), ('head', 'head')):
        if recipe.get(key) is not None:
            want[bone] = recipe[key]
    for side in ('_L', '_R'):
        if recipe.get('front'):
            for bone, elev in zip(('scapula', 'shoulder', 'elbow', 'carpus', 'toe_front'),
                                  recipe['front']):
                want[bone + side] = elev
        if recipe.get('hind'):
            for bone, elev in zip(('hip', 'knee', 'hock', 'meta', 'toe_hind'), recipe['hind']):
                want[bone + side] = elev
    if recipe.get('tail'):
        # 尾巴用"相对静止仰角的增量"描述: 静止时尾巴已经向上卷, 绝对值写起来容易翻错
        for i, delta in enumerate(recipe['tail']):
            bone = 'tail_%02d' % (i + 1)
            if bone in re:
                want[bone] = re[bone] + delta

    cum = {}
    out = {}
    for i in rig.order:
        nm = rig.names[i]
        parent_cum = cum.get(rig.pj[i], 0.0) if rig.pj[i] >= 0 else 0.0
        if nm in want and nm in re:
            own = wrap((re[nm] - want[nm]) - parent_cum)
        else:
            own = 0.0
        cum[i] = parent_cum + own
        if abs(own) > 1e-9:
            out[nm] = [round(own, 1), 0.0, 0.0]
    return out


def report(rig, pose, name):
    eul = {k: tuple(v) + (0.0,) * (3 - len(v)) for k, v in pose.items()}
    pos = fk_positions(rig, eul)
    verts = rig.deformed(eul, {})
    miny = float(verts[:, 1].min())
    shift = rig.rest_min - miny
    verts[:, 1] += shift
    p50, p99, pmax, bad = rig.stretch(verts)
    worst = max((abs(v[0]) for v in pose.values()), default=0.0)
    tally = {}
    for v in np.where(verts[:, 1] < verts[:, 1].min() + 0.01)[0]:
        k = int(np.argmax(rig.W[v]))
        nm = rig.names[rig.J[v, k]]
        tally[nm] = tally.get(nm, 0) + 1
    print('\n[%s] 骨数 %d, 最大单骨角 %.0f°' % (name, len(pose), worst))
    print('   高度 %.3f  长度 %.3f  高长比 %.2f (判据 A1 要求蜷缩 <= 0.62)'
          % (float(verts[:, 1].max()) - rig.rest_min,
             float(verts[:, 2].max() - verts[:, 2].min()),
             (float(verts[:, 1].max()) - rig.rest_min) /
             max(1e-6, float(verts[:, 2].max() - verts[:, 2].min()))))
    print('   拉伸 p50 %.2f p99 %.2f max %.2f (A4 要求 p99 <= 1.35)  贴地 %+.3f'
          % (p50, p99, pmax, shift))
    print('   贴地点:', dict(sorted(tally.items(), key=lambda kv: -kv[1])))
    for jn in ('toe_front_L', 'toe_hind_L', 'muzzle', 'chest', 'head'):
        if jn in pos:
            print('   %-12s 世界 %s' % (jn, np.round(pos[jn], 3)))
    return p99, worst, tally


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default='build/3d-rig/v2/cat_v2.glb')
    ap.add_argument('--pose', default='sit')
    ap.add_argument('--rest', action='store_true')
    ap.add_argument('--emit')
    args = ap.parse_args()
    rig = Rig(args.model)
    if args.rest:
        for nm, e in sorted(rest_elevations(rig).items(), key=lambda kv: kv[1]):
            print('   %-14s %7.1f' % (nm, e))
        return
    solved = {}
    for name in args.pose.split(','):
        pose = solve(rig, RECIPES[name])
        solved[name] = pose
        report(rig, pose, name)
        print('   angles:', {k: v[0] for k, v in sorted(pose.items())})
    if args.emit:
        json.dump(solved, open(args.emit, 'w'), indent=1, ensure_ascii=False)
        print('[emit]', args.emit)


if __name__ == '__main__':
    main()
