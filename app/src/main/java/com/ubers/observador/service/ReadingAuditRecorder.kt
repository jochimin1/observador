package com.ubers.observador.service

import android.content.Context
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.ReadingAuditDao
import com.ubers.observador.data.db.ReadingAuditEntity
import com.ubers.observador.domain.lifecycle.*
import java.security.MessageDigest
import java.util.UUID

/** Always-on, bounded audit of results. The optional capture recorder remains separate. */
object ReadingAuditRecorder {
    private var app: Context? = null
    private var dao: ReadingAuditDao? = null
    private val classifier = UberScreenClassifier.spanish(RideLifecycleMonitor.UBER_DRIVER_PACKAGE)
    private data class Cached(val key: String, val id: String, val reading: ScreenOfferReading,
        var lifecycle: String? = null, var financial: String? = null)
    private val windows = LinkedHashMap<Int, Cached>()

    @Synchronized
    fun init(context: Context) {
        app = context.applicationContext
        dao = AppDatabase.getInstance(context).readingAuditDao()
        windows.clear()
    }

    @Suppress("DEPRECATION")
    private fun uberVersion(): String? = runCatching {
        app?.packageManager?.getPackageInfo(RideLifecycleMonitor.UBER_DRIVER_PACKAGE, 0)?.let {
            "${it.versionName ?: "sin nombre"} (${it.longVersionCode})"
        }
    }.getOrNull()

    @Synchronized
    fun read(snapshot: ScreenSnapshot): ScreenOfferReading {
        if (snapshot.packageName != RideLifecycleMonitor.UBER_DRIVER_PACKAGE) return ScreenOfferReading(emptyList())
        val encoded = snapshot.texts.joinToString("") { "${it.length}:$it" }
        val key = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val window = snapshot.windowId ?: -1
        windows[window]?.takeIf { it.key == key }?.let { return it.reading }
        val id = UUID.randomUUID().toString()
        val parsed = OfferCardParser.diagnoseAll(snapshot.texts)
        val classification = classifier.classify(snapshot)
        // 1.39.0: each new screen is evidence that the reading works (or does not)
        // («Estás conectado/desconectado» counts too: the home map is not a ride screen, but it is understood)
        HealthMonitor.onReading(snapshot.timestampMs, classification.isRecognized ||
            snapshot.normalizedTexts.any { com.ubers.observador.domain.online.OnlineSignatures.evidenceOf(it) != null })
        val reasons = parsed.problems.map { it.name }.toMutableList()
        if (!classification.isRecognized) reasons += "SCREEN_${classification.type}"
        if (classification.provisional) reasons += "PROVISIONAL_SIGNATURE"
        reasons += classification.failedDetectors.map { "DETECTOR_FAILED_$it" }
        val version = uberVersion()
        val reading = parsed.copy(cards = parsed.cards.map { card ->
            card.copy(offer = card.offer?.copy(detectedAt = snapshot.timestampMs,
                readingTrace = OfferReadTrace(uberVersion = version, readingId = id,
                    confidence = classification.confidence, reasons = reasons.joinToString(","))))
        })
        windows[window] = Cached(key, id, reading)
        while (windows.size > 32) windows.remove(windows.keys.first())
        val row = ReadingAuditEntity(id, snapshot.timestampMs, ReadingVersions.PARSER, ReadingVersions.CLASSIFIER,
            version, classification.type.name, classification.confidence, classification.provisional,
            reasons.joinToString(","), reading.offers.size, reading.rejected.size)
        dao?.let { target -> PersistenceQueue.shared().enqueue { target.record(row) } }
        return reading
    }

    @Synchronized
    fun lifecycle(snapshot: ScreenSnapshot, state: String, session: String?, events: List<String>) {
        if (snapshot.packageName != RideLifecycleMonitor.UBER_DRIVER_PACKAGE) return
        read(snapshot)
        val cached = windows[snapshot.windowId ?: -1] ?: return
        val codes = events.distinct().joinToString(",")
        val key = "$state|$session|$codes"
        // A repaint with no new events must not erase the events already attached to this reading.
        if (cached.lifecycle == key || (events.isEmpty() && cached.lifecycle?.startsWith("$state|$session|") == true)) return
        cached.lifecycle = key
        dao?.let { target -> PersistenceQueue.shared().enqueue { target.lifecycle(cached.id, state, session, codes) } }
    }

    @Synchronized
    fun financial(snapshot: ScreenSnapshot, type: String, confidence: Double) {
        if (snapshot.packageName != RideLifecycleMonitor.UBER_DRIVER_PACKAGE) return
        read(snapshot)
        val cached = windows[snapshot.windowId ?: -1] ?: return
        if (cached.financial == "$type|$confidence") return
        cached.financial = "$type|$confidence"
        dao?.let { target -> PersistenceQueue.shared().enqueue { target.financial(cached.id, type, confidence) } }
    }

    @Synchronized
    fun versionFor(snapshot: ScreenSnapshot): String? = read(snapshot).offers.firstOrNull()?.readingTrace?.uberVersion ?: uberVersion()
}
