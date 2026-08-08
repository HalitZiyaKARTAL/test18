package a.htmlapprealizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Service
import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
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
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.*
import kotlin.jvm.JvmField

class Main : Activity() {
    val H = ArrayList<Any?>()
    val MC = HashMap<String, List<Method>>()
    val FC = HashMap<String, List<Field>>()
    val CC = HashMap<String, List<Constructor<*>>>()
    data class Proc(val p: Process, val out: ByteArrayOutputStream = ByteArrayOutputStream(), val err: ByteArrayOutputStream = ByteArrayOutputStream())
    data class FramePipe(val id: Int, val dir: java.io.File, @Volatile var run: Boolean = true, var th: Thread? = null)
    val PR = HashMap<Int, Proc>()
    val FP = HashMap<Int, FramePipe>()
    var prid = 1
    var fpid = 1
    val L = Array(3) { HashSet<String>() } // 0=Blacklist, 1=Graylist, 2=Whitelist
    val D = HashSet<String>() // Domain whitelist
    lateinit var root: FrameLayout
    lateinit var w: WebView
    lateinit var b: Button
    val pages = HashMap<String, WebView>()
    var m = 0 // Mode: 0=Visible, 1=Focus, 2=Open, 3=Perma
    var s = true // Sandbox
    var A = false // Auth status
    var NET = false // Net cut
    var PW = "" // Password
    var tts: TextToSpeech? = null
    var sr: SpeechRecognizer? = null
    val SL = HashMap<Int, android.hardware.SensorEventListener>()
    var sid = 1
    var mpCb = 0
    var mpCode = 0
    var mpData: Intent? = null
    var mp: MediaProjection? = null
    var ir: ImageReader? = null
    var vd: VirtualDisplay? = null
    @Volatile var mpStream = false
    var mpStreamThread: Thread? = null
    var lastIntent = "{}"
    val P: SharedPreferences by lazy { getSharedPreferences("Z", Context.MODE_PRIVATE) }

    fun S(k: String, v: Any) = with(P.edit()) {
        if (v is Boolean) putBoolean(k, v) else if (v is Int) putInt(k, v) else putString(k, v.toString())
        apply()
    }

    var editable: String? = null // set by local storage, reflection, or HTML
    var html = editable ?: """<!DOCTYPE html><title>App Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable="plaintext-only" oninput="c.textContent=i.textContent.length"></div><button onclick="var code=i.textContent;document.open();document.write(code);document.close()">Realize (<b id=c>0</b> chars)</button>""" // open fallback literal stays right here
    fun bootHtml() = html
    fun loadBoot() = runOnUiThread { w.loadDataWithBaseURL(null, bootHtml(), "text/html", "utf-8", null) }

