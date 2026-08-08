package a.htmlapprealizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.jvm.JvmField

interface PortalSink {
    fun onPortalEvent(type: String, value: Any?)
}

object PortalHub {
    private val live = ConcurrentHashMap<String, Any>()
    private val sinks = CopyOnWriteArraySet<PortalSink>()

    fun attach(name: String, value: Any) {
        live[name] = value
        emit("$name.ready", value)
    }

    fun detach(name: String, value: Any) {
        if (live.remove(name, value)) emit("$name.closed")
    }

    fun current(name: String): Any? = live[name]
    fun subscribe(sink: PortalSink) { sinks.add(sink) }
    fun unsubscribe(sink: PortalSink) { sinks.remove(sink) }
    fun emit(type: String, value: Any? = null) {
        sinks.forEach { sink -> runCatching { sink.onPortalEvent(type, value) } }
    }
}

class BridgePolicy private constructor(context: Context) {
    val P: SharedPreferences = context.applicationContext.getSharedPreferences("Z", Context.MODE_PRIVATE)
    val L = Array(3) { CopyOnWriteArraySet<String>() }
    @Volatile var s = P.getBoolean("S", true)
    @Volatile var PW = P.getString("W", "") ?: ""

    init {
        (0..2).forEach { i ->
            P.getString("$i", "")!!.split(",").filter { it.isNotEmpty() }.forEach { L[i].add(it) }
        }
    }

    fun S(k: String, v: Any) = with(P.edit()) {
        if (v is Boolean) putBoolean(k, v) else if (v is Int) putInt(k, v) else putString(k, v.toString())
        apply()
    }

    companion object {
        @Volatile private var instance: BridgePolicy? = null
        fun get(context: Context): BridgePolicy = instance ?: synchronized(this) {
            instance ?: BridgePolicy(context).also { instance = it }
        }
    }
}

