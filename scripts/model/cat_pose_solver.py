#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""猫猫姿态求解器: 用"世界仰角 + 两骨 IK"解出 App 需要的逐骨局部俯仰角

为什么需要它: App 的姿态系统只暴露"每根骨绕自身局部 X 轴转多少度", 而端坐/蜷缩这类姿态
要的是"前爪和后踝同时落在同一水平面上、骨盆压到最低、躯干抬到某个角度" —— 手调这些角度
几乎必然调出"腿在空中划水 / 骨盆悬空"。这里把姿态写成可读的参数, 由几何反解出角度。

两个关键事实让这件事变得简单:
  1) 这套骨架所有骨的局部 X 轴都等于世界 +X (build_cat_glb.py 对齐过), 所以绕局部 X 的
     俯仰在整条链上线性可加: 世界仰角 = 静止仰角 - 祖先俯仰和 - 自身俯仰。
     仰角定义: 0 = 水平朝前, +90 = 竖直向上。
  2) 后腿是"股骨 + 胫骨 + 跖骨", 给定髋关节与目标踝位置可以用余弦定理精确解出 (两骨 IK),
     跖骨再按贴地要求单独摆平 —— 不需要迭代拟合。

用法:
    python3 scripts/model/cat_pose_solver.py --pose sit,curl            # 只打印
    python3 scripts/model/cat_pose_solver.py --pose sit --emit out.json  # 给预览脚本用
    python3 scripts/model/cat_pose_solver.py --rest                      # 打印静止仰角
依赖: numpy
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preview_cat_poses import DEFAULT_GLB, Rig, pose_map_dict, rot_m  # noqa: E402

# 骨链的"下一根骨": 仰角要用链上的下一根骨算方向, 不能用 joint 的第一个子节点
# (Hips 的第一个子节点是大腿, Chest 的是肩, Head 的是耳朵, 会把仰角算歪)
NEXT_BONE = {
    'Hips': 'Spine', 'Spine': 'spine_mid', 'spine_mid': 'Chest', 'Chest': 'Neck',
    'Neck': 'Head', 'Head': 'muzzle',
    'l_hind_thigh': 'l_hind_shank', 'l_hind_shank': 'l_hind_foot', 'l_hind_foot': 'backleg2',
    'r_hind_thigh': 'r_hind_shank', 'r_hind_shank': 'r_hind_foot', 'r_hind_foot': 'R_backleg2',
    'l_front_shoulder': 'l_front_leg', 'l_front_leg': 'l_front_paw', 'l_front_paw': 'frontleg2',
    'r_front_shoulder': 'r_front_leg', 'r_front_leg': 'r_front_paw', 'r_front_paw': 'R_frontleg2',
    'tail1': 'tail2', 'tail2': 'tail3', 'tail3': 'tail4', 'tail4': 'tail5',
}

# 骨骼 -> PosePresets 的键 (PosePresets.kt 猫猫分支认这些键)
BONE2KEY = {
    'Hips': 'hip', 'Spine': 'spine', 'spine_mid': 'smid', 'Chest': 'chest',
    'Neck': 'neck', 'Head': 'head',
    'l_hind_thigh': 'hlleg', 'l_hind_shank': 'hlshank', 'l_hind_foot': 'hlfoot',
    'r_hind_thigh': 'hrleg', 'r_hind_shank': 'hrshank', 'r_hind_foot': 'hrfoot',
    'l_front_shoulder': 'flsh', 'l_front_leg': 'flleg', 'l_front_paw': 'flpaw',
    'r_front_shoulder': 'frsh', 'r_front_leg': 'frleg', 'r_front_paw': 'frpaw',
}

