package com.ubers.observador.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.IntelligenceRepository
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UberAccessibilityService : AccessibilityService() {

    private val engine = ProfitEngine()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val tracker = OfferSessionTracker()

    private lateinit var settings: AppSettings
    private lateinit var repository: OfferRepository
    private lateinit var shadowScorer: ShadowScorer
    private lateinit var overlayManager: OverlayManager
    private lateinit var notifier: OfferNotifier

    private val mainHandler = Handler(Looper.getMainLooper())

    /** False until the stored switch is read, and whenever the driver pauses Observador. */
    @Volatile private var observing = false
    private var debounceRunnable: Runnable? = null
    private var absenceRunnable: Runnable? = null
    private var recheckRunnable: Runnable? = null
    private var keepAliveRunnable: Runnable? = null

    companion object {
        private const val TAG = "UberA11y"
        private const val UBER_DRIVER_PACKAGE = RideLifecycleMonitor.UBER_DRIVER_PACKAGE
        private const val DEBOUNCE_MS = 300L

        /**
         * Fase 8A: while an online session is open, the screen is read again after this long without
         * events, so a still map with "Estás conectado" keeps the session seen. One read every 5 min.
         */
        private const val KEEP_ALIVE_MS = 5 * 60_000L

        /** Set while the service is connected; lets the app's UI reach the service-backed overlay. */
        @Volatile
        var instance: UberAccessibilityService? = null
            private set

        /** True when the user has enabled this service in the system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, UberAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(applicationContext)
        repository = OfferRepository(AppDatabase.getInstance(applicationContext))
        shadowScorer = ShadowScorer(settings, IntelligenceRepository(AppDatabase.getInstance(applicationContext)))
        // Use `this` so TYPE_ACCESSIBILITY_OVERLAY is backed by the service context
        overlayManager = OverlayManager(this)
        notifier = OfferNotifier(applicationContext)
        instance = this
        RideLifecycleMonitor.start(applicationContext)
        // The on/off switch decides when the lifecycle counts as connected (first value comes at once)
        scope.launch {
            settings.observerEnabled.collect { enabled -> mainHandler.post { setObserving(enabled) } }
        }
        Log.d(TAG, "Service connected")
    }

    /**
     * The master switch. Paused, nothing is read from Uber: no offers, overlay, notifications,
     * trips or money. A ride open at that moment ends as interrupted (its end was not seen), the
     * same as when the service is lost. Main thread only.
     */
    private fun setObserving(enabled: Boolean) {
        if (enabled == observing) return
        observing = enabled
        if (enabled) {
            RideLifecycleMonitor.onServiceConnected()
            Log.d(TAG, "Observador activo")
        } else {
            resetState()
            RideLifecycleMonitor.onObserverPaused()
            Log.d(TAG, "Observador en pausa")
        }
    }

    /** Shows [analysis] in the overlay as if it came from a real offer. Main thread only. */
    fun showTestOverlay(analysis: OfferAnalysis, quality: OfferQuality, durationSeconds: Int): Boolean {
        overlayManager.show(analysis, quality, durationSeconds)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!observing) return
        if (event.packageName != UBER_DRIVER_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val parse = Runnable { readScreen() }
        debounceRunnable = parse
        mainHandler.postDelayed(parse, DEBOUNCE_MS)
    }

    /** Reads the tree once; the offer parser and the ride lifecycle both work from that copy. */
    private fun readScreen() {
        if (!observing) return
        // Null root means the window tree isn't ready: unknown, not "card gone"
        val root = rootInActiveWindow ?: return
        val snapshot = try {
            ScreenSnapshotFactory.from(root, System.currentTimeMillis())
        } finally {
            root.recycle()
        }
        // If another app took the foreground, there is no Uber card to read
        val offer: UberOffer? =
            if (snapshot.packageName == UBER_DRIVER_PACKAGE) OfferParser.parse(snapshot.texts) else null
        observeLifecycle(snapshot, offer?.offerHash())
        scheduleKeepAlive()

        if (offer == null) {
            scheduleAbsenceCheck()
            return
        }

        cancelAbsenceCheck()
        val hash = offer.offerHash()
        if (tracker.onOfferSeen(hash, SystemClock.elapsedRealtime())) {
            sessionStart(offer, hash)
        }
    }

    /**
     * Fase 7A: only observes. Its result never decides what happens to offers, and a failure here
     * is logged and contained so offer reading carries on.
     */
    private fun observeLifecycle(snapshot: ScreenSnapshot, offerHash: String?) {
        val result = try {
            RideLifecycleMonitor.onSnapshot(snapshot, offerHash)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ride lifecycle failed on this screen; offers are not affected", e)
            return
        }
        result.recheckAfterMs?.let { scheduleRecheck(it) }
    }

    /** Reads the screen again to confirm a screen that is not yet stable. */
    private fun scheduleRecheck(delayMs: Long) {
        recheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val recheck = Runnable {
            recheckRunnable = null
            readScreen()
        }
        recheckRunnable = recheck
        mainHandler.postDelayed(recheck, delayMs)
    }

    /** Re-reads after a quiet spell while connected; any read (event or not) pushes it back. */
    private fun scheduleKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        if (!RideLifecycleMonitor.hasOpenOnlineSession) return
        val read = Runnable {
            keepAliveRunnable = null
            readScreen()
        }
        keepAliveRunnable = read
        mainHandler.postDelayed(read, KEEP_ALIVE_MS)
    }

    /** Confirms the card is really gone (not a flicker) before ending the session. */
    private fun scheduleAbsenceCheck() {
        if (!tracker.hasActiveSession || absenceRunnable != null) return
        val check = Runnable {
            absenceRunnable = null
            if (tracker.onOfferAbsent(SystemClock.elapsedRealtime())) sessionEnd()
        }
        absenceRunnable = check
        mainHandler.postDelayed(check, OfferSessionTracker.ABSENT_CONFIRM_MS)
    }

    private fun cancelAbsenceCheck() {
        absenceRunnable?.let { mainHandler.removeCallbacks(it) }
        absenceRunnable = null
    }

    private fun sessionStart(offer: UberOffer, hash: String) {
        Log.d(TAG, "Session started: $hash")
        overlayManager.dismiss()
        notifier.cancel()

        scope.launch {
            try {
                val analysis = engine.analyze(offer, settings.engineSettings.first())
                val quality = settings.qualityThresholds.first().classify(analysis.score)
                val windowMs = settings.graceWindowSeconds.first() * 1000L
                val saved = repository.saveOrSkip(analysis, windowMs)
                if (saved) {
                    Log.d(TAG, "Saved offer $hash score=${analysis.score} (${quality.label})")
                } else {
                    Log.d(TAG, "Already recorded within ${windowMs / 1000}s, not saving again: $hash")
                }

                val showOverlay = settings.showOverlay.first()
                val overlaySeconds = settings.overlayDurationSeconds.first()
                // Alerts only for a newly recorded GOOD or EXCELLENT offer, so a flicker never re-notifies
                val notify = saved &&
                    settings.operatingMode.first() == AppSettings.MODE_ASSISTED &&
                    quality != OfferQuality.BAD

                mainHandler.post {
                    // The card may already be gone or replaced while we were analysing
                    if (tracker.activeHash != hash) return@post
                    if (showOverlay) overlayManager.show(analysis, quality, overlaySeconds)
                    if (notify) notifier.notifyGoodOffer(analysis, quality)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process offer $hash", e)
            }
        }
        // Shadow scoring runs apart, after the visible path, and can never delay or break it
        scope.launch {
            try {
                shadowScorer.score(offer, hash, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Shadow scoring failed", e)
            }
        }
    }

    private fun sessionEnd() {
        Log.d(TAG, "Session ended")
        overlayManager.dismiss()
        notifier.cancel()
    }

    private fun resetState() {
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null
        recheckRunnable?.let { mainHandler.removeCallbacks(it) }
        recheckRunnable = null
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        cancelAbsenceCheck()
        tracker.reset()
        if (::overlayManager.isInitialized) overlayManager.dismiss()
        if (::notifier.isInitialized) notifier.cancel()
    }

    override fun onInterrupt() {
        // Feedback was interrupted, events keep coming: the ride lifecycle carries on
        resetState()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetState()
        if (observing) RideLifecycleMonitor.onServiceLost()
        observing = false
        if (instance === this) instance = null
        job.cancel()
    }
}