class BridgeKernel(
    private val root: Any,
    private val activity: Activity?,
    private val web: () -> WebView?,
    private val policy: BridgePolicy
) {
    val H = ArrayList<Any?>()
    @Volatile var A = false
    @Volatile private var events = false
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private fun h(i: Int) = synchronized(H) { H.getOrNull(i) }

    init {
        synchronized(H) { H.add(root) }
    }

    fun ok() = !closed && !policy.s && (policy.PW.isEmpty() || A)
    fun pageStarted() { A = false; events = false }
    fun close() { closed = true; events = false; synchronized(H) { H.clear() } }

    private fun postJs(script: String) {
        val action = Runnable {
            val target = web()
            if (!closed && target != null && !target.isDestroyed) target.evaluateJavascript(script, null)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
    }

    fun emit(type: String, value: Any?) {
        if (!events || !ok()) return
        val result = ret(value)
        postJs("window.onE&&window.onE(${org.json.JSONObject.quote(type)},${org.json.JSONObject.quote(result)})")
    }

    fun K(i: Int, s: String) = policy.L[i].contains("ALL") || policy.L[i].any { it != "" && s.contains(it) }
    fun C(n: String) = try { Class.forName(n) } catch (e: Exception) { null }

    fun R(t: Any?, n: String, j: String, y: Int, cI: Int): String {
        if (!ok()) return "E:SEC"
        try {
            val c = if (t is Class<*>) t else t?.javaClass ?: return "E:NUL"
            val a = JSONArray(j)
            val sig = "$n ${c.name}".lowercase()
            if (y < 4 && K(0, sig)) return "E:BL"

            val g = if (y > 3 || K(1, sig)) null else if (K(2, sig)) "ALL" else policy.L[2].find { it != "" && sig.contains(it) } ?: if (policy.L[0].isEmpty() && policy.L[1].isEmpty()) "ALL" else null

            if (g != null) {
                val host = activity ?: return "E:UI"
                val i = (System.currentTimeMillis() % 999).toInt()
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) {
                        cb(cI, "E:HOST")
                    } else {
                        AlertDialog.Builder(host).setTitle("REQ:$sig")
                            .setPositiveButton("1") { _, _ -> cb(cI, Ex(t, n, a, y)) }
                            .setNeutralButton("OK") { _, _ -> policy.L[1].add(g); policy.S("1", policy.L[1].joinToString(",")); cb(cI, Ex(t, n, a, y)) }
                            .setNegativeButton("NO") { _, _ -> policy.L[0].add(g); policy.S("0", policy.L[0].joinToString(",")); cb(cI, "E:BL") }
                            .setOnCancelListener { cb(cI, "E:CANCEL") }
                            .show()
                    }
                }
                return "W:$i"
            }
            return Ex(t, n, a, y)
        } catch (e: Exception) { return "E:$e" }
    }

    fun Ex(t: Any?, n: String, a: JSONArray, y: Int): String {
        try {
            val c = if (t is Class<*>) t else t!!.javaClass
            val l = a.length()
            if (y == 2) return ret(c.getField(n).get(t))
            if (y == 3) { val f = c.getField(n); f.set(t, cv(a.get(0), f.type)); return "OK" }
            if (y == 4) return ret(java.lang.reflect.Array.newInstance(c, a.getInt(0)))

            val proxyId = if (y == 5) a.getInt(0) else 0
            if (y == 5) return ret(Proxy.newProxyInstance(c.classLoader, arrayOf(c)) { _, m, r ->
                val g = r?.map { org.json.JSONObject.quote(ret(it)) }?.joinToString(",") ?: ""
                postJs("window.onL($proxyId,${org.json.JSONObject.quote(m.name)},${org.json.JSONObject.quote("[$g]")})")
                val rt = m.returnType
                if (rt == Boolean::class.javaPrimitiveType) false else if (rt == Int::class.javaPrimitiveType) 0 else if (rt.isPrimitive && rt != java.lang.Void.TYPE) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(rt, 1), 0) else null
            })

            if (y == 1) {
                for (con in c.constructors) {
                    if (con.parameterTypes.size != l) continue
                    try {
                        val v = Array(l) { i -> cv(a.get(i), con.parameterTypes[i]) }
                        return ret(con.newInstance(*v))
                    } catch (e: Exception) { continue }
                }
            } else {
                for (m in c.methods) {
                    if (m.name != n || m.parameterTypes.size != l) continue
                    try {
                        val v = Array(l) { i -> cv(a.get(i), m.parameterTypes[i]) }
                        return ret(m.invoke(if (t is Class<*>) null else t, *v))
                    } catch (e: Exception) { continue }
                }
            }
            return "E:SIG"
        } catch (e: Exception) {
            return if (e is InvocationTargetException) "E:${e.targetException}" else "E:$e"
        }
    }

    fun cv(o: Any?, t: Class<*>) =
        if (o === org.json.JSONObject.NULL) null else if (o is String && o.startsWith("P")) h(o.substring(1).toIntOrNull() ?: -1)
        else if (o is Number) when (t) {
            Int::class.javaPrimitiveType -> o.toInt(); Long::class.javaPrimitiveType -> o.toLong()
            Float::class.javaPrimitiveType -> o.toFloat(); Double::class.javaPrimitiveType -> o.toDouble()
            else -> o
        } else if (t == Boolean::class.javaPrimitiveType) o.toString().toBoolean() else o

    fun ret(o: Any?) = if (o == null || o is Unit) "V" else if (o is Number || o is String || o is Boolean) "V$o" else synchronized(H) { H.add(o); "P${H.size - 1}" }
    fun cb(i: Int, r: String) { if (i > 0) postJs("window.onC($i,${org.json.JSONObject.quote(r)})") }

    @JavascriptInterface fun sz() = synchronized(H) { H.size }
    @JavascriptInterface fun cls() { synchronized(H) { H.clear(); H.add(root) } }
    @JavascriptInterface fun c(n: String) = try { if (ok()) ret(Class.forName(n)) else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun n(c: String, j: String) = R(C(c), "", j, 1, 0)
    @JavascriptInterface fun x(p: Int, n: String, j: String) = R(h(p), n, j, 0, 0)
    @JavascriptInterface fun u(p: Int, n: String, j: String, cbId: Int) { main.post { cb(cbId, R(h(p), n, j, 0, cbId)) } }
    @JavascriptInterface fun g(p: Int, f: String) = R(h(p), f, "[]", 2, 0)
    @JavascriptInterface fun s(p: Int, f: String, v: String) = R(h(p), f, "[$v]", 3, 0)
    @JavascriptInterface fun a(t: String, l: Int) = R(C(t), "", "[$l]", 4, 0)
    @JavascriptInterface fun p(t: String, id: Int) = R(C(t), "", "[$id]", 5, 0)
    @JavascriptInterface fun del(p: Int) { if (ok()) synchronized(H) { if (p in 1 until H.size) H[p] = null } }
    @JavascriptInterface fun auth(p: String): Boolean { A = (p == policy.PW); return A }
    @JavascriptInterface fun cap(name: String) = if (!ok()) "E:SEC" else PortalHub.current(name)?.let { ret(it) } ?: "E:OFF"
    @JavascriptInterface fun listen(enabled: Boolean): Boolean { events = enabled && ok(); return events }
}

