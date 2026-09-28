package com.spop.poverlay.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Keeps the overlay minimised while any of this app's own screens is on display, and restores
 * it when the last one goes away. A counter is needed because when one screen opens another,
 * Android stops the old screen only after the new one has started, so a plain
 * "minimise on start / restore on stop" pair per screen lets the late restore win.
 */
object OverlayVisibility {
    private var startedScreens = 0

    @Synchronized
    fun onScreenStarted(context: Context) {
        startedScreens++
        send(context, OverlayService.ActionMinimizeOverlay)
    }

    @Synchronized
    fun onScreenStopped(context: Context) {
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
        if (startedScreens == 0) send(context, OverlayService.ActionRestoreOverlay)
    }

    private fun send(context: Context, action: String) {
        if (!OverlayService.isRunning.value) return
        val app = context.applicationContext
        ContextCompat.startForegroundService(
            app, Intent(app, OverlayService::class.java).setAction(action)
        )
    }
}
