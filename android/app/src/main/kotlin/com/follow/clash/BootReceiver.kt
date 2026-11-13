package com.follow.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.follow.clash.RootAutomation.rootMode
import com.follow.clash.RootAutomation.watchSsids
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val boot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            RootAutomation.isBootAction(context, intent)
        // Sent by the root watchdog after the system killed FlClash.
        val revive = RootAutomation.isReviveAction(context, intent)
        if (!boot && !revive) {
            return
        }
        val state = GlobalState.application.sharedState
        if (boot && !state.bootStart) {
            return
        }
        if (revive && !state.rootMode) {
            return
        }
        val wantRun = if (revive) RootAutomation.wantRun(context) else true
        if (revive) {
            GlobalState.log("Revived by the root watchdog, restore proxy: $wantRun")
        }
        RootAutomation.syncRootFiles(context, state)
        if (state.watchSsids) {
            // The watcher starts the service itself unless the phone is on an excluded network.
            RootWifiWatch.start(context, atBoot = boot, revive = revive && wantRun)
            return
        }
        if (!wantRun) {
            return
        }
        val pendingResult = goAsync()
        GlobalState.launch {
            try {
                ServiceState.handleStartAction()
            } catch (error: Exception) {
                GlobalState.log("Boot start failed: $error")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