class Main : Activity() {
    private lateinit var policy: BridgePolicy
    private lateinit var kernel: BridgeKernel
    val H: ArrayList<Any?> get() = kernel.H
    val L: Array<CopyOnWriteArraySet<String>> get() = policy.L
    val D = CopyOnWriteArraySet<String>()
    lateinit var w: WebView
    lateinit var b: Button
    var m = 0 // Mode: 0=Visible, 1=Focus, 2=Open, 3=Perma
    var s: Boolean
        get() = policy.s
        set(value) { policy.s = value }
    var A: Boolean
        get() = kernel.A
        set(value) { kernel.A = value }
    @Volatile var NET = false // WebView network cut
    var PW: String
        get() = policy.PW
        set(value) { policy.PW = value }
    val P: SharedPreferences by lazy { getSharedPreferences("Z", Context.MODE_PRIVATE) }
    private val portalSink = object : PortalSink {
        override fun onPortalEvent(type: String, value: Any?) {
            if (::kernel.isInitialized) kernel.emit(type, value)
        }
    }

    fun S(k: String, v: Any) = with(P.edit()) {
        if (v is Boolean) putBoolean(k, v) else if (v is Int) putInt(k, v) else putString(k, v.toString())
        apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policy = BridgePolicy.get(this)
        kernel = BridgeKernel(this, this, { if (::w.isInitialized) w else null }, policy)
        m = P.all["M"].toString().toIntOrNull() ?: 0
        NET = P.getBoolean("N", false)
        P.getString("D", "")!!.split(",").filter { it.isNotEmpty() }.forEach { D.add(it) }

        val f = FrameLayout(this)
        b = Button(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(120, 120, Gravity.TOP or Gravity.END)
            setOnClickListener { E() }
        }
        w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(kernel, "K")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    kernel.pageStarted()
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url
                    val h = url?.host
                    val allowed = !NET || url?.scheme in listOf("file", "data") ||
                            (h != null && D.any { it.isNotEmpty() && (h == it || h.endsWith(".$it")) })
                    return if (allowed) null else WebResourceResponse(null, null, null)
                }
            }
        }
        f.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(b)
        f.setOnApplyWindowInsetsListener { _, i ->
            val p = b.layoutParams as FrameLayout.LayoutParams
            val d = maxOf(p.width, p.height)
            val r = windowManager.currentWindowMetrics.bounds
            val z = runCatching {
                i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            }.getOrNull()
            fun q(x: Int, n: Int) = minOf(
                if (z == null) d + n / 67 else maxOf(d, x),
                maxOf(0, minOf(n / 2, n - d))
            )
            p.topMargin = q(z?.top ?: 0, r.height())
            p.marginEnd = q(
                if (b.layoutDirection == View.LAYOUT_DIRECTION_RTL) (z?.left ?: 0) else (z?.right ?: 0),
                r.width()
            )
            b.layoutParams = p
            i
        }
        setContentView(f)
        v(m)
        PortalHub.attach("activity", this)
        PortalHub.subscribe(portalSink)

        w.loadDataWithBaseURL(null, """<!DOCTYPE html><title>App Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable="plaintext-only" oninput="c.textContent=i.textContent.length"></div><button onclick="var code=i.textContent;document.open();document.write(code);document.close()">Realize (<b id=c>0</b> chars)</button>""", "text/html", "utf-8", null)
    }

    override fun onResume() {
        super.onResume()
        if (m == 1) v(0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        cb(requestCode, """{"type":"activity","resultCode":$resultCode,"data":${org.json.JSONObject.quote(ret(data))}}""")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        cb(requestCode, """{"type":"permission","permissions":${JSONArray(permissions.toList())},"grantResults":${JSONArray(grantResults.toList())}}""")
    }

    override fun onDestroy() {
        PortalHub.unsubscribe(portalSink)
        PortalHub.detach("activity", this)
        kernel.close()
        w.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            b.visibility = View.VISIBLE
            Handler(Looper.getMainLooper()).postDelayed({ v(m) }, 3000)
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    fun v(n: Int) {
        m = n
        if (m == 3) S("M", 3) else P.edit().remove("M").apply()
        b.visibility = if (m == 0) View.VISIBLE else View.GONE
    }

    fun ok() = kernel.ok()

    fun E() {
        val sv = ScrollView(this)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.parseColor("#333333"))
        }
        sv.addView(l)

        fun T(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = f(s.toString())
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })

        fun U(t: String, c: Int, h: String, v: String, a: (Button) -> Unit, x: (String) -> Unit) {
            val r = LinearLayout(this)
            val btn = Button(this).apply {
                text = t
                setTextColor(c)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { a(this) }
            }
            val edt = EditText(this).apply {
                hint = h
                setTextColor(Color.WHITE)
                setText(v)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            T(edt, x)
            r.addView(btn); r.addView(edt)
            l.addView(r)
        }

        U("Sandbox: ${if (s) "ON" else "OFF"}", if (s) Color.GREEN else Color.RED, "Password", PW, {
            s = !s; S("S", s)
            it.text = "Sandbox: ${if (s) "ON" else "OFF"}"
            it.setTextColor(if (s) Color.GREEN else Color.RED)
        }) { PW = it; S("W", it); A = false }

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            movementMethod = LinkMovementMethod.getInstance()
            text = SpannableStringBuilder().apply {
                val map = mapOf("unhide" to 0, "focus" to 1, "open" to 2, "perma" to 3)
                "unhide/hide settings:till next focus/open/perma".split(Regex("(?<=[a-z])(?=[/:])|(?<=[/:])(?=[a-z])")).forEach { word ->
                    if (map.containsKey(word)) {
                        val start = length
                        append(" $word ")
                        setSpan(object : ClickableSpan() {
                            override fun onClick(view: View) = v(map[word]!!)
                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = Color.WHITE; ds.bgColor = Color.DKGRAY; ds.isUnderlineText = false
                            }
                        }, start, length, 33)
                    } else append(word)
                }
            }
        }
        l.addView(tv)

        U("Cut Internet: ${if (NET) "ON" else "OFF"}", if (NET) Color.RED else Color.GREEN, "Exceptions", D.joinToString(","), {
            NET = !NET; S("N", NET)
            it.text = "Cut Internet: ${if (NET) "ON" else "OFF"}"
            it.setTextColor(if (NET) Color.RED else Color.GREEN)
        }) { D.clear(); D.addAll(it.split(",").filter { i -> i.isNotEmpty() }); S("D", it) }

        val n = arrayOf("Blacklist", "Graylist", "Whitelist")
        (0..2).forEach { i ->
            l.addView(TextView(this).apply { text = n[i]; setTextColor(Color.WHITE) })
            val e = EditText(this).apply {
                setText(L[i].joinToString(","))
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.DKGRAY)
            }
            T(e) { L[i].clear(); L[i].addAll(it.split(",").filter { x -> x.isNotEmpty() }); S("$i", it) }
            l.addView(e)
        }
        AlertDialog.Builder(this).setView(sv).show()
    }

    fun K(i: Int, s: String) = kernel.K(i, s)
    fun C(n: String) = kernel.C(n)
    fun R(t: Any?, n: String, j: String, y: Int, cI: Int) = kernel.R(t, n, j, y, cI)
    fun Ex(t: Any?, n: String, a: JSONArray, y: Int) = kernel.Ex(t, n, a, y)
    fun cv(o: Any?, t: Class<*>) = kernel.cv(o, t)
    fun ret(o: Any?) = kernel.ret(o)
    fun cb(i: Int, r: String) = kernel.cb(i, r)

    @JavascriptInterface fun sz() = kernel.sz()
    @JavascriptInterface fun cls() = kernel.cls()
    @JavascriptInterface fun c(n: String) = kernel.c(n)
    @JavascriptInterface fun n(c: String, j: String) = kernel.n(c, j)
    @JavascriptInterface fun x(p: Int, n: String, j: String) = kernel.x(p, n, j)
    @JavascriptInterface fun u(p: Int, n: String, j: String, cbId: Int) = kernel.u(p, n, j, cbId)
    @JavascriptInterface fun g(p: Int, f: String) = kernel.g(p, f)
    @JavascriptInterface fun s(p: Int, f: String, v: String) = kernel.s(p, f, v)
    @JavascriptInterface fun a(t: String, l: Int) = kernel.a(t, l)
    @JavascriptInterface fun p(t: String, id: Int) = kernel.p(t, id)
    @JavascriptInterface fun del(p: Int) = kernel.del(p)
    @JavascriptInterface fun auth(p: String) = kernel.auth(p)
    @JavascriptInterface fun cap(name: String) = kernel.cap(name)
    @JavascriptInterface fun listen(enabled: Boolean) = kernel.listen(enabled)
}

