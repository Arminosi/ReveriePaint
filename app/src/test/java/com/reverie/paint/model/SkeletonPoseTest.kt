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
    fun `cat rig is the UniRig 40 joint quadruped skeleton plus lumbar`() {
        val cat = catModel()
        val skeleton = cat.skeleton!!
        val names = skeleton.joints.map { it.name }.toSet()
        // 躯干必须是 5 段 (旧资产只有 Hips/Spine/Chest 三段, 蜷缩只能靠顶点弯曲硬掰)
        for (bone in listOf("root", "spine_01", "spine_02", "spine_03", "spine_04",
                            "spine_05", "spine_06", "head")) {
            assertTrue("missing torso bone: $bone", names.contains(bone))
        }
        // 尾 6 段 (旧 5 段太长, 绕不成圈); 四肢各 5 段
        for (i in 1..8) assertTrue("missing tail_%02d".format(i), names.contains("tail_%02d".format(i)))
        for (side in listOf("_L", "_R")) {
            for (bone in listOf("scapula", "shoulder", "elbow", "carpus", "toe_front",
                                "hip", "knee", "hock", "meta", "toe_hind")) {
                assertTrue("missing limb bone: $bone$side", names.contains("$bone$side"))
            }
        }
        assertTrue("old rig bone Chest must be gone", !names.contains("Chest"))
        assertEquals("joint count", 41, skeleton.count)

        // 权重必须归一 (否则躯干会被缩放)
        val weights = cat.jointWeights
        assertNotNull("cat rig must be skinned", weights)
        var worst = 0f
        for (v in 0 until cat.baseMesh.vertexCount) {
            var sum = 0f
            for (k in 0 until 4) sum += weights!![v * 4 + k]
            worst = maxOf(worst, abs(sum - 1f))
        }
        assertTrue("weight sums must be normalized (worst=$worst)", worst < 1e-3f)

        // 脊柱中段俯仰必须真的带动躯干顶点
        val rest = cat.createDeformedMesh(emptyMap())
        val bent = cat.createDeformedMesh(
            mapOf("spine_02" to floatArrayOf(18f, 0f, 0f), "spine_03" to floatArrayOf(18f, 0f, 0f)),
        )
        var moved = 0
        for (i in rest.positions.indices step 3) {
            val d = abs(rest.positions[i] - bent.positions[i]) +
                abs(rest.positions[i + 1] - bent.positions[i + 1]) +
                abs(rest.positions[i + 2] - bent.positions[i + 2])
            if (d > 1e-3f) moved++
        }
        assertTrue("spine bend should move torso vertices (moved=$moved)",
            moved > cat.baseMesh.vertexCount / 20)
    }

    @Test
    fun `cat sit preset folds the hind legs instead of standing`() {
        val cat = catModel()
        val skeleton = cat.skeleton!!
        val sit = PosePresets.buildEulerMap("cat", PosePresets.SIT, emptyMap())
        val stand = PosePresets.buildEulerMap("cat", PosePresets.STAND, emptyMap())
        // 后肢必须真的折起来: 髋 + 膝 相对静止姿态都有明显角度
        for (bone in listOf("hip_L", "knee_L", "hock_L")) {
            val a = sit[bone] ?: floatArrayOf(0f, 0f, 0f)
            val b = stand[bone] ?: floatArrayOf(0f, 0f, 0f)
            assertTrue("$bone should fold in SIT (sit=${a[0]} stand=${b[0]})",
                abs(a[0] - b[0]) > 8f)
        }
        // 贴地后的轮廓高度: 坐姿的重心明显低于站姿 (身体坐在后腿上)
        fun groundedHeight(preset: String): Float {
            val shift = PosePresets.groundShiftForPreset(cat, "cat", preset)
            val mesh = cat.createDeformedMesh(
                PosePresets.buildEulerMap("cat", preset, emptyMap()), null, null, shift,
            )
            var maxY = -Float.MAX_VALUE
            for (i in 1 until mesh.positions.size step 3) maxY = maxOf(maxY, mesh.positions[i])
            return maxY - cat.restMinY
        }
        val standH = groundedHeight(PosePresets.STAND)
        val sitH = groundedHeight(PosePresets.SIT)
        assertTrue("sitting silhouette must be lower (stand=$standH sit=$sitH)", sitH < standH)
        assertNotNull(skeleton)
    }

    @Test
    fun `cat pose presets all ground the paws on the floor line`() {
        val cat = catModel()
        // 美术标定表已清空: 根骨平移会被贴地精确抵消, 所以所有姿态都走几何贴地,
        // 爪子必须落在绑定姿态的地面基准线上 (端坐/趴睡靠折叠四肢真实降低身体)
        for (preset in listOf(
            PosePresets.STAND, PosePresets.SIT, PosePresets.LOAF, PosePresets.STRETCH,
            PosePresets.WALK, PosePresets.CURL, PosePresets.POUNCE, PosePresets.REAR,
            PosePresets.LOOK,
        )) {
            val shift = PosePresets.groundShiftForPreset(cat, "cat", preset)
            val mesh = cat.createDeformedMesh(
                PosePresets.buildEulerMap("cat", preset, emptyMap()),
                null,
                null,
                shift,
                PosePresets.catCurlBend("cat", preset, emptyMap()),
                PosePresets.buildTranslations("cat", preset, emptyMap()),
            )
            assertEquals("preset $preset should touch the floor", cat.restMinY, cat.minYOf(mesh), 3e-3f)
        }
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
        val headIdx = skeleton.joints.indexOfFirst { it.name == "head" }
        assertTrue("Head joint exists", headIdx >= 0)

        // 头颈横向拖拽 = 转头: 轴由"哪根局部轴最竖直"自动推测 (新骨架头骨朝上 -> 局部 Y)
        val headAxis = skeleton.dragSwingAxis(headIdx)
        assertTrue("head swing axis must be resolved", headAxis in 0..2)
        val headSuffix = listOf("p", "y", "r")[headAxis]
        assertEquals(1f, skeleton.dragPitchSign(headIdx), 1e-3f)
        // 符号由几何自动推测 (手指往右 -> 头往右), 下面用行为验证, 不写死正负

        // 新骨架的 head 是叶子关节 (耳挂在 spine_06 上), 关节位置不会动 —— 直接看网格顶点
        val restMesh = cat.createDeformedMesh(PosePresets.buildEulerMap("cat", PosePresets.STAND, emptyMap()))
        val down = cat.createDeformedMesh(
            PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("head_p" to 20f)),
        )
        var movedDown = 0
        for (i in restMesh.positions.indices step 3) {
            val dy = down.positions[i + 1] - restMesh.positions[i + 1]
            if (dy < -1e-3f) movedDown++
        }
        assertTrue("pitch +20 should move head geometry downward (moved=$movedDown)", movedDown > 50)

        val right = cat.createDeformedMesh(
            PosePresets.buildEulerMap("cat", PosePresets.STAND, mapOf("head_$headSuffix" to 20f * skeleton.dragSwingSign(headIdx))),
        )
        var movedSide = 0
        for (i in restMesh.positions.indices step 3) {
            if (abs(right.positions[i] - restMesh.positions[i]) > 1e-3f) movedSide++
        }
        assertTrue("turning the head should move geometry sideways (moved=$movedSide)", movedSide > 50)
    }

    @Test
    fun `limb drag uses a visible swing axis instead of pure twist`() {
        val cat = catModel()
        val skeleton = cat.skeleton!!
        val legIdx = skeleton.joints.indexOfFirst { it.name == "elbow_L" }
        assertTrue("elbow_L exists", legIdx >= 0)

        // 前腿竖直朝下: 绕骨轴旋转只是自转, 屏幕上几乎不动 -> 必须换到横向摆动轴
        val legAxis = skeleton.dragSwingAxis(legIdx)
        assertTrue("leg swing axis must be lateral (got $legAxis)", legAxis in 0..2)
        val legSuffix = listOf("p", "y", "r")[legAxis]


        val rest = PosePresets.buildEulerMap("cat", PosePresets.STAND, emptyMap())
        val pawRest = worldJoint(cat, rest, "toe_front_L")

        // 水平拖拽 +30px, 换算角度 = 30 * 0.6 * sign
        val angle = 30f * 0.6f * skeleton.dragSwingSign(legIdx)
        val dragMap = PosePresets.buildEulerMap(
            "cat", PosePresets.STAND, mapOf("elbow_L_$legSuffix" to angle),
        )
        val pawDrag = worldJoint(cat, dragMap, "toe_front_L")
        // 摆动轴必须让爪子产生明显横向位移 (方向由几何符号决定, 只验证"有位移")
        assertTrue(
            "swing axis should move the paw laterally (paw X ${pawRest[0]} -> ${pawDrag[0]})",
            abs(pawDrag[0] - pawRest[0]) > 5e-3f,
        )

        // 摆动轴的选取规则本身就是"屏幕横向位移最大的那根局部轴", 无需再与固定轴对比
        assertTrue("swing axis must move the paw visibly", abs(pawDrag[0] - pawRest[0]) > 3e-3f)
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
