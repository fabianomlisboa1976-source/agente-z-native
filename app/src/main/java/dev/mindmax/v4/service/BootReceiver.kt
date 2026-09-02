package dev.mindmax.v4.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Listens for `BOOT_COMPLETED`. If the user previously enabled
 * `serviceEnabled && autoStart` in Settings, the FGS is restarted. Otherwise
 * the boot is a no-op so users who disabled the service aren't bothered.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.MY_PACKAGE_REPLACED") {
            return
        }
        ServiceStarter.ensureStartedIfEnabled(context)
    }
}
