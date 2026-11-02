package com.root.fakeloc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.root.fakeloc.core.cell.MockCellEngine
import com.root.fakeloc.core.location.GeoPoint
import com.root.fakeloc.core.location.MockConfig
import com.root.fakeloc.core.location.MockLocationEngine
import com.root.fakeloc.core.location.MockMode
import com.root.fakeloc.core.location.applyFrom
import com.root.fakeloc.core.sensor.MockSensorEngine
import com.root.fakeloc.core.root.RootShell
import com.root.fakeloc.core.runtime.LocationBackend
import com.root.fakeloc.core.runtime.RuntimeEnvironment
import com.root.fakeloc.hook.bridge.HookFrame
import com.root.fakeloc.hook.bridge.HookStateStore
import com.root.fakeloc.ui.MainActivity
import com.root.fakeloc.ui.web.ConfigJson
import com.root.fakeloc.ui.web.FloatingMapWindow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.Executors

/** Runs location updates and config mutations on one worker, preserving route and step progress. */
class MockDaemonService : Service() {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(worker + SupervisorJob())
    private val configUpdates = Channel<MockConfig>(Channel.CONFLATED)
    private data class MotionInput(val x: Double = 0.0, val y: Double = 0.0, val resume: Boolean = false, val releaseOnly: Boolean = false)
    private val motionUpdates = Channel<MotionInput>(Channel.CONFLATED)
    private val wakeUpdates = Channel<Unit>(Channel.CONFLATED)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("mock-ui", MODE_PRIVATE) }
    private var floatingWindow: FloatingMapWindow? = null
    private var overlayEnabled = false
    private var mockJob: Job? = null
    private var locationEngine: MockLocationEngine? = null
    private var sensorEngine: MockSensorEngine? = null
    private lateinit var cellEngine: MockCellEngine
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var starting = false
    private var stopping = false
    private var backend = LocationBackend.HOOK

    companion object {
        const val ACTION_START = "com.root.fakeloc.ACTION_START"
        const val ACTION_STOP = "com.root.fakeloc.ACTION_STOP"
        const val ACTION_UPDATE_CONFIG = "com.root.fakeloc.ACTION_UPDATE_CONFIG"
        const val ACTION_OVERLAY_ENABLE = "com.root.fakeloc.OVERLAY_ENABLE"
        const val ACTION_OVERLAY_DISABLE = "com.root.fakeloc.OVERLAY_DISABLE"
        const val ACTION_VISIBILITY = "com.root.fakeloc.MAIN_VISIBILITY"
        const val ACTION_JOYSTICK = "com.root.fakeloc.JOYSTICK"
        const val ACTION_RESUME_ROUTE = "com.root.fakeloc.RESUME_ROUTE"
        const val ACTION_REFRESH_LOCATION = "com.root.fakeloc.REFRESH_LOCATION"
        const val ACTION_BACKEND_CHANGED = "com.root.fakeloc.BACKEND_CHANGED"
        const val EXTRA_CONFIG = "extra_config"
        const val BROADCAST_STATE_CHANGED = "com.root.fakeloc.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CONFIG_CHANGED = "config_changed"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isServiceRunning = false
            private set
        @Volatile var activeConfig = MockConfig()
            private set
        @Volatile var latestStats: JSONObject? = null
            private set
        @Volatile var isServiceAlive = false
            private set
        @Volatile var mainVisible = false
        @Volatile var isSessionBusy = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        backend = RuntimeEnvironment.backend(this)
        cellEngine = MockCellEngine(this)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JiDian:DaemonLock")
    }

    private fun readConfig(intent: Intent): MockConfig? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getSerializableExtra(EXTRA_CONFIG, MockConfig::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getSerializableExtra(EXTRA_CONFIG) as? MockConfig
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (stopping) { broadcastState("服务正在停止，请稍后再试"); return START_NOT_STICKY }
                if (isServiceRunning || starting) { broadcastState(); return START_NOT_STICKY }
                backend = RuntimeEnvironment.backend(this)
                if (!RuntimeEnvironment.ready(this)) {
                    broadcastState(if (backend == LocationBackend.HOOK) "请在运行环境中启用模块并选择目标应用" else "Root 尚未就绪，请返回应用等待自动授权")
                    if (!overlayEnabled) stopSelf(); return START_NOT_STICKY
                }
                val config = readConfig(intent) ?: activeConfig
                if (config.mode == MockMode.ROUTE_TRACK && config.waypoints.size < 2) {
                    broadcastState("请至少添加 2 个路线点")
                    if (!overlayEnabled) stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    enterForeground()
                    startMocking(config)
                } catch (error: Exception) {
                    broadcastState("模拟服务启动失败：${error.message}")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopping = true
                isServiceRunning = false
                HookStateStore.publish(this, null)
                val job = mockJob
                if (job == null) finishStopping()
                else {
                    job.invokeOnCompletion { mainHandler.post { finishStopping() } }
                    job.cancel()
                }
            }
            ACTION_UPDATE_CONFIG -> {
                readConfig(intent)?.let { next ->
                    preferences.edit { putString("config", ConfigJson.encode(next).toString()) }
                    if (isServiceRunning) { configUpdates.trySend(next); wakeUpdates.trySend(Unit) }
                    else activeConfig = next
                    sendBroadcast(Intent(BROADCAST_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_CONFIG_CHANGED, true))
                    sendOverlayState()
                }
                if (!isServiceRunning && !overlayEnabled) stopSelf()
            }
            ACTION_OVERLAY_ENABLE -> {
                backend = RuntimeEnvironment.backend(this)
                if (!Settings.canDrawOverlays(this) || (backend == LocationBackend.ROOT && !RootShell.environmentReady)) {
                    preferences.edit { putBoolean("overlay-enabled", false) }
                    broadcastState("请返回主界面开启悬浮窗权限")
                    if (!isServiceRunning) stopSelf()
                    return START_NOT_STICKY
                }
                enterForeground()
                overlayEnabled = true
                preferences.edit { putBoolean("overlay-enabled", true) }
                readConfig(intent)?.let { if (!isServiceRunning) activeConfig = it }
                if (floatingWindow == null) floatingWindow = FloatingMapWindow(this, ::handleFloatingMessage)
                sendOverlayState()
                updateOverlayVisibility()
                broadcastState()
            }
            ACTION_OVERLAY_DISABLE -> {
                overlayEnabled = false
                preferences.edit { putBoolean("overlay-enabled", false) }
                floatingWindow?.destroy(); floatingWindow = null
                broadcastState()
                if (!isServiceRunning && !starting) stopSelf()
            }
            ACTION_VISIBILITY -> { mainVisible = intent.getBooleanExtra("visible", false); sendOverlayState(); updateOverlayVisibility() }
            ACTION_JOYSTICK -> {
                if (isServiceRunning) { motionUpdates.trySend(MotionInput(intent.getDoubleExtra("x", 0.0), intent.getDoubleExtra("y", 0.0), releaseOnly = intent.getBooleanExtra("releaseOnly", false))); wakeUpdates.trySend(Unit) }
            }
            ACTION_RESUME_ROUTE -> { motionUpdates.trySend(MotionInput(resume = true)); wakeUpdates.trySend(Unit) }
            ACTION_REFRESH_LOCATION -> wakeUpdates.trySend(Unit)
            ACTION_BACKEND_CHANGED -> {
                if (!isSessionBusy) {
                    backend = RuntimeEnvironment.backend(this)
                    if (overlayEnabled) { enterForeground(); sendOverlayState() } else stopSelf()
                }
            }
            else -> stopSelf()
        }
        // A killed process cannot safely restore a location service without user interaction.
        return START_NOT_STICKY
    }

    private fun finishStopping() {
        stopping = false
        isSessionBusy = false
        mockJob = null
        if (!overlayEnabled) stopSelf() else { sendOverlayState(); broadcastState() }
    }

    private fun updateOverlayVisibility() {
        if (!overlayEnabled) return
        if (mainVisible) floatingWindow?.hide()
        else runCatching { floatingWindow?.show() }.onFailure { broadcastState(it.message ?: "无法显示悬浮小地图") }
    }

    private fun savedConfig(): MockConfig = runCatching { ConfigJson.decode(JSONObject(preferences.getString("config", "{}")!!)) }.getOrDefault(activeConfig)

    private fun sendOverlayState() {
        floatingWindow?.update(RuntimeEnvironment.putState(this, JSONObject().apply {
            put("type", "state"); put("running", isServiceRunning)
            put("root", if (RootShell.environmentReady) "granted" else "error")
            put("rootReady", RootShell.environmentReady); put("rootDetail", "Root 可用，定位环境已自动配置。")
            put("overlayEnabled", overlayEnabled)
            put("config", ConfigJson.encode(savedConfig()))
            latestStats?.let { put("stats", it) }
        }))
    }

    private fun handleFloatingMessage(action: String, payload: JSONObject) {
        val command = Intent(this, MockDaemonService::class.java)
        when (action) {
            "ready", "refresh" -> { sendOverlayState(); return }
            "config" -> command.setAction(ACTION_UPDATE_CONFIG).putExtra(EXTRA_CONFIG, ConfigJson.decode(payload))
            "start" -> {
                val next = ConfigJson.decode(payload)
                preferences.edit { putString("config", ConfigJson.encode(next).toString()) }
                command.setAction(ACTION_START).putExtra(EXTRA_CONFIG, next)
            }
            "stop" -> command.action = ACTION_STOP
            "joystick" -> command.setAction(ACTION_JOYSTICK).putExtra("x", payload.optDouble("x", 0.0)).putExtra("y", payload.optDouble("y", 0.0))
            "resumeRoute" -> command.action = ACTION_RESUME_ROUTE
            "refreshLocation" -> command.action = ACTION_REFRESH_LOCATION
            "releaseJoystick" -> { motionUpdates.trySend(MotionInput(releaseOnly = true)); wakeUpdates.trySend(Unit); return }
            "overlayDisable" -> command.action = ACTION_OVERLAY_DISABLE
            "openMain" -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); return }
            "copy" -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GPS 坐标", payload.optString("text").take(100))); return }
            else -> return
        }
        startService(command)
    }

    private fun startMocking(initial: MockConfig) {
        starting = true
        isSessionBusy = true
        latestStats = null
        while (motionUpdates.tryReceive().isSuccess) { /* A new session never inherits joystick input. */ }
        while (configUpdates.tryReceive().isSuccess) { /* Initial config is already the latest saved snapshot. */ }
        activeConfig = initial.copy(waypoints = initial.waypoints.toMutableList())
        mockJob = serviceScope.launch {
            val config = initial.copy(waypoints = initial.waypoints.toMutableList())
            try {
                locationEngine = MockLocationEngine(applicationContext, config, backend == LocationBackend.ROOT).also { it.start() }
                sensorEngine = MockSensorEngine(applicationContext, config).also { it.start() }
                cellEngine.start()
                ensureActive()
                wakeLock?.acquire(10 * 60 * 1000L)
                var renewedAt = SystemClock.elapsedRealtime()
                var lastTime = renewedAt
                isServiceRunning = true
                starting = false
                broadcastState()
                while (isActive && isServiceRunning) {
                    configUpdates.tryReceive().getOrNull()?.let { next ->
                        val geometryChanged = config.mode != next.mode || config.waypoints != next.waypoints
                        config.applyFrom(next)
                        if (geometryChanged) locationEngine?.resetRoute()
                        activeConfig = config.copy(waypoints = config.waypoints.toMutableList())
                    }
                    motionUpdates.tryReceive().getOrNull()?.let { motion ->
                        if (motion.resume) locationEngine?.resumeRoute()
                        else if (!motion.releaseOnly || locationEngine?.isManualControl == true) locationEngine?.joystick(motion.x, motion.y)
                    }
                    val now = SystemClock.elapsedRealtime()
                    val delta = (now - lastTime).coerceIn(0L, 5000L)
                    lastTime = now
                    if (now - renewedAt >= 5 * 60 * 1000L) {
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        wakeLock?.acquire(10 * 60 * 1000L)
                        renewedAt = now
                    }
                    val location = locationEngine!!.pushNextTick(delta)
                    if (backend == LocationBackend.HOOK) HookStateStore.publish(applicationContext, HookFrame.from(location, config.updateIntervalMs))
                    val motionEnabled = config.mode == MockMode.ROUTE_TRACK && config.enableSensorSync
                    val cellEnabled = config.mode == MockMode.SINGLE_POINT && config.enableCellSync
                    val sensor = if (motionEnabled) sensorEngine!!.tick(location.speed, delta) else null
                    if (cellEnabled) cellEngine.updateWithLocation(GeoPoint(location.latitude, location.longitude))
                    latestStats = JSONObject().apply {
                        put("type", "stats")
                        put("lat", location.latitude)
                        put("lng", location.longitude)
                        put("speed", (location.speed * 3.6f).toDouble())
                        put("mode", config.mode.name)
                        put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                        put("accuracy", location.accuracy.toDouble())
                        put("locationJitterEnabled", config.mode == MockMode.SINGLE_POINT && config.enableLocationJitter)
                        put("locationJitterMeters", config.locationJitterMeters)
                        put("cellEnabled", cellEnabled)
                        put("motionEnabled", motionEnabled)
                        put("manualControl", locationEngine!!.isManualControl)
                        put("steps", sensor?.totalSteps ?: 0L)
                        put("spm", sensor?.spm?.toDouble() ?: 0.0)
                        put("pace", sensor?.pace ?: "—")
                        put("stride", sensor?.strideLength?.toDouble() ?: 0.0)
                    }
                    sendBroadcast(Intent(BROADCAST_STATE_CHANGED).setPackage(packageName))
                    val snapshot = latestStats
                    mainHandler.post { snapshot?.let { floatingWindow?.update(it) } }
                    withTimeoutOrNull(if (locationEngine!!.isManuallyMoving) 100L else config.updateIntervalMs) { wakeUpdates.receive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MockDaemonService", "Location service failed", error)
                if (backend == LocationBackend.ROOT) RootShell.invalidateEnvironment()
                broadcastState("模拟已停止：${error.message ?: "定位更新失败"}")
                withContext(Dispatchers.Main) { stopSelf() }
            } finally {
                locationEngine?.stop()
                sensorEngine?.stop()
                cellEngine.stop()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                isServiceRunning = false
                HookStateStore.publish(applicationContext, null)
                starting = false
                isSessionBusy = false
                broadcastState()
            }
        }
    }

    private fun broadcastState(error: String? = null) {
        sendBroadcast(Intent(BROADCAST_STATE_CHANGED).setPackage(packageName).apply {
            putExtra(EXTRA_RUNNING, isServiceRunning)
            error?.let { putExtra(EXTRA_ERROR, it) }
        })
        mainHandler.post {
            sendOverlayState()
            error?.let { floatingWindow?.update(JSONObject().put("type", "error").put("message", it)) }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "mock_service_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "模拟定位", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MockDaemonService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("JiDian · 定位与悬浮小地图")
            .setContentText(if (backend == LocationBackend.HOOK) "Hook 模式 · 仅在所选应用中生效" else "Root 模式 · 系统级定位")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止模拟", stop)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        val type = when {
            backend == LocationBackend.ROOT && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    override fun onDestroy() {
        isServiceRunning = false
        HookStateStore.publish(this, null)
        isServiceAlive = false
        isSessionBusy = false
        floatingWindow?.destroy(); floatingWindow = null
        broadcastState()
        mockJob?.cancel()
        serviceScope.cancel()
        configUpdates.close()
        motionUpdates.close(); wakeUpdates.close()
        // Closing a coroutine dispatcher lets its queued finally block release providers and locks.
        worker.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
