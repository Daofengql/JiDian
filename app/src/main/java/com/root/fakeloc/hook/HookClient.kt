package com.root.fakeloc.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import de.robv.android.xposed.XposedBridge
import com.root.fakeloc.hook.bridge.HookFrame
import com.root.fakeloc.hook.bridge.HookStateStore

/** IPC is off the app's UI thread. Provider loss or stale data restores the original APIs. */
class HookClient(private val context: Context, private val packageName: String, private val onFrame: (HookFrame) -> Unit) {
    private val thread = HandlerThread("JiDian-location").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var frame: HookFrame? = null
    private var lastDelivered = -1L
    private var lastStatus = ""
    private var observing = false
    private val observer: ContentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { handler.removeCallbacks(refresh); handler.post(refresh) }
    }
    private val refresh: Runnable = object : Runnable {
        override fun run() {
            val response = runCatching { context.contentResolver.call(HookStateStore.URI, "snapshot", packageName, null) }
            val bundle = response.getOrNull()
            if (bundle?.getBoolean("allowed") == true && !observing) {
                observing = runCatching { context.contentResolver.registerContentObserver(HookStateStore.URI, false, observer); true }.getOrDefault(false)
            }
            val status = if (bundle != null) (if (bundle.getBoolean("allowed")) "connected" else "not selected")
                else response.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message?.take(180)}" } ?: "provider unavailable"
            if (status != lastStatus) {
                lastStatus = status
                XposedBridge.log("JiDian: $packageName bridge: $status")
                Log.i("JiDian", "$packageName bridge: $status")
            }
            frame = HookFrame.read(bundle)
            frame?.let { if (it.elapsedNanos != lastDelivered) { lastDelivered = it.elapsedNanos; runCatching { onFrame(it) } } }
            handler.removeCallbacks(this)
            handler.postDelayed(this, if (frame == null) 3000 else 1000)
        }
    }
    fun start() {
        handler.post {
            refresh.run()
        }
    }
    fun current(): HookFrame? = frame?.takeIf { it.fresh() }
}
