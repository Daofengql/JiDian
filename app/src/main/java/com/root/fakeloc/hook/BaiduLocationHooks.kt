package com.root.fakeloc.hook

import android.os.Handler
import android.os.Looper
import com.root.fakeloc.hook.bridge.HookFrame
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Wrap the SDK delivery boundary, never BDLocation getters used by saved map attachments. */
class BaiduLocationHooks(private val client: HookClient) {
    private data class Session(var running: Boolean = false, var coordinateType: String = "gcj02",
        val listeners: MutableList<WeakReference<Any>> = mutableListOf())
    private val sessions = WeakHashMap<Any, Session>()
    private val callbacks = HashSet<Method>()
    private val delivered = WeakHashMap<Any, Long>()
    private val synthetic = ThreadLocal<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var locationType: Class<*>? = null
    @Volatile private var installed = false

    @Synchronized fun install(loader: ClassLoader) {
        if (installed) return
        val sdk = XposedHelpers.findClassIfExists("com.baidu.location.LocationClient", loader) ?: return
        val location = XposedHelpers.findClassIfExists("com.baidu.location.BDLocation", loader) ?: return
        locationType = location
        for (name in listOf("registerLocationListener", "unRegisterLocationListener", "unregisterLocationListener", "setLocOption", "start", "stop", "restart")) {
            XposedBridge.hookAllMethods(sdk, name, object : XC_MethodHook() {
                override fun afterHookedMethod(call: MethodHookParam) {
                    if (call.hasThrowable()) return
                    val owner = call.thisObject ?: return
                    synchronized(sessions) {
                        val session = sessions.getOrPut(owner) { Session() }
                        when (name) {
                            "registerLocationListener" -> call.args.firstOrNull()?.let { listener ->
                                if (session.listeners.none { it.get() === listener }) session.listeners.add(WeakReference(listener))
                                installListener(listener, location)
                            }
                            "unRegisterLocationListener", "unregisterLocationListener" -> session.listeners.removeAll { it.get() == null || it.get() === call.args.firstOrNull() }
                            "setLocOption" -> session.coordinateType = call.args.firstOrNull()?.let { option ->
                                runCatching { XposedHelpers.callMethod(option, "getCoorType") as String }.getOrNull()
                                    ?: runCatching { XposedHelpers.getObjectField(option, "coorType") as String }.getOrNull()
                            } ?: session.coordinateType
                            "start", "restart" -> session.running = true
                            "stop" -> session.running = false
                        }
                    }
                    client.current()?.let(::deliver)
                }
            })
        }
        XposedBridge.hookAllMethods(sdk, "getLastKnownLocation", object : XC_MethodHook() {
            override fun beforeHookedMethod(call: MethodHookParam) {
                val frame = client.current() ?: return
                val coordinateType = synchronized(sessions) { sessions[call.thisObject]?.coordinateType ?: "gcj02" }
                createLocation(frame, coordinateType)?.let { call.result = it }
            }
        })
        installed = true
        XposedBridge.log("JiDian: Baidu location callback support installed")
    }

    private fun installListener(listener: Any, type: Class<*>) {
        var cls: Class<*>? = listener.javaClass
        while (cls != null && cls != Any::class.java) {
            for (method in cls.declaredMethods.filter { it.name == "onReceiveLocation" && !Modifier.isAbstract(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(type)) }) {
                if (!callbacks.add(method)) continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(call: MethodHookParam) {
                        val frame = client.current() ?: return
                        val coordinateType = synchronized(sessions) {
                            sessions.values.firstOrNull { s -> s.running && s.listeners.any { it.get() === call.thisObject } }?.coordinateType
                        } ?: return
                        val replacement = if (synthetic.get() == true) call.args[0] else createLocation(frame, coordinateType) ?: return
                        synchronized(delivered) {
                            if (delivered[call.thisObject] == frame.elapsedNanos) { call.result = null; return }
                            delivered[call.thisObject] = frame.elapsedNanos
                        }
                        call.args[0] = replacement
                    }
                })
            }
            cls = cls.superclass
        }
    }

    fun deliver(frame: HookFrame) {
        val active = synchronized(sessions) {
            sessions.values.filter { it.running }.flatMap { session -> session.listeners.mapNotNull { it.get()?.let { obj -> obj to session.coordinateType } } }
        }
        active.forEach { (listener, coordinateType) ->
            val value = createLocation(frame, coordinateType) ?: return@forEach
            main.post {
                if (client.current()?.elapsedNanos != frame.elapsedNanos) return@post
                val stillRegistered = synchronized(sessions) { sessions.values.any { it.running && it.listeners.any { ref -> ref.get() === listener } } }
                if (stillRegistered) {
                    synthetic.set(true)
                    try { runCatching { XposedHelpers.callMethod(listener, "onReceiveLocation", value) } }
                    finally { synthetic.remove() }
                }
            }
        }
    }

    private fun createLocation(frame: HookFrame, coordinateType: String): Any? = runCatching {
        val point = SdkCoordinates.convert(frame.latitude, frame.longitude, coordinateType) ?: return null
        val value = locationType!!.getDeclaredConstructor().newInstance()
        XposedHelpers.callMethod(value, "setLatitude", point.lat)
        XposedHelpers.callMethod(value, "setLongitude", point.lng)
        XposedHelpers.callMethod(value, "setCoorType", coordinateType)
        XposedHelpers.callMethod(value, "setLocType", 61)
        XposedHelpers.callMethod(value, "setRadius", frame.accuracy)
        XposedHelpers.callMethod(value, "setSpeed", frame.speed * 3.6f) // Baidu SDK uses km/h.
        XposedHelpers.callMethod(value, "setDirection", frame.bearing)
        runCatching { XposedHelpers.callMethod(value, "setTime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(frame.time))) }
        frame.altitude?.let { XposedHelpers.callMethod(value, "setAltitude", it) }
        value
    }.getOrNull()
}