object ForegroundNotice {
    const val CHANNEL = "html_realizer_runtime"

    fun build(context: Context, title: String, text: String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "HTML Realizer runtime", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, Main::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_more
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }
}

class RuntimePortal : Service() {
    lateinit var web: WebView
    lateinit var kernel: BridgeKernel
    private lateinit var policy: BridgePolicy
    private val main = Handler(Looper.getMainLooper())
    private val portalSink = object : PortalSink {
        override fun onPortalEvent(type: String, value: Any?) {
            if (::kernel.isInitialized) kernel.emit(type, value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, ForegroundNotice.build(this, "HTML Realizer running", "Background HTML/JavaScript is active"))
        policy = BridgePolicy.get(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        kernel = BridgeKernel(this, null, { if (::web.isInitialized) web else null }, policy)
        web.addJavascriptInterface(kernel, "K")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                kernel.pageStarted()
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url
                val host = url?.host
                val domains = policy.P.getString("D", "")!!.split(",").filter { it.isNotEmpty() }
                val allowed = !policy.P.getBoolean("N", false) || url?.scheme in listOf("file", "data") ||
                        (host != null && domains.any { host == it || host.endsWith(".$it") })
                return if (allowed) null else WebResourceResponse(null, null, null)
            }
        }
        PortalHub.subscribe(portalSink)
        PortalHub.attach("background", this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_HTML)?.let { loadHtml(it) }
        intent?.getStringExtra(EXTRA_SCRIPT)?.let { evaluate(it) }
        return START_NOT_STICKY
    }

