package com.root.fakeloc.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import com.root.fakeloc.hook.bridge.HookFrame
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Hooks live only in processes chosen in the framework. No system_server or global getter hooks. */
class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == MODULE_PACKAGE) {
            XposedHelpers.findAndHookMethod("com.root.fakeloc.hook.HookStatus", param.classLoader,
                "isActive", XC_MethodReplacement.returnConstant(true))
            return
        }
        if (param.packageName == "android" || param.packageName == "com.android.systemui") return
        val installed = AtomicBoolean()
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(call: MethodHookParam) {
                if (!installed.compareAndSet(false, true)) return
                runCatching {
                    LocationHooks(call.args[0] as Context, param.packageName, param.classLoader).install()
                }.onFailure { XposedBridge.log("JiDian: ${param.packageName}: ${it.javaClass.simpleName}: ${it.message}") }
            }
        })
    }

    private class LocationHooks(private val context: Context, packageName: String, private val loader: ClassLoader) {
        private val records = IdentityHashMap<LocationListener, ListenerRecord>()
        private val sdkClasses = HashSet<Class<*>>()
        private val baidu: BaiduLocationHooks by lazy { BaiduLocationHooks(client) }
        private val client: HookClient = HookClient(context, packageName) { frame ->
            val snapshot = synchronized(records) { records.values.toList() }
            snapshot.forEach { runCatching { it.deliver(frame) } }
            baidu.deliver(frame)
        }
        private fun hook(type: Class<*>, name: String, callback: XC_MethodHook) {
            runCatching { XposedBridge.hookAllMethods(type, name, callback) }
                .onFailure { XposedBridge.log("JiDian: cannot hook ${type.name}.$name: ${it.message}") }
        }
        private fun provider(args: Array<Any?>) = args.firstOrNull { it is String } as? String ?: LocationManager.GPS_PROVIDER
        private fun dispatch(args: Array<Any?>): Executor {
            (args.firstOrNull { it is Executor } as? Executor)?.let { return it }
            val looper = args.firstOrNull { it is Looper } as? Looper ?: Looper.myLooper() ?: Looper.getMainLooper()
            val handler = Handler(looper)
            return Executor { handler.post(it) }
        }

        fun install() {
            val type = LocationManager::class.java
            hook(type, "getLastKnownLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    client.current()?.let { call.result = it.location(provider(call.args)) }
                }
            })
            for (name in listOf("isProviderEnabled", "isLocationEnabled", "hasProvider")) hook(type, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) { if (client.current() != null) call.result = true }
            })
            hook(type, "getCurrentLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    val frame = client.current() ?: return
                    @Suppress("UNCHECKED_CAST") val consumer = call.args.firstOrNull { it is Consumer<*> } as? Consumer<Location> ?: return
                    val cancellation = call.args.firstOrNull { it is CancellationSignal } as? CancellationSignal
                    val provider = provider(call.args)
                    dispatch(call.args).execute { if (cancellation?.isCanceled != true && client.current() != null) consumer.accept(frame.location(provider)) }
                    call.result = null
                }
            })
            hook(type, "requestSingleUpdate", object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    val frame = client.current() ?: return
                    val listener = call.args.firstOrNull { it is LocationListener } as? LocationListener ?: return
                    val provider = provider(call.args)
                    dispatch(call.args).execute { if (client.current() != null) listener.onLocationChanged(frame.location(provider)) }
                    call.result = null
                }
            })
            hook(type, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    val index = call.args.indexOfFirst { it is LocationListener }
                    if (index < 0) return // PendingIntent delivery is intentionally untouched.
                    val listener = call.args[index] as LocationListener
                    if (synchronized(records) { records.values.any { it.forwarder === listener } }) return
                    val record = synchronized(records) {
                        records.getOrPut(listener) { ListenerRecord(listener, dispatch(call.args), provider(call.args),
                            (call.args.firstOrNull { it is Long } as? Long ?: 0L).coerceAtLeast(0)) }
                    }
                    call.args[index] = record.forwarder
                    call.setObjectExtra("jid.listener", record)
                }
                override fun afterHookedMethod(call: MethodHookParam) {
                    val record = call.getObjectExtra("jid.listener") as? ListenerRecord ?: return
                    if (call.hasThrowable()) {
                        synchronized(records) { records.remove(record.original) }; record.closed = true
                    } else { record.registered = true; client.current()?.let(record::deliver) }
                }
            })
            hook(type, "removeUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(call: MethodHookParam) {
                    val index = call.args.indexOfFirst { it is LocationListener }
                    if (index < 0) return
                    val listener = call.args[index] as LocationListener
                    val record = synchronized(records) { records.remove(listener) } ?: return
                    record.closed = true; call.args[index] = record.forwarder
                }
            })
            installSdk(context.classLoader)
            // Packed apps can expose their SDK only after attachBaseContext / Application.onCreate.
            XposedHelpers.findAndHookMethod(Instrumentation::class.java, "callApplicationOnCreate", Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(call: MethodHookParam) {
                    val app = call.args[0] as? Application ?: return
                    installSdk(app.classLoader)
                }
            })
            client.start()
        }

        private inner class ListenerRecord(val original: LocationListener, private val executor: Executor,
            private val provider: String, private val minimumMs: Long) {
            @Volatile var closed = false
            @Volatile var registered = false
            private var lastFrame = -1L
            fun deliver(frame: HookFrame) {
                if (!registered || closed) return
                synchronized(this) {
                    if (frame.elapsedNanos == lastFrame || (lastFrame >= 0 && frame.elapsedNanos - lastFrame < minimumMs * 1_000_000L)) return
                    lastFrame = frame.elapsedNanos
                }
                runCatching { executor.execute {
                    // A stop/deselection between queuing and delivery cannot leak another fake update.
                    if (!closed && client.current() != null) runCatching { original.onLocationChanged(frame.location(provider)) }
                } }
            }
            val forwarder = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (closed) return
                    val frame = client.current()
                    if (frame == null) original.onLocationChanged(location) else deliver(frame)
                }
                override fun onLocationChanged(locations: MutableList<Location>) {
                    if (closed) return
                    val frame = client.current()
                    if (frame != null) deliver(frame)
                    else if (Build.VERSION.SDK_INT >= 31) original.onLocationChanged(locations)
                    else locations.forEach(original::onLocationChanged)
                }
                override fun onProviderEnabled(provider: String) { if (!closed) original.onProviderEnabled(provider) }
                override fun onProviderDisabled(provider: String) { if (!closed && client.current() == null) original.onProviderDisabled(provider) }
                @Suppress("DEPRECATION") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { if (!closed) original.onStatusChanged(provider, status, extras) }
                override fun onFlushComplete(requestCode: Int) { if (!closed && Build.VERSION.SDK_INT >= 31) original.onFlushComplete(requestCode) }
            }
        }

        private fun installSdk(sdkLoader: ClassLoader) {
            runCatching { baidu.install(sdkLoader) }.onFailure { XposedBridge.log("JiDian: Baidu SDK: ${it.javaClass.simpleName}: ${it.message}") }
            installGoogleResults(sdkLoader)
            if (sdkLoader !== loader) { runCatching { baidu.install(loader) }; installGoogleResults(loader) }
        }

        private fun installGoogleResults(sdkLoader: ClassLoader) {
            XposedHelpers.findClassIfExists("com.google.android.gms.location.LocationResult", sdkLoader)?.takeIf { sdkClasses.add(it) }?.let { type ->
                for (name in listOf("getLastLocation", "getLocations")) hook(type, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(call: MethodHookParam) {
                        client.current()?.let { frame -> call.result = if (name == "getLocations") listOf(frame.location("fused")) else frame.location("fused") }
                    }
                })
            }
            XposedHelpers.findClassIfExists("com.google.android.gms.location.LocationAvailability", sdkLoader)?.takeIf { sdkClasses.add(it) }?.let { type ->
                hook(type, "isLocationAvailable", object : XC_MethodHook() {
                    override fun beforeHookedMethod(call: MethodHookParam) { if (client.current() != null) call.result = true }
                })
            }
        }
    }

    companion object { private const val MODULE_PACKAGE = "com.root.fakeloc" }
}
