package com.ubers.observador.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Real money (Fase 7C). Always whole minor units (centavos for DOP) in a Long, with its ISO 4217
 * code: never Double or Float, so 0.10 + 0.20 is exactly 30 centavos. BigDecimal is used only at
 * the edges: parsing text, percentages and display.
 */
data class MoneyAmount(val minorUnits: Long, val currencyCode: String) {

    val isNegative: Boolean get() = minorUnits < 0

    operator fun unaryMinus() = MoneyAmount(Math.negateExact(minorUnits), currencyCode)

    /** The same value as a decimal, for display and percentages only. */
    fun toDecimal(): BigDecimal =
        BigDecimal.valueOf(minorUnits, Currencies.minorDigits(currencyCode) ?: Currencies.DEFAULT_MINOR_DIGITS)

    companion object {
        fun zero(currencyCode: String) = MoneyAmount(0, currencyCode)

        /**
         * Edge conversion for amounts the app already stored as Double (offer prices, before 7C).
         * Uses the single rounding policy. Never used for observed money, which is parsed from text.
         */
        fun fromLegacyDouble(value: Double, currencyCode: String): MoneyAmount? {
            if (!value.isFinite()) return null
            val digits = Currencies.minorDigits(currencyCode) ?: return null
            return MoneyAmount(
                BigDecimal.valueOf(value).setScale(digits, MoneyRounding.MODE).unscaledValue().longValueExact(),
                currencyCode
            )
        }
    }
}

/** The one rounding rule of the app for money: half up (documented, never mixed). */
object MoneyRounding {
    val MODE: RoundingMode = RoundingMode.HALF_UP
}

/** Minor-unit digits per currency. Unknown currencies are refused rather than guessed. */
object Currencies {
    const val DOP = "DOP"
    const val USD = "USD"
    const val DEFAULT_MINOR_DIGITS = 2

    private val DIGITS = mapOf(DOP to 2, USD to 2)

    fun minorDigits(code: String): Int? = DIGITS[code]
}

/** Result of adding money: different currencies are never added. */
sealed interface MoneyResult {
    data class Ok(val amount: MoneyAmount) : MoneyResult
    data class CurrencyMismatch(val left: String, val right: String) : MoneyResult
}

object MoneyMath {

    /** Exact sum; throws ArithmeticException only on Long overflow (far beyond any real amount). */
    fun add(a: MoneyAmount, b: MoneyAmount): MoneyResult =
        if (a.currencyCode != b.currencyCode) {
            MoneyResult.CurrencyMismatch(a.currencyCode, b.currencyCode)
        } else {
            MoneyResult.Ok(MoneyAmount(Math.addExact(a.minorUnits, b.minorUnits), a.currencyCode))
        }

    /** Sum of same-currency amounts; null for an empty list; mismatch if currencies differ. */
    fun sum(amounts: List<MoneyAmount>): MoneyResult? {
        val first = amounts.firstOrNull() ?: return null
        var total = MoneyAmount.zero(first.currencyCode)
        for (a in amounts) {
            when (val r = add(total, a)) {
                is MoneyResult.Ok -> total = r.amount
                is MoneyResult.CurrencyMismatch -> return r
            }
        }
        return MoneyResult.Ok(total)
    }

    /** (actual - reference) / reference as a percentage with 1 decimal; null if reference <= 0 or currencies differ. */
    fun percentChange(reference: MoneyAmount, actual: MoneyAmount): BigDecimal? {
        if (reference.currencyCode != actual.currencyCode || reference.minorUnits <= 0) return null
        return BigDecimal.valueOf(actual.minorUnits - reference.minorUnits)
            .multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(reference.minorUnits), 1, MoneyRounding.MODE)
    }
}

/** Display only: "RD$1,978.38", "-RD$20.00", "USD 5.00". */
object MoneyFormat {
    fun format(amount: MoneyAmount, signed: Boolean = false): String {
        val decimal = amount.toDecimal().abs()
        val digits = Currencies.minorDigits(amount.currencyCode) ?: Currencies.DEFAULT_MINOR_DIGITS
        val number = "%,.${digits}f".format(java.util.Locale.US, decimal)
        val prefix = if (amount.currencyCode == Currencies.DOP) "RD$" else amount.currencyCode + " "
        val sign = when {
            amount.isNegative -> "-"
            signed && amount.minorUnits > 0 -> "+"
            else -> ""
        }
        return sign + prefix + number
    }
}