    fun loadHtml(html: String) {
        main.post { web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    fun loadHtmlAt(baseUrl: String, html: String) {
        main.post { web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) }
    }

    fun evaluate(script: String) {
        main.post { if (!web.isDestroyed) web.evaluateJavascript(script, null) }
    }

    fun getWebView(): WebView = web
    fun getBridgeKernel(): BridgeKernel = kernel
    fun stopRunner() { stopSelf() }

    override fun onDestroy() {
        PortalHub.unsubscribe(portalSink)
        PortalHub.detach("background", this)
        if (::kernel.isInitialized) kernel.close()
        if (::web.isInitialized) web.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "a.htmlapprealizer.STOP_RUNTIME"
        const val EXTRA_HTML = "html"
        const val EXTRA_SCRIPT = "script"
        private const val NOTIFICATION_ID = 1101
    }
}

class NotificationPortal : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        PortalHub.attach("notifications", this)
    }

    override fun onListenerDisconnected() {
        PortalHub.detach("notifications", this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        notification?.let { PortalHub.emit("notification.posted", it) }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        notification?.let { PortalHub.emit("notification.removed", it) }
    }

    override fun onDestroy() {
        PortalHub.detach("notifications", this)
        super.onDestroy()
    }
}

class ProjectionPortal : Service() {
    private var projection: MediaProjection? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, ForegroundNotice.build(this, "HTML Realizer screen capture", "Screen or app-window capture is active"))
        PortalHub.attach("projectionService", this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) {
            PortalHub.emit("projection.busy", projection)
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val consent = intent?.getParcelableExtra<Intent>(EXTRA_CONSENT)
        if (resultCode != Activity.RESULT_OK || consent == null) {
            PortalHub.emit("projection.error", "E:CONSENT")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = try {
            manager.getMediaProjection(resultCode, consent)
        } catch (e: Exception) {
            PortalHub.emit("projection.error", "E:$e")
            stopSelf()
            return START_NOT_STICKY
        }

        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (projection === created) {
                        projection = null
                        PortalHub.detach("projection", created)
                        stopSelf()
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                PortalHub.emit("projection.resize", intArrayOf(width, height))
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                PortalHub.emit("projection.visibility", isVisible)
            }
        }, main)

        projection = created
        PortalHub.attach("projection", created)
        return START_NOT_STICKY
    }

    fun getProjection(): MediaProjection? = projection
    fun stopProjection() { releaseProjection(true); stopSelf() }

    private fun releaseProjection(stop: Boolean) {
        val current = projection ?: return
        projection = null
        PortalHub.detach("projection", current)
        if (stop) runCatching { current.stop() }
    }

    override fun onDestroy() {
        releaseProjection(true)
        PortalHub.detach("projectionService", this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_CONSENT = "consent"
        private const val NOTIFICATION_ID = 1102
    }
}

