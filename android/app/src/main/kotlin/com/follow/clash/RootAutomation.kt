package com.follow.clash

import android.content.Context
import android.content.Intent
import com.follow.clash.common.GlobalState
import com.follow.clash.common.Su
import com.follow.clash.models.SharedState
import java.util.concurrent.Executors

/**
 * Root-only extras: the Wi-Fi watcher that stops and starts the service by SSID, and a
 * service.d script that wakes FlClash at boot for systems that hold back BOOT_COMPLETED.
 */
object RootAutomation {
    private const val PREFS = "root_automation"
    private const val KEY_SCRIPT = "boot_script"
    private const val SCRIPT_DIR = "/data/adb/service.d"
    const val ACTION_ROOT_BOOT = "intent.action.ROOT_BOOT"
    private val worker = Executors.newSingleThreadExecutor()

    val SharedState.rootMode: Boolean
        get() = vpnOptions?.let { it.enable && it.root } == true

    val SharedState.watchSsids: Boolean
        get() = rootMode && !excludeSSIDs.isNullOrEmpty()

    fun sync(context: Context, state: SharedState) {
        val app = context.applicationContext
        RootWifiWatch.sync(app, state)
        worker.execute { syncBootScript(app, state) }
    }

    private fun syncBootScript(context: Context, state: SharedState) {
        val wanted = state.rootMode && state.bootStart
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val script = "$SCRIPT_DIR/${context.packageName}.sh"
        val content = if (wanted) bootScript(context.packageName) else ""
        if (prefs.getString(KEY_SCRIPT, null) == content) {
            return
        }
        val result = if (wanted) {
            Su.run(
                "mkdir -p $SCRIPT_DIR && cat > ${Su.quote(script)} <<'FLCLASH_EOF'\n" +
                    content + "FLCLASH_EOF\nchmod 755 ${Su.quote(script)}",
            )
        } else {
            Su.run("rm -f ${Su.quote(script)}")
        }
        if (result.ok) {
            prefs.edit().putString(KEY_SCRIPT, content).apply()
        } else {
            GlobalState.log("Unable to update the boot script: ${result.output}")
        }
    }

    // Root may start a background app and reach its receiver even when ColorOS-style
    // managers block auto-start; the temporary allowlist lets it start a foreground service.
    private fun bootScript(packageName: String): String = """
        #!/system/bin/sh
        P=$packageName
        until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
        sleep 10
        cmd deviceidle whitelist +${'$'}P >/dev/null 2>&1
        cmd deviceidle tempwhitelist -d 120000 ${'$'}P >/dev/null 2>&1
        am broadcast -f 0x10000020 -n ${'$'}P/${BootReceiver::class.java.name} -a ${'$'}P.$ACTION_ROOT_BOOT >/dev/null 2>&1

    """.trimIndent() + "\n"

    fun isBootAction(context: Context, intent: Intent): Boolean =
        intent.action == "${context.packageName}.$ACTION_ROOT_BOOT"
}
