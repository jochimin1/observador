package com.ubers.observador

import com.ubers.observador.domain.ChartAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.29.2: Vico stops the app on a blank axis label (crash of 18/10), so none may be blank. */
class ChartAxisTest {

    @Test fun noLabelIsEverBlank() {
        val labels = listOf("1", "", " ", "4")
        assertEquals("1", ChartAxis.label(labels, 0))
        assertEquals("2", ChartAxis.label(labels, 1))
        assertEquals("3", ChartAxis.label(labels, 2))
        assertEquals("4", ChartAxis.label(labels, 3))
        // Vico may ask for a position outside the columns
        assertEquals("6", ChartAxis.label(labels, 5))
        assertEquals("0", ChartAxis.label(labels, -1))
        (-3..40).forEach { assertTrue(ChartAxis.label(emptyList(), it).isNotBlank()) }
    }

    @Test fun denseChartsLabelOneOfEachFive() {
        assertEquals(1, ChartAxis.every(7))
        assertEquals(1, ChartAxis.every(10))
        assertEquals(5, ChartAxis.every(31))
        assertEquals(2, ChartAxis.every(12, dense = 2))
    }
}
