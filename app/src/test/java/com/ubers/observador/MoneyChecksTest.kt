package com.ubers.observador

import com.ubers.observador.domain.earnings.MoneyCheck
import com.ubers.observador.domain.earnings.MoneyChecks
import com.ubers.observador.domain.earnings.MoneyVerdict
import com.ubers.observador.domain.earnings.TripMoneyRow
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.money.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyChecksTest {
    private fun ok(at: Long, trip: String) = MoneyCheck(at, trip, 24_530, "DOP", MoneyVerdict.MATCH)
    private fun fixed(at: Long, trip: String) = MoneyCheck(at, trip, 24_530, "DOP", MoneyVerdict.CORRECTED, 25_030)

    @Test fun threeMatchesInARowConfirmAndACorrectionStartsAgain() {
        assertFalse(MoneyChecks.state(listOf(ok(1, "a"), ok(2, "b"))).validated)
        assertTrue(MoneyChecks.state(listOf(ok(1, "a"), ok(2, "b"), ok(3, "c"))).validated)
        val broken = MoneyChecks.state(listOf(ok(1, "a"), ok(2, "b"), ok(3, "c"), fixed(4, "d")))
        assertFalse(broken.validated)
        assertEquals(0, broken.inARow)
        assertEquals(1, broken.corrections)
        assertTrue(MoneyChecks.describe(broken).startsWith("La última lectura que revisaste no coincidía"))
        // The same trip checked twice counts once, with its last answer
        val again = MoneyChecks.state(listOf(ok(1, "a"), fixed(2, "b"), ok(3, "c"), ok(4, "b"), ok(5, "d")))
        assertTrue(again.validated)
        assertEquals(0, again.corrections)
    }

    @Test fun linesKeepEveryField() {
        val c = fixed(1_763_000_000_000, "TRIP;1%")
        assertEquals(c, MoneyChecks.decode(MoneyChecks.encode(c)))
        assertEquals(ok(5, "x"), MoneyChecks.decode(MoneyChecks.encode(ok(5, "x"))))
        assertNull(MoneyChecks.decode("m1;roto"))
        assertEquals(2, MoneyChecks.parse(MoneyChecks.encode(c) + "\nbasura\n" + MoneyChecks.encode(ok(5, "x"))).size)
    }

    @Test fun onlyUnCheckedTotalsReadFromUberAreOffered() {
        fun row(id: String, at: Long, real: Long?, cancelled: Boolean = false) = TripMoneyRow(id, at, true, null,
            real?.let { MoneyAmount(it, "DOP") }, FinancialCompleteness.values().first(), null, null, 0, false, 0, cancelled = cancelled)
        val rows = listOf(row("a", 1, 100), row("b", 2, 200), row("c", 3, null), row("d", 4, 400, cancelled = true), row("e", 5, 500), row("f", 6, 600))
        val st = MoneyChecks.state(listOf(ok(1, "f")))
        val got = MoneyChecks.candidates(rows, setOf("a", "b", "c", "d", "e", "f"), st)
        assertEquals(listOf("e", "b", "a"), got.map { it.tripId })
        assertEquals(listOf("e"), MoneyChecks.candidates(rows, setOf("e"), st).map { it.tripId })
    }
}
