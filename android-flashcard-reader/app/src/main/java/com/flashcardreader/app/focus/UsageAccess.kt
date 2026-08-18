package com.flashcardreader.app.focus

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The two special grants Focus Gate needs, in one place.
 *
 * Neither is a runtime permission: both are Settings screens the user has to visit. Two things make
 * them awkward and are handled here rather than at every call site:
 *  - launching them always returns RESULT_CANCELED, so the result code is meaningless - the caller
 *    must re-check the real state (which is why these are plain queries, not callbacks);
 *  - some OEM Settings apps don't return at all, so callers re-check on resume instead.
 */
object UsageAccess {

    /** Usage access lets the service see which app is in the foreground. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = android.os.Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Drawing over other apps is what lets the gate appear on top of a blocked app. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * The general battery-optimisation list. Deliberately not ACTION_REQUEST_IGNORE_BATTERY_-
     * OPTIMIZATIONS, which is Play-policy restricted; this just shows the user where to look.
     */
    fun batteryOptimisationIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Opens a settings screen, falling back to top-level Settings on stripped ROMs. */
    fun open(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        false
    }.getOrDefault(false)

    /** True when everything the gate needs has been granted. */
    fun fullyGranted(context: Context): Boolean = hasUsageAccess(context) && canDrawOverlays(context)
}