    fun mkWeb(id: String): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        if (Build.VERSION.SDK_INT >= 21) settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        WebView.setWebContentsDebuggingEnabled(true)
        addJavascriptInterface(this@Main, "mirror")
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) { runOnUiThread { request?.grant(request.resources) } }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, true, false) }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val nid = "page" + System.currentTimeMillis()
                val nw = pageMake(nid, true)
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = nw
                resultMsg?.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { A = false }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url; val h = url?.host?.lowercase()
                val allowed = !NET || url?.scheme in listOf("file", "data", "about", "blob") || (h != null && D.any { it.isNotEmpty() && (h == it || h.endsWith(".$it")) })
                return if (allowed) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    fun pageMake(id: String, visible: Boolean): WebView {
        pages[id]?.let { return it }
        val v = mkWeb(id); pages[id] = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) pageShow(id)
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        H.add(this) // H[0] is always the Activity Context
        (0..2).forEach { i ->
            P.getString("$i", "")!!.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { L[i].add(it) }
        }
        m = P.all["M"].toString().toIntOrNull() ?: 0
        s = P.getBoolean("S", true)
        NET = P.getBoolean("N", true)
        PW = P.getString("W", "") ?: ""
        P.getString("D", "")!!.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { D.add(it) }
        editable = P.getString("BOOT_HTML", null); html = editable ?: """<!DOCTYPE html><title>App Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable="plaintext-only" oninput="c.textContent=i.textContent.length"></div><button onclick="var code=i.textContent;document.open();document.write(code);document.close()">Realize (<b id=c>0</b> chars)</button>"""

        val f = FrameLayout(this); root = f
        b = Button(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(120, 120, Gravity.TOP or Gravity.END)
            setOnClickListener { E() }
        }
        w = mkWeb("main")
        pages["main"] = w
        f.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(b)
        f.setOnApplyWindowInsetsListener { _, i ->
            val p = b.layoutParams as FrameLayout.LayoutParams
            val d = maxOf(p.width, p.height)
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                val dm = resources.displayMetrics
                Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
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

        // Initial HTML Load (modifiable boot string, default kept minimal)
        loadBoot()
        Handler(Looper.getMainLooper()).postDelayed({ procIntent(intent) }, 700)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        procIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7717) {
            mpCode = resultCode; mpData = data
            cb(mpCb, JSONObject().put("code", resultCode).put("data", if (data == null) "" else ret(data)).toString())
        }
    }

    override fun onResume() {
        super.onResume()
        if (m == 1) v(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { sr?.destroy() }; sr = null
        runCatching { tts?.shutdown() }; tts = null
        runCatching { val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager; SL.values.forEach { sm.unregisterListener(it) }; SL.clear() }
        runCatching { framePipeStop(0) }; runCatching { mpStreamStop() }; runCatching { mpStop() }
        runCatching { synchronized(PR) { PR.values.forEach { it.p.destroyForcibly() }; PR.clear() } }
        runCatching { pages.values.toList().forEach { it.destroy() }; pages.clear() }
        H.clear()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            b.visibility = View.VISIBLE
            Handler(Looper.getMainLooper()).postDelayed({ v(m) }, 3000)
            return false // Don't change volume
        }
        return super.onKeyDown(keyCode, event)
    }

    fun v(n: Int) {
        m = n
        if (m == 3) S("M", 3) else P.edit().remove("M").apply()
        b.visibility = if (m == 0) View.VISIBLE else View.GONE
    }

    fun ok() = !s && (PW.isEmpty() || A)

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
                "unhide/hide settings:till next focus/open/perma".split(Regex("(?<=[a-z])(?=[/:])|(?<=[/:])(?=[a-z])")).forEach { w ->
                    if (map.containsKey(w)) {
                        val start = length
                        append(" $w ")
                        setSpan(object : ClickableSpan() {
                            override fun onClick(view: View) = v(map[w]!!)
                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = Color.WHITE; ds.bgColor = Color.DKGRAY; ds.isUnderlineText = false
                            }
                        }, start, length, 33)
                    } else append(w)
                }
            }
        }
        l.addView(tv)

        U("Cut Internet: ${if (NET) "ON" else "OFF"}", if (NET) Color.RED else Color.GREEN, "Exceptions", D.joinToString(","), {
            NET = !NET; S("N", NET)
            it.text = "Cut Internet: ${if (NET) "ON" else "OFF"}"
            it.setTextColor(if (NET) Color.RED else Color.GREEN)
        }) { D.clear(); D.addAll(it.split(",").map { i -> i.trim().lowercase() }.filter { i -> i.isNotEmpty() }); S("D", D.joinToString(",")) }

        val n = arrayOf("Blacklist", "Graylist", "Whitelist")
        (0..2).forEach { i ->
            l.addView(TextView(this).apply { text = n[i]; setTextColor(Color.WHITE) })
            val e = EditText(this).apply {
                setText(L[i].joinToString(","))
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.DKGRAY)
            }
            T(e) { L[i].clear(); L[i].addAll(it.split(",").map { x -> x.trim().lowercase() }.filter { x -> x.isNotEmpty() }); S("$i", L[i].joinToString(",")) }
            l.addView(e)
        }
        AlertDialog.Builder(this).setView(sv).show()
    }

    fun K(i: Int, s: String) = L[i].any { it.equals("ALL", true) || (it.isNotBlank() && s.contains(it.trim().lowercase())) }

    fun C(n: String): Class<*>? = try {
        val x = n.trim()
        val prim = mapOf(
            "boolean" to java.lang.Boolean.TYPE, "byte" to java.lang.Byte.TYPE,
            "short" to java.lang.Short.TYPE, "char" to java.lang.Character.TYPE,
            "int" to java.lang.Integer.TYPE, "long" to java.lang.Long.TYPE,
            "float" to java.lang.Float.TYPE, "double" to java.lang.Double.TYPE,
            "void" to java.lang.Void.TYPE
        )
        when {
            prim.containsKey(x) -> prim[x]
            x.endsWith("[]") -> C(x.removeSuffix("[]"))?.let { java.lang.reflect.Array.newInstance(it, 0).javaClass }
            else -> Class.forName(x)
        }
    } catch (e: Exception) { null }

    fun hp(p: Int) = synchronized(H) { H.getOrNull(p) }
    fun keep(o: Any?): String = synchronized(H) { H.add(o); "P${H.size - 1}" }
    fun mainThread() = Looper.myLooper() == Looper.getMainLooper()

    fun exUi(t: Any?, n: String, a: JSONArray, y: Int): String {
        if (mainThread()) return Ex(t, n, a, y)
        var r = "E:TIME"
        val latch = CountDownLatch(1)
        runOnUiThread {
            r = try { Ex(t, n, a, y) } catch (e: Exception) { "E:$e" }
            latch.countDown()
        }
        return if (latch.await(30, TimeUnit.SECONDS)) r else "E:TIME"
    }

    fun R(t: Any?, n: String, j: String, y: Int, cI: Int): String {
        if (!ok()) return "E:SEC"
        try {
            val c = if (t is Class<*>) t else t?.javaClass ?: return "E:NUL"
            val a = JSONArray(j)
            val sig = "$n ${c.name}".lowercase()
            if (y < 4 && K(0, sig)) return "E:BL"

            // Ask once for unknown public reflection calls. Async calls receive W:first, then final via cb.
            // Sync JS calls run on WebView bridge thread, so they can wait for the dialog result.
            val g = if (y > 3 || K(1, sig)) null else if (K(2, sig)) "ALL" else L[2].find { it.isNotBlank() && sig.contains(it.trim().lowercase()) } ?: if (L[0].isEmpty() && L[1].isEmpty()) "ALL" else null
            if (g != null) {
                val waitId = (System.currentTimeMillis() % 999).toInt()
                if (cI <= 0 && !mainThread()) {
                    var r = "E:CANCEL"
                    val latch = CountDownLatch(1)
                    runOnUiThread {
                        val d = AlertDialog.Builder(this).setTitle("REQ:$sig")
                            .setPositiveButton("1") { _, _ -> r = exUi(t, n, a, y); latch.countDown() }
                            .setNeutralButton("OK") { _, _ -> L[1].add(g); S("1", L[1].joinToString(",")); r = exUi(t, n, a, y); latch.countDown() }
                            .setNegativeButton("NO") { _, _ -> L[0].add(g); S("0", L[0].joinToString(",")); r = "E:BL"; latch.countDown() }
                            .create()
                        d.setOnCancelListener { r = "E:CANCEL"; latch.countDown() }
                        d.show()
                    }
                    return if (latch.await(300, TimeUnit.SECONDS)) r else "E:TIME"
                }
                runOnUiThread {
                    val d = AlertDialog.Builder(this).setTitle("REQ:$sig")
                        .setPositiveButton("1") { _, _ -> cb(cI, exUi(t, n, a, y)) }
                        .setNeutralButton("OK") { _, _ -> L[1].add(g); S("1", L[1].joinToString(",")); cb(cI, exUi(t, n, a, y)) }
                        .setNegativeButton("NO") { _, _ -> L[0].add(g); S("0", L[0].joinToString(",")); cb(cI, "E:BL") }
                        .create()
                    d.setOnCancelListener { cb(cI, "E:CANCEL") }
                    d.show()
                }
                return "W:$waitId"
            }
            return exUi(t, n, a, y)
        } catch (e: Exception) { return "E:$e" }
    }

    fun acc(x: java.lang.reflect.AccessibleObject) = runCatching { x.isAccessible = true }.isSuccess

    fun fieldsOf(c: Class<*>): List<Field> = synchronized(FC) { FC.getOrPut(c.name) {
        val r = ArrayList<Field>(); val seen = HashSet<String>(); var k: Class<*>? = c
        while (k != null) { for (f in k.declaredFields) if (seen.add(f.name)) { acc(f); r.add(f) }; k = k.superclass }
        for (f in c.fields) if (seen.add(f.name)) { acc(f); r.add(f) }
        r
    } }

    fun fieldOf(c: Class<*>, n: String): Field? = fieldsOf(c).firstOrNull { it.name == n }

    fun methodsOf(c: Class<*>): List<Method> = synchronized(MC) { MC.getOrPut(c.name) {
        val r = ArrayList<Method>(); val seen = HashSet<String>(); var k: Class<*>? = c
        fun key(m: Method) = m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"
        while (k != null) { for (m in k.declaredMethods) if (seen.add(key(m))) { acc(m); r.add(m) }; k = k.superclass }
        for (m in c.methods) if (seen.add(key(m))) { acc(m); r.add(m) }
        r
    } }

    fun consOf(c: Class<*>): List<Constructor<*>> = synchronized(CC) { CC.getOrPut(c.name) { c.declaredConstructors.toList().onEach { acc(it) } } }

    fun Ex(t: Any?, n: String, a: JSONArray, y: Int): String {
        try {
            val c = if (t is Class<*>) t else t!!.javaClass
            val l = a.length()
            if (y == 2) return ret((fieldOf(c, n) ?: return "E:FIELD").get(if (t is Class<*>) null else t)) // Get field, including private/inherited where allowed
            if (y == 3) { val f = fieldOf(c, n) ?: return "E:FIELD"; f.set(if (t is Class<*>) null else t, cv(a.get(0), f.type)); return "OK" } // Set field, including private/inherited where allowed
            if (y == 4) return ret(java.lang.reflect.Array.newInstance(c, a.getInt(0))) // Array new

            // Proxy for Java interface callbacks into JS: window.onL(id, methodName, encodedArgsJsonString)
            val proxyId = if (y == 5) a.getInt(0) else 0
            if (y == 5) return ret(Proxy.newProxyInstance(c.classLoader, arrayOf(c)) { _, m, r ->
                if (m.declaringClass == Any::class.java) {
                    return@newProxyInstance when (m.name) {
                        "toString" -> "Proxy#$proxyId:${c.name}"
                        "hashCode" -> proxyId
                        "equals" -> false
                        else -> null
                    }
                }
                val g = r?.map { org.json.JSONObject.quote(ret(it)) }?.joinToString(",") ?: ""
                runOnUiThread { w.evaluateJavascript("window.onL($proxyId,${org.json.JSONObject.quote(m.name)},${org.json.JSONObject.quote("[$g]")})", null) }
                def(m.returnType)
            })

            var firstErr: Exception? = null
            if (y == 1) {
                for (con in consOf(c)) {
                    if (con.parameterTypes.size != l) continue
                    try {
                        val v = Array(l) { i -> cv(a.get(i), con.parameterTypes[i]) }
                        return ret(con.newInstance(*v))
                    } catch (e: Exception) { if (firstErr == null) firstErr = e }
                }
            } else {
                for (m in methodsOf(c)) {
                    if (m.name != n || m.parameterTypes.size != l) continue
                    try {
                        val v = Array(l) { i -> cv(a.get(i), m.parameterTypes[i]) }
                        return ret(m.invoke(if (t is Class<*>) null else t, *v))
                    } catch (e: Exception) { if (firstErr == null) firstErr = e }
                }
            }
            return if (firstErr != null) "E:SIG:${firstErr}" else "E:SIG"
        } catch (e: Exception) {
            return if (e is InvocationTargetException) "E:${e.targetException}" else "E:$e"
        }
    }

    fun boxed(t: Class<*>) = when (t) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> t
    }

    fun def(t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> null
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun cv(o: Any?, t: Class<*>): Any? {
        if (o === org.json.JSONObject.NULL) return null
        if (t.isArray && o is JSONArray) {
            val comp = t.componentType
            val arr = java.lang.reflect.Array.newInstance(comp, o.length())
            for (i in 0 until o.length()) java.lang.reflect.Array.set(arr, i, cv(o.get(i), comp))
            return arr
        }
        if (o is String && o.startsWith("P")) return hp(o.substring(1).toIntOrNull() ?: -1)
        val b = boxed(t)
        if (o == null) return null
        if (b.isInstance(o)) return o
        if (b == String::class.java || b == CharSequence::class.java) return o.toString()
        if (b == java.lang.Boolean::class.java) return if (o is Boolean) o else o.toString().toBoolean()
        if (b == java.lang.Character::class.java) return o.toString().firstOrNull() ?: 0.toChar()
        if (o is Number) return when (b) {
            java.lang.Byte::class.java -> o.toByte()
            java.lang.Short::class.java -> o.toShort()
            java.lang.Integer::class.java -> o.toInt()
            java.lang.Long::class.java -> o.toLong()
            java.lang.Float::class.java -> o.toFloat()
            java.lang.Double::class.java -> o.toDouble()
            else -> o
        }
        if (o is String) {
            when (b) {
                java.lang.Byte::class.java -> return o.toByte()
                java.lang.Short::class.java -> return o.toShort()
                java.lang.Integer::class.java -> return o.toInt()
                java.lang.Long::class.java -> return o.toLong()
                java.lang.Float::class.java -> return o.toFloat()
                java.lang.Double::class.java -> return o.toDouble()
                Class::class.java -> return C(o)
            }
            if (b.isEnum) return b.enumConstants.firstOrNull { (it as Enum<*>).name == o }
        }
        return o
    }

    fun ret(o: Any?) = if (o == null || o is Unit) "V" else if (o is Number || o is String || o is Boolean || o is Char) "V$o" else keep(o)

    fun cb(i: Int, r: String) = if (i > 0) runOnUiThread { w.evaluateJavascript("window.onC($i,${org.json.JSONObject.quote(r)})", null) } else Unit

    fun gz(v: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(v.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun ungz(v: String): String {
        val b = Base64.decode(v, Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(b)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun jput(a: JSONArray, v: Any?) { a.put(if (v == null) JSONObject.NULL else v) }

    fun cmd(o: JSONObject): String {
        val op = o.optString("op")
        return when (op) {
            "c" -> c(o.optString("n"))
            "cl" -> cl()
            "c2" -> c2(o.optInt("loader"), o.optString("n"))
            "new", "n" -> n(o.optString("c"), o.optString("j", "[]"))
            "x" -> x(o.optInt("p"), o.optString("n"), o.optString("j", "[]"))
            "u" -> { u(o.optInt("p"), o.optString("n"), o.optString("j", "[]"), o.optInt("cb")); "OK" }
            "g" -> g(o.optInt("p"), o.optString("f"))
            "s" -> s(o.optInt("p"), o.optString("f"), o.optString("v", "null"))
            "a" -> a(o.optString("t"), o.optInt("l"))
            "p" -> p(o.optString("t"), o.optInt("id"))
            "svc" -> svc()
            "str" -> str(o.optString("v"))
            "get" -> get(o.optInt("p"))
            "type" -> "V" + type(o.optInt("p"))
            "del" -> { del(o.optInt("p")); "OK" }
            "sleep" -> { Thread.sleep(o.optLong("ms", 0)); "OK" }
            "toast" -> toast(o.optString("v"))
            "vibe" -> vibe(o.optLong("ms", 80))
            "clip" -> clip(o.optString("v"))
            "getClip" -> getClip()
            "save" -> save(o.optString("k"), o.optString("v"), o.optBoolean("zip", false))
            "load" -> load(o.optString("k"), o.optBoolean("zip", false))
            "rm" -> rm(o.optString("k"))
            "keys" -> keys(o.optString("prefix", ""))
            "zip" -> "V" + gz(o.optString("v"))
            "unzip" -> "V" + ungz(o.optString("v"))
            "events" -> "V" + (GlobalService.instance?.events(o.optInt("max", 100)) ?: "[]")
            "now" -> "V" + System.currentTimeMillis()
            "later" -> later(o.optLong("ms"), o.optInt("cb"), o.optString("v", "OK"))
            "sh" -> sh(o.optString("cmd"), o.optString("in", ""), o.optLong("ms", 10000))
            "sys" -> "V" + sysRun(o.optString("mode", "sh"), o.optString("cmd"), o.optString("in", ""), o.optLong("ms", 10000))
            "page" -> pageOpen(o.optString("id"), o.optString("src"), o.optString("kind", "html"), o.optBoolean("visible", true))
            "pageEval" -> pageEval(o.optString("id"), o.optString("js"), o.optInt("cb"))
            "pageShow" -> pageShow(o.optString("id"))
            "pageClose" -> pageClose(o.optString("id"))
            "usb" -> usb()
            "usbList" -> usbList()
            "kc" -> kc(o.optString("n"))
            "kco" -> kco(o.optInt("p"))
            "kinfo" -> kinfo(o.optInt("p"))
            "jinfo" -> jinfo(o.optInt("p"))
            "q" -> q(o.optString("v"))
            "dex" -> dex(o.optString("path"), o.optString("opt", ""), o.optString("lib", ""))
            "lib" -> lib(o.optString("v"), o.optBoolean("abs", true))
            "sensor" -> sensor(o.optInt("type"), o.optInt("delay", 1), o.optInt("cb"))
            "sensorStop" -> sensorStop(o.optInt("id"))
            "mp" -> mediaProjection(o.optInt("cb"))
            "mpStart" -> mpStart(o.optInt("w", 0), o.optInt("h", 0), o.optInt("dpi", 0))
            "mpFrame" -> mpFrameEx(o.optInt("l", -1), o.optInt("t", -1), o.optInt("r", -1), o.optInt("b", -1), o.optInt("maxW", 640), o.optInt("quality", 70), o.optString("format", "jpg"))
            "mpStream" -> mpStreamStart(o.toString(), o.optInt("cb"))
            "mpStreamStop" -> mpStreamStop()
            "framePipe" -> framePipeStart(JSONObject().put("l", a.optInt(1, -1)).put("t", a.optInt(2, -1)).put("r", a.optInt(3, -1)).put("b", a.optInt(4, -1)).put("maxW", a.optInt(5, 320)).put("quality", a.optInt(6, 60)).put("format", a.optString(7, "jpg")).put("fps", a.optInt(8, 10)).put("slots", a.optInt(9, 3)).toString(), a.optInt(10))
            "framePipeStop" -> framePipeStop(a.optInt(1, 0))
            "framePipeInfo" -> framePipeInfo(a.optInt(1))
            "mpStop" -> mpStop()
            "mpData" -> if (mpData == null) "E:NUL" else ret(mpData)
            "intent" -> startEx(o.optString("action"), o.optString("uri", ""), o.optString("mime", ""), o.optString("pkg", ""), o.optString("cls", ""), o.optString("extras", "{}"), o.optString("cats", ""), o.optInt("flags", 0))
            "resolve" -> resolveIntent(o.optString("action"), o.optString("uri", ""), o.optString("mime", ""), o.optString("pkg", ""), o.optString("cls", ""), o.optString("extras", "{}"), o.optString("cats", ""), o.optInt("flags", 0))
            "canIntent" -> canIntent(o.optString("action"), o.optString("uri", ""), o.optString("mime", ""), o.optString("pkg", ""), o.optString("cls", ""), o.optString("extras", "{}"), o.optString("cats", ""), o.optInt("flags", 0))
            "open" -> open(o.optString("uri"))
            "openIn" -> openIn(o.optString("pkg"), o.optString("uri"))
            "launch" -> launch(o.optString("pkg"))
            "waitFor" -> waitFor(o.optString("q"), o.optLong("ms", 5000))
            "playClick" -> playClick(o.optString("q"), o.optLong("ms", 5000))
            "share" -> share(o.optString("text"), o.optString("mime", "text/plain"))
            "choose" -> choose(o.optString("action"), o.optString("uri", ""), o.optString("mime", ""), o.optString("extras", "{}"))
            "alarm" -> alarm(o.optLong("ms"), o.optString("v", ""))
            "notify" -> notify(o.optInt("id", 1), o.optString("title"), o.optString("text"))
            "fr" -> fr(o.optString("name"), o.optBoolean("zip", false))
            "fw" -> fw(o.optString("name"), o.optString("v"), o.optBoolean("zip", false))
            "fg" -> fg(o.optBoolean("on", true))
            "notis" -> "V" + NotifService.events(o.optInt("max", 100))
            "boot" -> boot(o.optString("html", ""), o.optBoolean("reload", false))
            "fetch" -> fetch(o.optString("url"), o.optString("method", "GET"), o.optString("headers", "{}"), o.optString("body", ""), o.optInt("timeout", 15000), o.optBoolean("clean", true), o.optBoolean("sensitive", false), o.optInt("max", 2097152))
            "screenshot" -> screenshot(o.optInt("l", -1), o.optInt("t", -1), o.optInt("r", -1), o.optInt("b", -1), o.optInt("maxW", 640), o.optInt("quality", 70))
            else -> "E:OP"
        }
    }

    fun argJson(a: JSONArray, i: Int): String = if (i >= a.length() || a.isNull(i)) "[]" else when (val v = a.get(i)) {
        is JSONArray -> v.toString()
        is JSONObject -> v.toString()
        else -> v.toString()
    }

    // Condensed op form for high-frequency loops: ["x",ptr,"method",[args...]], ["tap",x,y], ["save",k,v,zip]
    fun cmdA(a: JSONArray): String {
        val op = a.optString(0)
        return when (op) {
            "c" -> c(a.optString(1))
            "cl" -> cl()
            "c2" -> c2(a.optInt(1), a.optString(2))
            "n", "new" -> n(a.optString(1), argJson(a, 2))
            "x" -> x(a.optInt(1), a.optString(2), argJson(a, 3))
            "u" -> { u(a.optInt(1), a.optString(2), argJson(a, 3), a.optInt(4)); "OK" }
            "g" -> g(a.optInt(1), a.optString(2))
            "s" -> s(a.optInt(1), a.optString(2), argJson(a, 3).removeSurrounding("[", "]"))
            "a" -> this.a(a.optString(1), a.optInt(2))
            "p" -> p(a.optString(1), a.optInt(2))
            "svc" -> svc()
            "tap" -> GlobalService.instance?.tap(a.optDouble(1).toFloat(), a.optDouble(2).toFloat())?.let { "V$it" } ?: "E:SVC"
            "swipe" -> GlobalService.instance?.swipe(a.optDouble(1).toFloat(), a.optDouble(2).toFloat(), a.optDouble(3).toFloat(), a.optDouble(4).toFloat(), a.optLong(5, 120))?.let { "V$it" } ?: "E:SVC"
            "read" -> "V" + (GlobalService.instance?.readScreen() ?: "[]")
            "events" -> "V" + (GlobalService.instance?.events(a.optInt(1, 100)) ?: "[]")
            "clip" -> clip(a.optString(1))
            "getClip" -> getClip()
            "save" -> save(a.optString(1), a.optString(2), a.optBoolean(3, false))
            "load" -> load(a.optString(1), a.optBoolean(2, false))
            "sh" -> sh(a.optString(1), a.optString(2, ""), a.optLong(3, 10000))
            "sys" -> "V" + sysRun(a.optString(1, "sh"), a.optString(2), a.optString(3, ""), a.optLong(4, 10000))
            "page" -> pageOpen(a.optString(1), a.optString(2), a.optString(3, "html"), a.optBoolean(4, true))
            "pageEval" -> pageEval(a.optString(1), a.optString(2), a.optInt(3))
            "pageShow" -> pageShow(a.optString(1))
            "pageClose" -> pageClose(a.optString(1))
            "usb" -> usb()
            "usbList" -> usbList()
            "kc" -> kc(a.optString(1))
            "kco" -> kco(a.optInt(1))
            "kinfo" -> kinfo(a.optInt(1))
            "jinfo" -> jinfo(a.optInt(1))
            "q" -> q(a.optString(1))
            "dex" -> dex(a.optString(1), a.optString(2, ""), a.optString(3, ""))
            "lib" -> lib(a.optString(1), a.optBoolean(2, true))
            "sensor" -> sensor(a.optInt(1), a.optInt(2, 1), a.optInt(3))
            "sensorStop" -> sensorStop(a.optInt(1))
            "mp" -> mediaProjection(a.optInt(1))
            "mpStart" -> mpStart(a.optInt(1, 0), a.optInt(2, 0), a.optInt(3, 0))
            "mpFrame" -> mpFrameEx(a.optInt(1, -1), a.optInt(2, -1), a.optInt(3, -1), a.optInt(4, -1), a.optInt(5, 640), a.optInt(6, 70), a.optString(7, "jpg"))
            "mpStream" -> mpStreamStart(JSONObject().put("l", a.optInt(1, -1)).put("t", a.optInt(2, -1)).put("r", a.optInt(3, -1)).put("b", a.optInt(4, -1)).put("maxW", a.optInt(5, 320)).put("quality", a.optInt(6, 60)).put("format", a.optString(7, "jpg")).put("fps", a.optInt(8, 10)).toString(), a.optInt(9))
            "mpStreamStop" -> mpStreamStop()
            "framePipe" -> framePipeStart(JSONObject().put("l", a.optInt(1, -1)).put("t", a.optInt(2, -1)).put("r", a.optInt(3, -1)).put("b", a.optInt(4, -1)).put("maxW", a.optInt(5, 320)).put("quality", a.optInt(6, 60)).put("format", a.optString(7, "jpg")).put("fps", a.optInt(8, 10)).put("slots", a.optInt(9, 3)).toString(), a.optInt(10))
            "framePipeStop" -> framePipeStop(a.optInt(1, 0))
            "framePipeInfo" -> framePipeInfo(a.optInt(1))
            "mpStop" -> mpStop()
            "intent" -> startEx(a.optString(1), a.optString(2, ""), a.optString(3, ""), a.optString(4, ""), a.optString(5, ""), a.optString(6, "{}"), a.optString(7, ""), a.optInt(8, 0))
            "resolve" -> resolveIntent(a.optString(1), a.optString(2, ""), a.optString(3, ""), a.optString(4, ""), a.optString(5, ""), a.optString(6, "{}"), a.optString(7, ""), a.optInt(8, 0))
            "open" -> open(a.optString(1))
            "openIn" -> openIn(a.optString(1), a.optString(2))
            "launch" -> launch(a.optString(1))
            "waitFor" -> waitFor(a.optString(1), a.optLong(2, 5000))
            "playClick" -> playClick(a.optString(1), a.optLong(2, 5000))
            "share" -> share(a.optString(1), a.optString(2, "text/plain"))
            "choose" -> choose(a.optString(1), a.optString(2, ""), a.optString(3, ""), a.optString(4, "{}"))
            "alarm" -> alarm(a.optLong(1), a.optString(2, ""))
            "notify" -> notify(a.optInt(1, 1), a.optString(2), a.optString(3))
            "fr" -> fr(a.optString(1), a.optBoolean(2, false))
            "fw" -> fw(a.optString(1), a.optString(2), a.optBoolean(3, false))
            "fg" -> fg(a.optBoolean(1, true))
            "notis" -> "V" + NotifService.events(a.optInt(1, 100))
            "fetch" -> fetch(a.optString(1), a.optString(2, "GET"), a.optString(3, "{}"), a.optString(4, ""), a.optInt(5, 15000), a.optBoolean(6, true), a.optBoolean(7, false), a.optInt(8, 2097152))
            "screenshot" -> screenshot(a.optInt(1, -1), a.optInt(2, -1), a.optInt(3, -1), a.optInt(4, -1), a.optInt(5, 640), a.optInt(6, 70))
            else -> cmd(JSONObject().put("op", op))
        }
    }

    fun urlAllowed(u: String): Boolean = try {
        val uri = android.net.Uri.parse(u); val h = uri.host?.lowercase()
        !NET || uri.scheme in listOf("file", "data", "about", "blob") || (h != null && D.any { it.isNotEmpty() && (h == it || h.endsWith(".$it")) })
    } catch (_: Exception) { false }

    fun cleanUrl(u: String): String = try {
        val uri = android.net.Uri.parse(u); val drop = setOf("fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "igshid", "si")
        val b = uri.buildUpon().clearQuery(); for (k in uri.queryParameterNames) if (!k.startsWith("utm_") && !drop.contains(k)) uri.getQueryParameters(k).forEach { b.appendQueryParameter(k, it) }
        b.build().toString()
    } catch (_: Exception) { u }

    fun netFetch(url0: String, method: String, headers: String, body: String, timeout: Int, clean: Boolean, sensitive: Boolean, max: Int): String {
        return try {
        if (!ok()) return "E:SEC"
        val url = if (clean) cleanUrl(url0) else url0
        if (!urlAllowed(url)) return "E:NET"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.ifBlank { "GET" }.uppercase(); connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
            val hs = if (headers.isBlank()) JSONObject() else JSONObject(headers)
            for (k in hs.keys()) setRequestProperty(k, hs.get(k).toString())
            if (body.isNotEmpty()) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
        }
        val code = c.responseCode; val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.readBytes()?.let { if (it.size > max) it.copyOf(max) else it } ?: ByteArray(0)
        val hs = JSONObject(); c.headerFields?.forEach { (k, v) -> if (k != null) hs.put(k, JSONArray(v)) }
        JSONObject().put("url", url).put("code", code).put("type", c.contentType ?: "").put("headers", hs)
            .put("text", runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""))
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
    } catch (e: Exception) { "E:$e" }
    }

    fun shell(cmd: String, input: String, timeoutMs: Long): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
        if (input.isNotEmpty()) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val t1 = Thread { runCatching { p.inputStream.copyTo(out) } }
        val t2 = Thread { runCatching { p.errorStream.copyTo(err) } }
        t1.start(); t2.start()
        val deadline = System.currentTimeMillis() + maxOf(1L, timeoutMs)
        var ok = false; var code = -1
        while (System.currentTimeMillis() < deadline) {
            try { code = p.exitValue(); ok = true; break } catch (_: IllegalThreadStateException) { Thread.sleep(10) }
        }
        if (!ok) p.destroyForcibly() else code = p.exitValue()
        t1.join(200); t2.join(200)
        return JSONObject().put("code", code).put("timeout", !ok)
            .put("out", out.toString("UTF-8")).put("err", err.toString("UTF-8")).toString()
    }

    fun procStart(cmd: String, cwd: String, env: JSONObject?, input: String): String = try {
        if (!ok()) "E:SEC" else {
            val pb = ProcessBuilder("sh", "-c", cmd)
            if (cwd.isNotBlank()) pb.directory(java.io.File(cwd))
            env?.let { for (k in it.keys()) pb.environment()[k] = it.get(k).toString() }
            val pr = Proc(pb.start()); val id = synchronized(PR) { val i = prid++; PR[i] = pr; i }
            Thread { runCatching { pr.p.inputStream.copyTo(pr.out) } }.start()
            Thread { runCatching { pr.p.errorStream.copyTo(pr.err) } }.start()
            if (input.isNotEmpty()) runCatching { pr.p.outputStream.write(input.toByteArray()); pr.p.outputStream.flush() }
            "V" + JSONObject().put("id", id).toString()
        }
    } catch (e: Exception) { "E:$e" }

    fun procWrite(id: Int, data: String, close: Boolean): String = synchronized(PR) { PR[id] }?.let {
        runCatching { it.p.outputStream.write(data.toByteArray()); it.p.outputStream.flush(); if (close) it.p.outputStream.close() }.fold({ "OK" }, { e -> "E:$e" })
    } ?: "E:NOPROC"

    fun procRead(id: Int, clear: Boolean): String = synchronized(PR) { PR[id] }?.let { pr ->
        val out: String; val err: String
        synchronized(pr.out) { out = pr.out.toString("UTF-8"); if (clear) pr.out.reset() }
        synchronized(pr.err) { err = pr.err.toString("UTF-8"); if (clear) pr.err.reset() }
        val alive = runCatching { pr.p.exitValue(); false }.getOrDefault(true)
        val code = if (alive) JSONObject.NULL else runCatching { pr.p.exitValue() }.getOrDefault(-1)
        "V" + JSONObject().put("alive", alive).put("code", code).put("out", out).put("err", err).toString()
    } ?: "E:NOPROC"

    fun sysRun(mode: String, cmd: String, input: String, timeoutMs: Long): String = when (mode.lowercase()) {
        "root", "su" -> shell("su -c ${org.json.JSONObject.quote(cmd)}", input, timeoutMs)
        "adb" -> shell("adb shell ${org.json.JSONObject.quote(cmd)}", input, timeoutMs)
        "sh", "shell", "" -> shell(cmd, input, timeoutMs)
        else -> shell(mode + " " + cmd, input, timeoutMs)
    }

    @JavascriptInterface fun pageOpen(id: String, src: String, kind: String, visible: Boolean): String = try {
        if (!ok()) "E:SEC" else {
            runOnUiThread {
                val v = pageMake(id.ifBlank { "page" + System.currentTimeMillis() }, visible)
                when (kind.lowercase()) { "url" -> v.loadUrl(src); "base64" -> v.loadData(String(Base64.decode(src, Base64.NO_WRAP)), "text/html", "utf-8"); else -> v.loadDataWithBaseURL(null, src, "text/html", "utf-8", null) }
            }
            "OK"
        }
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun pageEval(id: String, js: String, cbId: Int): String { if (!ok()) return "E:SEC"; val v = pages[id] ?: return "E:PAGE"; runOnUiThread { v.evaluateJavascript(js) { r -> cb(cbId, r ?: "") } }; return "OK" }
    @JavascriptInterface fun pageShow(id: String): String { if (!ok()) return "E:SEC"; val v = pages[id] ?: return "E:PAGE"; runOnUiThread { pages.values.forEach { it.visibility = View.GONE }; v.visibility = View.VISIBLE; w = v; b.bringToFront() }; return "OK" }
    @JavascriptInterface fun pageClose(id: String): String { if (!ok()) return "E:SEC"; val v = pages.remove(id) ?: return "E:PAGE"; runOnUiThread { root.removeView(v); v.destroy(); if (w == v) pages["main"]?.let { w = it; it.visibility = View.VISIBLE } }; return "OK" }
    @JavascriptInterface fun pageList(): String { if (!ok()) return "E:SEC"; val a = JSONArray(); pages.keys.forEach { a.put(it) }; return "V" + a.toString() }
    @JavascriptInterface fun usb() = if (ok()) ret(getSystemService(Context.USB_SERVICE)) else "E:SEC"
    @JavascriptInterface fun usbList(): String { if (!ok()) return "E:SEC"; val m = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager; val a = JSONArray(); m.deviceList.values.forEach { d -> a.put(JSONObject().put("name", d.deviceName).put("vendor", d.vendorId).put("product", d.productId).put("class", d.deviceClass).put("subclass", d.deviceSubclass).put("interfaces", d.interfaceCount)) }; return "V" + a.toString() }

    fun procKill(id: Int): String = synchronized(PR) { PR.remove(id) }?.let { it.p.destroyForcibly(); "OK" } ?: "E:NOPROC"

    fun klassOf(p: Int): Class<*>? = when (val o = hp(p)) { is Class<*> -> o; else -> o?.javaClass }

    // Batch cuts JS<->Java overhead for fast game/control loops. Accepts object ops or compact array ops.
    fun batchExec(cmds: String): String {
        if (!ok()) return "[\"E:SEC\"]"
        val input = JSONArray(cmds)
        val out = JSONArray()
        for (i in 0 until input.length()) {
            try {
                val v = input.get(i)
                jput(out, if (v is JSONArray) cmdA(v) else cmd(v as JSONObject))
            } catch (e: Exception) { jput(out, "E:$e") }
        }
        return out.toString()
    }

    fun info(c: Class<*>, kotlinExtra: Boolean): String {
        val mods = java.lang.reflect.Modifier.toString(c.modifiers)
        return JSONObject().apply {
            put("name", c.name); put("simple", c.simpleName ?: ""); put("pkg", c.`package`?.name ?: ""); put("mods", mods)
            put("super", c.superclass?.name ?: ""); put("interfaces", JSONArray(c.interfaces.map { it.name }))
            put("kotlin", c.getAnnotation(kotlin.Metadata::class.java) != null)
            put("fields", JSONArray(fieldsOf(c).map { java.lang.reflect.Modifier.toString(it.modifiers) + " " + it.name + ":" + it.type.name }))
            put("constructors", JSONArray(consOf(c).map { "(" + it.parameterTypes.joinToString(",") { t -> t.name } + ")" }))
            put("methods", JSONArray(methodsOf(c).map { java.lang.reflect.Modifier.toString(it.modifiers) + " " + it.name + "(" + it.parameterTypes.joinToString(",") { t -> t.name } + "):" + it.returnType.name }.distinct().take(1200)))
            put("inners", JSONArray(c.declaredClasses.map { it.name }))
            if (kotlinExtra) runCatching {
                val kc = c.kotlin
                put("kQualified", kc.qualifiedName ?: ""); put("kSimple", kc.simpleName ?: "")
                val kclasses = Class.forName("kotlin.reflect.full.KClasses")
                fun names(fn: String): JSONArray = runCatching {
                    val m = kclasses.methods.firstOrNull { it.name == fn && it.parameterTypes.size == 1 } ?: return@runCatching JSONArray()
                    val col = m.invoke(null, kc) as? Collection<*> ?: return@runCatching JSONArray()
                    JSONArray(col.map { it.toString() })
                }.getOrDefault(JSONArray())
                put("kMembers", names("getMembers")); put("kFunctions", names("getFunctions")); put("kProperties", names("getMemberProperties"))
            }
        }.toString()
    }

    // Ultra-condensed TSV mini-eval. One op per line: T\tx\ty, W\tx1\ty1\tx2\ty2\tms, X\tp\tname\tjson, C\tclass, H\tshell.
    @JavascriptInterface fun q(src: String): String {
        if (!ok()) return "[\"E:SEC\"]"
        val out = JSONArray()
        src.split('\n', ';').filter { it.isNotBlank() }.forEach { line ->
            try {
                val a = line.split('\t')
                val r = when (a[0]) {
                    "C" -> c(a.getOrElse(1){""})
                    "N" -> n(a.getOrElse(1){""}, a.getOrElse(2){"[]"})
                    "X" -> x(a.getOrElse(1){"0"}.toInt(), a.getOrElse(2){""}, a.getOrElse(3){"[]"})
                    "G" -> g(a.getOrElse(1){"0"}.toInt(), a.getOrElse(2){""})
                    "S" -> s(a.getOrElse(1){"0"}.toInt(), a.getOrElse(2){""}, a.getOrElse(3){"null"})
                    "T" -> GlobalService.instance?.tap(a[1].toFloat(), a[2].toFloat())?.let { "V$it" } ?: "E:SVC"
                    "W" -> GlobalService.instance?.swipe(a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), a.getOrElse(5){"120"}.toLong())?.let { "V$it" } ?: "E:SVC"
                    "R" -> "V" + (GlobalService.instance?.readScreen() ?: "[]")
                    "E" -> "V" + (GlobalService.instance?.events(a.getOrElse(1){"100"}.toInt()) ?: "[]")
                    "H" -> sh(line.substringAfter('\t', ""), "", 10000)
                    "D" -> dex(a.getOrElse(1){""}, a.getOrElse(2){""}, a.getOrElse(3){""})
                    "I" -> start(a.getOrElse(1){""}, a.getOrElse(2){""}, a.getOrElse(3){""}, a.getOrElse(4){""}, a.getOrElse(5){"{}"})
                    "A" -> alarm(a.getOrElse(1){"0"}.toLong(), a.getOrElse(2){""})
                    else -> "E:QOP"
                }
                jput(out, r)
            } catch (e: Exception) { jput(out, "E:$e") }
        }
        return out.toString()
    }


    // Logical condensed flow: readable commands, one per line. Use "command args" or "command | payload".
    @JavascriptInterface fun flow(src: String): String {
        if (!ok()) return "[\"E:SEC\"]"
        val out = JSONArray()
        src.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            try { jput(out, flowOne(line)) } catch (e: Exception) { jput(out, "E:$e") }
        }
        return out.toString()
    }

    fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun pipe(s: String) = s.split("|", limit = 2).map { it.trim() }

    fun flowOne(line: String): String {
        val cmd = line.substringBefore(' ').substringBefore('|').trim()
        val rest = line.removePrefix(cmd).trim().removePrefix("|").trim()
        val w = words(rest)
        return when (cmd.lowercase()) {
            "class" -> c(rest)
            "classfrom" -> c2(w.getOrElse(0){"0"}.toInt(), w.drop(1).joinToString(" "))
            "new" -> n(w.getOrElse(0){""}, rest.substringAfter(' ', "[]"))
            "call" -> x(w.getOrElse(0){"0"}.toInt(), w.getOrElse(1){""}, rest.substringAfter(w.getOrElse(1){""}, "[]").trim().ifBlank { "[]" })
            "get" -> g(w.getOrElse(0){"0"}.toInt(), w.getOrElse(1){""})
            "set" -> s(w.getOrElse(0){"0"}.toInt(), w.getOrElse(1){""}, rest.substringAfter(w.getOrElse(1){""}, "null").trim())
            "tap" -> GlobalService.instance?.tap(w[0].toFloat(), w[1].toFloat())?.let { "V$it" } ?: "E:SVC"
            "swipe" -> GlobalService.instance?.swipe(w[0].toFloat(), w[1].toFloat(), w[2].toFloat(), w[3].toFloat(), w.getOrElse(4){"120"}.toLong())?.let { "V$it" } ?: "E:SVC"
            "readscreen", "screen" -> "V" + (GlobalService.instance?.readScreen() ?: "[]")
            "events" -> "V" + (GlobalService.instance?.events(w.getOrElse(0){"100"}.toInt()) ?: "[]")
            "notifications" -> "V" + NotifService.events(w.getOrElse(0){"100"}.toInt())
            "click" -> GlobalService.instance?.click(rest)?.let { "V$it" } ?: "E:SVC"
            "longclick" -> GlobalService.instance?.longClick(rest)?.let { "V$it" } ?: "E:SVC"
            "input" -> pipe(rest).let { GlobalService.instance?.input(it.getOrElse(0){""}, it.getOrElse(1){""})?.let { r -> "V$r" } ?: "E:SVC" }
            "back" -> GlobalService.instance?.back()?.let { "V$it" } ?: "E:SVC"
            "home" -> GlobalService.instance?.home()?.let { "V$it" } ?: "E:SVC"
            "recents" -> GlobalService.instance?.recents()?.let { "V$it" } ?: "E:SVC"
            "shell" -> sh(rest, "", 10000)
            "shellwithinput" -> pipe(rest).let { sh(it.getOrElse(0){""}, it.getOrElse(1){""}, 10000) }
            "loaddex" -> dex(w.getOrElse(0){""}, w.getOrElse(1){""}, w.getOrElse(2){""})
            "loadlibrary" -> lib(rest, true)
            "say" -> say(rest)
            "listen" -> listen(w.getOrElse(0){"0"}.toInt(), w.getOrElse(1){""}, rest.substringAfter(w.getOrElse(1){""}, "").trim())
            "sensor" -> sensor(w.getOrElse(0){"1"}.toInt(), w.getOrElse(1){"1"}.toInt(), w.getOrElse(2){"0"}.toInt())
            "stopsensor" -> sensorStop(w.getOrElse(0){"0"}.toInt())
            "notify" -> pipe(rest).let { notify(1, it.getOrElse(0){""}, it.getOrElse(1){""}) }
            "alarm" -> pipe(rest).let { alarm(it.getOrElse(0){"0"}.toLong(), it.getOrElse(1){""}) }
            "saveprofile" -> pipe(rest).let { fw(it.getOrElse(0){"profile.json.gz"}, it.getOrElse(1){""}, true) }
            "loadprofile" -> fr(rest.ifBlank { "profile.json.gz" }, true)
            "foreground" -> fg(rest.lowercase() != "off")
            "intent", "start" -> pipe(rest).let { startEx(it.getOrElse(0){""}, it.getOrElse(1){""}, it.getOrElse(2){""}, it.getOrElse(3){""}, it.getOrElse(4){""}, it.getOrElse(5){"{}"}, it.getOrElse(6){""}, it.getOrElse(7){"0"}.toIntOrNull() ?: 0) }
            "resolve" -> pipe(rest).let { resolveIntent(it.getOrElse(0){""}, it.getOrElse(1){""}, it.getOrElse(2){""}, it.getOrElse(3){""}, it.getOrElse(4){""}, it.getOrElse(5){"{}"}, it.getOrElse(6){""}, it.getOrElse(7){"0"}.toIntOrNull() ?: 0) }
            "canopen" -> pipe(rest).let { canIntent(it.getOrElse(0){Intent.ACTION_VIEW}, it.getOrElse(1){""}, it.getOrElse(2){""}, it.getOrElse(3){""}, it.getOrElse(4){""}, it.getOrElse(5){"{}"}, it.getOrElse(6){""}, it.getOrElse(7){"0"}.toIntOrNull() ?: 0) }
            "open" -> open(rest)
            "openin" -> pipe(rest).let { openIn(it.getOrElse(0){""}, it.getOrElse(1){""}) }
            "launch" -> launch(rest)
            "waitfor" -> waitFor(rest, 5000)
            "playclick" -> playClick(rest, 5000)
            "share" -> pipe(rest).let { share(it.getOrElse(0){""}, it.getOrElse(1){"text/plain"}) }
            "choose" -> pipe(rest).let { choose(it.getOrElse(0){Intent.ACTION_VIEW}, it.getOrElse(1){""}, it.getOrElse(2){""}, it.getOrElse(3){"{}"}) }
            "settings" -> startEx(when (rest.lowercase()) { "", "main" -> android.provider.Settings.ACTION_SETTINGS; "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS; "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS; "notification" -> android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS; "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS; "app" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS; else -> "android.settings." + rest.uppercase() }, "", "", "", "", "{}", "", 0)
            "mediaprojection" -> mediaProjection(w.getOrElse(0){"0"}.toInt())
            "wait" -> { Thread.sleep(w.getOrElse(0){"0"}.toLong()); "OK" }
            else -> "E:FLOW_OP:$cmd"
        }
    }

    @JavascriptInterface fun dex(path: String, opt: String, lib: String): String = try {
        if (!ok()) "E:SEC" else keep(dalvik.system.DexClassLoader(path, opt.ifBlank { codeCacheDir.absolutePath }, lib.ifBlank { null }, classLoader))
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun lib(v: String, abs: Boolean): String = try { if (!ok()) "E:SEC" else { if (abs) System.load(v) else System.loadLibrary(v); "OK" } } catch (e: Throwable) { "E:$e" }

    @JavascriptInterface fun sensor(type: Int, delay: Int, cbId: Int): String {
        if (!ok()) return "E:SEC"
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val se = sm.getDefaultSensor(type) ?: return "E:SENSOR"
        val id = sid++
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { cb(cbId, JSONObject().put("id", id).put("type", e.sensor.type).put("name", e.sensor.name).put("ts", e.timestamp).put("v", JSONArray(e.values.toList())).toString()) }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) { cb(cbId, JSONObject().put("id", id).put("accuracy", accuracy).toString()) }
        }
        SL[id] = l
        return if (sm.registerListener(l, se, delay)) "V$id" else "E:REG"
    }

    @JavascriptInterface fun sensorStop(id: Int): String { if (!ok()) return "E:SEC"; val l = SL.remove(id) ?: return "E:NUL"; (getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(l); return "OK" }

    fun mediaProjection(cbId: Int): String = try {
        if (!ok()) "E:SEC" else { mpCb = cbId; val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager; startActivityForResult(m.createScreenCaptureIntent(), 7717); "OK" }
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun mpStart(w0: Int, h0: Int, dpi0: Int): String {
        return try {
            if (!ok()) return "E:SEC"
            if (mpData == null) return "E:CONSENT"
            if (Build.VERSION.SDK_INT < 21) return "E:API"
            mpStop()
            val dm = resources.displayMetrics; val ww = if (w0 > 0) w0 else dm.widthPixels; val hh = if (h0 > 0) h0 else dm.heightPixels; val dd = if (dpi0 > 0) dpi0 else dm.densityDpi
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            mp = mgr.getMediaProjection(mpCode, mpData!!)
            ir = ImageReader.newInstance(ww, hh, PixelFormat.RGBA_8888, 3)
            vd = mp?.createVirtualDisplay("mirror", ww, hh, dd, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir!!.surface, null, null)
            "V" + JSONObject().put("w", ww).put("h", hh).put("dpi", dd).toString()
        } catch (e: Exception) { "E:$e" }
    }

    fun cropLatest(l: Int, t: Int, rr: Int, bb: Int, maxW: Int): Bitmap? {
        val img = ir?.acquireLatestImage() ?: return null
        img.use { im ->
            val plane = im.planes[0]; val buf = plane.buffer; val ps = plane.pixelStride; val rs = plane.rowStride; val rowPad = rs - ps * im.width
            val full = Bitmap.createBitmap(im.width + rowPad / ps, im.height, Bitmap.Config.ARGB_8888); full.copyPixelsFromBuffer(buf)
            val base = Bitmap.createBitmap(full, 0, 0, im.width, im.height)
            val rect = if (l >= 0 && t >= 0 && rr > l && bb > t) Rect(maxOf(0,l), maxOf(0,t), minOf(base.width,rr), minOf(base.height,bb)) else Rect(0,0,base.width,base.height)
            var bmp = Bitmap.createBitmap(base, rect.left, rect.top, rect.width(), rect.height())
            if (maxW > 0 && bmp.width > maxW) bmp = Bitmap.createScaledBitmap(bmp, maxW, maxOf(1, bmp.height * maxW / bmp.width), false)
            return bmp
        }
    }

    fun framePayload(bmp: Bitmap, format0: String, quality: Int): String {
        val format = format0.lowercase().ifBlank { "jpg" }
        val file = format.startsWith("file")
        val raw = format.contains("raw") || format.contains("rgba") || format.contains("gray")
        val mime: String; val ext: String; val bytes: ByteArray
        if (raw) {
            val pixels = IntArray(bmp.width * bmp.height); bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            if (format.contains("gray")) {
                val out = ByteArray(pixels.size)
                for (i in pixels.indices) { val c = pixels[i]; out[i] = (((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100).toByte() }
                bytes = out; mime = "application/x-gray8"; ext = "gray"
            } else {
                val out = ByteArray(pixels.size * 4); var j = 0
                for (c in pixels) { out[j++] = (c shr 16).toByte(); out[j++] = (c shr 8).toByte(); out[j++] = c.toByte(); out[j++] = (c ushr 24).toByte() }
                bytes = out; mime = "application/x-rgba"; ext = "rgba"
            }
        } else {
            val baos = ByteArrayOutputStream(); val png = format.contains("png")
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality.coerceIn(1,100), baos)
            bytes = baos.toByteArray(); mime = if (png) "image/png" else "image/jpeg"; ext = if (png) "png" else "jpg"
        }
        val o = JSONObject().put("w", bmp.width).put("h", bmp.height).put("mime", mime).put("bytes", bytes.size).put("format", ext)
        return if (file) {
            val f = java.io.File(cacheDir, "mirror_frame_${System.currentTimeMillis()}.$ext"); f.writeBytes(bytes)
            o.put("path", f.absolutePath).put("url", "file://" + f.absolutePath).toString()
        } else o.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
    }

    fun encodeFrame(bmp: Bitmap, format0: String, quality: Int): Pair<JSONObject, ByteArray> {
        val format = format0.lowercase().removePrefix("file").ifBlank { "jpg" }
        val raw = format.contains("raw") || format.contains("rgba") || format.contains("gray")
        val mime: String; val ext: String; val bytes: ByteArray
        if (raw) {
            val pixels = IntArray(bmp.width * bmp.height); bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            if (format.contains("gray")) {
                val out = ByteArray(pixels.size)
                for (i in pixels.indices) { val c = pixels[i]; out[i] = (((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100).toByte() }
                bytes = out; mime = "application/x-gray8"; ext = "gray"
            } else {
                val out = ByteArray(pixels.size * 4); var j = 0
                for (c in pixels) { out[j++] = (c shr 16).toByte(); out[j++] = (c shr 8).toByte(); out[j++] = c.toByte(); out[j++] = (c ushr 24).toByte() }
                bytes = out; mime = "application/x-rgba"; ext = "rgba"
            }
        } else {
            val baos = ByteArrayOutputStream(); val png = format.contains("png")
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality.coerceIn(1,100), baos)
            bytes = baos.toByteArray(); mime = if (png) "image/png" else "image/jpeg"; ext = if (png) "png" else "jpg"
        }
        return JSONObject().put("w", bmp.width).put("h", bmp.height).put("mime", mime).put("bytes", bytes.size).put("format", ext) to bytes
    }

    @JavascriptInterface fun mpFrame(l: Int, t: Int, rr: Int, bb: Int, maxW: Int, quality: Int): String = mpFrameEx(l, t, rr, bb, maxW, quality, "jpg")

    @JavascriptInterface fun mpFrameEx(l: Int, t: Int, rr: Int, bb: Int, maxW: Int, quality: Int, format: String): String {
        return try { if (!ok()) return "E:SEC"; val bmp = cropLatest(l, t, rr, bb, maxW) ?: return "E:NOFRAME"; "V" + framePayload(bmp, format, quality) } catch (e: Exception) { "E:$e" }
    }

    @JavascriptInterface fun mpStreamStart(config: String, cbId: Int): String {
        if (!ok()) return "E:SEC"
        val o = if (config.isBlank()) JSONObject() else JSONObject(config)
        mpStreamStop(); mpStream = true
        mpStreamThread = Thread {
            val fps = o.optInt("fps", 10).coerceIn(1, 60); val delay = maxOf(1L, 1000L / fps)
            val l = o.optInt("l", -1); val t = o.optInt("t", -1); val r = o.optInt("r", -1); val b = o.optInt("b", -1); val mw = o.optInt("maxW", 320); val q = o.optInt("quality", 60); val fmt = o.optString("format", "jpg")
            while (mpStream) { val res = mpFrameEx(l, t, r, b, mw, q, fmt); if (!res.startsWith("E:NOFRAME")) cb(cbId, res); Thread.sleep(delay) }
        }.apply { start() }
        return "OK"
    }

    @JavascriptInterface fun mpStreamStop(): String { mpStream = false; runCatching { mpStreamThread?.join(300) }; mpStreamThread = null; return "OK" }

    @JavascriptInterface fun framePipeStart(config: String, cbId: Int): String {
        if (!ok()) return "E:SEC"
        val o = if (config.isBlank()) JSONObject() else JSONObject(config)
        val id = synchronized(FP) { fpid++ }
        val dir = java.io.File(cacheDir, "mirror_frame_pipe_$id").apply { mkdirs() }
        val pipe = FramePipe(id, dir)
        synchronized(FP) { FP[id] = pipe }
        val fps = o.optInt("fps", 10).coerceIn(1, 60); val delay = maxOf(1L, 1000L / fps)
        val slots = o.optInt("slots", 3).coerceIn(1, 16)
        val l = o.optInt("l", -1); val t = o.optInt("t", -1); val r = o.optInt("r", -1); val b = o.optInt("b", -1); val mw = o.optInt("maxW", 320); val q = o.optInt("quality", 60); val fmt = o.optString("format", "jpg")
        pipe.th = Thread {
            var seq = 0L
            while (pipe.run) {
                try {
                    val bmp = cropLatest(l, t, r, b, mw)
                    if (bmp != null) {
                        val (meta0, bytes) = encodeFrame(bmp, fmt, q); val slot = (seq % slots).toInt(); val ext = meta0.optString("format", "bin")
                        val file = java.io.File(dir, "$slot.$ext"); file.writeBytes(bytes)
                        val meta = meta0.put("id", id).put("seq", seq).put("slot", slot).put("slots", slots).put("ts", System.currentTimeMillis()).put("path", file.absolutePath).put("url", "file://" + file.absolutePath).put("meta", "file://" + java.io.File(dir, "meta.json").absolutePath)
                        java.io.File(dir, "meta.json").writeText(meta.toString())
                        if (cbId > 0) cb(cbId, "V" + meta.toString())
                        seq++
                    }
                } catch (e: Exception) { if (cbId > 0) cb(cbId, "E:$e") }
                Thread.sleep(delay)
            }
        }.apply { start() }
        return "V" + JSONObject().put("id", id).put("dir", dir.absolutePath).put("url", "file://" + dir.absolutePath + "/").put("meta", "file://" + java.io.File(dir, "meta.json").absolutePath).put("slots", slots).put("fps", fps).toString()
    }

    @JavascriptInterface fun framePipeInfo(id: Int): String {
        if (!ok()) return "E:SEC"
        val pipe = synchronized(FP) { FP[id] } ?: return "E:PIPE"
        val meta = java.io.File(pipe.dir, "meta.json")
        return "V" + if (meta.exists()) meta.readText() else JSONObject().put("id", id).put("dir", pipe.dir.absolutePath).put("running", pipe.run).toString()
    }

    @JavascriptInterface fun framePipeStop(id: Int): String {
        if (!ok()) return "E:SEC"
        if (id == 0) { val all = synchronized(FP) { FP.values.toList().also { FP.clear() } }; all.forEach { it.run = false; runCatching { it.th?.join(300) } }; return "OK" }
        val pipe = synchronized(FP) { FP.remove(id) } ?: return "E:PIPE"
        pipe.run = false; runCatching { pipe.th?.join(300) }
        return "OK"
    }

    @JavascriptInterface fun mpStop(): String = try { mpStreamStop(); vd?.release(); vd = null; ir?.close(); ir = null; mp?.stop(); mp = null; "OK" } catch (e: Exception) { "E:$e" }

    fun extra(it: Intent, k: String, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> it.putExtra(k, "")
            is Boolean -> it.putExtra(k, v)
            is Int -> it.putExtra(k, v)
            is Long -> it.putExtra(k, v)
            is Double -> it.putExtra(k, v)
            is JSONArray -> it.putExtra(k, ArrayList<String>().apply { for (i in 0 until v.length()) add(v.get(i).toString()) })
            else -> it.putExtra(k, v.toString())
        }
    }

    fun makeIntent(action: String, uri: String, mime: String, pkg: String, cls: String, extras: String, cats: String, flags: Int): Intent {
        val it = Intent(if (action.isBlank()) Intent.ACTION_VIEW else action)
        if (uri.isNotBlank() && mime.isNotBlank()) it.setDataAndType(android.net.Uri.parse(uri), mime)
        else if (uri.isNotBlank()) it.data = android.net.Uri.parse(uri)
        else if (mime.isNotBlank()) it.type = mime
        if (pkg.isNotBlank() && cls.isNotBlank()) it.setClassName(pkg, cls) else if (pkg.isNotBlank()) it.setPackage(pkg)
        cats.split(',').map { c -> c.trim() }.filter { c -> c.isNotEmpty() }.forEach { c -> it.addCategory(c) }
        if (extras.isNotBlank()) { val o = JSONObject(extras); for (k in o.keys()) extra(it, k, o.get(k)) }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or flags)
        return it
    }

    fun resInfo(r: android.content.pm.ResolveInfo): JSONObject {
        val ai = r.activityInfo
        return JSONObject().put("label", r.loadLabel(packageManager)?.toString() ?: "")
            .put("package", ai?.packageName ?: "").put("activity", ai?.name ?: "")
            .put("exported", ai?.exported ?: false).put("priority", r.priority).put("match", r.match)
    }

    @JavascriptInterface fun resolveIntent(action: String, uri: String, mime: String, pkg: String, cls: String, extras: String, cats: String, flags: Int): String = try {
        if (!ok()) "E:SEC" else {
            val it = makeIntent(action, uri, mime, pkg, cls, extras, cats, flags)
            val a = JSONArray(); packageManager.queryIntentActivities(it, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).forEach { r -> a.put(resInfo(r)) }
            "V" + JSONObject().put("intent", it.toUri(0)).put("count", a.length()).put("activities", a).toString()
        }
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun canIntent(action: String, uri: String, mime: String, pkg: String, cls: String, extras: String, cats: String, flags: Int): String = try {
        if (!ok()) "E:SEC" else "V" + (makeIntent(action, uri, mime, pkg, cls, extras, cats, flags).resolveActivity(packageManager) != null)
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun start(action: String, uri: String, pkg: String, cls: String, extras: String): String = startEx(action, uri, "", pkg, cls, extras, "", 0)

    @JavascriptInterface fun startEx(action: String, uri: String, mime: String, pkg: String, cls: String, extras: String, cats: String, flags: Int): String = try {
        if (!ok()) "E:SEC" else { startActivity(makeIntent(action, uri, mime, pkg, cls, extras, cats, flags)); "OK" }
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun launch(pkg: String): String = try { if (!ok()) "E:SEC" else packageManager.getLaunchIntentForPackage(pkg)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it); "OK" } ?: "E:NOAPP" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun open(uri: String) = startEx(Intent.ACTION_VIEW, uri, "", "", "", "{}", "", 0)
    @JavascriptInterface fun openIn(pkg: String, uri: String) = startEx(Intent.ACTION_VIEW, uri, "", pkg, "", "{}", "", 0)
    @JavascriptInterface fun waitFor(q: String, ms: Long) = if (!ok()) "E:SEC" else GlobalService.instance?.waitFor(q, ms)?.let { "V$it" } ?: "E:SVC"
    @JavascriptInterface fun playClick(q: String, ms: Long) = if (!ok()) "E:SEC" else GlobalService.instance?.let { if (it.waitFor(q, ms)) "V" + it.click(q) else "E:WAIT" } ?: "E:SVC"
    @JavascriptInterface fun currentApp() = if (!ok()) "E:SEC" else "V" + (GlobalService.instance?.currentPackage() ?: "")
    @JavascriptInterface fun share(text: String, mime: String): String = try { if (!ok()) "E:SEC" else { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime.ifBlank { "text/plain" }).putExtra(Intent.EXTRA_TEXT, text), "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "OK" } } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun choose(action: String, uri: String, mime: String, extras: String): String = try { if (!ok()) "E:SEC" else { startActivity(Intent.createChooser(makeIntent(action, uri, mime, "", "", extras, "", 0), "Choose").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "OK" } } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun alarm(ms: Long, v: String): String = try {
        if (!ok()) "E:SEC" else {
            val it = Intent(this, Main::class.java).putExtra("mirror", v).putExtra("TS", System.currentTimeMillis())
            val pi = android.app.PendingIntent.getActivity(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), it, android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager; val at = System.currentTimeMillis() + maxOf(0L, ms)
            when { Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi); Build.VERSION.SDK_INT >= 19 -> am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi); else -> am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi) }; "OK"
        }
    } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun notify(id: Int, title: String, text: String): String = try {
        if (!ok()) "E:SEC" else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager; val ch = "mirror"
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(ch, "mirror", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val pi = android.app.PendingIntent.getActivity(this, id, Intent(this, Main::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            val icon = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.ic_dialog_info
            val nb = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, ch) else android.app.Notification.Builder(this)
            nm.notify(id, nb.setSmallIcon(icon).setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()); "OK"
        }
    } catch (e: Exception) { "E:$e" }

    fun fileOf(n: String) = java.io.File(filesDir, n.replace("..", "_"))
    @JavascriptInterface fun fw(n: String, v: String, zip: Boolean): String { if (!ok()) return "E:SEC"; val f = fileOf(n); f.parentFile?.mkdirs(); f.writeText(if (zip) gz(v) else v); return "OK" }
    @JavascriptInterface fun fr(n: String, zip: Boolean): String { if (!ok()) return "E:SEC"; val v = fileOf(n).takeIf { it.exists() }?.readText() ?: ""; return "V" + if (zip) runCatching { ungz(v) }.getOrElse { v } else v }

    @JavascriptInterface fun fg(on: Boolean): String = try {
        if (!ok()) "E:SEC" else { val it = Intent(this, BridgeService::class.java); if (on) { if (Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it) } else stopService(it); "OK" }
    } catch (e: Exception) { "E:$e" }

    fun procIntent(it: Intent?) {
        val o = JSONObject(); it?.extras?.keySet()?.forEach { k -> o.put(k, it.extras?.get(k)?.toString() ?: "") }
        lastIntent = o.toString(); runCatching { cb(999, lastIntent); w.evaluateJavascript("window.onIntent&&window.onIntent(${JSONObject.quote(lastIntent)})", null) }
    }

    fun speakNow(text: String, queue: Int = TextToSpeech.QUEUE_FLUSH): String {
        if (!ok()) return "E:SEC"
        runOnUiThread {
            if (tts == null) tts = TextToSpeech(this) { _ -> }
            tts?.speak(text, queue, null, "mirror" + System.currentTimeMillis())
        }
        return "OK"
    }

    fun listenOnce(cbId: Int, lang: String, prompt: String): String {
        if (!ok()) return "E:SEC"
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return "E:NO_SPEECH"
        runOnUiThread {
            runCatching { sr?.destroy() }
            sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    fun send(type: String, value: Any?) = cb(cbId, JSONObject().put("type", type).put("value", value ?: JSONObject.NULL).toString())
                    override fun onReadyForSpeech(params: Bundle?) = send("ready", null)
                    override fun onBeginningOfSpeech() = send("begin", null)
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = send("end", null)
                    override fun onError(error: Int) = send("error", error)
                    override fun onResults(results: Bundle?) {
                        val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                        send("result", JSONArray(l))
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                        send("partial", JSONArray(l))
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    if (lang.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    if (prompt.isNotBlank()) putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                })
            }
        }
        return "OK"
    }

    fun arrNames(a: JSONArray): List<String> = (0 until a.length()).map { a.get(it).toString() }
    fun capState(): JSONObject {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensors = JSONArray(); sm.getSensorList(android.hardware.Sensor.TYPE_ALL).forEach { sensors.put(JSONObject().put("type", it.type).put("name", it.name).put("vendor", it.vendor)) }
        val enabledAcc = runCatching { android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName, true) == true }.getOrDefault(false)
        val enabledNoti = runCatching { android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName, true) == true }.getOrDefault(false)
        return JSONObject().put("time", System.currentTimeMillis()).put("sandbox", s).put("auth", A).put("netCut", NET)
            .put("accessibility", JSONObject().put("enabled", GlobalService.instance != null || enabledAcc).put("settings", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            .put("notificationListener", JSONObject().put("enabled", enabledNoti).put("settings", android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            .put("speech", JSONObject().put("recognition", SpeechRecognizer.isRecognitionAvailable(this)).put("tts", true))
            .put("mediaProjection", JSONObject().put("prepared", mpData != null).put("needsConsent", true))
            .put("foreground", BridgeService.instance != null).put("shell", true).put("javaReflection", true)
            .put("kotlin", JSONObject().put("metadata", true).put("reflectFull", runCatching { Class.forName("kotlin.reflect.full.KClasses"); true }.getOrDefault(false)))
            .put("sensors", sensors).put("handles", sz())
    }

    fun eventState(max: Int): JSONObject = JSONObject()
        .put("accessibility", JSONArray(GlobalService.instance?.events(max) ?: "[]"))
        .put("notifications", JSONArray(NotifService.events(max)))
        .put("intent", JSONObject(lastIntent))

    fun observe(v: Any?): JSONObject {
        val names = when (v) { is JSONArray -> arrNames(v); is String -> v.split(',', ' ').filter { it.isNotBlank() }; else -> listOf("screen", "events") }
        val o = JSONObject()
        if (names.any { it.equals("screen", true) }) o.put("screen", JSONArray(GlobalService.instance?.readScreen() ?: "[]"))
        if (names.any { it.equals("events", true) }) o.put("events", eventState(100))
        if (names.any { it.equals("notifications", true) }) o.put("notifications", JSONArray(NotifService.events(100)))
        if (names.any { it.equals("capabilities", true) || it.equals("caps", true) }) o.put("capabilities", capState())
        if (names.any { it.equals("intent", true) }) o.put("intent", JSONObject(lastIntent))
        return o
    }

    fun actOne(v: Any?): Any? = when (v) {
        is String -> flowOne(v)
        is JSONArray -> cmdA(v)
        is JSONObject -> {
            if (v.has("op")) cmd(v) else when {
                v.has("tap") -> { val a = v.getJSONArray("tap"); GlobalService.instance?.tap(a.getDouble(0).toFloat(), a.getDouble(1).toFloat()) ?: false }
                v.has("swipe") -> { val a = v.getJSONArray("swipe"); GlobalService.instance?.swipe(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.optLong(4, 120)) ?: false }
                v.has("click") -> GlobalService.instance?.click(v.getString("click")) ?: false
                v.has("type") || v.has("input") -> { val a = v.optJSONArray("type") ?: v.optJSONArray("input") ?: JSONArray(); GlobalService.instance?.input(a.optString(0), a.optString(1)) ?: false }
                v.has("open") -> open(v.getString("open"))
                v.has("openIn") -> { val o = v.getJSONObject("openIn"); openIn(o.optString("pkg"), o.optString("uri")) }
                v.has("launch") -> launch(v.getString("launch"))
                v.has("waitFor") -> { val o = v.getJSONObject("waitFor"); waitFor(o.optString("q"), o.optLong("ms", 5000)) }
                v.has("playClick") -> { val o = v.getJSONObject("playClick"); playClick(o.optString("q"), o.optLong("ms", 5000)) }
                v.has("share") -> { val o = v.getJSONObject("share"); share(o.optString("text"), o.optString("mime", "text/plain")) }
                v.has("intent") -> { val o = v.getJSONObject("intent"); startEx(o.optString("action"), o.optString("uri"), o.optString("mime"), o.optString("pkg"), o.optString("cls"), o.optString("extras", "{}"), o.optString("cats"), o.optInt("flags")) }
                v.has("shell") -> sh(v.getString("shell"), "", v.optLong("timeout", 10000))
                v.has("sys") -> { val o = v.getJSONObject("sys"); "V" + sysRun(o.optString("mode", "sh"), o.optString("cmd"), o.optString("in", ""), o.optLong("timeout", 10000)) }
                v.has("page") -> { val o = v.getJSONObject("page"); when { o.has("eval") -> pageEval(o.optString("id"), o.optString("eval"), o.optInt("callback")); o.has("show") -> pageShow(o.optString("show", o.optString("id"))); o.has("close") -> pageClose(o.optString("close", o.optString("id"))); o.has("list") -> pageList(); else -> pageOpen(o.optString("id"), o.optString("src"), o.optString("kind", "html"), o.optBoolean("visible", true)) } }
                v.has("boot") -> { val o = v.getJSONObject("boot"); boot(o.optString("html", ""), o.optBoolean("reload", false)) }
                v.has("fetch") -> { val o = v.getJSONObject("fetch"); fetch(o.optString("url"), o.optString("method", "GET"), o.optString("headers", "{}"), o.optString("body", ""), o.optInt("timeout", 15000), o.optBoolean("clean", true), o.optBoolean("sensitive", false), o.optInt("max", 2097152)) }
                v.has("screenshot") -> { val a = v.optJSONArray("screenshot") ?: JSONArray(); screenshot(a.optInt(0, -1), a.optInt(1, -1), a.optInt(2, -1), a.optInt(3, -1), a.optInt(4, 640), a.optInt(5, 70)) }
                v.has("mpFrame") -> { val a = v.optJSONArray("mpFrame") ?: JSONArray(); mpFrameEx(a.optInt(0, -1), a.optInt(1, -1), a.optInt(2, -1), a.optInt(3, -1), a.optInt(4, 640), a.optInt(5, 70), a.optString(6, "jpg")) }
                v.has("mpStream") -> { val o = v.getJSONObject("mpStream"); mpStreamStart(o.toString(), o.optInt("callback", o.optInt("cb"))) }
                v.has("framePipe") -> { val o = v.getJSONObject("framePipe"); framePipeStart(o.toString(), o.optInt("callback", o.optInt("cb", 0))) }
                v.has("say") -> say(v.getString("say"))
                v.has("listen") -> { val o = v.getJSONObject("listen"); listen(o.optInt("callback"), o.optString("lang"), o.optString("prompt")) }
                v.has("notify") -> { val a = v.getJSONArray("notify"); notify(a.optInt(0, 1), a.optString(1), a.optString(2)) }
                v.has("alarm") -> { val o = v.getJSONObject("alarm"); alarm(o.optLong("in", o.optLong("ms")), o.optString("payload", o.optString("value"))) }
                v.has("sensor") -> { val o = v.getJSONObject("sensor"); sensor(o.optInt("type"), o.optInt("delay", 1), o.optInt("callback")) }
                v.has("save") -> { val o = v.getJSONObject("save"); if (o.has("file")) fw(o.getString("file"), o.optString("value"), o.optBoolean("compress", true)) else save(o.optString("key"), o.optString("value"), o.optBoolean("compress", true)) }
                v.has("load") -> { val o = v.getJSONObject("load"); if (o.has("file")) fr(o.getString("file"), o.optBoolean("compress", true)) else load(o.optString("key"), o.optBoolean("compress", true)) }
                v.has("call") -> { val o = v.getJSONObject("call"); x(o.optInt("handle"), o.optString("method"), o.optJSONArray("args")?.toString() ?: o.optString("args", "[]")) }
                v.has("process") -> procAction(v.getJSONObject("process"))
                else -> "E:ACT"
            }
        }
        else -> "E:ACT"
    }

    fun procAction(o: JSONObject): String = when {
        o.has("start") -> procStart(o.optString("start"), o.optString("cwd"), o.optJSONObject("env"), o.optString("stdin"))
        o.has("write") -> procWrite(o.optInt("id"), o.optString("write"), o.optBoolean("close"))
        o.has("read") -> procRead(o.optInt("read", o.optInt("id")), o.optBoolean("clear", true))
        o.has("kill") -> procKill(o.optInt("kill", o.optInt("id")))
        else -> "E:PROC"
    }

    @JavascriptInterface fun caps() = "V" + capState().toString()
    @JavascriptInterface fun events(req: String): String = try { val o = if (req.isBlank()) JSONObject() else JSONObject(req); "V" + eventState(o.optInt("max", 100)).toString() } catch (e: Exception) { "E:$e" }

    @JavascriptInterface fun exec(req: String): String {
        return try {
        if (!ok()) return "{\"ok\":false,\"error\":\"SEC\"}"
        val root: Any = if (req.trim().startsWith("[")) JSONArray(req) else JSONObject(req)
        val res = JSONObject().put("ok", true); val results = JSONArray()
        if (root is JSONArray) { for (i in 0 until root.length()) jput(results, actOne(root.get(i))); return res.put("results", results).toString() }
        val o = root as JSONObject
        if (o.optBoolean("capabilities") || o.optBoolean("caps")) res.put("capabilities", capState())
        if (o.has("observe") || o.has("get")) res.put("state", observe(if (o.has("observe")) o.get("observe") else o.get("get")))
        val acts = o.opt("act") ?: o.opt("do") ?: o.opt("actions")
        when (acts) { is JSONArray -> for (i in 0 until acts.length()) jput(results, actOne(acts.get(i))); null -> {}; else -> jput(results, actOne(acts)) }
        if (o.has("process")) jput(results, procAction(o.getJSONObject("process")))
        if (o.has("page")) jput(results, actOne(JSONObject().put("page", o.getJSONObject("page"))))
        if (o.has("remember")) { val m = o.getJSONObject("remember"); jput(results, if (m.has("file")) fw(m.getString("file"), m.optString("value"), m.optBoolean("compress", true)) else save(m.optString("key", "profile"), m.optString("value"), m.optBoolean("compress", true))) }
        if (o.has("schedule")) { val sc = o.get("schedule"); if (sc is JSONArray) for (i in 0 until sc.length()) { val x = sc.getJSONObject(i); jput(results, alarm(x.optLong("in", x.optLong("ms")), x.optString("payload", x.optString("value")))) } else { val x = sc as JSONObject; jput(results, alarm(x.optLong("in", x.optLong("ms")), x.optString("payload", x.optString("value")))) } }
        if (results.length() > 0) res.put("results", results)
        if (o.optBoolean("returnEvents")) res.put("events", eventState(o.optInt("maxEvents", 100)))
        res.toString()
    } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.toString()).toString() }
    }

    @JavascriptInterface fun execAsync(req: String, cbId: Int): String { Thread { cb(cbId, exec(req)) }.start(); return "OK" }

    // --- Javascript Interface ---
    @JavascriptInterface fun sz() = synchronized(H) { H.size }
    @JavascriptInterface fun cls() { synchronized(H) { H.clear(); H.add(this) } }
    @JavascriptInterface fun cacheClear() { synchronized(MC) { MC.clear() }; synchronized(FC) { FC.clear() }; synchronized(CC) { CC.clear() } }
    @JavascriptInterface fun c(n: String) = try { if (ok()) C(n)?.let { keep(it) } ?: "E:CLS" else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun cl() = if (ok()) ret(classLoader) else "E:SEC"
    @JavascriptInterface fun c2(loader: Int, n: String) = try { if (!ok()) "E:SEC" else (hp(loader) as? ClassLoader)?.loadClass(n)?.let { keep(it) } ?: "E:CL" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun n(c: String, j: String) = R(C(c), "", j, 1, 0)
    @JavascriptInterface fun x(p: Int, n: String, j: String) = R(hp(p), n, j, 0, 0)
    @JavascriptInterface fun u(p: Int, n: String, j: String, cbId: Int) { runOnUiThread { cb(cbId, R(hp(p), n, j, 0, cbId)) } }
    @JavascriptInterface fun g(p: Int, f: String) = R(hp(p), f, "[]", 2, 0)
    @JavascriptInterface fun s(p: Int, f: String, v: String) = R(hp(p), f, "[$v]", 3, 0)
    @JavascriptInterface fun a(t: String, l: Int) = R(C(t), "", "[$l]", 4, 0)
    @JavascriptInterface fun p(t: String, id: Int) = R(C(t), "", "[$id]", 5, 0)
    @JavascriptInterface fun svc() = if (ok()) ret(GlobalService.instance) else "E:SEC"
    @JavascriptInterface fun str(v: String) = if (ok()) keep(v) else "E:SEC" // pass literal strings like "P0" by handle
    @JavascriptInterface fun get(p: Int) = if (ok()) ret(hp(p)) else "E:SEC"
    @JavascriptInterface fun type(p: Int) = hp(p)?.javaClass?.name ?: ""
    @JavascriptInterface fun del(p: Int) { if (ok() && p > 0) synchronized(H) { if (p < H.size) H[p] = null } }
    @JavascriptInterface fun batch(cmds: String) = try { batchExec(cmds) } catch (e: Exception) { "[\"E:$e\"]" }
    @JavascriptInterface fun batchu(cmds: String, cbId: Int) { Thread { cb(cbId, batch(cmds)) }.start() }
    @JavascriptInterface fun zip(v: String) = try { if (ok()) "V" + gz(v) else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun unzip(v: String) = try { if (ok()) "V" + ungz(v) else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun save(k: String, v: String, zip: Boolean): String { if (!ok()) return "E:SEC"; S("U:$k", if (zip) gz(v) else v); S("UZ:$k", zip); return "OK" }
    @JavascriptInterface fun load(k: String, zip: Boolean): String { if (!ok()) return "E:SEC"; val v = P.getString("U:$k", "") ?: ""; val z = zip || P.getBoolean("UZ:$k", false); return "V" + if (z) runCatching { ungz(v) }.getOrElse { v } else v }
    @JavascriptInterface fun rm(k: String): String { if (!ok()) return "E:SEC"; P.edit().remove("U:$k").remove("UZ:$k").apply(); return "OK" }
    @JavascriptInterface fun keys(prefix: String): String { if (!ok()) return "E:SEC"; val a = JSONArray(); P.all.keys.filter { it.startsWith("U:$prefix") }.forEach { a.put(it.removePrefix("U:")) }; return "V" + a.toString() }
    @JavascriptInterface fun toast(v: String): String { if (!ok()) return "E:SEC"; runOnUiThread { Toast.makeText(this, v, Toast.LENGTH_SHORT).show() }; return "OK" }
    @JavascriptInterface fun vibe(ms: Long): String { if (!ok()) return "E:SEC"; runCatching { (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(ms) }; return "OK" }
    @JavascriptInterface fun clip(v: String): String { if (!ok()) return "E:SEC"; (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("mirror", v)); return "OK" }
    @JavascriptInterface fun getClip(): String { if (!ok()) return "E:SEC"; val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; return "V" + (cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "") }
    @JavascriptInterface fun say(v: String) = speakNow(v)
    @JavascriptInterface fun listen(cbId: Int, lang: String, prompt: String) = listenOnce(cbId, lang, prompt)
    @JavascriptInterface fun stopListen(): String { if (!ok()) return "E:SEC"; runOnUiThread { runCatching { sr?.stopListening() } }; return "OK" }
    @JavascriptInterface fun now() = System.currentTimeMillis()
    @JavascriptInterface fun later(ms: Long, cbId: Int, v: String): String { if (!ok()) return "E:SEC"; Handler(Looper.getMainLooper()).postDelayed({ cb(cbId, v) }, maxOf(0L, ms)); return "OK" }
    @JavascriptInterface fun sh(cmd: String, input: String, timeoutMs: Long) = try { if (ok()) "V" + shell(cmd, input, timeoutMs) else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun sys(mode: String, cmd: String, input: String, timeoutMs: Long) = try { if (ok()) "V" + sysRun(mode, cmd, input, timeoutMs) else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun fetch(url: String, method: String, headers: String, body: String, timeout: Int, clean: Boolean, sensitive: Boolean, max: Int) = "V" + netFetch(url, method, headers, body, timeout, clean, sensitive, max)
    @JavascriptInterface fun screenshot(l: Int, t: Int, r: Int, b: Int, maxW: Int, quality: Int) = if (!ok()) "E:SEC" else "V" + (GlobalService.instance?.shot(l, t, r, b, maxW, quality) ?: "E:SVC")
    @JavascriptInterface fun kc(n: String) = try { if (ok()) C(n)?.kotlin?.let { keep(it) } ?: "E:CLS" else "E:SEC" } catch (e: Throwable) { "E:$e" }
    @JavascriptInterface fun kco(p: Int) = try { if (ok()) klassOf(p)?.kotlin?.let { keep(it) } ?: "E:NUL" else "E:SEC" } catch (e: Throwable) { "E:$e" }
    @JavascriptInterface fun kinfo(p: Int): String { if (!ok()) return "E:SEC"; val c = klassOf(p) ?: return "E:NUL"; return "V" + info(c, true) }
    @JavascriptInterface fun jinfo(p: Int): String { if (!ok()) return "E:SEC"; val c = klassOf(p) ?: return "E:NUL"; return "V" + info(c, false) }
    @JavascriptInterface fun B(cmds: String) = batch(cmds)
    @JavascriptInterface fun boot(html: String, reload: Boolean): String { if (!ok()) return "E:SEC"; if (html.isNotEmpty()) { editable = html; this.html = editable ?: this.html; S("BOOT_HTML", html) }; if (reload) loadBoot(); return "OK" }
    @JavascriptInterface fun bootGet() = if (ok()) "V" + bootHtml() else "E:SEC"
    @JavascriptInterface fun bootReset(reload: Boolean): String { if (!ok()) return "E:SEC"; editable = null; html = editable ?: """<!DOCTYPE html><title>App Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable="plaintext-only" oninput="c.textContent=i.textContent.length"></div><button onclick="var code=i.textContent;document.open();document.write(code);document.close()">Realize (<b id=c>0</b> chars)</button>"""; P.edit().remove("BOOT_HTML").apply(); if (reload) loadBoot(); return "OK" }
    @JavascriptInterface fun e(src: String) = flow(src)
    @JavascriptInterface fun r(src: String) = flow(src)
    @JavascriptInterface fun run(src: String) = flow(src)
    @JavascriptInterface fun mp(cbId: Int) = mediaProjection(cbId)
    @JavascriptInterface fun mpData() = if (mpData == null) "E:NUL" else ret(mpData)
    @JavascriptInterface fun intent(action: String, uri: String, pkg: String, cls: String, extras: String) = start(action, uri, pkg, cls, extras)
    @JavascriptInterface fun lastIntent() = "V" + lastIntent
    @JavascriptInterface fun notis(max: Int) = "V" + NotifService.events(max)
    @JavascriptInterface fun auth(p: String): Boolean { A = (p == PW); return A }

}

class GlobalService : AccessibilityService() {
    companion object {
        @JvmField var instance: GlobalService? = null
        @JvmField var seqtokill = ArrayList<Int>().apply { add(25); add(24); add(25); add(24) }
        @JvmField var seqTimeout = 5000L
        @JvmField val EQ = ConcurrentLinkedQueue<String>()
    }

    private var step = 0
    private var startTime = 0L

    override fun onServiceConnected() { super.onServiceConnected(); instance = this; ev("service", JSONObject().put("state", "connected")) }
    override fun onInterrupt() { ev("service", JSONObject().put("state", "interrupt")) }
    override fun onDestroy() { super.onDestroy(); ev("service", JSONObject().put("state", "destroy")); instance = null }

    fun ev(type: String, o: JSONObject) {
        o.put("type", type); o.put("ts", System.currentTimeMillis())
        EQ.offer(o.toString())
        while (EQ.size > 500) EQ.poll()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val txt = JSONArray(); e.text?.forEach { txt.put(it.toString()) }
        ev("acc", JSONObject().apply {
            put("event", e.eventType); put("pkg", e.packageName?.toString() ?: ""); put("class", e.className?.toString() ?: "")
            put("text", txt); put("desc", e.contentDescription?.toString() ?: "")
        })
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            ev("key", JSONObject().put("code", e.keyCode).put("name", KeyEvent.keyCodeToString(e.keyCode)))
            val now = System.currentTimeMillis()
            if (now - startTime > seqTimeout) step = 0
            if (step == 0) startTime = now
            if (step < seqtokill.size && e.keyCode == seqtokill[step]) {
                step++
                if (step == seqtokill.size) { disableSelf(); step = 0; return true }
            } else step = 0
        }
        return super.onKeyEvent(e)
    }

    fun events(max: Int): String {
        val a = JSONArray(); var n = 0
        while (n < max) { val x = EQ.poll() ?: break; a.put(JSONObject(x)); n++ }
        return a.toString()
    }

    fun shot(l: Int, t: Int, rr: Int, bb: Int, maxW: Int, quality: Int): String {
        if (Build.VERSION.SDK_INT < 30) return "E:API"
        var out = "E:TIME"; val latch = CountDownLatch(1)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val src = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) ?: throw Exception("bitmap")
                    r.hardwareBuffer.close()
                    val rect = if (l >= 0 && t >= 0 && rr > l && bb > t) Rect(maxOf(0,l), maxOf(0,t), minOf(src.width,rr), minOf(src.height,bb)) else Rect(0,0,src.width,src.height)
                    var bmp = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
                    if (maxW > 0 && bmp.width > maxW) bmp = Bitmap.createScaledBitmap(bmp, maxW, maxOf(1, bmp.height * maxW / bmp.width), true)
                    val baos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1,100), baos)
                    out = JSONObject().put("w", bmp.width).put("h", bmp.height).put("mime", "image/jpeg").put("base64", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)).toString()
                } catch (e: Exception) { out = "E:$e" }
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { out = "E:SHOT:$errorCode"; latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path(); path.moveTo(x, y)
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 35)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path(); path.moveTo(x1, y1); path.lineTo(x2, y2)
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(1, ms))).build(), null, null)
    }

    // JSON strokes: [{"x":1,"y":2,"d":0,"ms":35},{"x1":1,"y1":2,"x2":3,"y2":4,"d":0,"ms":150}]
    fun gestures(spec: String): Boolean {
        val a = JSONArray(spec); val b = GestureDescription.Builder()
        for (i in 0 until minOf(a.length(), 64)) {
            val o = a.getJSONObject(i); val path = Path()
            if (o.has("x")) path.moveTo(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            else { path.moveTo(o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat()); path.lineTo(o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat()) }
            b.addStroke(GestureDescription.StrokeDescription(path, o.optLong("d", 0), maxOf(1, o.optLong("ms", 35))))
        }
        return dispatchGesture(b.build(), null, null)
    }

    fun global(action: Int) = performGlobalAction(action)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings() = if (Build.VERSION.SDK_INT >= 17) performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) else false

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""
    fun waitFor(q: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + maxOf(0L, ms)
        do { if (findNode(rootInActiveWindow, q.lowercase()) != null) return true; Thread.sleep(80) } while (System.currentTimeMillis() < end)
        return false
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "[]"
        val out = JSONArray(); traverse(root, out, "", 400)
        return out.toString()
    }

    fun nodes(q: String, limit: Int): String {
        val root = rootInActiveWindow ?: return "[]"
        val out = JSONArray(); findAll(root, q.lowercase(), out, minOf(limit, 300))
        return out.toString()
    }

    fun click(q: String): Boolean = act(q, AccessibilityNodeInfo.ACTION_CLICK)
    fun longClick(q: String): Boolean = act(q, AccessibilityNodeInfo.ACTION_LONG_CLICK)
    fun focus(q: String): Boolean = act(q, AccessibilityNodeInfo.ACTION_FOCUS)

    fun input(q: String, text: String): Boolean {
        val n = findNode(rootInActiveWindow, q.lowercase()) ?: return false
        val args = Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun act(q: String, action: Int): Boolean {
        var n = findNode(rootInActiveWindow, q.lowercase()) ?: return false
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.performAction(action)) return true
            cur = cur.parent
        }
        return false
    }

    fun findNode(n: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (matches(n, q)) return n
        for (i in 0 until n.childCount) findNode(n.getChild(i), q)?.let { return it }
        return null
    }

    fun findAll(n: AccessibilityNodeInfo?, q: String, out: JSONArray, limit: Int) {
        if (n == null || out.length() >= limit) return
        if (matches(n, q)) out.put(nodeJson(n))
        for (i in 0 until n.childCount) findAll(n.getChild(i), q, out, limit)
    }

    fun matches(n: AccessibilityNodeInfo, q: String): Boolean {
        if (q.isBlank() || q == "*") return true
        val hay = ((n.text ?: "").toString() + "\n" + (n.contentDescription ?: "").toString() + "\n" + (n.viewIdResourceName ?: "") + "\n" + (n.className ?: "").toString()).lowercase()
        return hay.contains(q)
    }

    fun nodeJson(n: AccessibilityNodeInfo): JSONObject {
        val r = Rect(); n.getBoundsInScreen(r)
        return JSONObject().apply {
            put("text", n.text?.toString() ?: ""); put("desc", n.contentDescription?.toString() ?: ""); put("id", n.viewIdResourceName ?: "")
            put("class", n.className?.toString() ?: ""); put("pkg", n.packageName?.toString() ?: "")
            put("x", r.centerX()); put("y", r.centerY()); put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom)
            put("click", n.isClickable); put("long", n.isLongClickable); put("edit", n.isEditable); put("enabled", n.isEnabled); put("checked", n.isChecked); put("focus", n.isFocused)
        }
    }

    fun traverse(n: AccessibilityNodeInfo?, out: JSONArray, path: String, limit: Int) {
        if (n == null || out.length() >= limit) return
        val j = nodeJson(n); j.put("path", path)
        val hasData = j.optString("text").isNotEmpty() || j.optString("desc").isNotEmpty() || j.optString("id").isNotEmpty() || j.optBoolean("click") || j.optBoolean("edit")
        if (hasData) out.put(j)
        for (i in 0 until n.childCount) traverse(n.getChild(i), out, if (path.isEmpty()) "$i" else "$path.$i", limit)
    }
}


