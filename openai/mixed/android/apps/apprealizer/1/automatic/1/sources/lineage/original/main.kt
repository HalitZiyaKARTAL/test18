package a.htmlapprealizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.jvm.JvmField

class Main : Activity() {
    val H = ArrayList<Any?>()
    val L = Array(3) { HashSet<String>() } // 0=Blacklist, 1=Graylist, 2=Whitelist
    val D = HashSet<String>() // Domain whitelist
    lateinit var w: WebView
    lateinit var b: Button
    var m = 0 // Mode: 0=Visible, 1=Focus, 2=Open, 3=Perma
    var s = true // Sandbox
    var A = false // Auth status
    var NET = false // Net cut
    var PW = "" // Password
    val P: SharedPreferences by lazy { getSharedPreferences("Z", Context.MODE_PRIVATE) }

    fun S(k: String, v: Any) = with(P.edit()) {
        if (v is Boolean) putBoolean(k, v) else if (v is Int) putInt(k, v) else putString(k, v.toString())
        apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        H.add(this) // H[0] is always the Activity Context
        (0..2).forEach { i ->
            P.getString("$i", "")!!.split(",").filter { it.isNotEmpty() }.forEach { L[i].add(it) }
        }
        m = P.all["M"].toString().toIntOrNull() ?: 0
        s = P.getBoolean("S", true)
        NET = P.getBoolean("N", false)
        PW = P.getString("W", "") ?: ""
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
            addJavascriptInterface(this@Main, "K")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    A = false
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

        // Initial HTML Load
        w.loadDataWithBaseURL(null, """<!DOCTYPE html><title>App Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable="plaintext-only" oninput="c.textContent=i.textContent.length"></div><button onclick="var code=i.textContent;document.open();document.write(code);document.close()">Realize (<b id=c>0</b> chars)</button>""", "text/html", "utf-8", null)
    }

