package a.htmlapprealizer

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object DebugLog {
    private const val LIMIT = 500
    private val entries = ArrayDeque<String>()
    private val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    @Synchronized fun add(area: String, message: String, error: Throwable? = null) {
        val detail = buildString {
            append(clock.format(Date())).append(' ').append(area).append(' ').append(message)
            error?.let { append(" | ").append(it.javaClass.name).append(": ").append(it.message.orEmpty()) }
        }
        entries.addLast(detail)
        while (entries.size > LIMIT) entries.removeFirst()
        if (error == null) Log.d("HTMLRealizerJ", "$area: $message")
        else Log.e("HTMLRealizerJ", "$area: $message", error)
    }

    @Synchronized fun report(): String = entries.joinToString("\n")
    @Synchronized fun clear() = entries.clear()
}

data class BridgeRequest(
    val engine: String,
    val operation: String,
    val className: String,
    val member: String,
    val signature: String
) {
    private val normalizedClass = className.trim().lowercase(Locale.ROOT)
    val packageName: String = normalizedClass.substringBeforeLast('.', "")
    val fullText: String = "$engine $operation $className $member $signature".lowercase(Locale.ROOT)

    fun scopes(): List<String> = buildList {
        add("signature:${signature.trim().lowercase(Locale.ROOT)}")
        if (member.isNotBlank()) add("member:$normalizedClass#${member.trim().lowercase(Locale.ROOT)}")
        add("class:$normalizedClass")
        if (packageName.isNotBlank()) add("package:$packageName")
        add("all")
    }.distinct()
}

enum class RuleDecision { BLACK, WHITE, GRAY, UNMATCHED }

object PolicyMatcher {
    fun normalizeRule(raw: String): String {
        val rule = raw.trim().lowercase(Locale.ROOT)
        if (rule == "*") return "all"
        if (rule == "all") return rule
        return if (rule.substringBefore(':') in setOf("package", "class", "member", "signature", "legacy"))
            rule else "legacy:$rule"
    }

    fun matches(rule: String, request: BridgeRequest): Boolean {
        val normalized = normalizeRule(rule)
        if (normalized == "all") return true
        if (normalized.startsWith("legacy:")) {
            val needle = normalized.removePrefix("legacy:")
            return needle.isNotEmpty() && request.fullText.contains(needle)
        }
        if (normalized.startsWith("package:")) {
            val allowed = normalized.removePrefix("package:").trimEnd('.')
            return request.packageName == allowed || request.packageName.startsWith("$allowed.")
        }
        return normalized in request.scopes()
    }

    fun decide(
        request: BridgeRequest,
        black: Collection<String>,
        gray: Collection<String>,
        white: Collection<String>
    ): RuleDecision = when {
        black.any { matches(it, request) } -> RuleDecision.BLACK
        white.any { matches(it, request) } -> RuleDecision.WHITE
        gray.any { matches(it, request) } -> RuleDecision.GRAY
        else -> RuleDecision.UNMATCHED
    }
}

