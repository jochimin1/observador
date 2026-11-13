package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ubers.observador.domain.trips.CorrectionField
import com.ubers.observador.domain.trips.CorrectionOrigin
import com.ubers.observador.domain.trips.TripCorrection

/**
 * 1.40.0: every change of a trip's metadata or status, from Uber's screen or by hand. Rows are
 * only added: an undo is another row. The rider's name is personal data, kept like the trip's.
 */
@Entity(tableName = "trip_corrections", indices = [Index(value = ["trip_id"])])
data class TripCorrectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "at") val atMs: Long,
    val field: String,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    val origin: String
)

fun TripCorrectionEntity.toDomain(): TripCorrection? {
    val f = CorrectionField.fromStorage(field) ?: return null
    val o = CorrectionOrigin.fromStorage(origin) ?: return null
    return TripCorrection(id, tripId, atMs, f, oldValue, newValue, o)
}

fun TripCorrection.toEntity() = TripCorrectionEntity(id, tripId, atMs, field.storageKey, oldValue, newValue, origin.storageKey)
