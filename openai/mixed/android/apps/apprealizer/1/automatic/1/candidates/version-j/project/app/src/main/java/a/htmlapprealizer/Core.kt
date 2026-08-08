package a.htmlapprealizer

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.IDN
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object HostPolicy {
    private val localSchemes = setOf("about", "blob", "content", "data", "file")
    private const val LOCAL_HOST = "html.realizer.local"

    fun normalizeHost(raw: String): String? = runCatching {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        val host = if ("://" in trimmed) Uri.parse(trimmed).host.orEmpty() else trimmed
        IDN.toASCII(host.trim().trimEnd('.')).lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('@') }
    }.getOrNull()

    fun isAllowed(uri: Uri?, store: PolicyStore?): Boolean {
        if (store?.cutHtmlInternet != true) return true
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (scheme in localSchemes) return true
        val host = normalizeHost(uri?.host.orEmpty()) ?: return false
        if (host == LOCAL_HOST) return true
        return synchronized(store) {
            store.domainExceptions.any { exception ->
                host == exception || host.endsWith(".$exception")
            }
        }
    }

    fun blocked(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked by Cut HTML Internet",
        mapOf("Cache-Control" to "no-store"), null
    )
}

object Core {
    internal data class ReflectionTarget(val receiver: Any, val type: Class<*>, val direct: Boolean)
    private data class Held(val value: Any, val epoch: String)
    private data class Delivery(
        val type: String,
        val id: Int,
        val token: String,
        val epoch: String,
        val urgent: Boolean,
        val legacy: Boolean,
        val autoRelease: Boolean
    )
    private data class Match(val values: Array<Any?>, val score: Int)
    private object Missing

