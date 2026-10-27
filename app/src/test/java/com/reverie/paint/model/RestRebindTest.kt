/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 关节静止位置偏移的核心不变量: **静止网格不能变**。
 *
 * 判定方式: 绑定姿态下 skinning = world * inverseBind 必须等于单位阵。
 * 满足则任何顶点在静止姿态下的位置不变 (P' = skin * P = P), 只是旋转轴心移动了。
 */
class RestRebindTest {

    /** 构造一条 3 关节的直链: 0 -> 1 -> 2, 每节沿 +Y 平移 0.5 */
    private fun chain(): Skeleton3D {
        val joints = ArrayList<Joint3D>()
        for (i in 0 until 3) {
            val local = Math3D.identity()
            local[13] = if (i == 0) 0f else 0.5f
            // 绑定世界 = 累计平移, 因此 IB = 逆平移
            val ib = Math3D.identity()
            ib[13] = -(0.5f * i)
            joints.add(
                Joint3D(
                    index = i,
                    name = "j$i",
                    parentIndex = if (i == 0) -1 else i - 1,
                    localMatrix = local,
                    inverseBindMatrix = ib,
                    children = if (i < 2) listOf(i + 1) else emptyList(),
                ),
            )
        }
        return Skeleton3D(joints)
    }

    /**
     * 静止姿态下 world * inverseBind 与单位阵的最大偏差。
     * 直接调 [Skeleton3D.computeSkinning] (公开入口), 每根关节的蒙皮矩阵应恰好是单位阵。
     */
    private fun restSkinningIdentity(skeleton: Skeleton3D): Float {
        val skin = FloatArray(skeleton.count * 16)
        skeleton.computeSkinning(emptyMap(), skin, null, null, null)
        var worst = 0f
        val identity = Math3D.identity()
        for (k in 0 until skeleton.count) {
            for (e in 0 until 16) {
                val d = kotlin.math.abs(skin[k * 16 + e] - identity[e])
                if (d > worst) worst = d
            }
        }
        return worst
    }

    @Test
    fun `rest offset keeps the bind pose skinning exactly identity`() {
        val base = chain()
        assertEquals("基线必须先满足自洽", 0f, restSkinningIdentity(base), 1e-5f)

        val moved = base.withRestOffsets(mapOf("j1" to floatArrayOf(0.2f, -0.3f, 0f)))
        assertEquals("移动关节后静止网格必须不变", 0f, restSkinningIdentity(moved), 1e-5f)
    }

    @Test
    fun `rest offset moves the joint and its descendants together`() {
        val base = chain()
        val moved = base.withRestOffsets(mapOf("j1" to floatArrayOf(0.2f, 0f, 0f)))
        // j1 的局部平移应加上 0.2 (父级是单位旋转, 直接透传)
        assertEquals(0.2f, moved.joints[1].localMatrix[12], 1e-4f)
        // j2 自身局部不变 (位移由父级继承)
        assertEquals(0f, moved.joints[2].localMatrix[12], 1e-4f)
        // j2 的逆绑定必须补偿父级的世界位移, 否则静止姿态会歪
        assertEquals(-0.2f, moved.joints[2].inverseBindMatrix[12], 1e-4f)
    }

    @Test
    fun `empty offsets return the same skeleton instance`() {
        val base = chain()
        val same = base.withRestOffsets(emptyMap())
        assertEquals(true, same === base)
    }
}
