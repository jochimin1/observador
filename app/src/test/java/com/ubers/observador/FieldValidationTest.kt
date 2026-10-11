package com.ubers.observador

import com.ubers.observador.domain.FieldResult
import com.ubers.observador.domain.FieldValidation
import com.ubers.observador.domain.formatNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldValidationTest {

    private fun dec(text: String, min: Double = 0.5, max: Double = 500.0) =
        FieldValidation.parseDecimal(text, min, max)

    private fun int(text: String, min: Int = 1, max: Int = 120) =
        FieldValidation.parseInt(text, min, max)

    @Test
    fun `decimal accepts plain, dot and comma values`() {
        assertEquals(FieldResult.Valid(6.0), dec("6"))
        assertEquals(FieldResult.Valid(6.5), dec("6.5"))
        assertEquals(FieldResult.Valid(6.5), dec("6,5"))
        assertEquals(FieldResult.Valid(6.0), dec("6."))
        assertEquals(FieldResult.Valid(0.5), dec(".5"))
        assertEquals(FieldResult.Valid(12.0), dec("  12  "))
    }

    @Test
    fun `decimal rejects empty, letters and symbols`() {
        assertEquals("Escribe un valor", (dec("") as FieldResult.Invalid).message)
        assertEquals("Escribe un valor", (dec("   ") as FieldResult.Invalid).message)
        assertTrue(dec("abc") is FieldResult.Invalid)
        assertTrue(dec("1e5") is FieldResult.Invalid)
        assertTrue(dec("NaN") is FieldResult.Invalid)
        assertTrue(dec("Infinity") is FieldResult.Invalid)
        assertTrue(dec("0x10") is FieldResult.Invalid)
        assertTrue(dec("1.2.3") is FieldResult.Invalid)
        assertTrue(dec(".") is FieldResult.Invalid)
    }

    @Test
    fun `decimal rejects negative values with a clear message`() {
        assertEquals("No puede ser negativo", (dec("-3") as FieldResult.Invalid).message)
    }

    @Test
    fun `decimal rejects zero when the minimum is above zero`() {
        val r = dec("0", min = 0.5) as FieldResult.Invalid
        assertEquals("Escribe un valor entre 0.5 y 500", r.message)
    }

    @Test
    fun `decimal accepts zero when the field allows it`() {
        assertEquals(FieldResult.Valid(0.0), dec("0", min = 0.0, max = 5000.0))
    }

    @Test
    fun `decimal enforces both ends of the range`() {
        assertTrue(dec("0.4") is FieldResult.Invalid)
        assertEquals(FieldResult.Valid(0.5), dec("0.5"))
        assertEquals(FieldResult.Valid(500.0), dec("500"))
        assertTrue(dec("500.01") is FieldResult.Invalid)
        assertTrue(dec("99999999999999999999999") is FieldResult.Invalid)
    }

    @Test
    fun `integer accepts whole numbers inside the range`() {
        assertEquals(FieldResult.Valid(12), int("12"))
        assertEquals(FieldResult.Valid(1), int("1"))
        assertEquals(FieldResult.Valid(120), int("120"))
        assertEquals(FieldResult.Valid(15), int(" 15 "))
    }

    @Test
    fun `integer rejects decimals, negatives, zero and overflow`() {
        assertEquals("Escribe un número entero", (int("1.5") as FieldResult.Invalid).message)
        assertEquals("Escribe un número entero", (int("abc") as FieldResult.Invalid).message)
        assertEquals("No puede ser negativo", (int("-4") as FieldResult.Invalid).message)
        assertEquals("Escribe un valor entre 1 y 120", (int("0") as FieldResult.Invalid).message)
        assertTrue(int("121") is FieldResult.Invalid)
        assertTrue(int("99999999999") is FieldResult.Invalid)
        assertEquals("Escribe un valor", (int("") as FieldResult.Invalid).message)
    }

    @Test
    fun `formatNumber drops useless zeros and always uses a dot`() {
        assertEquals("6", formatNumber(6.0))
        assertEquals("6.5", formatNumber(6.5))
        assertEquals("1.25", formatNumber(1.25))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals("300", formatNumber(300.0))
        assertEquals("0", formatNumber(0.0))
    }
}