/** How money text is written. One explicit profile; nothing is guessed from the digits. */
data class ParsingProfile(
    /** Text prefix -> ISO code. */
    val currencyPrefixes: Map<String, String>,
    val decimalSeparator: Char,
    val groupingSeparator: Char
) {
    companion object {
        /** Uber Driver in the Dominican Republic as seen on offer cards: "DOP136.06", "RD$1,978.38". */
        val DOMINICAN = ParsingProfile(
            currencyPrefixes = mapOf("RD$" to Currencies.DOP, "DOP" to Currencies.DOP, "US$" to Currencies.USD, "USD" to Currencies.USD),
            decimalSeparator = '.',
            groupingSeparator = ','
        )
    }
}

sealed interface MoneyParseResult {
    data class Success(val amount: MoneyAmount) : MoneyParseResult
    /** Readable but could mean more than one thing (two amounts, separators of another profile). */
    data class Ambiguous(val reason: String) : MoneyParseResult
    data class Invalid(val reason: String) : MoneyParseResult
}

/**
 * Reads money from screen text with an explicit [ParsingProfile]. Never returns 0 for a failure,
 * never picks the larger of two amounts, and refuses separators that do not fit the profile.
 */
class MoneyParser(private val profile: ParsingProfile = ParsingProfile.DOMINICAN) {

    private val prefixes = profile.currencyPrefixes.keys.sortedByDescending { it.length }
    private val token = Regex(
        "([+\\-−]?)\\s*(" + prefixes.joinToString("|") { Regex.escape(it) } + ")\\s*([+\\-−]?)\\s*([0-9][0-9.,]*)"
    )
    private val grouped = Regex(
        "^\\d{1,3}(" + Regex.escape(profile.groupingSeparator.toString()) + "\\d{3})*(" +
            Regex.escape(profile.decimalSeparator.toString()) + "\\d+)?$"
    )
    private val plain = Regex("^\\d+(" + Regex.escape(profile.decimalSeparator.toString()) + "\\d+)?$")

    /** Exactly one amount expected in [text]. */
    fun parseSingle(text: String): MoneyParseResult {
        val all = parseAll(text)
        return when {
            all.isEmpty() -> MoneyParseResult.Invalid("no amount")
            all.size > 1 -> MoneyParseResult.Ambiguous("${all.size} amounts")
            else -> all.single()
        }
    }

    /** Every amount found in [text], each parsed or refused on its own. */
    fun parseAll(text: String): List<MoneyParseResult> =
        token.findAll(text).map { m ->
            val sign = m.groupValues[1].ifEmpty { m.groupValues[3] }
            parseNumber(m.groupValues[4].trimEnd('.', ','), profile.currencyPrefixes.getValue(m.groupValues[2]), sign)
        }.toList()

    private fun parseNumber(raw: String, currency: String, sign: String): MoneyParseResult {
        val digits = Currencies.minorDigits(currency) ?: return MoneyParseResult.Invalid("unknown currency $currency")
        if (!grouped.matches(raw) && !plain.matches(raw)) return MoneyParseResult.Ambiguous("separators do not fit the profile: $raw")
        val normalized = raw.replace(profile.groupingSeparator.toString(), "").replace(profile.decimalSeparator, '.')
        val decimal = BigDecimal(normalized)
        if (decimal.scale() > digits) return MoneyParseResult.Invalid("more than $digits decimals: $raw")
        val minor = try {
            decimal.setScale(digits).unscaledValue().longValueExact()
        } catch (e: ArithmeticException) {
            return MoneyParseResult.Invalid("too large: $raw")
        }
        val negative = sign == "-" || sign == "−"
        return MoneyParseResult.Success(MoneyAmount(if (negative) -minor else minor, currency))
    }
}
