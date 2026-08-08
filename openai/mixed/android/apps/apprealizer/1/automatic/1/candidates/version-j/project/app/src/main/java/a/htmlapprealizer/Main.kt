package a.htmlapprealizer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class Main : Activity() {
    private lateinit var frame: FrameLayout
    private lateinit var control: Button
    private lateinit var policy: PolicyStore
    private val main = Handler(Looper.getMainLooper())
    private var visibilityMode = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        policy = PolicyStore.get(this)
        visibilityMode = policy.visibilityMode
        frame = FrameLayout(this)
        mount(Core.attach(this))
        createControl()
        setContentView(frame)
        frame.requestApplyInsets()
        applyVisibility(visibilityMode)
        DebugLog.add("ACTIVITY", "created")
    }

    private fun createControl() {
        val density = resources.displayMetrics.density
        val size = (68 * density).toInt()
        control = Button(this).apply {
            filterTouchesWhenObscured = true
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xdd202020.toInt())
            contentDescription = "Open native HTML Realizer settings"
            setOnClickListener { showSettings() }
            setOnLongClickListener {
                Core.loadDefaultEditor()
                Toast.makeText(this@Main, "Default editor restored", Toast.LENGTH_SHORT).show()
                true
            }
        }
        frame.addView(control, FrameLayout.LayoutParams(size * 2, size, Gravity.TOP or Gravity.END))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val layout = control.layoutParams as FrameLayout.LayoutParams
            val bounds = windowManager.currentWindowMetrics.bounds
            val safe = runCatching {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            }.getOrNull()
            val diameter = maxOf(layout.width, layout.height)
            fun safeMargin(cutout: Int, edge: Int): Int = minOf(
                if (safe == null) diameter + edge / 67 else maxOf(diameter, cutout),
                maxOf(0, minOf(edge / 2, edge - diameter))
            )
            layout.topMargin = safeMargin(safe?.top ?: 0, bounds.height())
            layout.marginEnd = safeMargin(
                if (control.layoutDirection == View.LAYOUT_DIRECTION_RTL) safe?.left ?: 0 else safe?.right ?: 0,
                bounds.width()
            )
            control.layoutParams = layout
            insets
        }
        refreshNativeState()
    }

    fun mount(view: WebView) {
        if (!::frame.isInitialized) return
        (view.parent as? ViewGroup)?.removeView(view)
        frame.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
    }

    fun refreshNativeState() {
        if (!::control.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(::refreshNativeState)
            return
        }
        val bridge = if (policy.bridgeEnabled) "B+" else "B−"
        val sandbox = if (policy.sandboxEnabled) "S+" else "S−"
        val auth = if (Core.isAuthenticated()) "A+" else "A−"
        control.text = "⚙ $bridge $sandbox $auth\n${Core.documentLabel()}"
        control.setBackgroundColor(
            when {
                !policy.bridgeEnabled -> 0xdd4d1b1b.toInt()
                policy.sandboxEnabled -> 0xdd665100.toInt()
                Core.isAuthenticated() -> 0xdd0b6127.toInt()
                else -> 0xdd50306f.toInt()
            }
        )
    }

    private fun applyVisibility(mode: Int) {
        visibilityMode = mode.coerceIn(0, 3)
        policy.setVisibilityMode(visibilityMode)
        control.visibility = if (visibilityMode == 0) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::control.isInitialized && visibilityMode == 1) applyVisibility(0)
        refreshNativeState()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && ::control.isInitialized) {
            control.visibility = View.VISIBLE
            main.postDelayed({ if (!isFinishing) applyVisibility(visibilityMode) }, 3000)
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showSettings() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
            setBackgroundColor(0xff292929.toInt())
        }
        scroll.addView(column)

        fun heading(value: String) = column.addView(TextView(this).apply {
            text = value
            textSize = 18f
            setTextColor(0xff8bd5ff.toInt())
            setPadding(0, 18, 0, 6)
        })
        fun note(value: String) = column.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xffdddddd.toInt())
            setPadding(4, 3, 4, 10)
        })
        fun button(value: String, action: (Button) -> Unit): Button = Button(this).apply {
            text = value
            setAllCaps(false)
            setOnClickListener { action(this) }
            column.addView(this)
        }
        fun editor(label: String, value: String, password: Boolean = false, changed: (String) -> Unit) {
            note(label)
            val input = EditText(this).apply {
                setText(value)
                setTextColor(Color.WHITE)
                setHintTextColor(0xffaaaaaa.toInt())
                setBackgroundColor(0xff3a3a3a.toInt())
                minLines = if (password) 1 else 2
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(text: Editable?) = changed(text?.toString().orEmpty())
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
            column.addView(input)
        }

        heading("Recovery and state")
        note(nativeStatus())
        button("Reload exact default editor") { Core.loadDefaultEditor() }
        button("Recreate runtime WebView") { Core.recreateWebView() }
        button("Revoke epoch, handles, callbacks, and temporary grants") { Core.revoke("native-revoke") }
        button("Export native diagnostic log") { createDiagnosticDocument() }

        heading("Independent security controls")
        button("Bridge: ${onOff(policy.bridgeEnabled)}") { source ->
            policy.setBridge(!policy.bridgeEnabled)
            source.text = "Bridge: ${onOff(policy.bridgeEnabled)}"
            refreshNativeState()
        }
        button("Sandbox: ${onOff(policy.sandboxEnabled)}") { source ->
            policy.setSandbox(!policy.sandboxEnabled)
            source.text = "Sandbox: ${onOff(policy.sandboxEnabled)}"
            refreshNativeState()
        }
        editor("Password (changing it revokes the current epoch)", policy.password, password = true) {
            if (it != policy.password) policy.setPassword(it)
        }
        note("Bridge OFF is the master gate. Sandbox ON blocks privileged calls. Authentication is per document. Blacklist always wins over Whitelist.")

        heading("HTML/WebView Internet")
        button("Cut HTML Internet: ${onOff(policy.cutHtmlInternet)}") { source ->
            policy.setCutHtmlInternet(!policy.cutHtmlInternet)
            source.text = "Cut HTML Internet: ${onOff(policy.cutHtmlInternet)}"
        }
        editor("Exception hosts, one per line (exact host and subdomains)", policy.domainText()) {
            policy.setDomains(it)
        }
        note("This controls WebView requests, not reflected java.net, sockets, Android networking services, or a device firewall. WebSocket interception remains platform-limited.")

        heading("Reflection policy")
        editor("Blacklist", policy.ruleText(RuleDecision.BLACK)) {
            policy.setRules(RuleDecision.BLACK, it)
        }
        editor("Graylist", policy.ruleText(RuleDecision.GRAY)) {
            policy.setRules(RuleDecision.GRAY, it)
        }
        editor("Whitelist", policy.ruleText(RuleDecision.WHITE)) {
            policy.setRules(RuleDecision.WHITE, it)
        }
        note("Scopes: all, package:name, class:name, member:class#name, signature:value. Migrated old entries are shown as legacy:substring.")

        heading("Runtime and Android consent")
        button("Start foreground runtime") { startForegroundService(Intent(this, RuntimeService::class.java)) }
        button("Stop foreground runtime") { stopService(Intent(this, RuntimeService::class.java)) }
        button("Select runtime permissions") { showRuntimePermissionPicker() }
        button("Accessibility settings — ${enabled(CapabilityFacade.accessibilityEnabled(this))}") {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Notification access — ${enabled(CapabilityFacade.notificationAccessEnabled(this))}") {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("Overlay access — ${enabled(Settings.canDrawOverlays(this))}") {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("MediaProjection consent") { CapabilityFacade.startProjectionConsent(this) }
        button("Exact alarm access — ${enabled(CapabilityFacade.exactAlarmEnabled(this))}") {
            if (Build.VERSION.SDK_INT >= 31)
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        button("All-files access — ${enabled(Environment.isExternalStorageManager())}") {
            openSettings(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        val power = getSystemService(PowerManager::class.java)
        button("Battery exemption — ${enabled(power.isIgnoringBatteryOptimizations(packageName))}") {
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("App details and permission state") {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        heading("Settings visibility and emergency recovery")
        note("Volume Up reveals this control for three seconds. Long-pressing it restores the exact default editor.")
        listOf(
            "Always visible" to 0,
            "Hide until next focus" to 1,
            "Hide until next app open" to 2,
            "Hide persistently" to 3
        ).forEach { (label, mode) -> button(label) { applyVisibility(mode) } }

        AlertDialog.Builder(this)
            .setTitle("HTML Realizer J — native control plane")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRuntimePermissionPicker() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        val selected = BooleanArray(permissions.size)
        AlertDialog.Builder(this)
            .setTitle("Request only selected permissions")
            .setMultiChoiceItems(permissions, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Request") { _, _ ->
                val requested = permissions.filterIndexed { index, _ -> selected[index] }.toTypedArray()
                if (requested.isNotEmpty()) requestPermissions(requested, REQUEST_RUNTIME_PERMISSIONS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, it.message.orEmpty(), Toast.LENGTH_LONG).show() }
    }

    private fun createDiagnosticDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "app_realizer_j_diagnostics.txt")
            }, REQUEST_EXPORT_LOG
        )
    }

    private fun nativeStatus(): String = buildString {
        append("Bridge=").append(onOff(policy.bridgeEnabled))
        append("  Sandbox=").append(onOff(policy.sandboxEnabled))
        append("  Auth=").append(enabled(Core.isAuthenticated()))
        append("\nEpoch=").append(if (Core.currentEpoch().isBlank()) "none" else Core.currentEpoch().take(8))
        append("  Handles=").append(Core.heapSize())
        append("  Dropped events=").append(Core.droppedEvents())
        append("\nRuntime=").append(enabled(Core.portValue("runtime") != null))
        append("  Notification=").append(enabled(Core.portValue("notifications") != null))
        append("  Projection=").append(enabled(Core.portValue("projection") != null))
        append("  Accessibility=").append(enabled(Core.portValue("accessibility") != null))
        append("\nKotlin reflection dependency=").append(if (BuildConfig.HAS_KOTLIN_REFLECT) "included" else "excluded")
    }

    private fun diagnosticReport(): String = buildString {
        appendLine("HTML Realizer J native diagnostics")
        appendLine(nativeStatus())
        appendLine("Document=${Core.documentLabel()}")
        appendLine("Cut HTML Internet=${onOff(policy.cutHtmlInternet)}")
        appendLine("Exception hosts=${policy.domainText().replace('\n', ',')}")
        appendLine("Blacklist=${policy.ruleText(RuleDecision.BLACK).replace('\n', ',')}")
        appendLine("Graylist=${policy.ruleText(RuleDecision.GRAY).replace('\n', ',')}")
        appendLine("Whitelist=${policy.ruleText(RuleDecision.WHITE).replace('\n', ',')}")
        appendLine()
        append(DebugLog.report())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Core.event("activity.intent", 0, intent)
    }

    @Deprecated("Activity result compatibility portal")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            CapabilityFacade.REQUEST_PROJECTION -> CapabilityFacade.receiveProjectionConsent(
                this, resultCode, data
            )
            REQUEST_EXPORT_LOG -> if (resultCode == RESULT_OK && data?.data != null) {
                runCatching {
                    contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use {
                        writer -> writer.write(diagnosticReport())
                    } ?: error("No writable document stream")
                }.onSuccess {
                    Toast.makeText(this, "Diagnostic log exported", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    DebugLog.add("EXPORT", "diagnostic export failed", it)
                    Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            else -> Core.event("activity.result", requestCode, arrayOf(resultCode, data))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Core.event("permission.result", requestCode, arrayOf<Any>(permissions, grantResults))
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            val denied = permissions.filterIndexed { index, _ ->
                grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
            }
            Toast.makeText(
                this,
                if (denied.isEmpty()) "Selected permissions granted" else "Denied: ${denied.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        if (::frame.isInitialized) Core.port("frame", frame, live = false)
        Core.detach(this, isChangingConfigurations)
        DebugLog.add("ACTIVITY", "destroyed changing=$isChangingConfigurations")
        super.onDestroy()
    }

    private fun onOff(value: Boolean) = if (value) "ON" else "OFF"
    private fun enabled(value: Boolean) = if (value) "enabled" else "disabled"

    companion object {
        private const val REQUEST_EXPORT_LOG = 4901
        private const val REQUEST_RUNTIME_PERMISSIONS = 4902
    }
}
