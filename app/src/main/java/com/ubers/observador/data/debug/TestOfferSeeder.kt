package com.ubers.observador.data.debug

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.models.UberOffer
import java.time.ZonedDateTime

/**
 * A fixed, known set of fake offers, so the dashboard can be checked without driving.
 * Offered only in debug builds. Every row's hash starts with [HASH_PREFIX], so the set can be
 * removed without touching real offers.
 *
 * Scores are computed with factory settings (min RD$/km 6, min RD$/h 300, pickup 6 km / 12 min,
 * fuel 1.50/km), so the mix below holds whatever the driver has configured:
 * - today: 20 offers, one every 2 minutes, the last one 2 minutes before "now"
 * - yesterday: 20 offers between 08:00 and 23:00
 * - 6 days ago: the same 20, except the last two, which land at 00:30 and 01:30 of the next day
 * Every block of 20 is 6 MALO, 9 BUENO and 5 EXCELENTE with cuts 60/80. Offers between 18:00
 * and 20:00 pay the most per hour, so a "best hours" chart has a known peak.
 */
object TestOfferSeeder {

    const val HASH_PREFIX = "TEST-"
    const val PER_DAY = 20

    private class Template(
        val price: Double,
        val pickupKm: Double,
        val pickupMin: Int,
        val tripKm: Double,
        val tripMin: Int
    )

    private class Slot(val hour: Int, val minute: Int, val template: Template)

    // Factory-settings scores in the comments
    private val E1 = Template(150.0, 0.8, 2, 5.0, 12)   // 95 EXCELENTE
    private val E2 = Template(180.0, 0.5, 2, 7.0, 15)   // 96 EXCELENTE
    private val E3 = Template(130.0, 1.0, 3, 4.5, 10)   // 93 EXCELENTE
    private val E4 = Template(120.0, 1.2, 3, 4.0, 9)    // 92 EXCELENTE
    private val E5 = Template(200.0, 1.5, 4, 9.0, 20)   // 85 EXCELENTE
    private val G1 = Template(75.50, 1.8, 4, 3.5, 9)    // 76 BUENO (the example from Settings)
    private val G2 = Template(95.0, 2.0, 5, 5.0, 13)    // 73 BUENO
    private val G3 = Template(110.0, 2.5, 6, 6.0, 16)   // 69 BUENO
    private val G4 = Template(85.0, 1.5, 4, 4.5, 12)    // 76 BUENO
    private val G5 = Template(130.0, 3.0, 7, 7.5, 18)   // 67 BUENO
    private val G6 = Template(65.0, 1.5, 4, 3.0, 9)     // 75 BUENO
    private val G7 = Template(140.0, 2.5, 6, 6.0, 13)   // 76 BUENO
    private val B1 = Template(70.0, 4.5, 10, 6.0, 15)   // 35 MALO
    private val B2 = Template(60.0, 3.5, 9, 4.0, 14)    // 43 MALO
    private val B3 = Template(90.0, 5.0, 12, 8.0, 22)   // 31 MALO
    private val B4 = Template(55.0, 2.5, 7, 3.0, 12)    // 56 MALO
    private val B5 = Template(80.0, 6.5, 14, 5.0, 13)   // 29 MALO
    private val B6 = Template(100.0, 4.0, 11, 9.0, 25)  // 39 MALO

    /** One working day in time order: 6 MALO, 9 BUENO, 5 EXCELENTE; 18:00-20:00 pays best. */
    private val DAY = listOf(
        Slot(8, 0, B1), Slot(9, 15, G2), Slot(10, 30, B2), Slot(11, 45, G3),
        Slot(13, 0, B3), Slot(14, 15, G4), Slot(15, 30, B4), Slot(16, 45, G5),
        Slot(18, 0, E1), Slot(18, 15, G7), Slot(18, 30, E2), Slot(18, 45, E3),
        Slot(19, 0, G7), Slot(19, 15, E4), Slot(19, 30, G1), Slot(19, 45, E5),
        Slot(20, 30, B5), Slot(21, 30, G6), Slot(22, 15, B6), Slot(23, 0, G2)
    )

    fun generate(
        now: ZonedDateTime,
        engine: ProfitEngine = ProfitEngine(),
        settings: EngineSettings = EngineSettings()
    ): List<OfferEntity> {
        val zone = now.zone
        val todayStamps = DAY.mapIndexed { i, slot ->
            slot.template to now.minusMinutes(2L * (PER_DAY - i))
        }
        val yesterday = now.toLocalDate().minusDays(1)
        val yesterdayStamps = DAY.map { slot ->
            slot.template to yesterday.atTime(slot.hour, slot.minute).atZone(zone)
        }
        val weekAgo = now.toLocalDate().minusDays(6)
        val weekAgoStamps = DAY.mapIndexed { i, slot ->
            val at = when (i) {
                PER_DAY - 2 -> weekAgo.plusDays(1).atTime(0, 30)
                PER_DAY - 1 -> weekAgo.plusDays(1).atTime(1, 30)
                else -> weekAgo.atTime(slot.hour, slot.minute)
            }
            slot.template to at.atZone(zone)
        }
        return (todayStamps + yesterdayStamps + weekAgoStamps).map { (t, at) ->
            val offer = UberOffer(
                price = t.price,
                pickupKm = t.pickupKm,
                pickupMin = t.pickupMin,
                tripKm = t.tripKm,
                tripMin = t.tripMin,
                detectedAt = at.toInstant().toEpochMilli()
            )
            engine.analyze(offer, settings).toEntity(offerHash = HASH_PREFIX + offer.offerHash())
        }
    }
}
