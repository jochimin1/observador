package com.ubers.observador.domain.reports

import org.junit.Assert.*
import org.junit.Test

class ClosureTest {
    @Test fun unknownDoesNotBecomeZeroOrMeetGoal() {
        assertEquals("Sin dato", ClosureText.display(ClosureLine("", "", null, "DOP_centavos")))
        assertEquals("No evaluable: faltan datos", ClosureText.goal(null, 100))
        assertEquals("RD$ 0.00", ClosureText.display(ClosureLine("", "", "0", "DOP_centavos")))
    }
    @Test fun exactGoalsWithoutFloatingPointOrImplicitDefaults() {
        assertNull(ClosureText.parseGoal("", 100))
        assertEquals(12345L, ClosureText.parseGoal("123,45", 100))
        assertEquals(5_400_000L, ClosureText.parseGoal("1.5", 3_600_000))
        listOf("0", "-1", "abc", "1.234", "1,000.00").forEach {
            assertTrue(runCatching { ClosureText.parseGoal(it, 100) }.isFailure)
        }
    }
    @Test fun profitAndMaximumTimeHaveDifferentDirections() {
        assertEquals("Meta pendiente", ClosureText.goal(-100, 100))
        assertEquals("Meta alcanzada", ClosureText.goal(100, 100))
        assertEquals("Dentro del tiempo máximo", ClosureText.goal(100, 100, true))
        assertEquals("Tiempo máximo superado", ClosureText.goal(101, 100, true))
    }
    @Test fun exportsPreserveUnknownIdentityQuotesAndExactUnits() {
        val lines = listOf(ClosureLine("Viajes", "Pasajero", "Ana, \"María\"\nPérez", reference = "trip-1"),
            ClosureLine("Resumen", "Ingreso", "10001", "DOP_centavos"), ClosureLine("Resumen", "Beneficio", null, "DOP_centavos"))
        val csv = ClosureText.csv(lines)
        val json = ClosureText.json(lines)
        assertTrue(csv.contains("\"Ana, \"\"María\"\"\nPérez\""))
        assertTrue(json.contains("Ana, \\\"María\\\"\\nPérez"))
        assertTrue(json.contains("\"value\":null"))
        assertTrue(csv.contains("10001,DOP_centavos"))
        assertTrue(json.contains("\"value\":\"10001\""))
        assertTrue(json.contains("trip-1"))
    }
}