    override fun onResume() {
        super.onResume()
        if (m == 1) v(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        w.destroy()
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

    fun K(i: Int, s: String) = L[i].contains("ALL") || L[i].any { it != "" && s.contains(it) }
    fun C(n: String) = try { Class.forName(n) } catch (e: Exception) { null }

    fun R(t: Any?, n: String, j: String, y: Int, cI: Int): String {
        if (!ok()) return "E:SEC"
        try {
            val c = if (t is Class<*>) t else t?.javaClass ?: return "E:NUL"
            val a = JSONArray(j)
            val sig = "$n ${c.name}".lowercase()
            if (y < 4 && K(0, sig)) return "E:BL"

            // Security Check Logic
            val g = if (y > 3 || K(1, sig)) null else if (K(2, sig)) "ALL" else L[2].find { it != "" && sig.contains(it) } ?: if (L[0].isEmpty() && L[1].isEmpty()) "ALL" else null

            if (g != null) {
                val i = (System.currentTimeMillis() % 999).toInt()
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("REQ:$sig").setPositiveButton("1") { _, _ -> cb(cI, Ex(t, n, a, y)) }
                        .setNeutralButton("OK") { _, _ -> L[1].add(g); S("1", L[1].joinToString(",")); cb(cI, Ex(t, n, a, y)) }
                        .setNegativeButton("NO") { _, _ -> L[0].add(g); S("0", L[0].joinToString(",")); cb(cI, "E:BL") }.show()
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
            if (y == 2) return ret(c.getField(n).get(t)) // Get Field
            if (y == 3) { val f = c.getField(n); f.set(t, cv(a.get(0), f.type)); return "OK" } // Set Field
            if (y == 4) return ret(java.lang.reflect.Array.newInstance(c, a.getInt(0))) // Array New

            // Proxy
            val proxyId = if (y == 5) a.getInt(0) else 0
            if (y == 5) return ret(Proxy.newProxyInstance(c.classLoader, arrayOf(c)) { _, m, r ->
                val g = r?.map { org.json.JSONObject.quote(ret(it)) }?.joinToString(",") ?: ""
                runOnUiThread { w.evaluateJavascript("window.onL($proxyId,${org.json.JSONObject.quote(m.name)},${org.json.JSONObject.quote("[$g]")})", null) }
                // Fix: Handle primitive returns to avoid crashes
                val rt = m.returnType
                if(rt == Boolean::class.javaPrimitiveType) false else if(rt == Int::class.javaPrimitiveType) 0 else if(rt.isPrimitive && rt != java.lang.Void.TYPE) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(rt, 1), 0) else null
            })

            // Method/Constructor Execution
            // Separate Constructor and Method logic to satisfy API 26+ Executable inference
            if (y == 1) {
                // Constructor
                for (con in c.constructors) {
                    if (con.parameterTypes.size != l) continue
                    try {
                        val v = Array(l) { i -> cv(a.get(i), con.parameterTypes[i]) }
                        return ret(con.newInstance(*v))
                    } catch (e: Exception) { continue }
                }
            } else {
                // Method
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
        if (o === org.json.JSONObject.NULL) null else if (o is String && o.startsWith("P")) H.getOrNull(o.substring(1).toIntOrNull() ?: -1)
        else if (o is Number) when (t) {
            Int::class.javaPrimitiveType -> o.toInt(); Long::class.javaPrimitiveType -> o.toLong()
            Float::class.javaPrimitiveType -> o.toFloat(); Double::class.javaPrimitiveType -> o.toDouble()
            else -> o
        } else if (t == Boolean::class.javaPrimitiveType) o.toString().toBoolean() else o

    fun ret(o: Any?) = if (o == null || o is Unit) "V" else if (o is Number || o is String || o is Boolean) "V$o" else { H.add(o); "P${H.size - 1}" }

    fun cb(i: Int, r: String) = if (i > 0) w.evaluateJavascript("window.onC($i,${org.json.JSONObject.quote(r)})", null) else Unit

    // --- Javascript Interface ---
    @JavascriptInterface fun sz() = H.size
    @JavascriptInterface fun cls() { H.clear(); H.add(this) }
    @JavascriptInterface fun c(n: String) = try { if (ok()) { H.add(Class.forName(n)); "P${H.size - 1}" } else "E:SEC" } catch (e: Exception) { "E:$e" }
    @JavascriptInterface fun n(c: String, j: String) = R(C(c), "", j, 1, 0)
    @JavascriptInterface fun x(p: Int, n: String, j: String) = R(H.getOrNull(p), n, j, 0, 0)
    @JavascriptInterface fun u(p: Int, n: String, j: String, cbId: Int) { runOnUiThread { cb(cbId, R(H.getOrNull(p), n, j, 0, cbId)) } }
    @JavascriptInterface fun g(p: Int, f: String) = R(H.getOrNull(p), f, "[]", 2, 0)
    @JavascriptInterface fun s(p: Int, f: String, v: String) = R(H.getOrNull(p), f, "[$v]", 3, 0)
    @JavascriptInterface fun a(t: String, l: Int) = R(C(t), "", "[$l]", 4, 0)
    @JavascriptInterface fun p(t: String, id: Int) = R(C(t), "", "[$id]", 5, 0)
    @JavascriptInterface fun del(p: Int) { if (ok() && p in 1 until H.size) H[p] = null }
    @JavascriptInterface fun auth(p: String): Boolean { A = (p == PW); return A }
}

class GlobalService : AccessibilityService() { companion object { @JvmField var instance: GlobalService? = null; @JvmField var seqtokill = ArrayList<Int>().apply { add(25); add(24); add(25); add(24) }; @JvmField var seqTimeout = 5000L }; private var step = 0; private var startTime = 0L; override fun onServiceConnected() { super.onServiceConnected(); instance = this }; override fun onAccessibilityEvent(e: AccessibilityEvent?) {}; override fun onInterrupt() {}; override fun onDestroy() { super.onDestroy(); instance = null }; override fun onKeyEvent(e: KeyEvent): Boolean { if (e.action == KeyEvent.ACTION_DOWN) { val now = System.currentTimeMillis(); if (now - startTime > seqTimeout) step = 0; if (step == 0) startTime = now; if (step < seqtokill.size && e.keyCode == seqtokill[step]) { step++; if (step == seqtokill.size) { disableSelf(); step = 0; return true } } else { step = 0 } }; return super.onKeyEvent(e) }; fun tap(x: Float, y: Float) { val path = Path(); path.moveTo(x, y); val builder = GestureDescription.Builder(); builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50)); dispatchGesture(builder.build(), null, null) }; fun readScreen(): String { val root = rootInActiveWindow ?: return "[]"; val list = ArrayList<String>(); traverse(root, list); return list.toString() }; fun traverse(n: AccessibilityNodeInfo?, l: ArrayList<String>) { if (n == null) return; if (n.text != null && n.text.isNotEmpty()) { val r = Rect(); n.getBoundsInScreen(r); l.add(n.text.toString() + "|" + r.centerX() + "|" + r.centerY()) }; for (i in 0 until n.childCount) { traverse(n.getChild(i), l) } } }
