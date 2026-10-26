/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 骨骼姿态核心逻辑测试 (纯 Kotlin, 不依赖 Android)
 *
 * 覆盖:
 * 1. 语义键的角度只施加一次 (旧实现会翻倍);
 * 2. 通用后缀键与语义键合成到同一根骨骼;
 * 3. 预设贴地量把最低点对齐到绑定姿态地面基准线;
 * 4. 拖拽轴角色: 头/颈绕骨轴转头, 四肢改用横向摆动轴, 且手指方向与骨尖移动方向一致;
 * 5. 蒙皮权重归一化;
 * 6. 关节顺序乱序时的 FK 拓扑正确性。
 */
class SkeletonPoseTest {

    private fun catModel(): SkinnedMesh3D {
        val f = File("src/main/assets/3d_models/anime_cat.glb")
        assertTrue("cat asset missing", f.exists())
        val skinned = GlbParser.parse(f.inputStream())
        assertNotNull(skinned.skeleton)
        return skinned
    }

    private fun boyModel(): SkinnedMesh3D {
        val f = File("src/main/assets/3d_models/anime_boy.glb")
        assertTrue("boy asset missing", f.exists())
        val skinned = GlbParser.parse(f.inputStream())
        assertNotNull(skinned.skeleton)
        return skinned
    }

    private fun worldJoint(skinned: SkinnedMesh3D, euler: Map<String, FloatArray>, name: String): FloatArray {
        val skeleton = skinned.skeleton!!
        val count = skeleton.count
        val out = FloatArray(count * 3)
        skinned.createDeformedMesh(euler, out)
        val idx = skeleton.joints.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        assertTrue("joint not found: $name", idx >= 0)
        return floatArrayOf(out[idx * 3], out[idx * 3 + 1], out[idx * 3 + 2])
    }

    // ------------------------------------------------------------------
    // 1 / 2: 角度施加次数
    // ------------------------------------------------------------------

    @Test
    fun `humanoid slider angles are applied exactly once`() {
        val map = PosePresets.buildEulerMap(
            "boy",
            PosePresets.STAND,
            mapOf("head_pitch" to 20f, "head_yaw" to -12f, "spine_pitch" to 15f, "spine_yaw" to 9f),
        )
        // 旧实现里 "head_pitch" 先被语义分支读走, 又被兜底分支按 "head" 追加一次 -> 20 变 40
        assertEquals(20f, map["Head"]!![0], 1e-4f)
        assertEquals(-12f, map["Head"]!![1], 1e-4f)
        assertEquals(15f, map["spine"]!![0], 1e-4f)
        assertEquals(9f, map["spine"]!![1], 1e-4f)
        // 上半身分摊到 chest 的一半
        assertEquals(7.5f, map["chest"]!![0], 1e-4f)
    }

    @Test
    fun `generic suffix keys compose with semantic preset angles`() {
        val map = PosePresets.buildEulerMap(
            "boy",
            PosePresets.STAND,
            mapOf("head_pitch" to 20f, "Head_y" to 10f, "head_r" to -5f),
        )
        val head = map["Head"]!!
        assertEquals(20f, head[0], 1e-4f)
        assertEquals(10f, head[1], 1e-4f)
        assertEquals(-5f, head[2], 1e-4f)
        // 语义分支登记过的 bone 键必须与兜底键共享同一个数组, 否则两根骨头会各转一份
        assertTrue(map["head"] === head)
    }

    // ------------------------------------------------------------------
    // 3: 预设贴地
    // ------------------------------------------------------------------

