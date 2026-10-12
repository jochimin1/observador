package com.ubers.observador

import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.money.MoneyMath
import com.ubers.observador.domain.money.MoneyParseResult
import com.ubers.observador.domain.money.MoneyParser
import com.ubers.observador.domain.money.MoneyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 7C: money is Long minor units; text and BigDecimal only at the edges. */
class MoneyTest {

    private val parser = MoneyParser()
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)
    private fun ok(text: String): MoneyAmount = (parser.parseSingle(text) as MoneyParseResult.Success).amount

    @Test fun parsesDominicanAmountsToMinorUnits() {
        assertEquals(dop(197_838), ok("RD$1,978.38"))
        assertEquals(dop(13_606), ok("DOP136.06"))
        assertEquals(dop(15_000), ok("RD$ 150"))
        assertEquals(dop(14_150), ok("RD$141.5"))
        assertEquals(dop(123_456_789), ok("RD$1,234,567.89"))
        assertEquals(MoneyAmount(2_000, Currencies.USD), ok("US$20.00"))
    }

    @Test fun parsesSignsBeforeOrAfterCurrency() {
        assertEquals(dop(-1_000), ok("-RD$10.00"))
        assertEquals(dop(-1_500), ok("RD$-15"))
        assertEquals(dop(2_000), ok("+RD$20"))
    }

    @Test fun foreignSeparatorsAreAmbiguousNeverGuessed() {
        assertTrue(parser.parseSingle("RD$1.234,56") is MoneyParseResult.Ambiguous)
        assertTrue(parser.parseSingle("RD$1,97") is MoneyParseResult.Ambiguous)
    }

    @Test fun tooManyDecimalsIsInvalidNotRounded() {
        assertTrue(parser.parseSingle("RD$1.234") is MoneyParseResult.Invalid)
    }

    @Test fun failureIsNeverZero() {
        assertTrue(parser.parseSingle("Ganaste") is MoneyParseResult.Invalid)
        assertTrue(parser.parseSingle("") is MoneyParseResult.Invalid)
        assertTrue(parser.parseSingle("12 min") is MoneyParseResult.Invalid)
    }

    @Test fun twoAmountsInOneTextAreAmbiguousNotTheLargest() {
        assertTrue(parser.parseSingle("RD$100 - RD$150") is MoneyParseResult.Ambiguous)
        assertEquals(2, parser.parseAll("RD$100 - RD$150").size)
    }

    @Test fun noFloatingPointError() {
        // 0.10 + 0.20 as Double is 0.30000000000000004; in minor units it is exactly 30
        val sum = MoneyMath.add(ok("RD$0.10"), ok("RD$0.20")) as MoneyResult.Ok
        assertEquals(30L, sum.amount.minorUnits)
        val many = MoneyMath.sum(List(1000) { dop(10) }) as MoneyResult.Ok
        assertEquals(10_000L, many.amount.minorUnits)
    }

    @Test fun differentCurrenciesAreNeverAdded() {
        val r = MoneyMath.add(dop(100), MoneyAmount(100, Currencies.USD))
        assertTrue(r is MoneyResult.CurrencyMismatch)
        assertTrue(MoneyMath.sum(listOf(dop(1), dop(2), MoneyAmount(3, Currencies.USD))) is MoneyResult.CurrencyMismatch)
    }

    @Test fun legacyDoubleConvertsOnceWithHalfUp() {
        assertEquals(dop(13_606), MoneyAmount.fromLegacyDouble(136.06, Currencies.DOP))
        assertEquals(dop(30), MoneyAmount.fromLegacyDouble(0.1 + 0.2, Currencies.DOP))
        assertNull(MoneyAmount.fromLegacyDouble(1.0, "XXX"))
    }

    @Test fun formatsOnlyAtTheEdge() {
        assertEquals("RD$1,978.38", MoneyFormat.format(dop(197_838)))
        assertEquals("-RD$15.00", MoneyFormat.format(dop(-1_500)))
        assertEquals("+RD$20.00", MoneyFormat.format(dop(2_000), signed = true))
        assertEquals("RD$0.00", MoneyFormat.format(dop(0)))
    }

    @Test fun percentChangeNeedsAPositiveReference() {
        assertEquals("10.0", MoneyMath.percentChange(dop(10_000), dop(11_000))!!.toPlainString())
        assertNull(MoneyMath.percentChange(dop(0), dop(11_000)))
        assertNull(MoneyMath.percentChange(dop(100), MoneyAmount(100, Currencies.USD)))
    }
}
