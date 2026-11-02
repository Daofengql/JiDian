package com.root.fakeloc.hook.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.root.fakeloc.service.MockDaemonService
import java.util.concurrent.ConcurrentHashMap

object HookStateStore {
    val URI: Uri = Uri.parse("content://com.root.fakeloc.hook.state/snapshot")
    @Volatile var frame: HookFrame? = null
        private set
    private val clients = ConcurrentHashMap<String, Long>()
    private val lastReport = ConcurrentHashMap<String, Long>()
    fun publish(context: Context, value: HookFrame?) {
        frame = value
        context.contentResolver.notifyChange(URI, null)
    }
    fun report(context: Context, packageName: String) {
        val now = SystemClock.elapsedRealtime()
        clients[packageName] = now
        if (now - (lastReport[packageName] ?: -15_000L) >= 10_000) {
            lastReport[packageName] = now
            context.sendBroadcast(Intent(MockDaemonService.BROADCAST_STATE_CHANGED)
                .setPackage(context.packageName).putExtra("environment_changed", true))
        }
    }
    fun connectedTargets(selected: Set<String>): Set<String> {
        val now = SystemClock.elapsedRealtime()
        return clients.filter { (pkg, seen) -> pkg in selected && now - seen < 15_000 }.keys
    }
}
