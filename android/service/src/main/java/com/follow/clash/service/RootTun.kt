package com.follow.clash.service

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.follow.clash.common.AccessControlMode
import com.follow.clash.common.GlobalState
import com.follow.clash.common.Su
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.models.getIpv4RouteAddress
import com.follow.clash.service.models.getIpv6RouteAddress
import com.follow.clash.service.models.tunMtu
import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Root mode: a root helper opens the TUN device and passes its descriptor over an
 * abstract socket, and policy rules send every uid but FlClash's own (and the ones
 * access control leaves out) into the TUN table, so the Core's sockets need no protect().
 */
object RootTun {
    const val INTERFACE = "flclash0"
    private const val TABLE = 2022
    private const val PREF_BYPASS = 8990
    private const val PREF_ROUTE = 8995
    private const val PREF_ANCHOR = 8999
    private const val HELPER = "libflroot.so"
    private const val HANDOVER_SECONDS = 20L

    fun open(
        context: Context,
        options: VpnOptions,
        ipv4Address: String,
        ipv6Address: String,
    ): Int {
        cleanup()
        val fd = receiveTun(context)
        val result = Su.run(setupScript(context, options, ipv4Address, ipv6Address))
        if (!result.ok) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            cleanup()
            error("Root TUN setup failed: ${result.output}")
        }
        return fd
    }

    fun cleanup() {
        val prefs = (PREF_BYPASS..PREF_ANCHOR).joinToString(" ")
        Su.run(
            """
            for f in "" -6; do
              for p in $prefs; do while ip ${'$'}f rule del pref ${'$'}p 2>/dev/null; do :; done; done
              ip ${'$'}f route flush table $TABLE 2>/dev/null
            done
            ip link del $INTERFACE 2>/dev/null
            true
            """.trimIndent(),
        )
    }

    private fun receiveTun(context: Context): Int {
        val name = "flclash_root_" + UUID.randomUUID().toString().replace("-", "")
        val helper = context.applicationInfo.nativeLibraryDir + "/" + HELPER
        val executor = Executors.newFixedThreadPool(2)
        try {
            LocalServerSocket(name).use { server ->
                val su = executor.submit<Su.Result> {
                    Su.run("${Su.quote(helper)} ${Su.quote(name)} $INTERFACE", HANDOVER_SECONDS)
                }
                val accept = executor.submit<LocalSocket> { server.accept() }
                val deadline = System.currentTimeMillis() + HANDOVER_SECONDS * 1000
                while (!accept.isDone) {
                    val failed = su.isDone && !su.get().ok
                    if (failed || System.currentTimeMillis() > deadline) {
                        wakeAccept(name)
                        val output = if (su.isDone) su.get().output else "timed out"
                        error("Root helper did not hand over the TUN: $output")
                    }
                    Thread.sleep(50)
                }
                accept.get().use { socket ->
                    socket.inputStream.read()
                    val descriptors: Array<FileDescriptor> = socket.ancillaryFileDescriptors
                        ?: error("Root helper sent no descriptor")
                    val detached = ParcelFileDescriptor.dup(descriptors.first()).detachFd()
                    descriptors.forEach { runCatching { Os.close(it) } }
                    return detached
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // LocalServerSocket.accept cannot time out; a throwaway client unblocks it.
    private fun wakeAccept(name: String) {
        runCatching {
            LocalSocket().use { it.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
        }
    }

    private fun setupScript(
        context: Context,
        options: VpnOptions,
        ipv4Address: String,
        ipv6Address: String,
    ): String {
        val ownUid = Process.myUid()
        val pm = context.packageManager
        fun uidOf(name: String): Int? = runCatching { pm.getApplicationInfo(name, 0).uid }.getOrNull()
        val access = options.accessControlProps
        val acceptUids = if (access.enable && access.mode == AccessControlMode.ACCEPT_SELECTED) {
            access.acceptList.mapNotNull(::uidOf).filter { it != ownUid }.distinct()
        } else {
            null
        }
        val rejectUids = if (access.enable && access.mode == AccessControlMode.REJECT_SELECTED) {
            access.rejectList.mapNotNull(::uidOf).distinct()
        } else {
            emptyList()
        }
        val families = buildList {
            add(Triple("", ipv4Address, options.getIpv4Routes()))
            if (options.ipv6) add(Triple("-6", ipv6Address, options.getIpv6Routes()))
        }
        return buildString {
            appendLine("set -e")
            appendLine("ip link set $INTERFACE mtu ${options.tunMtu} up")
            for ((family, address, routes) in families) {
                appendLine("ip $family addr add $address dev $INTERFACE")
                routes.forEach { appendLine("ip $family route replace $it dev $INTERFACE table $TABLE") }
                appendLine("ip $family rule add pref $PREF_ANCHOR nop")
                (rejectUids + ownUid).distinct().forEach {
                    appendLine("ip $family rule add pref $PREF_BYPASS uidrange $it-$it goto $PREF_ANCHOR")
                }
                if (acceptUids == null) {
                    appendLine("ip $family rule add pref $PREF_ROUTE lookup $TABLE")
                } else {
                    acceptUids.forEach {
                        appendLine("ip $family rule add pref $PREF_ROUTE uidrange $it-$it lookup $TABLE")
                    }
                }
            }
        }.also { GlobalState.log("Root TUN setup:\n$it") }
    }

    private fun VpnOptions.getIpv4Routes(): List<String> =
        if (routeAddress.isEmpty()) listOf("0.0.0.0/0")
        else runCatching { getIpv4RouteAddress().map { "${it.address.hostAddress}/${it.prefixLength}" } }
            .getOrDefault(listOf("0.0.0.0/0"))

    private fun VpnOptions.getIpv6Routes(): List<String> =
        if (routeAddress.isEmpty()) listOf("::/0")
        else runCatching { getIpv6RouteAddress().map { "${it.address.hostAddress}/${it.prefixLength}" } }
            .getOrDefault(listOf("::/0"))
}
