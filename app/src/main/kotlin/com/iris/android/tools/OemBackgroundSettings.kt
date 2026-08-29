package com.iris.android.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * There is no standard Android API for "let this app run in the background" beyond battery
 * optimization exemption — several manufacturers (Xiaomi/MIUI, Oppo/ColorOS, Vivo/FuntouchOS,
 * Huawei/EMUI, Honor, Letv, Asus) ship their OWN separate autostart/background-app managers that
 * silently kill background services regardless of standard permissions. This tries known launcher
 * activities for the current build's manufacturer, best-effort — there's no guarantee any given
 * device/OS-version still uses the same activity name, since these are undocumented and change
 * across manufacturer software updates.
 */
object OemBackgroundSettings {

    private val candidates = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
    )

    /** Tries each known component in turn; returns true if one actually launched. */
    fun tryOpen(context: Context): Boolean {
        for (component in candidates) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Not this manufacturer / activity no longer exists on this build — try the next one
            }
        }
        return false
    }

    fun manufacturerHint(): String = Build.MANUFACTURER
}
