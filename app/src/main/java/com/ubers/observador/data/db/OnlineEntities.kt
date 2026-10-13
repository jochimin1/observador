package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ubers.observador.domain.online.OnlineEndReason
import com.ubers.observador.domain.online.OnlineGapCodec
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind

/**
 * One online session (Fase 8A). Only its last sighting, gaps and close are ever updated; rows
 * are never deleted, except TEST- rows from the debug panel. Enums are stored by fixed keys.
 * Indexed by start (periods) and by end (the open session is the one with ended_at NULL).
 */
@Entity(
    tableName = "online_sessions",
    indices = [Index(value = ["started_at"]), Index(value = ["ended_at"])]
)
data class OnlineSessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "start_kind") val startKind: String,
    @ColumnInfo(name = "last_online_seen_at") val lastOnlineSeenAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "possible_end_at") val possibleEndAt: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?,
    @ColumnInfo(name = "unobserved_ms") val unobservedMs: Long,
    /** "start-end;start-end", see OnlineGapCodec. */
    @ColumnInfo(name = "gaps") val gaps: String,
    @ColumnInfo(name = "anomaly_codes") val anomalyCodes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

fun OnlineSession.toEntity() = OnlineSessionEntity(
    id = id,
    startedAt = startedAt,
    startKind = startKind.storageKey,
    lastOnlineSeenAt = lastOnlineSeenAt,
    endedAt = endedAt,
    possibleEndAt = possibleEndAt,
    endReason = endReason?.storageKey,
    unobservedMs = unobservedMs,
    gaps = OnlineGapCodec.encode(gaps),
    anomalyCodes = OnlineGapCodec.encodeAnomalies(anomalies),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun OnlineSessionEntity.toDomain() = OnlineSession(
    id = id,
    startedAt = startedAt,
    startKind = OnlineStartKind.fromStorage(startKind),
    lastOnlineSeenAt = lastOnlineSeenAt,
    endedAt = endedAt,
    possibleEndAt = possibleEndAt,
    endReason = OnlineEndReason.fromStorage(endReason),
    unobservedMs = unobservedMs,
    gaps = OnlineGapCodec.decode(gaps),
    anomalies = OnlineGapCodec.decodeAnomalies(anomalyCodes),
    createdAt = createdAt,
    updatedAt = updatedAt
)