# 每根骨在链上的父骨 (挑 IK 分支时要用父链累计)
_PARENT = {
    'l_hind_thigh': 'Hips', 'l_hind_shank': 'l_hind_thigh', 'l_hind_foot': 'l_hind_shank',
    'r_hind_thigh': 'Hips', 'r_hind_shank': 'r_hind_thigh', 'r_hind_foot': 'r_hind_shank',
    'l_front_shoulder': 'Chest', 'l_front_leg': 'l_front_shoulder', 'l_front_paw': 'l_front_leg',
    'r_front_shoulder': 'Chest', 'r_front_leg': 'r_front_shoulder', 'r_front_paw': 'r_front_leg',
    'Neck': 'Chest', 'Head': 'Neck',
}


def _wrap(deg):
    """把角度归一到 (-180, 180], 用于比较转动量"""
    return (deg + 180.0) % 360.0 - 180.0


FRONT_LEG_BONES = ('l_front_shoulder', 'r_front_shoulder', 'l_front_leg', 'r_front_leg',
                   'l_front_paw', 'r_front_paw')

# ---------------------------------------------------------------------------
# 姿态配方: 全部是"人能读懂"的量, 不是角度
#   theta    躯干 (Spine 骨) 的世界仰角, 0 = 水平朝前, 90 = 竖直
#   pelvis   骨盆骨 (Hips) 的世界仰角 (静止 -76.7, 越大骨盆越"躺平")
#   chest/neck/head  胸骨 / 颈骨 / 头骨的世界仰角
#   hock     后踝 (跗关节) 相对前爪落点的高度偏移, 0 = 与前爪同高
#   hock_z   后踝相对骨盆的前后位置 (负 = 在骨盆后面)
#   foot     跖骨世界仰角, 0 = 贴地平放
# ---------------------------------------------------------------------------
RECIPES = {
    # 端坐: 骨盆压到后脚上, 脊柱抬到 48 度, 前腿竖直支撑, 后踝与前爪同高
    'sit': dict(theta=48, pelvis=-70, chest=-25, neck=70, head=0,
                hock=0.0, hock_z=-0.05, foot=0.0,
                tail=dict(tailp=30, tailside=20)),
    # 蜷缩入睡: 全身趴低, 脊柱把胸腔卷向地面, 头收进前爪, 尾巴绕到体侧
    'curl': dict(theta=6, pelvis=-88, chest=-33, neck=55, head=-10, headyaw=32,
                 hock=0.0, hock_z=-0.06,
                 front=0.40, ground=-0.02,
                 tail=dict(tailp=-18, tailside=62)),
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
    for nm, nxt in NEXT_BONE.items():
        if nm in pos and nxt in pos:
            d = pos[nxt] - pos[nm]
            out[nm] = float(np.degrees(np.arctan2(d[1], d[2])))
    return out


def two_bone_ik(a, target, l1, l2):
    """平面 (前, 上) 两骨 IK: 返回两个解 (共线两侧), 由调用方按"角度最小"挑一支

    两个解在几何上都成立, 但其中一支常是"反关节" (膝盖朝后上方), 需要把股骨翻 150 度以上,
    线性混合蒙皮撑不住这么大的折角 —— 表现出来的就是髋部塌成一团。所以挑转动量小的那支。
    """
    d = target - a
    L = float(np.linalg.norm(d))
    L = min(L, (l1 + l2) * 0.999)
    u = d / np.linalg.norm(d)
    cosA = max(-1.0, min(1.0, (l1 * l1 + L * L - l2 * l2) / (2 * l1 * L)))
    A = np.arccos(cosA)
    n = np.array([u[1], -u[0]])
    out = []
    for sign in (1.0, -1.0):
        out.append(a + l1 * (np.cos(A) * u + sign * np.sin(A) * n))
    return out


def angles_from_world(rig, re, want):
    """世界仰角 -> 局部俯仰 (沿链线性累加), 输出 PosePresets 键字典"""
    cum = {}
    pose = {}
    for i in rig.order:
        nm = rig.names[i]
        parent_cum = cum.get(rig.pj[i], 0.0) if rig.pj[i] >= 0 else 0.0
        own = (re[nm] - want[nm]) - parent_cum if nm in want else 0.0
        cum[i] = parent_cum + own
        if nm in want and nm in BONE2KEY and abs(own) > 1e-9:
            # 归一到 (-180, 180]: 旋转矩阵是周期的, 归一不改变姿态, 但导出值更好读
            pose[BONE2KEY[nm]] = round(float((own + 180.0) % 360.0 - 180.0), 1)
    return pose


def solve_limb(want, re, rig, pos, anchor, names, target, tip_elev):
    """把一条腿的两段骨用 IK 摆到 target (相对骨盆的 y,z), 末端骨按 tip_elev 摆平

    两个 IK 解里挑"两段骨转动量之和最小"的一支 —— 反关节解虽然位置也对, 但要把上段骨翻 150 度,
    蒙皮会塌。这条规则同时把"人看着自然"和"变形最小"两件事对齐了。
    """
    upper, lower, tip = names
    a = (pos[upper] - anchor)[[1, 2]]
    # IK 在 (y,z) 平面里做, 骨的有效长度要取平面投影长度 (骨本身带 x 分量)
    d1 = (pos[lower] - pos[upper])[[1, 2]]
    d2 = (pos[tip] - pos[lower])[[1, 2]]
    l1 = float(np.linalg.norm(d1))
    l2 = float(np.linalg.norm(d2))
    tgt = np.asarray(target, dtype=float)
    parent_local = want.get(_PARENT[upper])
    best = None
    for mid in two_bone_ik(a, tgt, l1, l2):
        e_up = float(np.degrees(np.arctan2(*(mid - a)[::-1])))
        e_low = float(np.degrees(np.arctan2(*(tgt - mid)[::-1])))
        if upper not in re or lower not in re:
            cost = pd = _wrap(e_up - re[upper]) + _wrap(e_low - re[lower])
        else:
            # 局部俯仰 = (静止仰角 - 目标仰角) - 父链累计; 父链累计对两个解相同, 只比较转动量
            pd = _wrap(re[upper] - e_up) + _wrap(re[lower] - e_low)
            cost = abs(_wrap(re[upper] - e_up)) + abs(_wrap(re[lower] - e_low - (_wrap(re[upper] - e_up))))
        if best is None or cost < best[0]:
            best = (cost, mid, e_up, e_low, pd)
    _, mid, e_up, e_low, _ = best
    want[upper] = e_up
    want[lower] = e_low
    if tip in re:      # 末端骨若是叶子 (muzzle) 没有骨方向, 不参与仰角求解
        # tip_elev=None 表示"保持静止朝向" (局部 0): 脚掌/跖骨强行摆平会把脚部蒙皮拧断
        want[tip] = re[tip] if tip_elev is None else tip_elev


def solve(rig, recipe):
    """解出一条姿态的逐骨局部俯仰 (返回 PosePresets 键字典)"""
    re = rest_elevations(rig)
    want = {
        'Hips': recipe['pelvis'], 'Spine': recipe['theta'], 'spine_mid': recipe['theta'],
        'Chest': recipe['chest'],
        'Neck': recipe.get('neck', 70.2), 'Head': recipe.get('head', -26.9),
    }
    for b in FRONT_LEG_BONES:          # 前腿竖直: 世界仰角保持静止值
        want[b] = re[b]
    stage1 = angles_from_world(rig, re, want)

    # 用第一阶段的躯干位置做参考, 再解前腿 (可选折叠) 与后腿 IK
    pos = fk_positions(rig, pose_map_dict(stage1))
    anchor = pos['Hips']
    toe = (pos['frontleg2'] - anchor)[[1, 2]]          # 前腿竖直时的落点 = 地面基准
    ground_y = toe[0] + recipe.get('ground', 0.0)

    if 'muzzle' in recipe:     # 头: 用颈+头两骨 IK 把吻部放到指定位置 (低头收进来 / 抬头平视)
        my, mz = recipe['muzzle']
        solve_limb(want, re, rig, pos, anchor, ('Neck', 'Head', 'muzzle'),
                   (ground_y + my, mz), 0.0)

    if 'front' in recipe:      # 前腿折叠 (农民揣 / 蜷缩): 前掌摆到指定位置
        fz = recipe['front']
        for side in ('l', 'r'):
            solve_limb(want, re, rig, pos, anchor,
                       (side + '_front_shoulder', side + '_front_leg', side + '_front_paw'),
                       (ground_y, fz), recipe.get('front_paw'))
    for side in ('l', 'r'):
        solve_limb(want, re, rig, pos, anchor,
                   (side + '_hind_thigh', side + '_hind_shank', side + '_hind_foot'),
                   (ground_y + recipe.get('hock', 0.0), recipe['hock_z']), recipe.get('foot'))

    pose = angles_from_world(rig, re, want)
    pose.update(recipe.get('tail', {}))
    pose['typh'] = 0.0
    return pose


# ---------------------------------------------------------------------------
def report(rig, pose, name):
    eul = pose_map_dict(pose)
    pos = fk_positions(rig, eul)
    anchor = pos['Hips']

    def rel(n):
        return np.round(pos[n] - anchor, 3)

    verts = rig.deformed(eul, {})
    min_y = float(verts[:, 1].min())
    shift = rig.rest_min - min_y
    verts[:, 1] += shift
    p50, p99, pmax, bad = rig.stretch(verts)
    print('\n[%s] %s' % (name, {k: v for k, v in sorted(pose.items())}))
    for jn in ('frontleg2', 'l_hind_foot', 'backleg2', 'l_hind_shank', 'Chest', 'Neck', 'Head',
               'tail5', 'muzzle'):
        print('   %-14s 相对骨盆 %s' % (jn, rel(jn)))
    print('   高度 %.3f 长度 %.3f (站立 %.3f / %.3f), 拉伸 p99 %.2f max %.2f (>1.25: %d)'
          % (float(verts[:, 1].max()) - rig.rest_min,
             float(verts[:, 2].max() - verts[:, 2].min()),
             float(rig.P[:, 1].max()) - rig.rest_min,
             float(rig.P[:, 2].max() - rig.P[:, 2].min()), p99, pmax, bad))
    tally = {}
    for v in np.where(verts[:, 1] < verts[:, 1].min() + 0.01)[0]:
        k = int(np.argmax(rig.W[v]))
        nm = rig.names[rig.J[v, k]]
        tally[nm] = tally.get(nm, 0) + 1
    print('   贴地点:', dict(sorted(tally.items(), key=lambda kv: -kv[1])))


def emit_line(pose, name):
    keys = ['hip', 'spine', 'smid', 'chest', 'neck', 'head',
            'flsh', 'flleg', 'flpaw', 'hlleg', 'hlshank', 'hlfoot', 'tailp', 'tailside']
    vals = ', '.join('%s=%s' % (k, pose[k]) for k in keys if k in pose)
    print("    '%s': dict(%s)," % (name, vals))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default=DEFAULT_GLB)
    ap.add_argument('--pose', default='sit')
    ap.add_argument('--rest', action='store_true')
    ap.add_argument('--emit', help='写成 JSON, 供 preview_cat_poses.py --poses-json 载入')
    args = ap.parse_args()
    rig = Rig(args.model)
    if args.rest:
        for nm, e in sorted(rest_elevations(rig).items(), key=lambda kv: kv[1]):
            print('   %-18s %7.1f' % (nm, e))
        return
    if 'spine_mid' not in rig.index:
        sys.exit('模型没有 spine_mid, 先跑 add_spine_mid.py')
    solved = {}
    for name in args.pose.split(','):
        pose = solve(rig, RECIPES[name])
        solved[name] = pose
        report(rig, pose, name)
        emit_line(pose, name)
    if args.emit:
        json.dump(solved, open(args.emit, 'w'), indent=1, ensure_ascii=False)
        print('[emit]', args.emit)


if __name__ == '__main__':
    main()
