package com.megah.goldwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the watcher after a phone reboot, unconditionally — the
 * watcher is always on and has no off switch in the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WatchService.start(ctx)
        }
    }
}
