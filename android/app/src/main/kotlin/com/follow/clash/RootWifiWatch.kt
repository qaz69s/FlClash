package com.follow.clash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import com.follow.clash.RootAutomation.watchSsids
import com.follow.clash.common.GlobalState
import com.follow.clash.common.Su
import com.follow.clash.models.SharedState
import kotlinx.coroutines.launch

/**
 * Stops the service on an excluded Wi-Fi and starts it again on leaving, reading the
 * SSID through root so it needs no location permission and keeps working with the UI gone.
 * It acts only when home/away changes, so a manual start or stop sticks until the next move.
 */
class RootWifiWatch : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var connectivity: ConnectivityManager
    private val wifiUp = mutableSetOf<Network>()
    private var nullRetries = 0
    private var leaveAt = 0L
    private var bootStart = false
    private val check = Runnable { evaluate() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiUp.add(network)
            nullRetries = 0
            schedule(CONNECT_DELAY)
        }

        override fun onLost(network: Network) {
            wifiUp.remove(network)
            schedule(CONNECT_DELAY)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("root-wifi-watch").apply { start() }
        handler = Handler(thread.looper)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wi-Fi", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The last state survives a reboot but the service does not: forget it once per boot.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt(KEY_BOOT, -2) != boot) {
            prefs.edit().remove(KEY_LAST).putInt(KEY_BOOT, boot).apply()
        }
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback,
            handler,
        )
        handler.post {
            Su.run("cmd deviceidle whitelist +$packageName >/dev/null 2>&1")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) {
            bootStart = true
        }
        schedule(200)
        return START_STICKY
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(check)
        handler.postDelayed(check, delay)
    }

    private fun evaluate() {
        val state = GlobalState.application.sharedState
        if (!state.watchSsids) {
            stopSelf()
            return
        }
        val ssid = currentSsid()
        if (ssid == null && wifiUp.isNotEmpty() && nullRetries < 6) {
            nullRetries++
            schedule(300)
            return
        }
        val home = ssid != null && state.excludeSSIDs.orEmpty().contains(ssid)
        val current = if (home) HOME else AWAY
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST, null)
        if (current == last) {
            leaveAt = 0
            return
        }
        if (!home && last == HOME) {
            // A Wi-Fi blip must not start the service: confirm the leave once.
            val now = android.os.SystemClock.elapsedRealtime()
            if (leaveAt == 0L) {
                leaveAt = now + LEAVE_CONFIRM
                schedule(LEAVE_CONFIRM)
                return
            }
            if (now < leaveAt) {
                schedule(leaveAt - now)
                return
            }
        }
        leaveAt = 0
        prefs.edit().putString(KEY_LAST, current).apply()
        // The first reading only starts the service at boot; turning the watcher on never does.
        val mayStart = last != null || bootStart
        bootStart = false
        GlobalState.launch {
            try {
                when {
                    home && ServiceState.isRunRequested -> ServiceState.handleStopAction()
                    !home && mayStart && !ServiceState.isRunRequested -> ServiceState.handleStartAction()
                }
            } catch (error: Exception) {
                GlobalState.log("Wi-Fi watcher action failed: $error")
            }
        }
        GlobalState.log("Wi-Fi watcher: ${ssid ?: "no Wi-Fi"} -> $current")
    }

    private fun currentSsid(): String? {
        val result = Su.run(
            """
            s=${'$'}(cmd wifi status 2>/dev/null); echo "${'$'}s"
            case "${'$'}s" in *'connected to "'*|*'Wifi is not connected'*|*'Wifi is disabled'*) ;;
            *) echo '@@@'; dumpsys wifi 2>/dev/null | grep -m 4 'mWifiInfo' ;; esac
            """.trimIndent(),
            15,
        )
        val parts = result.output.split("@@@", limit = 2)
        val status = parts[0]
        Regex("connected to \"(.*?)\"").find(status)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        if (status.contains("Wifi is not connected") || status.contains("Wifi is disabled")) {
            return null
        }
        return parts.getOrNull(1)?.lineSequence()
            ?.filter { it.contains("COMPLETED") }
            ?.mapNotNull { Regex("SSID: \"?(.*?)\"?, BSSID").find(it)?.groupValues?.get(1) }
            ?.firstOrNull { it.isNotEmpty() && !it.contains("unknown ssid") }
    }

    private fun notification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.follow.clash.service.R.drawable.ic_service)
            .setContentTitle("FlClash")
            .setContentText("Wi-Fi · Root")
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "root_wifi_watch"
        private const val NOTIFICATION_ID = 0x5717
        private const val PREFS = "root_wifi_watch"
        private const val KEY_LAST = "last"
        private const val KEY_BOOT = "boot"
        private const val HOME = "home"
        private const val AWAY = "away"
        private const val EXTRA_BOOT = "boot"
        private const val CONNECT_DELAY = 400L
        private const val LEAVE_CONFIRM = 2000L

        fun start(context: Context, atBoot: Boolean = false) {
            val intent = Intent(context, RootWifiWatch::class.java).putExtra(EXTRA_BOOT, atBoot)
            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                GlobalState.log("Unable to start the Wi-Fi watcher: $error")
            }
        }

        fun sync(context: Context, state: SharedState) {
            if (state.watchSsids) {
                start(context)
            } else {
                context.stopService(Intent(context, RootWifiWatch::class.java))
            }
        }
    }
}
