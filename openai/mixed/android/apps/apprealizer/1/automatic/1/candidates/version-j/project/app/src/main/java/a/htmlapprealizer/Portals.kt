package a.htmlapprealizer

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object BrowserPortal : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request != null && !Core.portal("web.permission", request)) request.deny()
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        request?.let { Core.portal("web.permission.cancelled", it) }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean = filePathCallback != null && Core.portal(
        "web.file", arrayOf(filePathCallback, fileChooserParams)
    )

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?, callback: GeolocationPermissions.Callback?
    ) {
        if (callback != null && !Core.portal("web.geolocation", arrayOf(origin, callback)))
            callback.invoke(origin, false, false)
    }

    override fun onGeolocationPermissionsHidePrompt() {
        Core.portal("web.geolocation.cancelled", null)
    }
}

object ForegroundNotice {
    private const val CHANNEL_RUNTIME = "html_realizer_runtime"
    private const val CHANNEL_EVENTS = "html_realizer_events"

    fun runtime(context: Context, projection: Boolean = false): Notification {
        val channel = if (projection) "html_realizer_projection" else CHANNEL_RUNTIME
        val name = if (projection) "HTML Realizer screen capture" else "HTML Realizer runtime"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, name, NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, Main::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (projection) "HTML Realizer capture active" else "HTML Realizer runtime active")
            .setContentText(if (projection) "Screen/app-window capture is active" else "User-authored HTML/JavaScript is active")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    fun event(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "HTML Realizer events", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, Main::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

class RuntimeService : Service() {
    @Volatile private var active = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, ForegroundNotice.runtime(this))
        Core.ensure(this)
        Core.port("runtime", this)
        active = true
        DebugLog.add("RUNTIME", "foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRuntime("explicit-stop")
            return START_NOT_STICKY
        }
        Core.event("runtime.start", startId, intent)
        return START_NOT_STICKY
    }

    fun stopRuntime(reason: String = "bridge-stop") {
        if (!active) return
        active = false
        Core.port("runtime", this, live = false, deferOwnerCheck = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        DebugLog.add("RUNTIME", reason)
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        Core.event("runtime.timeout", startId, foregroundServiceType)
        stopRuntime("system-timeout")
    }

    override fun onDestroy() {
        Core.port("runtime", this, live = false, deferOwnerCheck = true)
        if (active) stopForeground(STOP_FOREGROUND_REMOVE)
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "a.htmlapprealizer.STOP_RUNTIME"
        private const val NOTIFICATION_ID = 1101
    }
}

class NotificationPortal : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Core.port("notifications", this)
    }

    override fun onListenerDisconnected() {
        Core.port("notifications", this, live = false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(value: StatusBarNotification?, rankingMap: RankingMap?) {
        value?.let {
            Core.event("notification.posted", it.id, it)
            rankingMap?.let { ranking -> Core.event("notification.posted.ranking", it.id, ranking) }
        }
    }

    override fun onNotificationRemoved(
        value: StatusBarNotification?, rankingMap: RankingMap?, reason: Int
    ) {
        value?.let {
            Core.event("notification.removed", it.id, it)
            Core.event("notification.removed.detail", it.id, arrayOf(rankingMap, reason))
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        rankingMap?.let { Core.event("notification.ranking", 0, it) }
    }

    override fun onListenerHintsChanged(hints: Int) { Core.event("notification.hints", hints) }
    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        Core.event("notification.filter", interruptionFilter)
    }

    override fun onDestroy() {
        Core.port("notifications", this, live = false)
        super.onDestroy()
    }
}

class ProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var releasing = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, ForegroundNotice.runtime(this, projection = true))
        Core.port("projectionService", this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjection("explicit-stop")
            return START_NOT_STICKY
        }
        if (projection != null) {
            Core.event("projection.busy", startId, projection)
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val consent = intent?.getParcelableExtra<Intent>(EXTRA_CONSENT)
        if (resultCode != Activity.RESULT_OK || consent == null) {
            Core.event("projection.error", startId, "E:CONSENT")
            stopSelf()
            return START_NOT_STICKY
        }
        val created = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent)
        } catch (error: Throwable) {
            DebugLog.add("PROJECTION", "consent rejected", error)
            Core.event("projection.error", startId, error)
            stopSelf()
            return START_NOT_STICKY
        } ?: run {
            Core.event("projection.error", startId, "E:PROJECTION")
            stopSelf()
            return START_NOT_STICKY
        }
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (projection === created) {
                        projection = null
                        Core.port("projection", created, live = false)
                        if (!releasing) stopSelf()
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                Core.event("projection.resize", 0, intArrayOf(width, height))
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Core.event("projection.visibility", 0, isVisible)
            }
        }, main)
        projection = created
        Core.port("projection", created)
        DebugLog.add("PROJECTION", "projection started")
        return START_NOT_STICKY
    }

    fun stopProjection(reason: String = "bridge-stop") {
        val current = projection
        projection = null
        releasing = true
        if (current != null) {
            Core.port("projection", current, live = false)
            runCatching { current.stop() }
        }
        releasing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        DebugLog.add("PROJECTION", reason)
    }

    override fun onDestroy() {
        val current = projection
        projection = null
        if (current != null) {
            Core.port("projection", current, live = false)
            runCatching { current.stop() }
        }
        Core.port("projectionService", this, live = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "a.htmlapprealizer.STOP_PROJECTION"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_CONSENT = "consent"
        private const val NOTIFICATION_ID = 1102
    }
}

