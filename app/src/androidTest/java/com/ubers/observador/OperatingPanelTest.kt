package com.ubers.observador

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.operating.*
import com.ubers.observador.ui.earnings.OperatingModelDialog
import com.ubers.observador.ui.earnings.OperatingPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class OperatingPanelTest {
    @get:Rule val compose = createComposeRule()
    private val clock = WorkdayClock(4, ZoneId.of("UTC"))

    @Test fun formCollectsExactParametersAndSavesOnlyAfterConfirmation() {
        var shown by mutableStateOf(true)
        var saved: OperatingCostInput? = null
        compose.setContent { MaterialTheme { if (shown) OperatingModelDialog(emptyList(), clock,
            onSave = { saved = it; null }, onDismiss = { shown = false }) } }
        compose.onNodeWithText("Guardar nueva versión").assertIsNotEnabled()
        compose.onNodeWithText("Precio RD$/galón").performTextInput("300")
        compose.onNodeWithText("Rendimiento km/galón").performTextInput("100")
        compose.onNodeWithText("Reserva mantenimiento RD$/km").performScrollTo().performTextClearance()
        compose.onNodeWithText("Reserva mantenimiento RD$/km").performTextInput("1.50")
        compose.onNodeWithText("Costo fijo laboral RD$").performScrollTo().performTextClearance()
        compose.onNodeWithText("Costo fijo laboral RD$").performTextInput("40")
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Guardar nueva versión").performClick()
        compose.waitUntil { saved != null }
        compose.runOnIdle {
            assertEquals(listOf(30_000L, 100_000L, 150L, 0L, 4_000L, 100L), saved!!.values())
            assertFalse(shown)
        }
    }

    @Test fun rejectedVigenceStaysOpenAndShowsTheReason() {
        compose.setContent { MaterialTheme { OperatingModelDialog(emptyList(), clock,
            onSave = { "La fecha no puede reescribir una jornada anterior." }, onDismiss = {}) } }
        compose.onNodeWithText("Precio RD$/galón").performTextInput("300")
        compose.onNodeWithText("Rendimiento km/galón").performTextInput("100")
        compose.onNodeWithText("Guardar nueva versión").performClick()
        compose.onNodeWithText("La fecha no puede reescribir una jornada anterior.").assertExists()
        compose.onNodeWithText("Guardar nueva versión").assertIsEnabled()
    }

    @Test fun missingDataDoesNotRenderAProfitRate() {
        val report = OperatingReport(0, OperatingCostParts(), OperatingCostParts(), emptyList(), 0, 0, 0,
            null, emptySet(), setOf(OperatingIssue.MODEL))
        compose.setContent { MaterialTheme { OperatingPanel(report, emptyList(), clock) { null } } }
        compose.onNodeWithText("Beneficio estimado: no calculable").assertExists()
        compose.onAllNodesWithText("Por hora en línea:", substring = true).assertCountEquals(0)
    }
}
