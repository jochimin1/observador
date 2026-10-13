package com.ubers.observador.domain.finance

import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyParseResult
import com.ubers.observador.domain.money.MoneyParser
import com.ubers.observador.domain.money.ParsingProfile
import java.time.Instant
import java.time.ZoneId

/**
 * Fase 8A: the pill above Uber's map ("DOP 2,069.95 · HOY"), Uber's own figure for the day. It
 * is stored as a platform reference (PlatformEarningsSnapshot, DAY) and never as income. Its
 * period limits are unknown (midnight to midnight, or the driver's working day?), so they stay
 * null and reconciliation says "periodo distinto" until a capture settles it.
 *
 * The amount counts only with the exact label "hoy" beside it: in the same node (with nothing
 * else but the currency), or in the node just before or after. With the ▾ arrow Uber can show
 * other periods; those labels are not read until their names are known. Seen only in image
 * captures so far, so the evidence says PROVISIONAL_SIGNATURE.
 */
class UberTodayPillDetector(
    private val parser: MoneyParser = MoneyParser(),
    profile: ParsingProfile = ParsingProfile.DOMINICAN
) {

    companion object {
        const val SCREEN_TYPE = "UBER_TODAY_PILL"
        const val LABEL = "hoy"
        const val LABEL_SAME_NODE = "TODAY_LABEL_SAME_NODE"
        const val LABEL_NEXT_TO_AMOUNT = "TODAY_LABEL_NEXT_TO_AMOUNT"
        const val CURRENCY_IN_OWN_NODE = "CURRENCY_IN_OWN_NODE"
        const val CONFIDENCE = 0.90

        private val WORD = Regex("[\\p{L}]+")
    }

    /** "dop", "rd", "us", "usd": the letters of the currency prefixes, which may sit beside the label. */
    private val currencyWords: Set<String> =
        profile.currencyPrefixes.keys.flatMap { WORD.findAll(it.lowercase()).map { m -> m.value }.toList() }.toSet()
    private val prefixes = profile.currencyPrefixes.keys

    private data class Found(val amount: MoneyAmount, val evidence: String, val splitCurrency: Boolean)

    fun detect(snapshot: ScreenSnapshot, lifecycleScreen: UberScreenType, nowMs: Long, zone: ZoneId): PlatformEarningsSnapshot? {
        // An offer card's price is not money earned, nor Uber's day
        if (lifecycleScreen == UberScreenType.OFFER) return null
        val texts = snapshot.texts
        val norm = snapshot.normalizedTexts
        fun isLabel(i: Int) = i in norm.indices && norm[i].trim { !it.isLetterOrDigit() } == LABEL
        val found = ArrayList<Found>()
        texts.forEachIndexed { i, raw ->
            val amounts = parser.parseAll(raw).filterIsInstance<MoneyParseResult.Success>()
            if (amounts.size == 1) {
                val words = WORD.findAll(norm[i]).map { it.value }.filter { it !in currencyWords }.toList()
                when {
                    words == listOf(LABEL) -> found += Found(amounts.single().amount, LABEL_SAME_NODE, false)
                    words.isEmpty() && (isLabel(i - 1) || isLabel(i + 1)) ->
                        found += Found(amounts.single().amount, LABEL_NEXT_TO_AMOUNT, false)
                }
            } else if (amounts.isEmpty() && raw.trim() in prefixes && i + 1 < texts.size) {
                // "DOP" and "2,069.95" in two nodes: read together, with the label right before or after the pair
                val joined = parser.parseSingle(raw.trim() + " " + texts[i + 1].trim())
                if (joined is MoneyParseResult.Success && (isLabel(i - 1) || isLabel(i + 2))) {
                    found += Found(joined.amount, LABEL_NEXT_TO_AMOUNT, true)
                }
            }
        }
        val distinct = found.distinctBy { it.amount }
        // Two different figures labelled "hoy" cannot both be the day: neither is kept
        if (distinct.size != 1) return null
        val pick = distinct.single()
        if (pick.amount.isNegative) return null
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        return PlatformEarningsSnapshot(
            snapshotId = FinancialIds.snapshotId(PlatformPeriodType.DAY, pick.amount, "$day|$SCREEN_TYPE"),
            capturedAtMs = nowMs,
            periodType = PlatformPeriodType.DAY,
            periodStartMs = null,
            periodEndMs = null,
            amount = pick.amount,
            confidence = CONFIDENCE,
            screenType = SCREEN_TYPE,
            evidence = listOfNotNull(
                FinancialEvidence.SCREEN_PREFIX + SCREEN_TYPE,
                pick.evidence,
                if (pick.splitCurrency) CURRENCY_IN_OWN_NODE else null,
                FinancialEvidence.PROVISIONAL_SIGNATURE
            )
        )
    }
}
