package com.flashcardreader.app.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the gate after a reboot, so it doesn't quietly stop working overnight.
 *
 * Note this cannot fully guarantee itself: an app that has been force-stopped from Settings
 * receives no broadcasts at all until it is launched by hand, and some OEMs skip boot broadcasts
 * for apps not on their autostart list. Focus Gate is a speed bump for the impulsive self, not a
 * lock - the settings screen says so plainly rather than pretending otherwise.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = FocusPrefs(context)
        if (prefs.enabled && UsageAccess.fullyGranted(context)) {
            FocusGateService.start(context)
        }
    }
}
