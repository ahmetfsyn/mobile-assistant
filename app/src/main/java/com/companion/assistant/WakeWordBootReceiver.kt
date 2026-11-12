package com.companion.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** Re-arms only a previously opted-in listener after boot or an app update. */
class WakeWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = WakeWordSettingsStore(context)
        if (!settings.isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.setEnabled(false)
            settings.setStatus(WakeWordStatus.ERROR)
            Log.w("WakeWordService", "boot_rearm_skipped reason=MICROPHONE_PERMISSION")
            return
        }
        if (settings.status() == WakeWordStatus.DETECTED) return
        try {
            WakeWordServiceControl.start(context)
        } catch (error: RuntimeException) {
            settings.setStatus(WakeWordStatus.ERROR)
            Log.w("WakeWordService", "boot_rearm_failed type=${error.javaClass.simpleName}")
        }
    }
}