class PolicyStore private constructor(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("Z", Context.MODE_PRIVATE)

    @Volatile var bridgeEnabled = preferences.getBoolean(KEY_BRIDGE, false)
        private set
    @Volatile var sandboxEnabled = preferences.getBoolean(KEY_SANDBOX, true)
        private set
    @Volatile var password = preferences.getString(KEY_PASSWORD, "").orEmpty()
        private set
    @Volatile var cutHtmlInternet = preferences.getBoolean(KEY_NET_CUT, false)
        private set
    @Volatile var visibilityMode = preferences.getInt(KEY_VISIBILITY, 0)
        private set

    val black = linkedSetOf<String>()
    val gray = linkedSetOf<String>()
    val white = linkedSetOf<String>()
    val domainExceptions = linkedSetOf<String>()

    init {
        migrateLegacyRules()
        replace(black, preferences.getString(KEY_BLACK, "").orEmpty())
        replace(gray, preferences.getString(KEY_GRAY, "").orEmpty())
        replace(white, preferences.getString(KEY_WHITE, "").orEmpty())
        replace(domainExceptions, preferences.getString(KEY_DOMAINS, "").orEmpty(), normalize = false)
    }

    @Synchronized fun setBridge(value: Boolean) {
        bridgeEnabled = value
        preferences.edit().putBoolean(KEY_BRIDGE, value).apply()
        Core.revoke(if (value) "bridge-enabled" else "bridge-disabled")
    }

    @Synchronized fun setSandbox(value: Boolean) {
        sandboxEnabled = value
        preferences.edit().putBoolean(KEY_SANDBOX, value).apply()
        Core.revoke("sandbox-changed")
    }

    @Synchronized fun setPassword(value: String) {
        password = value
        preferences.edit().putString(KEY_PASSWORD, value).apply()
        Core.revoke("password-changed")
    }

    @Synchronized fun setCutHtmlInternet(value: Boolean) {
        cutHtmlInternet = value
        preferences.edit().putBoolean(KEY_NET_CUT, value).apply()
    }

    @Synchronized fun setVisibilityMode(value: Int) {
        visibilityMode = value.coerceIn(0, 3)
        with(preferences.edit()) {
            if (visibilityMode == 3) putInt(KEY_VISIBILITY, 3) else remove(KEY_VISIBILITY)
            apply()
        }
    }

    @Synchronized fun setRules(kind: RuleDecision, value: String) {
        val target = when (kind) {
            RuleDecision.BLACK -> black
            RuleDecision.GRAY -> gray
            RuleDecision.WHITE -> white
            RuleDecision.UNMATCHED -> return
        }
        replace(target, value)
        saveRules(kind)
    }

    @Synchronized fun addRule(kind: RuleDecision, value: String) {
        val target = when (kind) {
            RuleDecision.BLACK -> black
            RuleDecision.GRAY -> gray
            RuleDecision.WHITE -> white
            RuleDecision.UNMATCHED -> return
        }
        target += PolicyMatcher.normalizeRule(value)
        saveRules(kind)
    }

    @Synchronized fun setDomains(value: String) {
        replace(domainExceptions, value, normalize = false)
        preferences.edit().putString(KEY_DOMAINS, domainExceptions.joinToString("\n")).apply()
    }

    @Synchronized fun ruleText(kind: RuleDecision): String = when (kind) {
        RuleDecision.BLACK -> black
        RuleDecision.GRAY -> gray
        RuleDecision.WHITE -> white
        RuleDecision.UNMATCHED -> emptySet()
    }.joinToString("\n")

    @Synchronized fun domainText(): String = domainExceptions.joinToString("\n")

    private fun replace(target: MutableSet<String>, text: String, normalize: Boolean = true) {
        target.clear()
        text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).forEach {
            target += if (normalize) PolicyMatcher.normalizeRule(it) else HostPolicy.normalizeHost(it).orEmpty()
        }
        target.remove("")
    }

    private fun saveRules(kind: RuleDecision) {
        val (key, values) = when (kind) {
            RuleDecision.BLACK -> KEY_BLACK to black
            RuleDecision.GRAY -> KEY_GRAY to gray
            RuleDecision.WHITE -> KEY_WHITE to white
            RuleDecision.UNMATCHED -> return
        }
        preferences.edit().putString(key, values.joinToString("\n")).apply()
    }

    private fun migrateLegacyRules() {
        if (preferences.getBoolean(KEY_MIGRATED, false)) return
        val editor = preferences.edit()
        listOf("0" to KEY_BLACK, "1" to KEY_GRAY, "2" to KEY_WHITE).forEach { (old, current) ->
            val migrated = preferences.getString(old, "").orEmpty()
                .split(',').map(String::trim).filter(String::isNotEmpty)
                .joinToString("\n") { "legacy:$it" }
            if (migrated.isNotEmpty() && preferences.getString(current, "").isNullOrEmpty())
                editor.putString(current, migrated)
        }
        editor.putBoolean(KEY_MIGRATED, true).apply()
    }

    companion object {
        private const val KEY_BRIDGE = "J.bridge"
        private const val KEY_SANDBOX = "S"
        private const val KEY_PASSWORD = "W"
        private const val KEY_NET_CUT = "N"
        private const val KEY_VISIBILITY = "J.visibility"
        private const val KEY_BLACK = "J.policy.black"
        private const val KEY_GRAY = "J.policy.gray"
        private const val KEY_WHITE = "J.policy.white"
        private const val KEY_DOMAINS = "D"
        private const val KEY_MIGRATED = "J.policy.migrated"
        @Volatile private var instance: PolicyStore? = null

        fun get(context: Context): PolicyStore = instance ?: synchronized(this) {
            instance ?: PolicyStore(context).also { instance = it }
        }
    }
}

enum class Authorization { ALLOW, DENY }

object PolicyEngine {
    fun authorize(request: BridgeRequest): Authorization {
        val store = Core.policyOrNull() ?: return deny(request, "policy-unavailable")
        if (!store.bridgeEnabled) return deny(request, "bridge-off")
        if (store.sandboxEnabled) return deny(request, "sandbox-on")
        if (!Core.isAuthenticated()) return deny(request, "authentication-required")

        val decision = synchronized(store) {
            PolicyMatcher.decide(request, store.black, store.gray, store.white)
        }
        return when (decision) {
            RuleDecision.BLACK -> deny(request, "blacklist")
            RuleDecision.WHITE -> allow(request, "whitelist")
            RuleDecision.GRAY, RuleDecision.UNMATCHED -> prompt(request, decision)
        }
    }

    private fun prompt(request: BridgeRequest, source: RuleDecision): Authorization {
        val activity = Core.currentActivity() ?: return deny(request, "no-native-host")
        if (Looper.myLooper() == Looper.getMainLooper()) return deny(request, "prompt-on-main-thread")
        val latch = CountDownLatch(1)
        var result = Authorization.DENY
        val scopes = request.scopes().toTypedArray()
        var selected = 0
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            AlertDialog.Builder(activity)
                .setTitle("Reflection request (${request.engine})")
                .setMessage("${request.operation}: ${request.className}#${request.member}\n${request.signature}")
                .setSingleChoiceItems(scopes, selected) { _, which -> selected = which }
                .setPositiveButton("Allow once") { _, _ ->
                    result = Authorization.ALLOW
                    latch.countDown()
                }
                .setNeutralButton("Always allow") { _, _ ->
                    Core.policyOrNull()?.addRule(RuleDecision.WHITE, scopes[selected])
                    result = Authorization.ALLOW
                    latch.countDown()
                }
                .setNegativeButton("Block") { _, _ ->
                    Core.policyOrNull()?.addRule(RuleDecision.BLACK, scopes[selected])
                    latch.countDown()
                }
                .setOnCancelListener { latch.countDown() }
                .setOnDismissListener { latch.countDown() }
                .show()
        }
        if (!latch.await(120, TimeUnit.SECONDS)) return deny(request, "prompt-timeout")
        return if (result == Authorization.ALLOW) allow(request, "${source.name.lowercase()}-prompt")
        else deny(request, "${source.name.lowercase()}-prompt")
    }

    private fun allow(request: BridgeRequest, reason: String): Authorization {
        DebugLog.add("POLICY", "ALLOW $reason ${request.fullText}")
        return Authorization.ALLOW
    }

    private fun deny(request: BridgeRequest, reason: String): Authorization {
        DebugLog.add("POLICY", "DENY $reason ${request.fullText}")
        return Authorization.DENY
    }
}
