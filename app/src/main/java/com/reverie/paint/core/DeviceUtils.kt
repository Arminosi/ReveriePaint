/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min

/**
 * Device capability and form-factor detection utilities.
 */
object DeviceUtils {

    /**
     * Determines whether the given device screen configuration corresponds to a tablet.
     * Pure logic: smallest screen width (sw) >= 600dp is the standard Android tablet definition.
     * When sw is undefined or invalid (<= 0), falls back to screenLayout size >= LARGE (3).
     */
    fun isTabletMetrics(smallestScreenWidthDp: Int, screenLayoutSize: Int = 0): Boolean {
        return if (smallestScreenWidthDp > 0) {
            smallestScreenWidthDp >= 600
        } else {
            // Configuration.SCREENLAYOUT_SIZE_LARGE is 3, SCREENLAYOUT_SIZE_XLARGE is 4
            screenLayoutSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
        }
    }

    /**
     * Determines whether the physical device is a tablet based on physical screen bounds,
     * unaffected by multi-window, split-screen, or freeform window sizing.
     */
    fun isPhysicalTablet(context: Context): Boolean {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                val density = context.resources.displayMetrics.density
                if (density > 0f) {
                    val minDimensionDp = min(bounds.width(), bounds.height()) / density
                    return minDimensionDp >= 600f
                }
            } else if (windowManager != null) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.getRealMetrics(dm)
                if (dm.density > 0f) {
                    val minDimensionDp = min(dm.widthPixels, dm.heightPixels) / dm.density
                    return minDimensionDp >= 600f
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the device is a tablet based on physical dimensions or the given [Context]'s configuration.
     */
    fun isTablet(context: Context): Boolean {
        if (isPhysicalTablet(context)) {
            return true
        }
        val config = context.resources.configuration
        val sw = config.smallestScreenWidthDp
        val layoutSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return isTabletMetrics(sw, layoutSize)
    }
}
