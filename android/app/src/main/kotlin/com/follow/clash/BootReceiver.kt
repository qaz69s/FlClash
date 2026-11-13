package com.follow.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.follow.clash.RootAutomation.watchSsids
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            !RootAutomation.isBootAction(context, intent)
        ) {
            return
        }
        val state = GlobalState.application.sharedState
        if (!state.bootStart) {
            return
        }
        if (state.watchSsids) {
            // The watcher starts the service itself unless the phone is on an excluded network.
            RootWifiWatch.start(context, atBoot = true)
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
