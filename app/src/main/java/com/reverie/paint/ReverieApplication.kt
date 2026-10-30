/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import android.app.Application
import com.reverie.paint.core.CrashHandler
import com.reverie.paint.core.telemetry.TelemetryReporter

class ReverieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        com.reverie.paint.core.FontManager.appContext = this
        // 延迟 30s 的后台任务, 只读本地崩溃日志; 端点未注入时自身 no-op
        TelemetryReporter.scheduleStartupScan(this)
    }
}