object CapabilityFacade {
    const val REQUEST_PROJECTION = 4801

    private fun gate(operation: String, className: String = "android.app.Application"): String? {
        Core.validateEpoch()?.let { return it }
        val request = BridgeRequest("portal", operation, className, operation, "portal:$operation")
        return if (Core.authorized(request)) null else "E:POLICY"
    }

    fun manifest(): String {
        gate("manifest")?.let { return it }
        val context = Core.portValue("app") as? Context ?: return "E:PORT"
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManagerFlags.permissions()
            )
            val result = JSONObject()
                .put("package", info.packageName)
                .put("version", info.versionName)
                .put("requestedPermissions", JSONArray(info.requestedPermissions?.toList().orEmpty()))
            "V$result"
        }.getOrElse { Core.bridgeFailure(it) }
    }

    fun resources(): String {
        gate("resources", "android.content.res.Resources")?.let { return it }
        val context = Core.portValue("app") as? Context ?: return "E:PORT"
        return Core.keep(context.resources)
    }

    fun root(): String {
        gate("root", "android.view.View")?.let { return it }
        val root = Core.currentActivity()?.window?.decorView?.rootView
            ?: GlobalService.instance?.rootInActiveWindow
            ?: return "E:PORT"
        return Core.keep(root)
    }

    fun requestPermission(permission: String, requestCode: Int): String {
        gate("permission:$permission", "android.app.Activity")?.let { return it }
        val activity = Core.currentActivity() ?: return "E:UI"
        activity.runOnUiThread { activity.requestPermissions(arrayOf(permission), requestCode) }
        return "W$requestCode"
    }

    fun stop(portal: String): String {
        gate("stop:$portal", "android.app.Service")?.let { return it }
        return when (portal.lowercase()) {
            "runtime" -> {
                (Core.portValue("runtime") as? RuntimeService)?.stopRuntime() ?: return "E:PORT"
                "V"
            }
            "projection" -> {
                (Core.portValue("projectionService") as? ProjectionService)?.stopProjection()
                    ?: return "E:PORT"
                "V"
            }
            "overlay" -> overlayClose()
            else -> "E:PORT"
        }
    }

    fun overlay(html: String, mode: String, configuration: String): String {
        gate("overlay", "android.view.WindowManager")?.let { return it }
        return GlobalService.instance?.openOverlay(html, mode, configuration) ?: "E:ACCESSIBILITY"
    }

    fun overlayEval(script: String): String {
        gate("overlay-eval", "android.webkit.WebView")?.let { return it }
        return GlobalService.instance?.evaluateOverlay(script) ?: "E:ACCESSIBILITY"
    }

    fun overlayClose(): String {
        gate("overlay-close", "android.view.WindowManager")?.let { return it }
        return if (GlobalService.instance?.closeOverlay("bridge-close") == true) "V" else "E:ACCESSIBILITY"
    }

    fun vibrate(durationMs: Long): String {
        gate("vibrate", "android.os.Vibrator")?.let { return it }
        val context = Core.portValue("app") as? Context ?: return "E:PORT"
        return runCatching {
            val effect = VibrationEffect.createOneShot(durationMs.coerceIn(1, 60_000), VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
            else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
            "V"
        }.getOrElse { Core.bridgeFailure(it) }
    }

    fun notify(id: Int, title: String, text: String): String {
        gate("notify", "android.app.NotificationManager")?.let { return it }
        val context = Core.portValue("app") as? Context ?: return "E:PORT"
        return runCatching { ForegroundNotice.event(context, id, title, text); "V" }
            .getOrElse { Core.bridgeFailure(it) }
    }

    fun alarm(delayMs: Long, title: String, text: String, exact: Boolean): String {
        gate("alarm", "android.app.AlarmManager")?.let { return it }
        val context = Core.portValue("app") as? Context ?: return "E:PORT"
        val manager = context.getSystemService(AlarmManager::class.java)
        val requestCode = ((System.currentTimeMillis() xor delayMs) and 0x7fffffff).toInt()
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmPortal::class.java)
                .putExtra("id", requestCode)
                .putExtra("title", title)
                .putExtra("text", text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = System.currentTimeMillis() + delayMs.coerceAtLeast(0)
        val usedExact = exact && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
        if (usedExact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        return if (usedExact) "VEXACT" else "VINEXACT"
    }

    fun startProjectionConsent(activity: Main) {
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    fun receiveProjectionConsent(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            DebugLog.add("PROJECTION", "user denied or cancelled consent")
            Core.event("projection.error", REQUEST_PROJECTION, "E:CONSENT")
            return
        }
        context.startForegroundService(
            Intent(context, ProjectionService::class.java)
                .putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ProjectionService.EXTRA_CONSENT, data)
        )
    }

    fun accessibilityEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().contains(context.packageName, ignoreCase = true)

    fun notificationAccessEnabled(context: Context): Boolean =
        Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty().contains(context.packageName, ignoreCase = true)

    fun exactAlarmEnabled(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

private object PackageManagerFlags {
    fun permissions(): Int = android.content.pm.PackageManager.GET_PERMISSIONS
}

class AlarmPortal : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 1200)
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "HTML Realizer condition" }
        val text = intent.getStringExtra("text").orEmpty()
        runCatching { ForegroundNotice.event(context, id, title, text) }
            .onFailure { DebugLog.add("ALARM", "notification failed", it) }
        Core.event("alarm", id, intent)
    }
}

