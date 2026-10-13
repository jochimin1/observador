package com.ubers.observador.domain.online

/** Every timing rule of Fase 8A in one place, adjustable in tests. */
data class OnlinePolicy(
    /** Time without seeing Uber that still counts as online (decision 1 of the plan: 30 min). */
    val toleranceMs: Long = 30 * 60_000L,
    /** A change of state needs the same signal in two reads at least this far apart. */
    val confirmMs: Long = 500L,
    /** A first sighting not confirmed within this long is forgotten: a confirmation must follow it closely. */
    val pendingMaxMs: Long = 10_000L,
    /** "Last seen online" is written at most this often, to save battery. */
    val heartbeatPersistMs: Long = 60_000L,
    /** A session longer than this is flagged as an anomaly; it is never cut or deleted. */
    val maxSessionMs: Long = 24 * 3_600_000L,
    /** Rates per online hour are not shown below this much confirmed time: too few minutes to mean anything. */
    val minRateMs: Long = 15 * 60_000L
)

/** How a session started. Fixed storage keys, so renaming the enum never changes stored rows. */
enum class OnlineStartKind(val storageKey: String) {
    /** "Fuera de línea" was seen shortly before: the start is the real one. */
    EXACT("EXACT"),
    /** Observador found you already online: you started earlier, so the start is a minimum. */
    RECOVERED("RECOVERED");

    companion object {
        /** Unknown keys read as RECOVERED: never claim an exact start that was not seen. */
        fun fromStorage(key: String?): OnlineStartKind = entries.firstOrNull { it.storageKey == key } ?: RECOVERED
    }
}

enum class OnlineEndReason(val storageKey: String) {
    /** "Estás desconectado" or "Conectarse" was confirmed. */
    WENT_OFFLINE("WENT_OFFLINE"),
    /** Observador was paused: nothing after that can be seen (decision 2). */
    PAUSED("PAUSED"),
    /** After a restart, Uber was next seen too late to say the session went on. */
    OBSERVATION_LOST("OBSERVATION_LOST"),
    /** A newer open session existed (should never happen; kept as an anomaly). */
    SUPERSEDED("SUPERSEDED");

    companion object {
        fun fromStorage(key: String?): OnlineEndReason? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Typed anomaly codes, never free text. */
enum class OnlineAnomaly(val storageKey: String) {
    MULTIPLE_OPEN_SESSIONS("MULTIPLE_OPEN_SESSIONS"),
    LONGER_THAN_24H("LONGER_THAN_24H");

    companion object {
        fun fromStorage(key: String?): OnlineAnomaly? = entries.firstOrNull { it.storageKey == key }
    }
}

/** A stretch of time, [startMs, endMs). */
data class TimeSpan(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = maxOf(0L, endMs - startMs)
}

/**
 * One stretch connected to Uber, as Observador saw it. A session is one row: only its last
 * sighting, its gaps and its close are ever updated, and nothing is deleted. Times are epoch ms.
 *
 * Two figures, never one: confirmed time counts only what was seen (gaps over the tolerance are
 * left out and listed in [gaps]); possible time also counts those gaps and a late close.
 */
data class OnlineSession(
    val id: String,
    val startedAt: Long,
    val startKind: OnlineStartKind,
    val lastOnlineSeenAt: Long,
    val endedAt: Long? = null,
    /** Where the session may have really ended; later than [endedAt] only when the close came after a long gap. */
    val possibleEndAt: Long? = null,
    val endReason: OnlineEndReason? = null,
    /** Sum of [gaps]: time inside the session in which Uber was not seen for longer than the tolerance. */
    val unobservedMs: Long = 0,
    val gaps: List<TimeSpan> = emptyList(),
    val anomalies: Set<OnlineAnomaly> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long
) {
    val isOpen: Boolean get() = endedAt == null

    /**
     * Where confirmed time ends. A closed session: its end. An open one: now, while Uber was seen
     * within the tolerance; otherwise the last sighting (nothing unseen is counted).
     */
    fun confirmedEnd(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): Long =
        endedAt ?: if (nowMs - lastOnlineSeenAt <= policy.toleranceMs) maxOf(nowMs, lastOnlineSeenAt) else lastOnlineSeenAt

    /** Where possible time ends. An open session never reaches past what was seen until it closes. */
    fun possibleEnd(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): Long =
        maxOf(possibleEndAt ?: endedAt ?: confirmedEnd(nowMs, policy), confirmedEnd(nowMs, policy))

    /** The confirmed stretches: from start to [confirmedEnd], without the gaps. */
    fun confirmedSpans(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): List<TimeSpan> {
        val end = confirmedEnd(nowMs, policy)
        val spans = ArrayList<TimeSpan>()
        var cursor = startedAt
        gaps.sortedBy { it.startMs }.forEach { gap ->
            val gapStart = gap.startMs.coerceIn(cursor, end)
            if (gapStart > cursor) spans += TimeSpan(cursor, gapStart)
            cursor = maxOf(cursor, minOf(gap.endMs, end))
        }
        if (end > cursor) spans += TimeSpan(cursor, end)
        return spans
    }

    fun confirmedMs(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): Long = confirmedSpans(nowMs, policy).sumOf { it.durationMs }

    fun possibleMs(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): Long = maxOf(0L, possibleEnd(nowMs, policy) - startedAt)

    /** True when Uber has not been seen for longer than the tolerance and the session is still open. */
    fun isStale(nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): Boolean =
        isOpen && nowMs - lastOnlineSeenAt > policy.toleranceMs
}

/** Gaps stored as "start-end;start-end" in one column. Malformed parts are skipped, never guessed. */
object OnlineGapCodec {

    fun encode(gaps: List<TimeSpan>): String = gaps.joinToString(";") { "${it.startMs}-${it.endMs}" }

    fun decode(raw: String?): List<TimeSpan> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val bounds = part.split('-')
            if (bounds.size != 2) return@mapNotNull null
            val start = bounds[0].toLongOrNull() ?: return@mapNotNull null
            val end = bounds[1].toLongOrNull() ?: return@mapNotNull null
            if (end < start) null else TimeSpan(start, end)
        }

    fun encodeAnomalies(anomalies: Set<OnlineAnomaly>): String = anomalies.map { it.storageKey }.sorted().joinToString(",")

    fun decodeAnomalies(raw: String?): Set<OnlineAnomaly> =
        raw.orEmpty().split(',').mapNotNull { OnlineAnomaly.fromStorage(it.trim()) }.toSet()
}
