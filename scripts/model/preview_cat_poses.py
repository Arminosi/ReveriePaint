#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""猫猫 3D 姿态预览 / 校核工具

用与 App 完全一致的数学 (FK + 线性混合蒙皮 + 预设角度表 + 整体升降) 离线形变内置猫猫模型,
导出 OBJ 并用 Blender 渲染对照图, 用于人工核对姿态与"贴地"是否符合预期。

依赖: numpy, 可选 blender (在 PATH 中时自动渲染对照图)
用法:
    python3 scripts/model/preview_cat_poses.py
    python3 scripts/model/preview_cat_poses.py --out build/3d-pose-review --no-render

注意: 本脚本内的预设数值与 TUNED 升降需要与 app/src/main/java/com/reverie/paint/model/PosePresets.kt 同步,
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
GLB = os.path.join(ROOT, 'app/src/main/assets/3d_models/anime_cat.glb')


def export_obj(path, verts, indices):
    """导出 OBJ (转换到 Blender 的 Z-up: x, -z, y)"""
    tris = indices.reshape(-1, 3)
    with open(path, 'w') as f:
        for p in verts:
            f.write('v %.6f %.6f %.6f\n' % (p[0], -p[2], p[1]))
        for t in tris:
            f.write('f %d %d %d\n' % (t[0] + 1, t[1] + 1, t[2] + 1))
def load_glb(path):
    data = open(path,'rb').read(); off = 12; js=None; bin_=None
    while off < len(data):
        clen, ctype = struct.unpack_from('<II', data, off); off += 8
        chunk = data[off:off+clen]; off += clen
        if ctype == 0x4E4F534A: js = json.loads(chunk)
        else: bin_ = chunk
    return js, bin_

def accessor(js, bin_, idx):
    acc = js['accessors'][idx]; bv = js['bufferViews'][acc['bufferView']]
    bvoff = bv.get('byteOffset', 0); accoff = acc.get('byteOffset', 0)
    ncomp = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}[acc['type']]
    ct = acc['componentType']; count = acc['count']
    csize = {5120:1,5121:1,5122:2,5123:2,5125:4,5126:4}[ct]
    elem = csize*ncomp
    stride = bv.get('byteStride') or elem
    out = np.zeros((count, ncomp), dtype=np.float64)
    for i in range(count):
        base = bvoff+accoff+i*stride
        for c in range(ncomp):
            p = base + c*csize
            if ct == 5126: out[i,c] = struct.unpack_from('<f', bin_, p)[0]
            elif ct == 5121: out[i,c] = bin_[p]
            elif ct == 5123: out[i,c] = struct.unpack_from('<H', bin_, p)[0]
            elif ct == 5125: out[i,c] = struct.unpack_from('<I', bin_, p)[0]
            elif ct == 5120: out[i,c] = struct.unpack_from('<b', bin_, p)[0]
            elif ct == 5122: out[i,c] = struct.unpack_from('<h', bin_, p)[0]
    return out