class BroadcastCompletion @JvmOverloads constructor(
    private val pending: BroadcastReceiver.PendingResult,
    timeoutMs: Long = 9000
) {
    private val completed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val expiry = Runnable { finish() }
    init { main.postDelayed(expiry, timeoutMs.coerceAtLeast(1)) }
    fun raw(): BroadcastReceiver.PendingResult = pending
    fun finish(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        main.removeCallbacks(expiry)
        runCatching { pending.finish() }
        return true
    }
}

class BroadcastEvents @JvmOverloads constructor(@JvmField var id: Int = 0) : BroadcastReceiver() {
    private val born = Core.currentEpoch()
    override fun onReceive(context: Context?, intent: Intent?) {
        val completion = BroadcastCompletion(goAsync())
        if (born == Core.currentEpoch()) Core.event("broadcast", id, arrayOf(context, intent, completion))
        else completion.finish()
    }
}

class ContentEvents @JvmOverloads constructor(
    @JvmField var id: Int = 0
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val born = Core.currentEpoch()
    override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
        if (born == Core.currentEpoch()) Core.event("content", id, arrayOf(selfChange, uri, flags))
    }

    override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
        if (born == Core.currentEpoch()) Core.event("content", id, arrayOf(selfChange, uris, flags))
    }
}

class FileEvents @JvmOverloads constructor(
    path: String,
    mask: Int = FileObserver.ALL_EVENTS,
    @JvmField var id: Int = 0
) : FileObserver(File(path), mask) {
    private val born = Core.currentEpoch()
    override fun onEvent(event: Int, path: String?) {
        if (born == Core.currentEpoch()) Core.event("file", id, arrayOf<Any?>(event, path))
    }
}