    @Test
    fun `cat pose presets keep the art-tuned vertical placement`() {
        val cat = catModel()
        // 端坐/农民揣/伸懒腰是美术标定过的构图参数: 骨架只能旋转, 骨盆没法自己落地, 只能整模型补升降
        val expected = mapOf(
            PosePresets.STAND to 0f,
            PosePresets.SIT to -0.50f,
            PosePresets.LOAF to -0.60f,
            PosePresets.STRETCH to -0.10f,
        )
        val shifts = LinkedHashMap<String, Float>()
        for ((preset, want) in expected) {
            val shift = PosePresets.groundShiftForPreset(cat, "cat", preset)
            shifts[preset] = shift
            assertEquals("preset $preset shift", want, shift, 1e-4f)
            val euler = PosePresets.buildEulerMap("cat", preset, emptyMap())
            cat.createDeformedMesh(euler, null, null, shift).let { mesh ->
                assertTrue("$preset mesh finite", cat.minYOf(mesh).isFinite())
            }
        }
        println("cat preset shifts = $shifts")
    }

    @Test
    fun `untuned presets auto-ground onto the bind pose floor`() {
        val boy = boyModel()
        val shifts = LinkedHashMap<String, Float>()
        for (preset in listOf(
            PosePresets.STAND, PosePresets.SIT, PosePresets.ACTION,
            PosePresets.CROSSED, PosePresets.HIPS, PosePresets.KNEEL,
        )) {
            val shift = PosePresets.groundShiftForPreset(boy, "boy", preset)
            shifts[preset] = shift
            val euler = PosePresets.buildEulerMap("boy", preset, emptyMap())
            val mesh = boy.createDeformedMesh(euler, null, null, shift)
            assertEquals(
                "preset $preset should sit on the bind-pose floor",
                boy.restMinY,
                boy.minYOf(mesh),
                2e-3f,
            )
            assertTrue("$preset shift finite", shift.isFinite())
            assertTrue("$preset shift sane: $shift", shift in -1.5f..1.5f)
        }
        println("boy auto-ground shifts = $shifts")
        // 站姿就是绑定姿态, 不应该被移动
        assertEquals(0f, shifts[PosePresets.STAND]!!, 2e-3f)
    }

    @Test
    fun `auto ground keeps joint positions and mesh consistent`() {
        val boy = boyModel()
        val shift = PosePresets.groundShiftForPreset(boy, "boy", PosePresets.SIT)
        val euler = PosePresets.buildEulerMap("boy", PosePresets.SIT, emptyMap())
        val joints = FloatArray(boy.skeleton!!.count * 3)
        val mesh = boy.createDeformedMesh(euler, joints, null, shift)
        // 关节世界坐标同样要跟着整体升降, 否则骨架覆盖层会和模型错位
        val headIdx = boy.skeleton!!.joints.indexOfFirst { it.name == "Head" }
        assertTrue("Head joint above floor", joints[headIdx * 3 + 1] > boy.restMinY)
        // 网格最低点与地面基准线一致 (float 误差内)
        assertTrue(abs(boy.minYOf(mesh) - boy.restMinY) < 2e-3f)
    }

    // ------------------------------------------------------------------
    // 4: 拖拽轴角色
    // ------------------------------------------------------------------

    @Test
    fun `head drag turns around the bone axis with finger-consistent signs`() {
        val cat = catModel()
        val skeleton = cat.skeleton!!
        val headIdx = skeleton.joints.indexOfFirst { it.name == "Head" }
        assertTrue("Head joint exists", headIdx >= 0)

        // 头颈横向拖拽 = 转头 (绕局部 Y 扭转), 纵向拖拽 = 低头/抬头
        assertEquals(1, skeleton.dragSwingAxis(headIdx))
        assertEquals(1f, skeleton.dragPitchSign(headIdx), 1e-3f)
        assertEquals(1f, skeleton.dragSwingSign(headIdx), 1e-3f)

        val rest = PosePresets.buildEulerMap("cat", PosePresets.STAND, emptyMap())
        val muzzleRest = worldJoint(cat, rest, "muzzle")

        // 手指向下拖拽 -> pitch 为正 -> 口吻 (muzzle) 世界 Y 下降
        val downDrag = PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("Head_p" to 20f))
        val muzzleDown = worldJoint(cat, downDrag, "muzzle")
        assertTrue(
            "drag down should pitch the head down (muzzle Y ${muzzleRest[1]} -> ${muzzleDown[1]})",
            muzzleDown[1] < muzzleRest[1] - 1e-3f,
        )

