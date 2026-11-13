package com.ubers.observador

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.trips.*
import com.ubers.observador.ui.trips.TripMoneyEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TripMoneyEditorTest {
    @get:Rule val compose = createComposeRule()
    private val trip = Trip("t", "s", null, TripStatus.COMPLETED, false, false,
        completedAt = 3_000, createdAt = 1_000, updatedAt = 3_000)

    @Test fun formPreviewsSavesAndUndoesWithoutLosingHistory() {
        var history by mutableStateOf(emptyList<FinancialObservation>())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TripMoneyEditor(trip, TripFinancialAggregator.aggregate("t", null, history), history,
                        onSave = { request ->
                            history = history + ManualMoney.rows(trip, request, null, 4_000)
                            MoneyEditResult.Saved(request.commandId)
                        }, onVoid = {}, onUndo = { command ->
                            history = history + ManualMoney.undoRows(trip, command, history, "undo", 5_000).orEmpty()
                        })
                }
            }
        }
        compose.onNodeWithText("Registrar dinero").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar y guardar").assertIsNotEnabled()
        compose.onNodeWithText("Importe en RD$").performTextInput("100.50")
        compose.onNodeWithText("Ingreso antes:", substring = true).assertTextContains("100.50", substring = true)
        compose.onNodeWithText("Confirmar y guardar").performClick()
        compose.waitUntil { history.size == 1 }
        compose.runOnIdle { assertEquals(10_050L, TripFinancialAggregator.aggregate("t", null, history).effectiveEarnings!!.minorUnits) }
        compose.onNodeWithText("Deshacer último cambio de dinero").performScrollTo().performClick()
        compose.waitUntil { history.size == 2 }
        compose.runOnIdle { assertNull(TripFinancialAggregator.aggregate("t", null, history).effectiveEarnings) }
    }

    @Test fun rejectedSaveKeepsTheFormAndShowsTheReason() {
        compose.setContent {
            MaterialTheme { TripMoneyEditor(trip, TripFinancialAggregator.aggregate("t", null, emptyList()), emptyList(),
                onSave = { MoneyEditResult.Rejected("Ese importe cambió. Vuelve a abrir su ficha.") }, onVoid = {}, onUndo = {}) }
        }
        compose.onNodeWithText("Registrar dinero").performClick()
        compose.onNodeWithText("Importe en RD$").performTextInput("100")
        compose.onNodeWithText("Confirmar y guardar").performClick()
        compose.onNodeWithText("Ese importe cambió. Vuelve a abrir su ficha.").assertExists()
        compose.onNodeWithText("Confirmar y guardar").assertIsEnabled()
    }
}