class GlobalService : AccessibilityService() { companion object { @JvmField var instance: GlobalService? = null; @JvmField var seqtokill = ArrayList<Int>().apply { add(25); add(24); add(25); add(24) }; @JvmField var seqTimeout = 5000L }; private var step = 0; private var startTime = 0L; override fun onServiceConnected() { super.onServiceConnected(); instance = this; PortalHub.attach("accessibility", this) }; override fun onAccessibilityEvent(e: AccessibilityEvent?) {}; override fun onInterrupt() {}; override fun onDestroy() { PortalHub.detach("accessibility", this); instance = null; super.onDestroy() }; override fun onKeyEvent(e: KeyEvent): Boolean { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && seqtokill.isNotEmpty()) { val now = System.currentTimeMillis(); if (now - startTime > seqTimeout) step = 0; if (step == 0) startTime = now; if (step < seqtokill.size && e.keyCode == seqtokill[step]) { step++; if (step == seqtokill.size) { disableSelf(); step = 0 } } else { step = if (e.keyCode == seqtokill[0]) 1 else 0; if (step == 1) startTime = now } }; return super.onKeyEvent(e) }; fun tap(x: Float, y: Float) { val path = Path(); path.moveTo(x, y); val builder = GestureDescription.Builder(); builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50)); dispatchGesture(builder.build(), null, null) }; fun readScreen(): String { val root = rootInActiveWindow ?: return "[]"; val list = ArrayList<String>(); traverse(root, list); return list.toString() }; fun traverse(n: AccessibilityNodeInfo?, l: ArrayList<String>) { if (n == null) return; if (n.text != null && n.text.isNotEmpty()) { val r = Rect(); n.getBoundsInScreen(r); l.add(n.text.toString() + "|" + r.centerX() + "|" + r.centerY()) }; for (i in 0 until n.childCount) { traverse(n.getChild(i), l) } } }
