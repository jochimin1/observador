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
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
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
    private lateinit var overlayManager: OverlayManager
    private lateinit var notifier: OfferNotifier

    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var absenceRunnable: Runnable? = null

    companion object {
        private const val TAG = "UberA11y"
        private const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
        private const val DEBOUNCE_MS = 300L

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
        // Use `this` so TYPE_ACCESSIBILITY_OVERLAY is backed by the service context
        overlayManager = OverlayManager(this)
        notifier = OfferNotifier(applicationContext)
        instance = this
        Log.d(TAG, "Service connected")
    }

    /** Shows [analysis] in the overlay as if it came from a real offer. Main thread only. */
    fun showTestOverlay(analysis: OfferAnalysis, quality: OfferQuality, durationSeconds: Int): Boolean {
        overlayManager.show(analysis, quality, durationSeconds)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != UBER_DRIVER_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val parse = Runnable { tryParseOffer() }
        debounceRunnable = parse
        mainHandler.postDelayed(parse, DEBOUNCE_MS)
    }

    private fun tryParseOffer() {
        // Null root means the window tree isn't ready: unknown, not "card gone"
        val root = rootInActiveWindow ?: return
        val offer: UberOffer? = try {
            // If another app took the foreground, there is no Uber card to read
            if (root.packageName?.toString() == UBER_DRIVER_PACKAGE) OfferParser.parse(root) else null
        } finally {
            root.recycle()
        }

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
    }

    private fun sessionEnd() {
        Log.d(TAG, "Session ended")
        overlayManager.dismiss()
        notifier.cancel()
    }

    private fun resetState() {
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null
        cancelAbsenceCheck()
        tracker.reset()
        if (::overlayManager.isInitialized) overlayManager.dismiss()
        if (::notifier.isInitialized) notifier.cancel()
    }

    override fun onInterrupt() {
        resetState()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetState()
        if (instance === this) instance = null
        job.cancel()
    }
}