def node_local(n):
    m = np.eye(4)
    if 'matrix' in n: return np.array(n['matrix']).reshape(4,4).T
    T=np.eye(4); R=np.eye(4); S=np.eye(4)
    if 'translation' in n: T[:3,3]=n['translation']
    if 'rotation' in n:
        x,y,z,w=n['rotation']
        R[:3,:3]=np.array([
          [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
          [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
          [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
    if 'scale' in n: S[0,0],S[1,1],S[2,2]=n['scale']
    return T@R@S

def rot_m(deg, x, y, z):
    r = np.radians(deg); c=np.cos(r); s=np.sin(r)
    L=np.hypot(np.hypot(x,y),z); 
    if L<1e-9: return np.eye(4)
    nx,ny,nz = x/L, y/L, z/L; nc=1-c
    m=np.eye(4)
    m[0,0]=nx*nx*nc+c;   m[1,0]=ny*nx*nc+nz*s; m[2,0]=nz*nx*nc-ny*s
    m[0,1]=nx*ny*nc-nz*s; m[1,1]=ny*ny*nc+c;    m[2,1]=nz*ny*nc+nx*s
    m[0,2]=nx*nz*nc+ny*s; m[1,2]=ny*nz*nc-nx*s; m[2,2]=nz*nz*nc+c
    return m

js, bin_ = load_glb(GLB)
skin = js['skins'][0]; joint_nodes = skin['joints']
nodes = js['nodes']
invbinds = accessor(js, bin_, skin['inverseBindMatrices'])   # count x 16
parent = {}
for i,n in enumerate(nodes):
    for c in n.get('children',[]): parent[c]=i

names = [nodes[j].get('name','?') for j in joint_nodes]
index_of = {n:i for i,n in enumerate(names)}
locals_ = [node_local(nodes[j]) for j in joint_nodes]

prim = js['meshes'][0]['primitives'][0]
attrs = prim['attributes']
P = accessor(js, bin_, attrs['POSITION'])
N = accessor(js, bin_, attrs['NORMAL']) if 'NORMAL' in attrs else None
J = accessor(js, bin_, attrs['JOINTS_0']).astype(int)
W = accessor(js, bin_, attrs['WEIGHTS_0'])
IDX = accessor(js, bin_, prim['indices']).astype(int).reshape(-1)

# ---- 姿态映射 (与 PosePresets 猫猫分支一致) ----
ALIAS = {
 'Hips':['Hips','hips'], 'Spine':['Spine','spine'], 'Chest':['Chest','chest'],
 'Neck':['Neck','neck'], 'Head':['Head','head'],
 'l_front_shoulder':['l_front_shoulder','frontleg'], 'r_front_shoulder':['r_front_shoulder','R_frontleg'],
 'l_front_leg':['leftFrontLeg','LeftFrontLeg','l_front_leg','frontleg0'],
 'r_front_leg':['rightFrontLeg','RightFrontLeg','r_front_leg','R_frontleg0'],
 'l_front_paw':['l_front_paw','frontleg1','frontleg2'],
 'r_front_paw':['r_front_paw','R_frontleg1','R_frontleg2'],
 'l_hind_thigh':['leftHindLeg','LeftHindLeg','l_hind_thigh','backleg'],
 'r_hind_thigh':['rightHindLeg','RightHindLeg','r_hind_thigh','R_backleg'],
 'l_hind_shank':['l_hind_shank','backleg0'], 'r_hind_shank':['r_hind_shank','R_backleg0'],
 'l_hind_foot':['l_hind_foot','backleg1','backleg2'], 'r_hind_foot':['r_hind_foot','R_backleg1','R_backleg2'],
 'tail1':['Tail1','tail1','tail'], 'tail2':['Tail2','tail2','tailstart'],
 'tail3':['Tail3','tail3'], 'tail4':['Tail4','tail4'], 'tail5':['tail5'],
}

def pose_map(preset):
    hipP=headP=spineArch=chestP=neckP=0.0
    flSh=frSh=flLeg=frLeg=flPaw=frPaw=0.0
    hlLeg=hrLeg=hlShank=hrShank=hlFoot=hrFoot=0.0
    tailP, tailSide = 35.0, 8.0
    if preset=='sit':
        hipP=-15; spineArch=10; chestP=15; neckP=5; headP=-10
        flSh=12; frSh=12; flLeg=-12; frLeg=-12
        hlLeg=-42; hrLeg=-42; hlShank=55; hrShank=55; hlFoot=-25; hrFoot=-25
        tailP=-30; tailSide=20
    elif preset=='loaf':
        hipP=-12; spineArch=-4; chestP=8; headP=-6
        flSh=42; frSh=42; flLeg=-68; frLeg=-68; flPaw=26; frPaw=26
        hlLeg=-52; hrLeg=-52; hlShank=70; hrShank=70; hlFoot=-22; hrFoot=-22
        tailP=-25; tailSide=30
    elif preset=='walk':
        hipP=3; spineArch=-3; chestP=3; neckP=2; headP=2
        flSh=-12; flLeg=-18; flPaw=10
        frSh=10; frLeg=14; frPaw=-6
        hlLeg=12; hlShank=-8
        hrLeg=-10; hrShank=10
        tailP=25; tailSide=12
    elif preset=='curl':
        hipP=-12; spineArch=4; chestP=8; neckP=12; headP=18
        flSh=42; frSh=42; flLeg=-68; frLeg=-68; flPaw=26; frPaw=26
        hlLeg=-52; hrLeg=-52; hlShank=70; hrShank=70; hlFoot=-22; hrFoot=-22
        tailP=-18; tailSide=50
    elif preset=='pounce':
        hipP=14; spineArch=-6; chestP=-10; neckP=-6; headP=6
        flSh=-18; frSh=-18; flLeg=8; frLeg=8; flPaw=8; frPaw=8
        hlLeg=-20; hrLeg=-20; hlShank=35; hrShank=35; hlFoot=-10; hrFoot=-10
        tailP=10; tailSide=18
    elif preset=='stretch':
        hipP=10; spineArch=-8; chestP=-18; neckP=-5; headP=18
        flSh=-25; frSh=-25; flLeg=-10; frLeg=-10; flPaw=15; frPaw=15
        hlLeg=15; hrLeg=15; hlShank=-10; hrShank=-10; hlFoot=5; hrFoot=5
        tailP=40; tailSide=0
    vals = {
      'Hips':(hipP,0,0), 'Spine':(spineArch,0,0), 'Chest':(chestP,0,0), 'Neck':(neckP,0,0),
      'Head':(headP,0,0),
      'l_front_shoulder':(flSh,0,0), 'r_front_shoulder':(frSh,0,0),
      'l_front_leg':(flLeg,0,0), 'r_front_leg':(frLeg,0,0),
      'l_front_paw':(flPaw,0,0), 'r_front_paw':(frPaw,0,0),
      'l_hind_thigh':(hlLeg,0,0), 'r_hind_thigh':(hrLeg,0,0),
      'l_hind_shank':(hlShank,0,0), 'r_hind_shank':(hrShank,0,0),
      'l_hind_foot':(hlFoot,0,0), 'r_hind_foot':(hrFoot,0,0),
      'tail1':(tailP,0,tailSide),
      'tail2':(26+tailP*0.25,0,tailSide*0.8),
      'tail3':(20+tailP*0.2,0,tailSide*0.6),
      'tail4':(14+tailP*0.15,0,tailSide*0.4),
      'tail5':(10+tailP*0.1,0,tailSide*0.2),
    }
    m = {}
    for key,(p,y,r) in vals.items():
        for nm in ALIAS[key]:
            m[nm] = (p,y,r)
    return m

def fk_and_skin(preset, shift):
    eul = pose_map(preset)
    world = [np.eye(4)]*len(joint_nodes)
    # 拓扑序
    order=[]; seen=set()
    def visit(i):
        if i in seen: return
        p = index_of.get(nodes[joint_nodes[i]].get('name'))  # not used
        seen.add(i); order.append(i)
    # build parent index among joints
    pj = {}
    for i,jn in enumerate(joint_nodes):
        p = parent.get(jn)
        while p is not None and p not in joint_nodes: p = parent.get(p)
        pj[i] = joint_nodes.index(p) if p is not None else -1
    # topological
    order=[]; state=[0]*len(joint_nodes)
    def dfs(i):
        if state[i]==2: return
        if state[i]==1: return
        state[i]=1
        if pj[i]>=0: dfs(pj[i])
        state[i]=2; order.append(i)
    for i in range(len(joint_nodes)): dfs(i)

    for i in order:
        loc = locals_[i]
        e = eul.get(names[i])
        if e and any(e):
            R = rot_m(e[1],0,1,0) @ rot_m(e[0],1,0,0) @ rot_m(e[2],0,0,1)
            loc = loc @ R
        world[i] = (world[pj[i]] @ loc) if pj[i]>=0 else loc

    skin_mats = [world[i] @ invbinds[i].reshape(4,4).T for i in range(len(joint_nodes))]
    V = P.copy()
    out = np.zeros_like(V)
    for v in range(len(V)):
        p = np.array([V[v,0],V[v,1],V[v,2],1.0])
        acc = np.zeros(3); tw = 0.0
        for k in range(4):
            w = W[v,k]
            if w <= 0.001: continue
            M = skin_mats[J[v,k]]
            acc += w * (M[:3,:3] @ p[:3] + M[:3,3]); tw += w
        if tw < 0.001: out[v] = V[v]
        else: out[v] = acc / tw
    out[:,1] += shift
    return out, np.array([[skin_mats[0][0,0]]])  # dummy

def bbox_min(v): return float(v[:,1].min())

# 绑定姿态 (stand, 无偏移) 作为地面基准
rest, _ = fk_and_skin('stand', 0.0)
rest_min = bbox_min(rest)
print('rest minY', rest_min)

variants = {}
for preset in ['stand','sit','loaf','stretch']:
    raw, _ = fk_and_skin(preset, 0.0)
    raw_min = bbox_min(raw)
    computed = rest_min - raw_min
    tuned = {'stand':0.0,'sit':-0.50,'loaf':-0.60,'stretch':-0.10}[preset]
    for label, shift in (('tuned', tuned), ('auto', computed)):
        v, _ = fk_and_skin(preset, shift)
        variants[f'{preset}_{label}'] = v
        print(preset, label, 'shift', round(shift,4), 'minY', round(bbox_min(v),4))


RENDER_TEMPLATE = r"""
import bpy, math
from mathutils import Vector
OUT_DIR = {out_dir!r}
GROUPS = {groups!r}
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

for name in GROUPS:
    clear()
    bpy.ops.mesh.primitive_plane_add(size=6, location=(0, 0, GROUND_Z))
    bpy.context.object.color = (0.42, 0.44, 0.47, 1)
    bpy.ops.object.camera_add(location=(3.4, 0.0, 0.2))
    cam = bpy.context.object
    cam.data.lens = 55
    look_at(cam, (0, 0, -0.02))
    scene.camera = cam
    bpy.ops.wm.obj_import(filepath=OUT_DIR + '/' + name + '.obj', forward_axis='Y', up_axis='Z')
    obj = bpy.context.selected_objects[0]
    obj.color = (0.86, 0.84, 0.82, 1)
    for poly in obj.data.polygons:
        poly.use_smooth = False
    cam.location = Vector((3.6, 0.0, 0.15))
    look_at(cam, (0, 0, -0.02))
    scene.render.filepath = OUT_DIR + '/' + name + '_side.png'
    bpy.ops.render.render(write_still=True)
print('PREVIEW DONE')
"""

def render_sheet(out_dir, names, ground_z):
    if shutil.which('blender') is None:
        print('[preview] blender not found, skip rendering')
        return
    script = out_dir + '/_render.py'
    with open(script, 'w') as f:
        f.write(RENDER_TEMPLATE.format(out_dir=out_dir, groups=names, ground_z=ground_z))
    subprocess.run(['blender', '-b', '--factory-startup', '-P', script], check=False)
    print('[preview] images in', out_dir)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(ROOT, 'build/3d-pose-review'))
    ap.add_argument('--no-render', action='store_true')
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.exists(GLB):
        sys.exit('missing asset: ' + GLB)

    rest, _ = fk_and_skin('stand', 0.0)
    rest_min = bbox_min(rest)
    print('rest minY =', round(rest_min, 4))

    exported = []
    for preset in ['stand', 'sit', 'loaf', 'stretch', 'walk', 'curl', 'pounce']:
        raw, _ = fk_and_skin(preset, 0.0)
        auto = rest_min - bbox_min(raw)
        tuned = {'stand': 0.0, 'loaf': -0.60, 'curl': -0.60, 'stretch': -0.10}.get(preset, auto)
        for label, shift in (('app', tuned), ('auto', auto)):
            verts, _ = fk_and_skin(preset, shift)
            name = '{}_{}'.format(preset, label)
            export_obj(os.path.join(out_dir, name + '.obj'), verts, IDX)
            exported.append(name)
            print('  {:16s} shift={:+.4f} minY={:+.4f}'.format(name, shift, bbox_min(verts)))

    print('[preview] objs in', out_dir)
    if not args.no_render:
        render_sheet(out_dir, exported, rest_min)


if __name__ == '__main__':
    main()
