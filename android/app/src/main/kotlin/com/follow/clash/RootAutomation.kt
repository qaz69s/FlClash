package com.follow.clash

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.follow.clash.common.GlobalState
import com.follow.clash.common.Su
import com.follow.clash.models.SharedState
import java.io.File
import java.util.concurrent.Executors

/**
 * Root-only extras: the Wi-Fi watcher that stops and starts the service by SSID, a
 * service.d script that wakes FlClash at boot for systems that hold back BOOT_COMPLETED,
 * and a root watchdog that brings FlClash back when the system kills it (a swipe from
 * recents on ColorOS is a force-stop and takes every service of the app with it).
 */
object RootAutomation {
    private const val PREFS = "root_automation"
    private const val KEY_SCRIPT = "boot_script"
    private const val KEY_WATCHDOG = "watchdog_script"
    private const val KEY_WANT = "want_run"
    private const val KEY_WANT_BOOT = "want_run_boot"
    private const val KEEPALIVE_FILE = "root_keepalive"
    private const val SCRIPT_DIR = "/data/adb/service.d"
    private const val ROOT_DIR = "/data/adb/flclash"
    const val ACTION_ROOT_BOOT = "intent.action.ROOT_BOOT"
    const val ACTION_ROOT_REVIVE = "intent.action.ROOT_REVIVE"
    private val worker = Executors.newSingleThreadExecutor()

    // Once per process is enough: the script itself exits when another copy is alive.
    @Volatile
    private var watchdogLaunched = false

    val SharedState.rootMode: Boolean
        get() = vpnOptions?.let { it.enable && it.root } == true

    val SharedState.watchSsids: Boolean
        get() = rootMode && !excludeSSIDs.isNullOrEmpty()

    fun sync(context: Context, state: SharedState) {
        val app = context.applicationContext
        RootWifiWatch.sync(app, state)
        syncRootFiles(app, state)
    }

    /** Boot script, watchdog and keep-alive flag, without touching the Wi-Fi watcher. */
    fun syncRootFiles(context: Context, state: SharedState) {
        val app = context.applicationContext
        writeKeepalive(app, state)
        worker.execute {
            syncBootScript(app, state)
            syncWatchdog(app, state)
        }
    }

    /** Called on every settled run state, so a kill (which settles nothing) keeps the last wish. */
    fun onRunState(context: Context, running: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WANT, running)
            .putInt(KEY_WANT_BOOT, bootCount(app))
            .apply()
        runCatching { writeKeepalive(app, GlobalState.application.sharedState) }
    }

    /** Whether the proxy was running in this boot when the app last went away. */
    fun wantRun(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WANT, false) &&
            prefs.getInt(KEY_WANT_BOOT, -2) == bootCount(context)
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    // The watchdog reads this file as root: 1 means FlClash should be alive.
    private fun writeKeepalive(context: Context, state: SharedState) {
        val on = state.rootMode && (state.watchSsids || wantRun(context))
        runCatching {
            File(context.filesDir, KEEPALIVE_FILE).writeText(if (on) "1" else "0")
        }
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

    private fun syncWatchdog(context: Context, state: SharedState) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = "$ROOT_DIR/${context.packageName}"
        if (!state.rootMode) {
            if (prefs.getString(KEY_WATCHDOG, null) != null) {
                val result = Su.run("kill ${'$'}(cat $dir/pid 2>/dev/null) 2>/dev/null; rm -rf $dir")
                if (result.ok) {
                    watchdogLaunched = false
                    prefs.edit().remove(KEY_WATCHDOG).apply()
                }
            }
            return
        }
        val content = watchdogScript(context)
        val changed = prefs.getString(KEY_WATCHDOG, null) != content
        if (!changed && watchdogLaunched) {
            return
        }
        val write = if (changed) {
            "kill ${'$'}(cat $dir/pid 2>/dev/null) 2>/dev/null\n" +
                "cat > $dir/watchdog.sh <<'FLCLASH_EOF'\n" + content + "FLCLASH_EOF\n" +
                "chmod 755 $dir/watchdog.sh\n"
        } else {
            ""
        }
        val result = Su.run(
            "mkdir -p $dir\n" + write +
                "setsid sh $dir/watchdog.sh </dev/null >/dev/null 2>&1 &\n" +
                "true",
        )
        if (result.ok) {
            watchdogLaunched = true
            prefs.edit().putString(KEY_WATCHDOG, content).apply()
        } else {
            GlobalState.log("Unable to start the root watchdog: ${result.output}")
        }
    }

    // Root may start a background app and reach its receiver even when ColorOS-style
    // managers block auto-start; the temporary allowlist lets it start a foreground service.
    private fun bootScript(packageName: String): String = """
        #!/system/bin/sh
        P=$packageName
        until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
        sleep 10
        W=$ROOT_DIR/${'$'}P/watchdog.sh
        [ -f "${'$'}W" ] && setsid sh "${'$'}W" </dev/null >/dev/null 2>&1 &
        cmd deviceidle whitelist +${'$'}P >/dev/null 2>&1
        cmd deviceidle tempwhitelist -d 120000 ${'$'}P >/dev/null 2>&1
        am broadcast -f 0x10000020 -n ${'$'}P/${BootReceiver::class.java.name} -a ${'$'}P.$ACTION_ROOT_BOOT >/dev/null 2>&1

    """.trimIndent() + "\n"

    // A root shell outside the app's cgroup, so killing the app leaves it running. Every
    // 10 s it checks the keep-alive flag and, when FlClash should be up but is gone, wakes
    // its receiver. At most 5 wakes per 5 minutes, so a crashing app is not hammered.
    private fun watchdogScript(context: Context): String = """
        #!/system/bin/sh
        P=${context.packageName}
        D=$ROOT_DIR/${'$'}P
        F=${context.filesDir.absolutePath}/$KEEPALIVE_FILE
        for c in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs; do echo ${'$'}${'$'} > ${'$'}c 2>/dev/null; done
        old=${'$'}(cat ${'$'}D/pid 2>/dev/null)
        if [ -n "${'$'}old" ] && [ "${'$'}old" != "${'$'}${'$'}" ] && grep -q watchdog.sh /proc/${'$'}old/cmdline 2>/dev/null; then exit 0; fi
        echo ${'$'}${'$'} > ${'$'}D/pid
        n=0
        t0=${'$'}(date +%s)
        while true; do
          sleep 10
          [ -d "${'$'}{F%/*/*}" ] || exit 0
          [ "${'$'}(cat ${'$'}F 2>/dev/null)" = "1" ] || continue
          pidof ${'$'}P >/dev/null 2>&1 && continue
          now=${'$'}(date +%s)
          if [ ${'$'}((now - t0)) -gt 300 ]; then n=0; t0=${'$'}now; fi
          n=${'$'}((n + 1))
          [ ${'$'}n -gt 5 ] && continue
          cmd deviceidle whitelist +${'$'}P >/dev/null 2>&1
          cmd deviceidle tempwhitelist -d 120000 ${'$'}P >/dev/null 2>&1
          am broadcast -f 0x10000020 -n ${'$'}P/${BootReceiver::class.java.name} -a ${'$'}P.$ACTION_ROOT_REVIVE >/dev/null 2>&1
        done

    """.trimIndent() + "\n"

    fun isBootAction(context: Context, intent: Intent): Boolean =
        intent.action == "${context.packageName}.$ACTION_ROOT_BOOT"

    fun isReviveAction(context: Context, intent: Intent): Boolean =
        intent.action == "${context.packageName}.$ACTION_ROOT_REVIVE"
}