    private const val HEAP_LIMIT = 4096
    private const val NORMAL_QUEUE_LIMIT = 1024
    private const val URGENT_QUEUE_LIMIT = 256
    private const val DELIVERY_TIMEOUT_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "html-realizer-bridge").apply { isDaemon = true }
    }
    private val heap = ConcurrentHashMap<Long, Held>()
    private val nextHandle = AtomicLong(1)
    private val ports = ConcurrentHashMap<String, Any>()
    private val waits = ConcurrentHashMap<String, String>()
    private val dropped = AtomicInteger()
    private val pendingNormal = AtomicInteger()
    private val pendingUrgent = AtomicInteger()
    private val normal = ArrayDeque<Delivery>()
    private val urgent = ArrayDeque<Delivery>()
    private val ownerVersion = AtomicInteger()
    private var active: Delivery? = null
    private var deliverySerial = 0L
    private var expiry: Runnable? = null

    private lateinit var app: Context
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var policy: PolicyStore
    private val bridge = BridgeFacade
    @Volatile private var epoch = ""
    @Volatile private var authenticated = false
    @Volatile private var ready = false
    @Volatile private var eventsEnabled = false
    @Volatile private var documentUrl = ""
    @Volatile private var documentBoundaryToken = ""
    @Volatile var web: WebView? = null
        private set

    @Synchronized fun ensure(context: Context): WebView {
        web?.let { return it }
        app = context.applicationContext
        policy = PolicyStore.get(app)
        wrapper = MutableContextWrapper(app)
        val created = WebView(wrapper)
        web = created
        created.settings.javaScriptEnabled = true
        created.settings.domStorageEnabled = true
        created.settings.allowFileAccess = true
        created.settings.allowContentAccess = true
        created.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        created.addJavascriptInterface(bridge, "K")
        created.addJavascriptInterface(bridge, "mirror")
        created.webChromeClient = BrowserPortal
        created.webViewClient = runtimeClient()
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    if (HostPolicy.isAllowed(request.url, policyOrNull())) null else HostPolicy.blocked()
            })
        }.onFailure { DebugLog.add("NET", "ServiceWorkerClient unavailable", it) }
        port("core", this)
        port("app", app)
        port("main", main)
        port("web", created)
        port("bridge", bridge)
        loadDefaultEditor()
        return created
    }

    private fun runtimeClient() = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            ready = false
            documentBoundaryToken = ""
            documentUrl = url.orEmpty()
            revoke("document-start", createReplacement = false)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            documentUrl = url.orEmpty()
            ready = true
            createEpoch("document-finished")
            injectDocumentBoundaryHooks()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            !HostPolicy.isAllowed(request?.url, policyOrNull())

        @Deprecated("Compatibility callback")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            !HostPolicy.isAllowed(url?.let(Uri::parse), policyOrNull())

        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?
        ): WebResourceResponse? = if (HostPolicy.isAllowed(request?.url, policyOrNull())) null
        else HostPolicy.blocked()

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (view != null) recoverRenderer(view, detail)
            return true
        }
    }

    @Synchronized private fun recoverRenderer(dead: WebView, detail: RenderProcessGoneDetail?) {
        if (web !== dead) return
        DebugLog.add("WEB", "renderer gone; crashed=${detail?.didCrash()}")
        revoke("renderer-gone", createReplacement = false)
        (dead.parent as? ViewGroup)?.removeView(dead)
        ports.remove("web", dead)
        web = null
        runCatching { dead.destroy() }
        val activity = currentActivity()
        val fresh = ensure(activity ?: app)
        activity?.mount(fresh)
    }

    fun attach(activity: Main): WebView {
        val view = ensure(activity)
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper.setBaseContext(activity)
        port("activity", activity)
        return view
    }

    fun detach(activity: Main, changingConfiguration: Boolean) {
        if (ports["activity"] !== activity) return
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper.setBaseContext(app)
        port("activity", activity, live = false, deferOwnerCheck = changingConfiguration)
    }

    @Synchronized fun port(
        name: String,
        value: Any,
        live: Boolean = true,
        deferOwnerCheck: Boolean = false
    ): Boolean {
        val changed: Boolean
        val retired: Any?
        if (live) {
            retired = ports.put(name, value)
            changed = retired !== value
        } else {
            changed = ports.remove(name, value)
            retired = if (changed) value else null
        }
        if (!changed) return false
        event("port.${if (live) "ready" else "closed"}", 0, name)
        if (retired != null && ports.values.none { it === retired }) releaseValuesMatching(retired)
        if (name == "activity" || name == "runtime") ownerCheck(if (deferOwnerCheck) 1000 else 0)
        currentActivity()?.refreshNativeState()
        return true
    }

    private fun ownerCheck(delay: Long) {
        val version = ownerVersion.incrementAndGet()
        main.postDelayed({
            if (version == ownerVersion.get() && ports["activity"] == null && ports["runtime"] == null)
                revoke("no-live-owner", createReplacement = false)
        }, delay)
    }

    fun currentActivity(): Main? = ports["activity"] as? Main
    fun policyOrNull(): PolicyStore? = if (::policy.isInitialized) policy else null
    fun currentEpoch(): String = epoch
    fun isAuthenticated(): Boolean = authenticated && epoch.isNotEmpty()
    fun isTrusted(): Boolean = epoch.isNotEmpty() && policyOrNull()?.bridgeEnabled == true
    fun eventsActive(): Boolean = eventsEnabled && isTrusted()
    fun heapSize(): Int = heap.size
    fun droppedEvents(): Int = dropped.get()
    fun documentLabel(): String = when {
        documentUrl.startsWith("data:") -> "DATA"
        documentUrl.startsWith("file:") -> "FILE"
        documentUrl.startsWith("about:") -> "LOCAL"
        else -> documentUrl.substringAfter("://", documentUrl.substringBefore(':'))
            .substringBefore('/').take(18).ifBlank { "PAGE" }
    }

    @Synchronized private fun createEpoch(reason: String) {
        val store = policyOrNull() ?: return
        epoch = if (ready && store.bridgeEnabled) UUID.randomUUID().toString() else ""
        authenticated = epoch.isNotEmpty() && store.password.isEmpty()
        eventsEnabled = false
        if (epoch.isNotEmpty()) {
            val script = "window.KC=${JSONObject.quote(epoch)};window.dispatchEvent(new CustomEvent('realizer-epoch',{detail:window.KC}))"
            web?.evaluateJavascript(script, null)
        }
        DebugLog.add("EPOCH", "$reason ${if (epoch.isEmpty()) "closed" else "opened"}")
        currentActivity()?.refreshNativeState()
    }

    fun revoke(reason: String, createReplacement: Boolean = true) {
        synchronized(this) {
            epoch = ""
            authenticated = false
            eventsEnabled = false
            waits.clear()
            clearDeliveries()
            clearHandles()
            dropped.set(0)
        }
        GlobalService.instance?.closeOverlay("epoch-revoked")
        DebugLog.add("EPOCH", "revoked: $reason")
        if (createReplacement && ready && policyOrNull()?.bridgeEnabled == true)
            synchronized(this) { createEpoch(reason) }
        else currentActivity()?.refreshNativeState()
    }

    fun authenticate(candidate: String): Boolean {
        if (!masterOpen()) return false
        authenticated = candidate == policy.password
        DebugLog.add("AUTH", if (authenticated) "accepted" else "rejected")
        currentActivity()?.refreshNativeState()
        return authenticated
    }

    private fun masterOpen(): Boolean =
        ready && epoch.isNotEmpty() && policyOrNull()?.bridgeEnabled == true

    fun validateEpoch(candidate: String? = null, requireAuth: Boolean = true): String? {
        if (!masterOpen()) return "E:SEC"
        if (candidate != null && candidate != epoch) return "E:STALE"
        if (requireAuth && !isAuthenticated()) return "E:AUTH"
        return null
    }

    fun setEvents(enabled: Boolean): Boolean {
        if (validateEpoch() != null) return false
        eventsEnabled = enabled
        return eventsEnabled
    }

    fun capabilities(): JSONArray = JSONArray().apply { ports.keys.sorted().forEach(::put) }
    fun portValue(name: String): Any? = ports[name]

    fun loadDefaultEditor() {
        ready = false
        revoke("default-editor", createReplacement = false)
        web?.loadDataWithBaseURL(DEFAULT_ORIGIN, DEFAULT_EDITOR, "text/html", "utf-8", null)
    }

    fun recreateWebView() {
        val dead = web ?: return
        recoverRenderer(dead, null)
    }

    fun evaluate(script: String, callback: ((String) -> Unit)? = null) {
        main.post { web?.evaluateJavascript(script, callback) }
    }

    private fun injectDocumentBoundaryHooks() {
        val script = """(()=>{if(Document.prototype.__realizerJ)return;const o=Document.prototype.open,w=Document.prototype.write,c=Document.prototype.close;Object.defineProperty(Document.prototype,'__realizerJ',{value:true});Document.prototype.open=function(){window.__realizerBoundaryToken=window.KC||'';try{K.boundary(window.__realizerBoundaryToken,'open')}catch(e){};return o.apply(this,arguments)};Document.prototype.write=function(){if(!window.__realizerBoundaryToken){window.__realizerBoundaryToken=window.KC||'';try{K.boundary(window.__realizerBoundaryToken,'open')}catch(e){}}return w.apply(this,arguments)};Document.prototype.close=function(){const r=c.apply(this,arguments);try{K.boundary(window.__realizerBoundaryToken||'','close')}catch(e){};window.__realizerBoundaryToken='';return r}})()"""
        web?.evaluateJavascript(script, null)
    }

    fun documentBoundary(candidate: String, stage: String): Boolean = when (stage) {
        "open" -> {
            if (candidate.isEmpty() || candidate != epoch || policyOrNull()?.bridgeEnabled != true) false
            else {
                documentBoundaryToken = candidate
                ready = false
                revoke("document-open", createReplacement = false)
                true
            }
        }
        "close" -> {
            if (candidate.isEmpty() || candidate != documentBoundaryToken || policyOrNull()?.bridgeEnabled != true)
                false
            else {
                documentBoundaryToken = ""
                ready = true
                synchronized(this) { createEpoch("document-close") }
                injectDocumentBoundaryHooks()
                true
            }
        }
        else -> false
    }

    private fun tokenIndex(token: String): Long? =
        if (token.length > 1 && token[0] == 'P') token.drop(1).toLongOrNull() else null

    internal fun resolveValue(raw: Any?, classes: Boolean = false): Any? {
        if (raw === JSONObject.NULL) return null
        if (raw !is String) return raw
        if (raw.startsWith("PP") || raw.startsWith("@@")) return raw.drop(1)
        val index = tokenIndex(raw)
        if (index == 0L) return currentActivity() ?: app
        return when {
            index != null -> heap[index]?.takeIf { it.epoch == epoch }?.value ?: Missing
            raw.startsWith("@") -> ports[raw.drop(1)] ?: Missing
            classes -> Class.forName(raw, false, app.classLoader)
            else -> raw
        }
    }

    private fun copyLease(value: Any?): Any? = when (value) {
        is android.view.accessibility.AccessibilityEvent ->
            android.view.accessibility.AccessibilityEvent.obtain(value)
        is android.view.KeyEvent -> android.view.KeyEvent(value)
        is android.view.MotionEvent -> android.view.MotionEvent.obtain(value)
        else -> value
    }

    private fun release(value: Any?) {
        when (value) {
            is android.view.accessibility.AccessibilityEvent -> runCatching { value.recycle() }
            is android.view.accessibility.AccessibilityNodeInfo -> runCatching { value.recycle() }
            is android.view.MotionEvent -> runCatching { value.recycle() }
            is EpochCleanup -> value.close()
            is AutoCloseable -> runCatching { value.close() }
        }
    }

    private fun releaseValuesMatching(value: Any) {
        heap.entries.forEach { entry ->
            if (entry.value.value === value) heap.remove(entry.key, entry.value)
        }
        releaseIfUnreferenced(value)
    }

    @Synchronized internal fun keep(value: Any?, born: String = epoch): String {
        return when (value) {
            null, is Unit -> "V"
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is Char, is String -> "V$value"
            else -> {
                if (born.isEmpty() || born != epoch) return "E:STALE"
                if (heap.size >= HEAP_LIMIT) return "E:HEAP"
                val id = nextHandle.getAndIncrement()
                heap[id] = Held(value, born)
                "P$id"
            }
        }
    }

    fun retain(token: String): String {
        val value = resolveValue(token)
        return if (value === Missing) "E:HANDLE" else keep(copyLease(value))
    }

    @Synchronized fun drop(token: String): Boolean {
        val index = tokenIndex(token) ?: return false
        if (index == 0L) return false
        val held = heap.remove(index) ?: return false
        releaseIfUnreferenced(held.value)
        return true
    }

    @Synchronized fun clearHandles() {
        val values = heap.values.map { it.value }
        heap.clear()
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        values.forEach { value -> if (seen.put(value, true) == null) releaseIfUnreferenced(value) }
    }

    private fun releaseIfUnreferenced(value: Any) {
        if (heap.values.any { it.value === value } || ports.values.any { it === value }) return
        release(value)
    }

    private fun lease(value: Any?, born: String): String = keep(copyLease(value), born)

    fun event(type: String, id: Int = 0, value: Any? = null): Boolean {
        val born = epoch
        if (!eventsEnabled || born.isEmpty()) return false
        return queue(type, id, lease(value, born), born, urgentDelivery = false, legacy = false, autoRelease = true)
    }

    fun portal(type: String, value: Any?): Boolean {
        val born = epoch
        if (!eventsEnabled || born.isEmpty()) return false
        return queue(type, 0, lease(value, born), born, urgentDelivery = true, legacy = false, autoRelease = true)
    }

    fun result(type: String, id: Int, value: Any?, born: String = epoch, legacy: Boolean = false) {
        if (born.isEmpty() || born != epoch) return
        queue(type, id, keep(value, born), born, urgentDelivery = true, legacy = legacy, autoRelease = false)
    }

    private fun queue(
        type: String,
        id: Int,
        token: String,
        born: String,
        urgentDelivery: Boolean,
        legacy: Boolean,
        autoRelease: Boolean
    ): Boolean {
        if (token.startsWith("E:") && type != "return") return false
        val pending = if (urgentDelivery) pendingUrgent else pendingNormal
        val limit = if (urgentDelivery) URGENT_QUEUE_LIMIT else NORMAL_QUEUE_LIMIT
        if (pending.incrementAndGet() > limit) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            if (autoRelease) drop(token)
            return false
        }
        val delivery = Delivery(type, id, token, born, urgentDelivery, legacy, autoRelease)
        if (!main.post { enqueue(delivery) }) {
            discard(delivery)
            return false
        }
        return true
    }

    @Synchronized private fun enqueue(delivery: Delivery) {
        if (delivery.epoch != epoch || !ready || web == null) {
            discard(delivery)
            return
        }
        (if (delivery.urgent) urgent else normal).addLast(delivery)
        pump()
    }

    @Synchronized private fun pump() {
        if (active != null) return
        while (true) {
            val delivery = when {
                urgent.isNotEmpty() -> urgent.removeFirst()
                normal.isNotEmpty() -> normal.removeFirst()
                else -> return
            }
            if (delivery.epoch != epoch || !ready || web == null) {
                discard(delivery)
                continue
            }
            active = delivery
            val serial = ++deliverySerial
            val timeout = Runnable { finish(serial, delivered = false) }
            expiry = timeout
            main.postDelayed(timeout, DELIVERY_TIMEOUT_MS)
            val quotedType = JSONObject.quote(delivery.type)
            val quotedToken = JSONObject.quote(delivery.token)
            val script = if (delivery.legacy)
                "window.onC&&window.onC(${delivery.id},$quotedToken)"
            else "window.onK&&window.onK($quotedType,${delivery.id},$quotedToken)"
            runCatching {
                web!!.evaluateJavascript(script) { main.post { finish(serial, delivered = true) } }
            }.onFailure { finish(serial, delivered = false) }
            return
        }
    }

    @Synchronized private fun finish(serial: Long, delivered: Boolean) {
        if (serial != deliverySerial) return
        expiry?.let(main::removeCallbacks)
        expiry = null
        val delivery = active ?: return
        active = null
        if (!delivered || delivery.autoRelease) drop(delivery.token)
        decrementPending(delivery)
        pump()
    }

    @Synchronized private fun clearDeliveries() {
        expiry?.let(main::removeCallbacks)
        expiry = null
        active?.let(::discard)
        active = null
        urgent.forEach(::discard)
        normal.forEach(::discard)
        urgent.clear()
        normal.clear()
        deliverySerial++
    }

    private fun discard(delivery: Delivery) {
        drop(delivery.token)
        decrementPending(delivery)
    }

    private fun decrementPending(delivery: Delivery) {
        (if (delivery.urgent) pendingUrgent else pendingNormal).decrementAndGet()
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        else -> type
    }

    private fun numberRank(type: Class<*>): Int = when (boxed(type)) {
        Byte::class.javaObjectType -> 0
        Short::class.javaObjectType -> 1
        Int::class.javaObjectType -> 2
        Long::class.javaObjectType -> 3
        Float::class.javaObjectType -> 4
        Double::class.javaObjectType -> 5
        else -> -1
    }

    internal fun coerceFor(raw: Any?, type: Class<*>): Pair<Any?, Int> {
        val value = resolveValue(raw)
        if (value === Missing) throw IllegalArgumentException("missing handle or port")
        if (value == null) {
            if (type.isPrimitive) throw IllegalArgumentException("null for primitive ${type.name}")
            return null to 7
        }
        val target = boxed(type)
        if (target == value.javaClass) return value to 0
        if (target.isInstance(value)) return value to 1
        if (value is Number && numberRank(target) >= 0) {
            val converted: Any = when (target) {
                Byte::class.javaObjectType -> value.toByte()
                Short::class.javaObjectType -> value.toShort()
                Int::class.javaObjectType -> value.toInt()
                Long::class.javaObjectType -> value.toLong()
                Float::class.javaObjectType -> value.toFloat()
                else -> value.toDouble()
            }
            val sourceRank = numberRank(value.javaClass)
            val targetRank = numberRank(target)
            val distance = if (sourceRank < 0) 4 else if (targetRank >= sourceRank)
                targetRank - sourceRank else 5 + sourceRank - targetRank
            return converted to 2 + distance
        }
        if (target == Boolean::class.javaObjectType && value is String) return when (value.lowercase()) {
            "true" -> true to 6
            "false" -> false to 6
            else -> throw IllegalArgumentException("not a boolean")
        }
        if (target == Char::class.javaObjectType) return when (value) {
            is Number -> value.toInt().toChar() to 7
            is String -> value.singleOrNull()?.let { it to 6 }
                ?: throw IllegalArgumentException("not one character")
            else -> throw IllegalArgumentException("not a character")
        }
        if (type.isEnum && value is String) {
            val constant = type.enumConstants?.firstOrNull {
                (it as Enum<*>).name.equals(value, ignoreCase = false)
            } ?: throw IllegalArgumentException("unknown enum ${type.name}.$value")
            return constant to 6
        }
        if (type.isArray && value is JSONArray) {
            val component = type.componentType ?: throw IllegalArgumentException("array has no component")
            val array = java.lang.reflect.Array.newInstance(component, value.length())
            var score = 8
            for (index in 0 until value.length()) {
                val converted = coerceFor(value.get(index), component)
                java.lang.reflect.Array.set(array, index, converted.first)
                score += converted.second
            }
            return array to score
        }
        throw IllegalArgumentException("${value.javaClass.name} is incompatible with ${type.name}")
    }

    private fun match(executable: Executable, args: JSONArray): Match? = runCatching {
        val types = executable.parameterTypes
        if (!executable.isVarArgs) {
            if (types.size != args.length()) return@runCatching null
            var score = 0
            val values = Array<Any?>(types.size) { index ->
                coerceFor(args.get(index), types[index]).also { score += it.second }.first
            }
            return@runCatching Match(values, score)
        }
        val fixed = types.size - 1
        if (args.length() < fixed) return@runCatching null
        var score = 0
        val values = arrayOfNulls<Any?>(types.size)
        for (index in 0 until fixed) {
            val converted = coerceFor(args.get(index), types[index])
            values[index] = converted.first
            score += converted.second
        }
        if (args.length() == types.size) {
            runCatching { coerceFor(args.get(fixed), types[fixed]) }.getOrNull()?.let {
                values[fixed] = it.first
                return@runCatching Match(values, score + it.second)
            }
        }
        val component = types[fixed].componentType ?: return@runCatching null
        val packed = java.lang.reflect.Array.newInstance(component, args.length() - fixed)
        for (index in fixed until args.length()) {
            val converted = coerceFor(args.get(index), component)
            java.lang.reflect.Array.set(packed, index - fixed, converted.first)
            score += converted.second
        }
        values[fixed] = packed
        Match(values, score + 2)
    }.getOrNull()

    private fun request(
        engine: String,
        operation: String,
        type: Class<*>,
        member: String,
        signature: String
    ) = BridgeRequest(engine, operation, type.name, member, signature)

    internal fun authorized(request: BridgeRequest): Boolean =
        PolicyEngine.authorize(request) == Authorization.ALLOW

    internal fun resolveReflectionTarget(target: String): ReflectionTarget? {
        val direct = tokenIndex(target) == null && !target.startsWith("@") && !target.startsWith("PP")
        val receiver = resolveValue(target, classes = direct)
        if (receiver === Missing || receiver == null) return null
        return ReflectionTarget(receiver, if (direct) receiver as Class<*> else receiver.javaClass, direct)
    }

    internal fun bridgeFailure(error: Throwable): String = failure(error)

    private fun failure(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException else error
        ports["lastError"] = cause
        DebugLog.add("BRIDGE", "invocation failure", cause)
        return "E:THROW:${cause.javaClass.name}:${cause.message.orEmpty()}"
    }

    private fun field(target: String, member: String, args: JSONArray): String {
        val direct = tokenIndex(target) == null && !target.startsWith("@") && !target.startsWith("PP")
        val receiver = resolveValue(target, classes = direct)
        if (receiver === Missing) return "E:HANDLE"
        val type = if (direct) receiver as Class<*> else receiver?.javaClass ?: return "E:NUL"
        val set = member.startsWith("#set:")
        val name = member.substringAfter(':')
        val found: Field = runCatching { type.getField(name) }.getOrElse { return "E:FIELD" }
        val operation = if (set) "field-set" else "field-get"
        val policyRequest = request("java", operation, type, name, found.toGenericString())
        if (!authorized(policyRequest)) return "E:POLICY"
        return try {
            val host = if (Modifier.isStatic(found.modifiers)) null else receiver
            if (set) {
                if (args.length() != 1) return "E:ARGS"
                found.set(host, coerceFor(args.get(0), found.type).first)
                "V"
            } else keep(found.get(host))
        } catch (error: Throwable) { failure(error) }
    }

    private fun newArray(target: String, args: JSONArray): String {
        if (args.length() != 1) return "E:ARGS"
        val type = runCatching { Class.forName(target, false, app.classLoader) }.getOrElse { return "E:CLASS" }
        val policyRequest = request("java", "array-new", type, "[]", "java:array:${type.name}")
        if (!authorized(policyRequest)) return "E:POLICY"
        return runCatching { keep(java.lang.reflect.Array.newInstance(type, args.getInt(0))) }
            .getOrElse(::failure)
    }

    private fun proxy(target: String, member: String, args: JSONArray, born: String): String {
        val type = runCatching { Class.forName(target, false, app.classLoader) }.getOrElse { return "E:CLASS" }
        if (!type.isInterface) return "E:TYPE"
        val policyRequest = request("java", "proxy", type, member, "java:proxy:${type.name}")
        if (!authorized(policyRequest)) return "E:POLICY"
        val configured = if (args.length() > 0) args.get(0) else Missing
        val returns = configured as? JSONObject
        val channel = member.removePrefix("@proxy").trimStart(':').ifBlank { "default" }
        val created = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, values ->
            if (method.declaringClass == Any::class.java) return@newProxyInstance when (method.name) {
                "toString" -> "BridgeProxy($channel)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values?.firstOrNull()
                else -> null
            }
            if (born != epoch) return@newProxyInstance zero(method.returnType)
            val token = lease(values ?: emptyArray<Any?>(), born)
            queue("proxy.$channel.${method.name}", 0, token, born, false, false, true)
            val raw = when {
                returns?.has(method.name) == true -> returns.get(method.name)
                returns?.has("*") == true -> returns.get("*")
                returns == null -> configured
                else -> Missing
            }
            if (raw === Missing) zero(method.returnType)
            else runCatching { coerceFor(raw, method.returnType).first }.getOrElse { zero(method.returnType) }
        }
        return keep(created, born)
    }

    private fun zero(type: Class<*>): Any? =
        if (!type.isPrimitive || type == Void.TYPE) null
        else java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0)

    private fun invokeOnMain(task: () -> Any?): Any? {
        if (Looper.myLooper() == Looper.getMainLooper()) return task()
        val future = FutureTask<Any?>(java.util.concurrent.Callable { task() })
        if (!main.post(future)) throw IllegalStateException("main queue rejected call")
        return future.get(30, TimeUnit.SECONDS)
    }

    private fun javaCall(target: String, memberRaw: String, args: JSONArray, born: String): String {
        var member = memberRaw
        val onMain = member.startsWith("!")
        if (onMain) member = member.drop(1)
        val deferred = member.startsWith("~")
        if (deferred) member = member.drop(1)
        val direct = tokenIndex(target) == null && !target.startsWith("@") && !target.startsWith("PP")
        val receiver = resolveValue(target, classes = direct)
        if (receiver === Missing) return if (target.startsWith("@")) "E:PORT" else "E:HANDLE"
        if (receiver == null) return "E:NUL"
        val type = if (direct) receiver as Class<*> else receiver.javaClass
        val candidates: Sequence<Executable> = if (member.isEmpty() && !deferred) {
            if (!direct) return "E:TYPE"
            type.constructors.asSequence()
        } else type.methods.asSequence().filter {
            it.name == member && (!direct || Modifier.isStatic(it.modifiers))
        }
        val matches = candidates.mapNotNull { candidate -> match(candidate, args)?.let { candidate to it } }
            .sortedWith(compareBy({ it.second.score }, { it.first.toGenericString() }))
            .toList()
        if (matches.isEmpty()) return "E:SIG"
        val (candidate, matched) = matches.first()
        val operation = when {
            candidate is Constructor<*> -> "constructor"
            deferred -> "cleanup"
            else -> "method"
        }
        val policyRequest = request("java", operation, type, member, candidate.toGenericString())
        if (!authorized(policyRequest)) return "E:POLICY"
        return try {
            if (deferred) {
                if (candidate !is Method) return "E:TYPE"
                keep(EpochCleanup(if (direct) null else receiver, candidate, matched.values, main), born)
            } else {
                val call = {
                    if (candidate is Constructor<*>) candidate.newInstance(*matched.values)
                    else (candidate as Method).invoke(if (direct) null else receiver, *matched.values)
                }
                keep(if (onMain) invokeOnMain(call) else call(), born)
            }
        } catch (error: Throwable) { failure(error) }
    }

    fun javaLookup(className: String): String {
        val gate = validateEpoch() ?: ""
        if (gate.isNotEmpty()) return gate
        val type = runCatching { Class.forName(className, false, app.classLoader) }.getOrElse { return "E:CLASS" }
        val policyRequest = request("java", "class-lookup", type, "", "java:class:${type.name}")
        return if (authorized(policyRequest)) keep(type) else "E:POLICY"
    }

    fun executeJava(target: String, member: String, json: String, born: String = epoch): String {
        validateEpoch(born)?.let { return it }
        if (member == "+") return retain(target)
        if (member == "-") return if (drop(target)) "V" else "E:HANDLE"
        return try {
            val args = JSONArray(json)
            when {
                member.startsWith("#get:") || member.startsWith("#set:") -> field(target, member, args)
                member == "#array" -> newArray(target, args)
                member.startsWith("@proxy") -> proxy(target, member, args, born)
                else -> javaCall(target, member, args, born)
            }
        } catch (error: Throwable) { failure(error) }
    }

    fun executeKotlin(target: String, member: String, json: String, born: String = epoch): String {
        validateEpoch(born)?.let { return it }
        return KotlinDynamic.execute(target, member, json, born)
    }

    fun async(
        engine: String,
        target: String,
        member: String,
        json: String,
        callback: Int,
        legacy: Boolean = false
    ): String {
        val born = epoch
        validateEpoch(born)?.let { return it }
        if (callback <= 0) return "E:CALLBACK"
        val pinned = runCatching { pinCall(target, member, json, born) }
            .getOrElse { return failure(it) }
        workers.execute {
            try {
                val value = if (born != epoch) "E:STALE" else if (engine == "kotlin")
                    executeKotlin(pinned.target, member, pinned.json, born)
                else executeJava(pinned.target, member, pinned.json, born)
                if (born == epoch) queue("return", callback, value, born, true, legacy, false)
            } finally {
                pinned.pins.forEach(::drop)
            }
        }
        return "W$callback"
    }

    private data class PinnedCall(val target: String, val json: String, val pins: Set<String>)

    private fun pinCall(target: String, member: String, json: String, born: String): PinnedCall {
        val mapped = HashMap<String, String>()
        val pins = linkedSetOf<String>()
        fun pin(raw: String): String {
            if (raw == "P0") return raw
            mapped[raw]?.let { return it }
            val index = tokenIndex(raw) ?: return raw
            val held = heap[index]?.takeIf { it.epoch == born }
                ?: throw IllegalArgumentException("missing handle $raw")
            return keep(copyLease(held.value), born).also { copy ->
                if (copy.startsWith("E:")) throw IllegalStateException(copy)
                if (copy.startsWith('P')) {
                    mapped[raw] = copy
                    pins += copy
                }
            }
        }
        fun copy(value: Any?): Any? = when (value) {
            is String -> pin(value)
            is JSONArray -> JSONArray().also { output ->
                for (index in 0 until value.length()) output.put(copy(value.get(index)))
            }
            is JSONObject -> JSONObject().also { output ->
                value.keys().forEach { key -> output.put(key, copy(value.get(key))) }
            }
            else -> value
        }
        val input = if (json.trimStart().startsWith('{')) JSONObject(json) else JSONArray(json)
        val pinnedJson = copy(input).toString()
        val pinnedTarget = if (member == "-") target else pin(target)
        return PinnedCall(pinnedTarget, pinnedJson, pins)
    }

    fun runTsv(source: String, callback: Int): String {
        val born = epoch
        validateEpoch(born)?.let { return it }
        if (callback <= 0) return "E:CALLBACK"
        val lines = source.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
            .take(513).toList()
        if (lines.size > 512) return "E:LIMIT"
        workers.execute {
            val results = JSONArray()
            for (line in lines) {
                if (born != epoch) break
                val columns = line.split('\t', limit = 4)
                if (columns.size < 3) {
                    results.put("E:TSV")
                    continue
                }
                val engine = if (columns.size == 4) columns[0] else "java"
                val offset = if (columns.size == 4) 1 else 0
                val target = columns[offset]
                val member = columns[offset + 1]
                val arguments = columns.getOrElse(offset + 2) { "[]" }
                results.put(if (engine == "kotlin") executeKotlin(target, member, arguments, born)
                else executeJava(target, member, arguments, born))
            }
            if (born == epoch) result("tsv", callback, results.toString(), born)
        }
        return "W$callback"
    }

    fun legacyProxy(id: Int, method: String, values: Array<out Any?>?, born: String) {
        if (born.isEmpty() || born != epoch) return
        val tokens = JSONArray()
        values?.forEach { tokens.put(keep(copyLease(it), born)) }
        val script = "window.onL&&window.onL($id,${JSONObject.quote(method)},${JSONObject.quote(tokens.toString())})"
        main.post {
            if (born == epoch && ready && web != null)
                web?.evaluateJavascript(script, null)
        }
    }

    const val DEFAULT_ORIGIN = "https://html.realizer.local/"
    const val DEFAULT_EDITOR = "<!DOCTYPE html><title>HTML Realizer</title><style>body{margin:0;padding:4px;background:#111;font-family:monospace}div{min-height:40vh;background:#222;color:#eee;border:solid #444;padding:8px;white-space:pre-wrap;margin-bottom:4px;overflow:auto}button{border:0;background:#058;color:#fff;width:100%;height:5vh;font-size:2vh}</style><div id=i contenteditable=\"plaintext-only\" oninput=\"c.textContent=i.textContent.length\"></div><button onclick=\"var code=i.textContent;document.open();document.write(code);document.close()\">Realize (<b id=c>0</b> characters)</button>\n"
}

class EpochCleanup internal constructor(
    private val receiver: Any?,
    private val method: Method,
    private val arguments: Array<Any?>,
    private val handler: Handler
) : AutoCloseable {
    @Volatile private var pending = true
    @Synchronized fun cancel() { pending = false }
    @Synchronized override fun close() {
        if (!pending) return
        pending = false
        val task = Runnable { runCatching { method.invoke(receiver, *arguments) } }
        if (!handler.post(task)) task.run()
    }
}
