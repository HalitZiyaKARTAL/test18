package a.htmlapprealizer

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GlobalService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "html-realizer-screenshot").apply { isDaemon = true }
    }
    private var sequenceStep = 0
    private var sequenceStarted = 0L
    private var overlayView: WebView? = null
    private var overlayEpoch = ""
    private var overlayParameters: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Core.port("accessibility", this)
        DebugLog.add("ACCESSIBILITY", "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { Core.event("accessibility.event", it.eventType, it) }
    }

    override fun onInterrupt() { Core.event("accessibility.interrupt") }

    override fun onGesture(gestureId: Int): Boolean {
        Core.event("accessibility.gesture", gestureId, gestureId)
        return consumeGestures || super.onGesture(gestureId)
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        Core.event("accessibility.gesture", gestureEvent.gestureId, gestureEvent)
        return consumeGestures
    }

    override fun onMotionEvent(event: MotionEvent) {
        Core.event("accessibility.motion", event.action, event)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Core.event("accessibility.key", event.keyCode, event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val sequence = emergencySequence
            if (sequence.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                if (now - sequenceStarted > sequenceTimeoutMs || sequenceStep >= sequence.size)
                    sequenceStep = 0
                if (sequenceStep == 0) sequenceStarted = now
                sequenceStep = if (event.keyCode == sequence[sequenceStep]) sequenceStep + 1
                else if (event.keyCode == sequence[0]) 1 else 0
                if (sequenceStep == sequence.size) {
                    sequenceStep = 0
                    closeOverlay("emergency-disable")
                    disableSelf()
                    return true
                }
            }
        }
        return consumeKeys || super.onKeyEvent(event)
    }

    fun screenTree(maxNodes: Int = 1000): String {
        val limit = maxNodes.coerceIn(1, 5000)
        val count = intArrayOf(0)
        val result = JSONObject().put("windows", JSONArray())
        val output = result.getJSONArray("windows")
        val currentWindows = windows
        try {
            for (window in currentWindows) {
                if (count[0] >= limit) break
                val bounds = Rect().also(window::getBoundsInScreen)
                val value = JSONObject()
                    .put("id", window.id)
                    .put("type", window.type)
                    .put("layer", window.layer)
                    .put("active", window.isActive)
                    .put("focused", window.isFocused)
                    .put("accessibilityFocused", window.isAccessibilityFocused)
                    .put("title", window.title?.toString().orEmpty())
                    .put("bounds", rectJson(bounds))
                val root = window.root
                if (root != null) {
                    try { value.put("root", nodeTree(root, JSONArray(), count, limit)) }
                    finally { recycle(root) }
                }
                output.put(value)
            }
        } finally {
            currentWindows.forEach(::recycle)
        }
        result.put("truncated", count[0] >= limit)
        result.put("nodeCount", count[0])
        return result.toString()
    }

    fun find(query: String, maxNodes: Int = 100): String {
        val needle = query.lowercase()
        val result = JSONArray()
        val root = rootInActiveWindow ?: return result.toString()
        try { findNodes(root, JSONArray(), needle, result, maxNodes.coerceIn(1, 1000)) }
        finally { recycle(root) }
        return result.toString()
    }

    fun perform(locatorJson: String, actionName: String, argument: String = ""): Boolean {
        val locator = runCatching { JSONObject(locatorJson) }.getOrNull() ?: return false
        val node = resolveLocator(locator) ?: return false
        return try {
            val action = actionName.toIntOrNull() ?: when (actionName.lowercase()) {
                "click" -> AccessibilityNodeInfo.ACTION_CLICK
                "longclick", "long-click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
                "accessibilityfocus", "accessibility-focus" -> AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                "clearfocus", "clear-focus" -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                "scrollforward", "scroll-forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "scrollbackward", "scroll-backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "show", "showonscreen" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                "settext", "set-text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
                else -> return false
            }
            val arguments = if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, argument)
            } else null
            performWithParents(node, action, arguments)
        } finally { recycle(node) }
    }

    private fun performWithParents(
        initial: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle?
    ): Boolean {
        var node: AccessibilityNodeInfo? = initial
        var ownsNode = false
        while (node != null) {
            if (node.performAction(action, arguments)) {
                if (ownsNode) recycle(node)
                return true
            }
            val parent = node.parent
            if (ownsNode) recycle(node)
            node = parent
            ownsNode = true
        }
        return false
    }

    fun tap(x: Float, y: Float, durationMs: Long = 35): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0, durationMs)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 700): Boolean =
        tap(x, y, durationMs)

    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300
    ): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(path, 0, durationMs)
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean =
        dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(
                    path, startMs.coerceAtLeast(0), durationMs.coerceAtLeast(1)
                )
            ).build(), GestureEvents(), main
        )

    fun gestures(specification: String): Boolean {
        val strokes = JSONArray(specification)
        if (strokes.length() !in 1..128) return false
        val builder = GestureDescription.Builder()
        for (index in 0 until strokes.length()) {
            val stroke = strokes.getJSONObject(index)
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path(stroke),
                    stroke.optLong("d", stroke.optLong("start", 0)).coerceAtLeast(0),
                    stroke.optLong("ms", stroke.optLong("duration", 35)).coerceAtLeast(1)
                )
            )
        }
        return dispatchGesture(builder.build(), GestureEvents(), main)
    }

    private fun path(value: JSONObject): Path {
        val result = Path()
        val points = value.optJSONArray("points") ?: value.optJSONArray("p")
        if (points != null && points.length() > 0) {
            val first = points.getJSONArray(0)
            result.moveTo(first.getDouble(0).toFloat(), first.getDouble(1).toFloat())
            for (index in 1 until points.length()) {
                val point = points.getJSONArray(index)
                result.lineTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            }
        } else if (value.has("x")) {
            result.moveTo(value.getDouble("x").toFloat(), value.getDouble("y").toFloat())
        } else {
            result.moveTo(value.getDouble("x1").toFloat(), value.getDouble("y1").toFloat())
            result.lineTo(value.getDouble("x2").toFloat(), value.getDouble("y2").toFloat())
        }
        return result
    }

    fun global(action: Int): Boolean = performGlobalAction(action)
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun screenshot(
        left: Int = -1,
        top: Int = -1,
        right: Int = -1,
        bottom: Int = -1,
        maxWidth: Int = 1080,
        quality: Int = 82
    ): String {
        val latch = CountDownLatch(1)
        var output = "E:TIMEOUT"
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                output = runCatching {
                    val source = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false) ?: error("hardware buffer conversion failed")
                    result.hardwareBuffer.close()
                    val requested = if (left >= 0 && top >= 0 && right > left && bottom > top)
                        Rect(left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtMost(source.width), bottom.coerceAtMost(source.height))
                    else Rect(0, 0, source.width, source.height)
                    require(requested.width() > 0 && requested.height() > 0) { "empty crop" }
                    val cropped = Bitmap.createBitmap(
                        source, requested.left, requested.top, requested.width(), requested.height()
                    )
                    val scaled = if (maxWidth > 0 && cropped.width > maxWidth)
                        Bitmap.createScaledBitmap(
                            cropped, maxWidth, (cropped.height * maxWidth / cropped.width).coerceAtLeast(1), true
                        ) else cropped
                    val bytes = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bytes)
                    JSONObject()
                        .put("width", scaled.width)
                        .put("height", scaled.height)
                        .put("mime", "image/jpeg")
                        .put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                        .toString()
                }.getOrElse { "E:SCREENSHOT:${it.message.orEmpty()}" }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                output = "E:SCREENSHOT:$errorCode"
                latch.countDown()
            }
        })
        latch.await(8, TimeUnit.SECONDS)
        return output
    }

    fun openOverlay(html: String, mode: String, configuration: String): String {
        if (html.length > 1_000_000) return "E:LIMIT"
        val born = Core.currentEpoch()
        if (born.isEmpty()) return "E:STALE"
        return onMain {
            closeOverlayInternal("replace")
            val config = runCatching { JSONObject(configuration.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val density = resources.displayMetrics.density
            fun pixels(key: String, fallbackDp: Int): Int =
                (config.optDouble(key, fallbackDp.toDouble()) * density).toInt().coerceAtLeast(1)
            val parameters = WindowManager.LayoutParams(
                pixels("width", 240),
                pixels("height", 180),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = pixels("x", 0)
                y = pixels("y", 0)
                alpha = config.optDouble("alpha", 1.0).toFloat().coerceIn(0.05f, 1f)
            }
            when (mode.uppercase()) {
                "PLACE" -> parameters.flags = parameters.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                "VISUAL" -> parameters.flags = parameters.flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                "HIDDEN" -> parameters.flags = parameters.flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else -> return@onMain "E:MODE"
            }
            val view = WebView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                addJavascriptInterface(OverlayBridge(born), "O")
                webViewClient = overlayClient()
                visibility = if (mode.equals("HIDDEN", true)) View.GONE else View.VISIBLE
                loadDataWithBaseURL(Core.DEFAULT_ORIGIN, html, "text/html", "utf-8", null)
            }
            getSystemService(WindowManager::class.java).addView(view, parameters)
            overlayView = view
            overlayParameters = parameters
            overlayEpoch = born
            Core.port("overlay", view)
            "V"
        }
    }

    fun evaluateOverlay(script: String): String = onMain {
        val view = overlayView ?: return@onMain "E:OVERLAY"
        if (overlayEpoch != Core.currentEpoch()) return@onMain "E:STALE"
        view.evaluateJavascript(script, null)
        "V"
    }

    fun closeOverlay(reason: String): Boolean = onMain {
        closeOverlayInternal(reason)
        true
    } == true

    private fun closeOverlayInternal(reason: String) {
        val view = overlayView ?: return
        overlayView = null
        overlayParameters = null
        overlayEpoch = ""
        Core.port("overlay", view, live = false)
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        runCatching { view.destroy() }
        DebugLog.add("OVERLAY", "closed: $reason")
    }

    private fun overlayClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            !HostPolicy.isAllowed(request?.url, Core.policyOrNull())

        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?
        ): WebResourceResponse? = if (HostPolicy.isAllowed(request?.url, Core.policyOrNull())) null
        else HostPolicy.blocked()

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            closeOverlay("renderer-gone")
            return true
        }
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = CountDownLatch(1)
        var value: Result<T>? = null
        main.post {
            value = runCatching(action)
            latch.countDown()
        }
        if (!latch.await(30, TimeUnit.SECONDS)) throw IllegalStateException("main thread timeout")
        return value!!.getOrThrow()
    }

    private fun nodeTree(
        node: AccessibilityNodeInfo,
        path: JSONArray,
        count: IntArray,
        limit: Int
    ): JSONObject {
        count[0]++
        val value = nodeJson(node, path)
        val children = JSONArray()
        if (count[0] < limit) {
            for (index in 0 until node.childCount) {
                if (count[0] >= limit) break
                val child = node.getChild(index) ?: continue
                try {
                    val childPath = JSONArray(path.toString()).put(index)
                    children.put(nodeTree(child, childPath, count, limit))
                } finally { recycle(child) }
            }
        }
        value.put("children", children)
        return value
    }

    private fun nodeJson(node: AccessibilityNodeInfo, path: JSONArray): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        val actions = JSONArray()
        node.actionList.forEach { action ->
            actions.put(JSONObject().put("id", action.id).put("label", action.label?.toString().orEmpty()))
        }
        return JSONObject()
            .put("locator", JSONObject()
                .put("windowId", node.windowId)
                .put("path", path)
                .put("viewId", node.viewIdResourceName.orEmpty())
                .put("class", node.className?.toString().orEmpty())
                .put("package", node.packageName?.toString().orEmpty())
                .put("bounds", rectJson(bounds)))
            .put("text", node.text?.toString().orEmpty())
            .put("contentDescription", node.contentDescription?.toString().orEmpty())
            .put("viewId", node.viewIdResourceName.orEmpty())
            .put("package", node.packageName?.toString().orEmpty())
            .put("class", node.className?.toString().orEmpty())
            .put("bounds", rectJson(bounds))
            .put("enabled", node.isEnabled)
            .put("clickable", node.isClickable)
            .put("longClickable", node.isLongClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("checkable", node.isCheckable)
            .put("checked", node.isChecked)
            .put("selected", node.isSelected)
            .put("focused", node.isFocused)
            .put("accessibilityFocused", node.isAccessibilityFocused)
            .put("password", node.isPassword)
            .put("visible", node.isVisibleToUser)
            .put("actions", actions)
    }

    private fun findNodes(
        node: AccessibilityNodeInfo,
        path: JSONArray,
        query: String,
        output: JSONArray,
        limit: Int
    ) {
        val haystack = listOf(
            node.text, node.contentDescription, node.viewIdResourceName, node.className, node.packageName
        ).joinToString("\n") { it?.toString().orEmpty() }.lowercase()
        if ((query.isBlank() || query == "*" || haystack.contains(query)) && output.length() < limit)
            output.put(nodeJson(node, path))
        for (index in 0 until node.childCount) {
            if (output.length() >= limit) break
            val child = node.getChild(index) ?: continue
            try { findNodes(child, JSONArray(path.toString()).put(index), query, output, limit) }
            finally { recycle(child) }
        }
    }

    private fun resolveLocator(locator: JSONObject): AccessibilityNodeInfo? {
        val windowId = locator.optInt("windowId", -1)
        val path = locator.optJSONArray("path") ?: JSONArray()
        val currentWindows = windows
        val matchingWindow = currentWindows.firstOrNull { it.id == windowId }
        var node = matchingWindow?.root ?: rootInActiveWindow
        if (node == null) {
            currentWindows.forEach(::recycle)
            return null
        }
        try {
            for (index in 0 until path.length()) {
                val child = node.getChild(path.getInt(index))
                if (child == null) {
                    recycle(node)
                    return null
                }
                recycle(node)
                node = child
            }
            val expectedId = locator.optString("viewId")
            val expectedClass = locator.optString("class")
            val expectedPackage = locator.optString("package")
            if (expectedId.isNotBlank() && node.viewIdResourceName != expectedId ||
                expectedClass.isNotBlank() && node.className?.toString() != expectedClass ||
                expectedPackage.isNotBlank() && node.packageName?.toString() != expectedPackage
            ) {
                recycle(node)
                return null
            }
            return node
        } finally {
            currentWindows.forEach(::recycle)
        }
    }

    private fun rectJson(value: Rect) = JSONObject()
        .put("left", value.left).put("top", value.top)
        .put("right", value.right).put("bottom", value.bottom)

    @Suppress("DEPRECATION")
    private fun recycle(value: AccessibilityNodeInfo) { runCatching { value.recycle() } }
    @Suppress("DEPRECATION")
    private fun recycle(value: AccessibilityWindowInfo) { runCatching { value.recycle() } }

    override fun onDestroy() {
        closeOverlay("service-destroyed")
        Core.port("accessibility", this, live = false)
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        DebugLog.add("ACCESSIBILITY", "service destroyed")
        super.onDestroy()
    }

    companion object {
        @Volatile @JvmField var instance: GlobalService? = null
        @Volatile @JvmField var emergencySequence = intArrayOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP
        )
        @Volatile @JvmField var sequenceTimeoutMs = 5000L
        @Volatile @JvmField var consumeKeys = false
        @Volatile @JvmField var consumeGestures = false
    }
}

private class GestureEvents : AccessibilityService.GestureResultCallback() {
    private val born = Core.currentEpoch()
    override fun onCompleted(gestureDescription: GestureDescription?) {
        if (born == Core.currentEpoch()) Core.event("gesture.completed", 0, gestureDescription)
    }
    override fun onCancelled(gestureDescription: GestureDescription?) {
        if (born == Core.currentEpoch()) Core.event("gesture.cancelled", 0, gestureDescription)
    }
}

private class OverlayBridge(private val born: String) {
    @JavascriptInterface fun post(type: String, json: String): Boolean =
        born == Core.currentEpoch() && Core.event("overlay.$type", 0, json)

    @JavascriptInterface fun close(): Boolean =
        born == Core.currentEpoch() && GlobalService.instance?.closeOverlay("overlay-request") == true
}
