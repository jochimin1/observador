package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "detected_offers",
    indices = [Index(value = ["offer_hash"]), Index(value = ["detectedAt"])]
)
data class OfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "offer_hash") val offerHash: String,
    val detectedAt: Long,
    val price: Double,
    val pickupKm: Double,
    val pickupMin: Int,
    val tripKm: Double,
    val tripMin: Int,
    val totalKm: Double,
    val totalMin: Int,
    val rdpPerKm: Double,
    val rdpPerHour: Double,
    val fuelCost: Double,
    val netProfit: Double,
    val score: Int,
    // Fase 8F (DB 11): addresses as Uber wrote them on the card; null on older rows
    @ColumnInfo(name = "pickup_address") val pickupAddress: String? = null,
    @ColumnInfo(name = "dropoff_address") val dropoffAddress: String? = null,
    // Fase 8J (DB 12): the card's button, OfferCardAction.storageKey ("INTEREST" or "ACCEPT"); null on older rows
    @ColumnInfo(name = "card_action") val cardAction: String? = null,
    // Fase 8J (DB 13): the modality above the price ("Moto") and the "Exclusivo" label; null on older rows
    @ColumnInfo(name = "product") val product: String? = null,
    @ColumnInfo(name = "exclusive") val exclusive: Boolean? = null
)
