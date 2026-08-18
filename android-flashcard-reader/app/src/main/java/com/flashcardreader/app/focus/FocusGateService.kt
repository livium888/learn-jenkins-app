package com.flashcardreader.app.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flashcardreader.app.MainActivity
import com.flashcardreader.app.R
import com.flashcardreader.app.theme.FlashcardReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches which app is in the foreground and puts the gate up when a blocked app is opened with no
 * banked reading time.
 *
 * Detection is UsageStatsManager polling rather than an AccessibilityService: it only sees the
 * foreground *package* (so this gates whole apps, not individual web pages), but it is the accepted
 * digital-wellbeing pattern and far less likely to get the app pulled from Play.
 *
 * Two things keep this from being a battery problem: polling only runs while the screen is on, and
 * the interval relaxes when nothing blocked has been seen recently.
 *
 * The service is its own LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner because a
 * ComposeView outside an Activity crashes on attach without all three ViewTree owners set.
 */
class FocusGateService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var prefs: FocusPrefs
    private lateinit var bank: CreditBank
    private lateinit var usage: UsageStatsManager
    private lateinit var windowManager: WindowManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var overlay: ComposeView? = null
    private var overlayForPackage: String? = null
    private var lastEventCursor = 0L
    private var lastBlockedSeenAt = 0L

    /** Flipped so the watchdog can tell whether the process still has a live service. */
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "focus-gate"
        private const val NOTIF_ID = 4711
        private const val FAST_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 2_500L
        private const val RECENT_BLOCKED_MS = 30_000L

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, FocusGateService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FocusGateService::class.java)) }
        }
    }

    /** Polling is pointless with the screen off, and it is the main battery cost if left running. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> startPolling()
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                    scope.launch { hideOverlay() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        prefs = FocusPrefs(this)
        bank = CreditBank(this)
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (prefs.enabled && UsageAccess.fullyGranted(this)) startPolling() else stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopPolling()
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlayView()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- polling

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val blockedNow = runCatching { currentForegroundPackage() }.getOrNull()
                evaluate(blockedNow)
                val recentlyBlocked =
                    android.os.SystemClock.elapsedRealtime() - lastBlockedSeenAt < RECENT_BLOCKED_MS
                delay(if (recentlyBlocked || overlay != null) FAST_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The package most recently moved to the foreground. Usage events arrive late and batched, so
     * the window is queried with an overlap rather than exactly since the last poll.
     */
    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val since = if (lastEventCursor == 0L) now - 10_000 else lastEventCursor - 5_000
        val events = usage.queryEvents(since, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        var latestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= latestAt) {
                latest = event.packageName
                latestAt = event.timeStamp
            }
        }
        if (latestAt > 0) lastEventCursor = latestAt
        return latest ?: lastForeground
    }

    private var lastForeground: String? = null

    /** Decides, once per poll, whether to spend credit, raise the gate, or take it down. */
    private suspend fun evaluate(foreground: String?) {
        if (foreground != null) lastForeground = foreground
        val pkg = lastForeground
        val blocked = pkg != null && pkg in prefs.blockedPackages && pkg != packageName
        if (!blocked) {
            if (overlay != null) hideOverlay()
            return
        }
        lastBlockedSeenAt = android.os.SystemClock.elapsedRealtime()
        val balance = bank.balanceSeconds
        if (balance > 0) {
            // Spend while the app is in front; the poll interval is the tick.
            bank.spend(FAST_POLL_MS / 1000)
            if (bank.balanceSeconds <= 0) showOverlay(pkg!!) else if (overlay != null) hideOverlay()
        } else {
            showOverlay(pkg!!)
        }
    }

    // ---------------------------------------------------------------- overlay

    private suspend fun showOverlay(pkg: String) = withContext(Dispatchers.Main) {
        if (overlay != null && overlayForPackage == pkg) return@withContext
        removeOverlayView()
        overlayForPackage = pkg
        val label = runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("This app")

        val view = ComposeView(this@FocusGateService).apply {
            setViewTreeLifecycleOwner(this@FocusGateService)
            setViewTreeViewModelStoreOwner(this@FocusGateService)
            setViewTreeSavedStateRegistryOwner(this@FocusGateService)
            setContent {
                FlashcardReaderTheme {
                    BlockingGate(
                        appLabel = label,
                        balanceSeconds = bank.balanceSeconds,
                        onRead = {
                            startActivity(
                                Intent(this@FocusGateService, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            scope.launch { hideOverlay() }
                        },
                        onClose = {
                            startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            scope.launch { hideOverlay() }
                        },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        runCatching {
            windowManager.addView(view, params)
            overlay = view
        }
    }

    private suspend fun hideOverlay() = withContext(Dispatchers.Main) { removeOverlayView() }

    private fun removeOverlayView() {
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        overlayForPackage = null
    }

    // ---------------------------------------------------------------- foreground notification

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus Gate", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps watch so gated apps stay gated."
                },
            )
        }
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Focus Gate is on")
            .setContentText("Reading earns time for your gated apps.")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tap)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }
}