        // 手指向右拖拽 -> yaw 为正 -> 口吻世界 X 向右 (+X)
        val rightDrag = PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("Head_y" to 20f))
        val muzzleRight = worldJoint(cat, rightDrag, "muzzle")
        assertTrue(
            "drag right should turn the head right (muzzle X ${muzzleRest[0]} -> ${muzzleRight[0]})",
            muzzleRight[0] > muzzleRest[0] + 1e-3f,
        )
    }

    @Test
    fun `limb drag uses a visible swing axis instead of pure twist`() {
        val cat = catModel()
        val skeleton = cat.skeleton!!
        val legIdx = skeleton.joints.indexOfFirst { it.name == "l_front_leg" }
        assertTrue("l_front_leg exists", legIdx >= 0)

        // 前腿竖直朝下: 绕骨轴 (局部 Y) 旋转只是自转, 屏幕上几乎不动 -> 必须换到横向摆动轴
        assertEquals(2, skeleton.dragSwingAxis(legIdx))
        assertEquals(-1f, skeleton.dragSwingSign(legIdx), 1e-3f)

        val rest = PosePresets.buildEulerMap("cat", PosePresets.STAND, emptyMap())
        val pawRest = worldJoint(cat, rest, "l_front_paw")

        // 水平拖拽 +30px, 换算角度 = 30 * 0.6 * sign
        val angle = 30f * 0.6f * skeleton.dragSwingSign(legIdx)
        val dragMap = PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("l_front_leg_r" to angle))
        val pawDrag = worldJoint(cat, dragMap, "l_front_paw")
        assertTrue(
            "drag right should swing the paw right (paw X ${pawRest[0]} -> ${pawDrag[0]})",
            pawDrag[0] > pawRest[0] + 1e-3f,
        )

        // 对照: 同样的角度若加在骨轴 (Y) 上, 爪子的横向位移应小一个数量级
        val twistMap = PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("l_front_leg_y" to angle))
        val pawTwist = worldJoint(cat, twistMap, "l_front_paw")
        val swingDx = abs(pawDrag[0] - pawRest[0])
        val twistDx = abs(pawTwist[0] - pawRest[0])
        assertTrue("swing ($swingDx) should dominate twist ($twistDx)", swingDx > twistDx * 3f)
    }

    @Test
    fun `humanoid arm drag also swings instead of twisting`() {
        val boy = boyModel()
        val skeleton = boy.skeleton!!
        val armIdx = skeleton.joints.indexOfFirst { it.name == "upperarm.l" }
        assertTrue("upperarm.l exists", armIdx >= 0)
        assertEquals(2, skeleton.dragSwingAxis(armIdx))

        val rest = PosePresets.buildEulerMap("boy", PosePresets.STAND, emptyMap())
        val handRest = worldJoint(boy, rest, "hand.l")
        val angle = 30f * 0.6f * skeleton.dragSwingSign(armIdx)
        val dragMap = PosePresets.buildEulerMap("boy", PosePresets.STAND, mapOf("upperarm.l_r" to angle))
        val handDrag = worldJoint(boy, dragMap, "hand.l")
        val sign = skeleton.dragSwingSign(armIdx)
        val dx = handDrag[0] - handRest[0]
        assertTrue(
            "drag right (sign $sign) should swing the hand right, dx=$dx",
            dx * sign > 1e-3f,
        )
    }

    // ------------------------------------------------------------------
    // 5: 权重归一化
    // ------------------------------------------------------------------

    @Test
    fun `skinning normalizes weights that do not sum to one`() {
        val mesh = Mesh3D(
            positions = floatArrayOf(1f, 0f, 0f),
            normals = floatArrayOf(1f, 0f, 0f),
            indices = intArrayOf(),
            wireframeIndices = intArrayOf(),
        )
        val joint = Joint3D(
            index = 0,
            name = "root",
            parentIndex = -1,
            localMatrix = Math3D.identity(),
            inverseBindMatrix = Math3D.identity(),
        )
        val skinned = SkinnedMesh3D(
            baseMesh = mesh,
            skeleton = Skeleton3D(listOf(joint)),
            jointIndices = intArrayOf(0, 0, 0, 0),
            jointWeights = floatArrayOf(0.5f, 0f, 0f, 0f), // 权重和只有 0.5
        )
        // 绕 Z 轴 90°: (1,0,0) -> (0,1,0)。归一化后必须走到完整位置, 而不是走到一半
        val deformed = skinned.createDeformedMesh(mapOf("root" to floatArrayOf(0f, 0f, 90f)))
        assertEquals(0f, deformed.positions[0], 1e-4f)
        assertEquals(1f, deformed.positions[1], 1e-4f)
    }

    // ------------------------------------------------------------------
    // 6: FK 拓扑序
    // ------------------------------------------------------------------

    @Test
    fun `forward kinematics is correct when children come first in the joint list`() {
        val identity = Math3D.identity()
        val tipLocal = Math3D.translate(identity, 1f, 0f, 0f)
        val tipInverseBind = Math3D.translate(identity, -1f, 0f, 0f)
        // 故意让子关节排在父关节前面
        val child = Joint3D(0, "tip", 1, tipLocal, tipInverseBind)
        val parent = Joint3D(1, "root", -1, Math3D.identity(), Math3D.identity())
        val skeleton = Skeleton3D(listOf(child, parent))

        val mesh = Mesh3D(
            positions = floatArrayOf(1f, 0f, 0f),
            normals = floatArrayOf(1f, 0f, 0f),
            indices = intArrayOf(),
            wireframeIndices = intArrayOf(),
        )
        val skinned = SkinnedMesh3D(mesh, skeleton, intArrayOf(0, 0, 0, 0), floatArrayOf(1f, 0f, 0f, 0f))
        val out = FloatArray(2 * 3)
        skinned.createDeformedMesh(mapOf("root" to floatArrayOf(0f, 0f, 90f)), out)

        // 子关节索引是 0 (故意排在父前面), 父关节索引 1
        // 父绕 Z 转 90° 后: 父世界位置 (0,0,0), 子 (1,0,0) -> (0,1,0)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[1], 1e-4f)
        assertEquals(0f, out[3], 1e-4f)
        assertEquals(0f, out[4], 1e-4f)
        // 蒙皮后的顶点也应当落在同一位置
        val deformed = skinned.createDeformedMesh(mapOf("root" to floatArrayOf(0f, 0f, 90f)))
        assertEquals(0f, deformed.positions[0], 1e-4f)
        assertEquals(1f, deformed.positions[1], 1e-4f)
    }

    // ------------------------------------------------------------------
    // 7: 缓存无关的形变稳定性
    // ------------------------------------------------------------------

    @Test
    fun `deform buffer reuse produces identical vertices`() {
        val cat = catModel()
        val euler = PosePresets.buildEulerMap(
            "cat",
            PosePresets.SIT,
            mapOf("cat_head_yaw" to 18f, "cat_tail_pitch" to 22f),
        )
        val owned = cat.createDeformedMesh(euler)
        val buffer = DeformBuffer(cat.baseMesh.vertexCount, cat.skeleton!!.count)
        val reused = cat.createDeformedMesh(euler, null, buffer)
        assertEquals(owned.vertexCount, reused.vertexCount)
        for (i in owned.positions.indices) {
            if (abs(owned.positions[i] - reused.positions[i]) > 1e-4f) {
                throw AssertionError("vertex mismatch at $i: ${owned.positions[i]} vs ${reused.positions[i]}")
            }
        }
    }
}
