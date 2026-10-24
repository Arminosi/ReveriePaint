/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.hypot

enum class SwipeDirection {
    NONE,
    DOWN,
    UP,
    LEFT,
    RIGHT
}

/** Tracks three fixed pointer IDs. The host owns event delivery and consumes until all fingers lift. */
class ThreeFingerSwipe {
    private val startX = FloatArray(3)
    private val startY = FloatArray(3)
    private val dx = FloatArray(3)
    private val dy = FloatArray(3)
    private var startedAt = 0L
    private var density = 1f
    var moved = false
        private set
    var rejected = false
        private set
    var swiped = false
        private set
    var swipeDirection = SwipeDirection.NONE
        private set

    fun begin(time: Long, density: Float) {
        startedAt = time
        this.density = density.coerceAtLeast(0.1f)
        moved = false
        rejected = false
        swiped = false
        swipeDirection = SwipeDirection.NONE
        dx.fill(0f)
        dy.fill(0f)
    }

    fun setStart(pointer: Int, x: Float, y: Float) {
        if (pointer in 0..2) {
            startX[pointer] = x
            startY[pointer] = y
        }
    }

    fun update(pointer: Int, x: Float, y: Float) {
        if (pointer in 0..2) {
            dx[pointer] = x - startX[pointer]
            dy[pointer] = y - startY[pointer]
            if (hypot(dx[pointer], dy[pointer]) > 10f * density) moved = true
        }
    }

    fun evaluate(time: Long) {
        if (rejected || swiped) return
        if (time - startedAt > 650L || time < startedAt) {
            reject()
            return
        }

        val avgDy = (dy[0] + dy[1] + dy[2]) / 3f
        val avgDx = (dx[0] + dx[1] + dx[2]) / 3f
        val spreadY = dy.max() - dy.min()
        val spreadDx = dx.max() - dx.min()

        // 1. Swipe Down
        if (avgDy >= 36f * density && dy.all { it >= 24f * density } &&
            dx.all { abs(it) <= 80f * density && abs(it) <= (dy.max() * 0.85f).coerceAtLeast(20f * density) } &&
            spreadY <= 42f * density && spreadDx <= 36f * density
        ) {
            swiped = true
            swipeDirection = SwipeDirection.DOWN
            return
        }

        // 2. Swipe Up
        if (avgDy <= -36f * density && dy.all { it <= -24f * density } &&
            dx.all { abs(it) <= 80f * density && abs(it) <= (abs(dy.min()) * 0.85f).coerceAtLeast(20f * density) } &&
            spreadY <= 42f * density && spreadDx <= 36f * density
        ) {
            swiped = true
            swipeDirection = SwipeDirection.UP
            return
        }

        // 3. Swipe Left
        if (avgDx <= -36f * density && dx.all { it <= -24f * density } &&
            dy.all { abs(it) <= 80f * density && abs(it) <= (abs(dx.min()) * 0.85f).coerceAtLeast(20f * density) } &&
            spreadDx <= 42f * density && spreadY <= 36f * density
        ) {
            swiped = true
            swipeDirection = SwipeDirection.LEFT
            return
        }

        // 4. Swipe Right
        if (avgDx >= 36f * density && dx.all { it >= 24f * density } &&
            dy.all { abs(it) <= 80f * density && abs(it) <= (dx.max() * 0.85f).coerceAtLeast(20f * density) } &&
            spreadDx <= 42f * density && spreadY <= 36f * density
        ) {
            swiped = true
            swipeDirection = SwipeDirection.RIGHT
            return
        }
    }

    fun reject() {
        rejected = true
        swiped = false
        swipeDirection = SwipeDirection.NONE
    }

    fun isTap(time: Long): Boolean = !rejected && !swiped && !moved && time - startedAt in 0L..420L
}
