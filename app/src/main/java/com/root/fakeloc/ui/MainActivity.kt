package com.root.fakeloc.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.root.fakeloc.BuildConfig
import com.root.fakeloc.R
import com.root.fakeloc.core.location.MockConfig
import com.root.fakeloc.core.location.MockMode
import com.root.fakeloc.core.root.RootShell
import com.root.fakeloc.core.runtime.LocationBackend
import com.root.fakeloc.core.runtime.RuntimeEnvironment
import com.root.fakeloc.hook.bridge.HookAccessPolicy
import com.root.fakeloc.hook.bridge.HookStateStore
import com.root.fakeloc.service.MockDaemonService
import com.root.fakeloc.ui.web.ConfigJson
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rootExecutor = Executors.newSingleThreadExecutor()
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("mock-ui", MODE_PRIVATE) }
    private var config = MockConfig()
    private var pageReady = false
    private var receiverRegistered = false
    private var resumed = false
    private var rootStatus = "unknown"
    private var rootDetail = "正在自动检测并请求 SU 授权…"
    private var rootCheckRunning = false
    private var lastRootCheckAt = 0L
    private var pendingImport: String? = null
    private var pendingGeocode: JSONObject? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockDaemonService.BROADCAST_STATE_CHANGED) return
            intent.getStringExtra(MockDaemonService.EXTRA_ERROR)?.let(::sendError)
            if (intent.getBooleanExtra("environment_changed", false)) {
                sendState()
            } else if (intent.getBooleanExtra(MockDaemonService.EXTRA_CONFIG_CHANGED, false)) {
                sendState()
            } else if (intent.hasExtra(MockDaemonService.EXTRA_RUNNING)) {
                sendState()
            } else MockDaemonService.latestStats?.let(::sendToWeb)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        config = if (MockDaemonService.isServiceRunning) MockDaemonService.activeConfig.copy(
            waypoints = MockDaemonService.activeConfig.waypoints.toMutableList()
        ) else runCatching { ConfigJson.decode(JSONObject(preferences.getString("config", "{}")!!)) }.getOrDefault(MockConfig())
        webView = findViewById(R.id.webViewApp)
        applySystemTheme(preferences.getBoolean("dark", false))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appRoot)) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setupWebView()
        if (savedInstanceState != null) pendingImport = savedInstanceState.getString("pending-import")
        else acceptImport(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.MockApp ? window.MockApp.back() : false") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.setBackgroundColor(Color.rgb(247, 249, 248))
        webView.addJavascriptInterface(WebBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (request.url.toString() == APP_URL) return false
                // External pages never run inside the WebView holding the native bridge.
                if (request.isForMainFrame && request.url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
        }
        webView.loadUrl(APP_URL)
    }

    private inner class WebBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (raw.length > 100_000) return
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                try {
                    val message = JSONObject(raw)
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    when (message.getString("action")) {
                        "ready" -> { pageReady = true; sendState(); deliverImport() }
                        "refresh" -> sendState()
                        "backend" -> changeBackend(payload.optString("backend"))
                        "listHookApps" -> listHookApps()
                        "hookTargets" -> saveHookTargets(payload.optJSONArray("packages") ?: JSONArray())
                        "openFramework" -> openFramework()
                        "openHookTarget" -> openHookTarget(payload.optString("packageName"))
                        "geocode" -> geocode(payload.optString("address"), payload.optLong("requestId"))
                        "config" -> {
                            config = ConfigJson.decode(payload)
                            preferences.edit { putString("config", ConfigJson.encode(config).toString()) }
                            if (MockDaemonService.isServiceRunning) serviceIntent(MockDaemonService.ACTION_UPDATE_CONFIG, config)
                        }
                        "start" -> startMock(ConfigJson.decode(payload))
                        "stop" -> serviceIntent(MockDaemonService.ACTION_STOP)
                        "overlayToggle" -> toggleSystemOverlay(payload.optBoolean("enabled"))
                        "joystick" -> if (resumed && MockDaemonService.isServiceRunning) startService(Intent(this@MainActivity, MockDaemonService::class.java).setAction(MockDaemonService.ACTION_JOYSTICK)
                            .putExtra("x", payload.optDouble("x", 0.0)).putExtra("y", payload.optDouble("y", 0.0)))
                        "resumeRoute" -> if (MockDaemonService.isServiceRunning) serviceIntent(MockDaemonService.ACTION_RESUME_ROUTE)
                        "refreshLocation" -> if (MockDaemonService.isServiceRunning) serviceIntent(MockDaemonService.ACTION_REFRESH_LOCATION)
                        "copy" -> {
                            val text = payload.optString("text").take(100)
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GPS 坐标", text))
                            if (Build.VERSION.SDK_INT < 33) sendNotice("坐标已复制")
                        }
                        "theme" -> applySystemTheme(payload.optBoolean("dark"))
                    }
                } catch (error: Exception) {
                    sendError(error.message ?: "操作未完成，请重试")
                }
            }
        }
    }

    private fun startMock(next: MockConfig) {
        if (!resumed) { sendError("请返回应用后开始模拟"); return }
        val backend = RuntimeEnvironment.backend(this)
        if (backend == LocationBackend.HOOK && !RuntimeEnvironment.ready(this)) {
            sendError("请在运行环境中启用 Xposed 模块并选择目标应用")
            sendState(); return
        }
        if (backend == LocationBackend.ROOT && rootCheckRunning) { sendState(); return }
        if (backend == LocationBackend.ROOT && (rootStatus != "granted" || !RootShell.environmentReady)) {
            sendError("Root 尚未就绪，请查看运行环境中的 Root 状态")
            sendState()
            return
        }
        if (backend == LocationBackend.ROOT && (!hasLocationPermission() || !hasMockPermission())) {
            RootShell.invalidateEnvironment()
            checkRootAndGrant()
            sendError("正在通过 Root 恢复定位环境，完成后即可开始")
            return
        }
        if (next.mode == MockMode.ROUTE_TRACK && next.waypoints.size < 2) { sendError("请至少添加 2 个路线点"); return }
        config = next
        preferences.edit { putString("config", ConfigJson.encode(config).toString()) }
        serviceIntent(MockDaemonService.ACTION_START, next)
    }

    private fun serviceIntent(action: String, next: MockConfig? = null) {
        val intent = Intent(this, MockDaemonService::class.java).apply {
            this.action = action
            next?.let { putExtra(MockDaemonService.EXTRA_CONFIG, it) }
        }
        if (action == MockDaemonService.ACTION_START || action == MockDaemonService.ACTION_OVERLAY_ENABLE) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }

    private fun reloadSavedConfig() {
        config = runCatching { ConfigJson.decode(JSONObject(preferences.getString("config", "{}")!!)) }.getOrDefault(config)
    }

    private fun toggleSystemOverlay(enabled: Boolean) {
        val rootMode = RuntimeEnvironment.backend(this) == LocationBackend.ROOT
        if (enabled && rootMode && (rootStatus != "granted" || !RootShell.environmentReady)) { sendError("请等待 Root 自动配置完成后开启悬浮窗"); sendState(); return }
        if (enabled && !Settings.canDrawOverlays(this)) {
            if (rootMode) { checkRootAndGrant(); sendError("正在通过 Root 自动配置悬浮窗权限，请稍后再开启") }
            else {
                preferences.edit { putBoolean("overlay-permission-pending", true) }
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        preferences.edit { putBoolean("overlay-enabled", enabled) }
        if (enabled) serviceIntent(MockDaemonService.ACTION_OVERLAY_ENABLE, config)
        else if (MockDaemonService.isServiceAlive) serviceIntent(MockDaemonService.ACTION_OVERLAY_DISABLE)
        sendState()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasMockPermission(): Boolean = runCatching {
        val manager = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val result = if (Build.VERSION.SDK_INT >= 29) manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        else manager.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        result == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun checkRootAndGrant() {
        if (rootCheckRunning || RuntimeEnvironment.backend(this) != LocationBackend.ROOT) return
        rootCheckRunning = true
        rootStatus = "checking"
        rootDetail = "正在自动请求 SU 授权，首次使用请允许系统授权弹窗。"
        lastRootCheckAt = SystemClock.elapsedRealtime()
        sendState()
        rootExecutor.execute {
            val result = RootShell.prepareEnvironment(applicationContext)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                rootCheckRunning = false
                if (RuntimeEnvironment.backend(this) != LocationBackend.ROOT) { sendState(); return@post }
                val configured = result.status == "granted" && hasLocationPermission() && hasMockPermission()
                rootStatus = if (result.status == "granted" && !configured) "error" else result.status
                rootDetail = if (result.status == "granted" && !configured) "Root 已授权，系统尚未接受定位配置，返回应用后会自动重试。" else result.detail
                if (!configured) RootShell.invalidateEnvironment()
                if (configured && resumed && preferences.getBoolean("overlay-enabled", false)) serviceIntent(MockDaemonService.ACTION_OVERLAY_ENABLE, config)
                sendState()
            }
        }
    }

    private fun sendState(configChanged: Boolean = false) {
        if (!pageReady) return
        sendToWeb(RuntimeEnvironment.putState(this, JSONObject().apply {
            put("type", "state")
            put("running", MockDaemonService.isServiceRunning)
            put("root", rootStatus)
            put("rootReady", rootStatus == "granted" && RootShell.environmentReady && hasLocationPermission() && hasMockPermission())
            put("rootDetail", rootDetail)
            put("overlayEnabled", preferences.getBoolean("overlay-enabled", false))
            put("configChanged", configChanged)
            put("config", ConfigJson.encode(config))
            MockDaemonService.latestStats?.let { put("stats", it) }
        }))
    }

    private fun changeBackend(value: String) {
        val backend = runCatching { LocationBackend.valueOf(value) }.getOrNull() ?: return
        if (MockDaemonService.isSessionBusy) { sendError("停止模拟后再切换运行模式"); sendState(); return }
        if (backend == RuntimeEnvironment.backend(this)) { sendState(); return }
        preferences.edit { putString("location-backend", backend.name) }
        HookStateStore.publish(this, null)
        if (backend == LocationBackend.ROOT) checkRootAndGrant()
        else if (preferences.getBoolean("overlay-enabled", false) && Settings.canDrawOverlays(this)) serviceIntent(MockDaemonService.ACTION_OVERLAY_ENABLE, config)
        else if (MockDaemonService.isServiceAlive) serviceIntent(MockDaemonService.ACTION_BACKEND_CHANGED)
        sendState()
    }

    private fun saveHookTargets(packages: JSONArray) {
        val previous = RuntimeEnvironment.targets(this)
        val selected = (0 until packages.length().coerceAtMost(100)).map { packages.optString(it) }
            .filter { HookAccessPolicy.validTarget(it, packageName) }.toSet()
        preferences.edit { putStringSet("hook-targets", selected) }
        (previous - selected).forEach { pkg -> runCatching { revokeUriPermission(pkg, HookStateStore.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        grantHookBridgeVisibility()
        HookStateStore.publish(this, HookStateStore.frame)
        sendState()
    }

    private fun grantHookBridgeVisibility() {
        // Android 11+ hides undeclared provider authorities. An explicit URI grant makes
        // this provider visible to a selected app without modifying that app's manifest.
        RuntimeEnvironment.targets(this).forEach { pkg ->
            runCatching { grantUriPermission(pkg, HookStateStore.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun listHookApps() {
        rootExecutor.execute {
            val result = runCatching {
                @Suppress("DEPRECATION") val apps = packageManager.getInstalledApplications(0)
                val selected = RuntimeEnvironment.targets(this)
                val entries = apps.filter { info -> HookAccessPolicy.validTarget(info.packageName, packageName) && info.enabled &&
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 || info.packageName in selected || packageManager.getLaunchIntentForPackage(info.packageName) != null) }
                    .map { it.packageName to packageManager.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
                JSONArray().apply { entries.forEach { (pkg, label) -> put(JSONObject().put("packageName", pkg).put("label", label)) } }
            }
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { sendToWeb(JSONObject().put("type", "hookApps").put("apps", it)) }
                    .onFailure { sendError("应用列表加载失败：${it.message}") }
            }
        }
    }

    private fun openFramework() {
        val launch = listOf("org.lsposed.manager", "io.github.lsposed.manager", "de.robv.android.xposed.installer")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) startActivity(launch)
        else sendNotice("请在 Xposed／LSPosed 管理器中启用 JiDian，并勾选 JiDian 和目标应用")
    }

    @Suppress("DEPRECATION")
    private fun openHookTarget(pkg: String) {
        if (pkg !in RuntimeEnvironment.targets(this)) return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { sendNotice("目标应用没有可打开的界面"); return }
        // Activity-for-result also establishes caller visibility on Android package filters.
        runCatching { startActivityForResult(launch, 102) }.onFailure { sendNotice("暂时无法打开此应用") }
    }

    private fun sendError(message: String) = sendToWeb(JSONObject().put("type", "error").put("message", message))
    private fun sendNotice(message: String) = sendToWeb(JSONObject().put("type", "notice").put("message", message))

    private fun sendToWeb(message: JSONObject) {
        if (!pageReady || !resumed || isDestroyed) return
        webView.evaluateJavascript("window.MockApp && window.MockApp.receive($message);", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptImport(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImport?.let { outState.putString("pending-import", it) }
        super.onSaveInstanceState(outState)
    }

    private fun acceptImport(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        pendingImport = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(32768)?.takeIf { it.isNotBlank() }
        deliverImport()
    }

    private fun deliverImport() {
        if (!pageReady || !resumed) return
        val text = pendingImport ?: return
        pendingImport = null
        sendToWeb(JSONObject().put("type", "importText").put("text", text))
    }

    @Suppress("DEPRECATION")
    private fun geocode(address: String, requestId: Long) {
        val query = address.trim().take(300)
        if (query.length < 2) return
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        fun result(locations: List<Address>, error: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                val items = JSONArray()
                locations.take(8).filter { it.hasLatitude() && it.hasLongitude() }.forEach { item ->
                    items.put(JSONObject().put("point", JSONObject().put("lat", item.latitude).put("lng", item.longitude))
                        .put("name", item.featureName.orEmpty()).put("address", item.getAddressLine(0) ?: query).put("system", "WGS84"))
                }
                val response = JSONObject().put("type", "geocode").put("requestId", requestId).put("locations", items).put("error", error.orEmpty())
                if (resumed && pageReady) sendToWeb(response) else pendingGeocode = response
            }
        }
        if (!Geocoder.isPresent()) { result(emptyList(), "系统未提供地址查找服务，请粘贴带坐标的地点数据。"); return }
        mainHandler.postDelayed({ result(emptyList(), "地址查找超时，请检查网络或直接粘贴坐标。") }, 12_000)
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { geocoder.getFromLocationName(query, 8, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = result(addresses)
                override fun onError(errorMessage: String?) = result(emptyList(), "地址查找暂不可用，请补充坐标或稍后重试。")
            }) }.onFailure { result(emptyList(), "地址查找暂不可用，请直接粘贴坐标。") }
        } else geocodeExecutor.execute {
            runCatching { geocoder.getFromLocationName(query, 8).orEmpty() }
                .onSuccess { result(it) }.onFailure { result(emptyList(), "地址查找暂不可用，请直接粘贴坐标。") }
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemTheme(dark: Boolean) {
        preferences.edit { putBoolean("dark", dark) }
        val background = if (dark) Color.rgb(20, 28, 24) else Color.rgb(247, 249, 248)
        findViewById<android.view.View>(R.id.appRoot).setBackgroundColor(background)
        webView.setBackgroundColor(background)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, webView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        grantHookBridgeVisibility()
        MockDaemonService.mainVisible = true
        webView.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(MockDaemonService.BROADCAST_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        reloadSavedConfig()
        if (MockDaemonService.isServiceAlive) startService(Intent(this, MockDaemonService::class.java).setAction(MockDaemonService.ACTION_VISIBILITY).putExtra("visible", true))
        sendState(configChanged = true)
        val rootMode = RuntimeEnvironment.backend(this) == LocationBackend.ROOT
        val needsCheck = rootStatus == "unknown" || !RootShell.environmentReady || !hasLocationPermission() || !hasMockPermission()
        if (rootMode && !rootCheckRunning && needsCheck && (rootStatus == "unknown" || SystemClock.elapsedRealtime() - lastRootCheckAt >= 3000L)) checkRootAndGrant()
        if (preferences.getBoolean("overlay-permission-pending", false)) {
            preferences.edit { putBoolean("overlay-permission-pending", false); if (Settings.canDrawOverlays(this@MainActivity)) putBoolean("overlay-enabled", true) }
        }
        if ((!rootMode || (!rootCheckRunning && RootShell.environmentReady)) && Settings.canDrawOverlays(this) && preferences.getBoolean("overlay-enabled", false)) serviceIntent(MockDaemonService.ACTION_OVERLAY_ENABLE, config)
        sendState()
        deliverImport()
        pendingGeocode?.let { sendToWeb(it); pendingGeocode = null }
    }

    override fun onPause() {
        if (pageReady) webView.evaluateJavascript("window.MockApp && window.MockApp.suspend()", null)
        if (MockDaemonService.isServiceRunning) startService(Intent(this, MockDaemonService::class.java).setAction(MockDaemonService.ACTION_JOYSTICK).putExtra("releaseOnly", true))
        resumed = false
        if (receiverRegistered) { unregisterReceiver(statusReceiver); receiverRegistered = false }
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        MockDaemonService.mainVisible = false
        if (MockDaemonService.isServiceAlive) startService(Intent(this, MockDaemonService::class.java).setAction(MockDaemonService.ACTION_VISIBILITY).putExtra("visible", false))
    }

    override fun onDestroy() {
        rootExecutor.shutdownNow()
        geocodeExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("AndroidBridge")
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_URL = "https://appassets.androidplatform.net/assets/ui/index.html"
    }
}
