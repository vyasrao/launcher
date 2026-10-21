package com.vedav.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

// Sony ranks the stock launcher above any normal app, so Home always opens it.
// This service watches for the stock launcher coming to the front and sends you to ours.
class HomeRedirectService : AccessibilityService() {

    private var last = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != "com.google.android.tvlauncher") return

        val now = SystemClock.uptimeMillis()
        if (now - last < 700) return
        last = now

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    override fun onInterrupt() {}
}