class BridgeService : Service() {
    companion object { @JvmField var instance: BridgeService? = null }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this; foreground("HTML Realizer bridge active") }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { foreground(intent?.getStringExtra("text") ?: "HTML Realizer bridge active"); return START_STICKY }
    fun foreground(text: String) {
        val ch = "K_fg"; val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(ch, "HTML Realizer", android.app.NotificationManager.IMPORTANCE_LOW))
        val pi = android.app.PendingIntent.getActivity(this, 1, Intent(this, Main::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val icon = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.ic_dialog_info
        val nb = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, ch) else android.app.Notification.Builder(this)
        startForeground(7, nb.setSmallIcon(icon).setContentTitle("HTML Realizer").setContentText(text).setContentIntent(pi).setOngoing(true).build())
    }
}

class NotifService : NotificationListenerService() {
    companion object {
        @JvmField val EQ = ConcurrentLinkedQueue<String>()
        fun push(o: JSONObject) { o.put("ts", System.currentTimeMillis()); EQ.offer(o.toString()); while (EQ.size > 500) EQ.poll() }
        fun events(max: Int): String { val a = JSONArray(); var n = 0; while (n < max) { val x = EQ.poll() ?: break; a.put(JSONObject(x)); n++ }; return a.toString() }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) push(JSONObject().put("op", "post").put("pkg", sbn.packageName).put("id", sbn.id).put("tag", sbn.tag ?: "").put("title", sbn.notification.extras?.getCharSequence("android.title")?.toString() ?: "").put("text", sbn.notification.extras?.getCharSequence("android.text")?.toString() ?: "")) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) push(JSONObject().put("op", "rm").put("pkg", sbn.packageName).put("id", sbn.id).put("tag", sbn.tag ?: "")) }
}

class MainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val i = Intent(context, Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.extras?.keySet()?.forEach { k -> i.putExtra(k, intent.extras?.get(k)?.toString() ?: "") }
        context.startActivity(i)
    }
}
