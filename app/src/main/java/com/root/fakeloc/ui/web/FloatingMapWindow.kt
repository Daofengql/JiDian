package com.root.fakeloc.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import com.root.fakeloc.BuildConfig
import org.json.JSONObject
import kotlin.math.roundToInt

/** A real TYPE_APPLICATION_OVERLAY window owned by the foreground service. */
class FloatingMapWindow(private val context: Context, private val onMessage: (String, JSONObject) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var collapseButton: TextView? = null
    private var collapsed = false
    private var pageReady = false
    private var attached = false
    private var lastState: JSONObject? = null
    private var lastStats: JSONObject? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(247, 249, 248)); cornerRadius = dp(18).toFloat() }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
        val handle = TextView(context).apply {
            text = "JiDian · 小地图"; textSize = 11f; setTextColor(Color.rgb(35, 61, 54))
            gravity = Gravity.CENTER_VERTICAL; contentDescription = "拖动悬浮小地图"
        }
        title = handle
        header.addView(handle, LinearLayout.LayoutParams(0, dp(32), 1f))
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        handle.setOnTouchListener { _, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = layout.x; originY = layout.y; true }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = originX + (event.rawX - downX).roundToInt()
                    layout.y = originY + (event.rawY - downY).roundToInt()
                    clampPosition(layout); if (attached) manager.updateViewLayout(container, layout); true
                }
                MotionEvent.ACTION_UP -> { handle.performClick(); true }
                else -> true
            }
        }
        fun headerButton(label: String, description: String, action: () -> Unit): TextView = TextView(context).apply {
            text = label; textSize = 20f; setTextColor(Color.rgb(40, 107, 86)); gravity = Gravity.CENTER
            contentDescription = description; isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
        collapseButton = headerButton("−", "收起悬浮小地图") { toggleCollapsed() }
        header.addView(collapseButton, LinearLayout.LayoutParams(dp(30), dp(32)))
        header.addView(headerButton("↗", "打开主界面") { onMessage("openMain", JSONObject()) }, LinearLayout.LayoutParams(dp(30), dp(32)))
        header.addView(headerButton("×", "关闭悬浮窗，保留模拟运行") { onMessage("overlayDisable", JSONObject()) }, LinearLayout.LayoutParams(dp(30), dp(32)))
        container.addView(header)

        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        val web = WebView(context).apply {
            setBackgroundColor(Color.rgb(247, 249, 248))
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(raw: String) {
                    if (raw.length > 100_000) return
                    handler.post {
                        if (root == null) return@post
                        runCatching {
                            val message = JSONObject(raw)
                            val action = message.getString("action")
                            val payload = message.optJSONObject("payload") ?: JSONObject()
                            when (action) {
                                "ready" -> { pageReady = true; lastState?.let(::send); lastStats?.let(::send); onMessage("ready", payload) }
                                "overlayFocus" -> this@FloatingMapWindow.setFocusable(payload.optBoolean("focus"))
                                "joystick" -> if (attached && !collapsed) onMessage(action, payload) else onMessage("releaseJoystick", JSONObject())
                                else -> onMessage(action, payload)
                            }
                        }.onFailure { send(JSONObject().put("type", "error").put("message", it.message ?: "悬浮窗操作失败")) }
                    }
                }
            }, "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = request.url.toString() != URL
            }
        }
        webView = web
        container.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root = container
        val screen = screenSize()
        params = WindowManager.LayoutParams(
            minOf(dp(248), screen.first - dp(24)), expandedHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screen.first - width - dp(12); y = dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        web.loadUrl(URL)
    }

    fun show() {
        check(Settings.canDrawOverlays(context)) { "请返回主界面开启悬浮窗权限" }
        if (root == null) create()
        if (!attached) {
            params?.let(::clampPosition)
            manager.addView(root, params); attached = true; webView?.onResume()
            lastState?.let { send(JSONObject(it.toString()).put("configChanged", true)) }; lastStats?.let(::send)
        }
    }

    fun hide() {
        suspendInput()
        onMessage("releaseJoystick", JSONObject())
        setFocusable(false)
        if (attached) { manager.removeView(root); attached = false; webView?.onPause() }
    }

    fun update(state: JSONObject) {
        if (state.optString("type") == "state") lastState = state else if (state.optString("type") == "stats") lastStats = state
        title?.text = if (lastState?.optBoolean("running") == true) "JiDian · 模拟中" else "JiDian · 小地图"
        val height = if (collapsed) dp(34) else expandedHeight()
        params?.takeIf { it.height != height }?.let { layout ->
            layout.height = height; clampPosition(layout)
            if (attached) manager.updateViewLayout(root, layout)
        }
        send(state)
    }

    private fun send(message: JSONObject) {
        if (pageReady && attached && !collapsed) webView?.evaluateJavascript("window.MockApp && window.MockApp.receive($message)", null)
    }

    private fun setFocusable(focus: Boolean) {
        val layout = params ?: return
        if (!focus) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(webView?.windowToken, 0)
        layout.flags = if (focus) layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (attached) manager.updateViewLayout(root, layout)
        if (focus) webView?.requestFocus()
    }

    private fun suspendInput() {
        if (pageReady) webView?.evaluateJavascript("window.MockApp && window.MockApp.suspend()", null)
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        if (collapsed) { suspendInput(); onMessage("releaseJoystick", JSONObject()); setFocusable(false); webView?.onPause() } else webView?.onResume()
        webView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseButton?.text = if (collapsed) "+" else "−"
        collapseButton?.contentDescription = if (collapsed) "展开悬浮小地图" else "收起悬浮小地图"
        params?.apply { height = if (collapsed) dp(34) else expandedHeight(); clampPosition(this) }
        if (attached) manager.updateViewLayout(root, params)
        if (!collapsed) { lastState?.let { send(JSONObject(it.toString()).put("configChanged", true)) }; lastStats?.let(::send) }
    }

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun expandedHeight(): Int = minOf(dp(if (lastState?.optJSONObject("config")?.optString("mode") == "ROUTE_TRACK") 352 else 288), screenSize().second - dp(100))

    private fun clampPosition(layout: WindowManager.LayoutParams) {
        val (width, height) = screenSize()
        layout.width = minOf(layout.width, width - dp(16))
        layout.height = minOf(layout.height, height - dp(60))
        layout.x = layout.x.coerceIn(0, (width - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(dp(24), (height - layout.height - dp(24)).coerceAtLeast(dp(24)))
    }

    fun destroy() {
        hide(); handler.removeCallbacksAndMessages(null)
        webView?.apply { removeJavascriptInterface("AndroidBridge"); stopLoading(); destroy() }
        webView = null; root = null; pageReady = false
    }

    companion object { private const val URL = "https://appassets.androidplatform.net/assets/ui/index.html?overlay=1" }
}
