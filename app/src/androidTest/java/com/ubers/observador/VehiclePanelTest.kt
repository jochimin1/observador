package com.ubers.observador

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.operating.*
import com.ubers.observador.ui.expenses.*
import com.ubers.observador.ui.earnings.OperatingModelDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class VehiclePanelTest {
    @get:Rule val compose=createComposeRule()

    @Test fun editingVehicleRequiresSavingAndPreservesTheDeclaredShare() {
        var shown by mutableStateOf(true)
        var saved: Triple<String,Int,Boolean>?=null
        compose.setContent { MaterialTheme { if(shown) VehicleDialog(VehicleProfile(name="Honda",workSharePct=80),false,
            { name, share, replace -> saved=Triple(name,share,replace); null },{shown=false}) } }
        compose.onNodeWithText("Nombre o modelo de la moto").performTextReplacement("Honda CB")
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil { saved!=null }
        compose.runOnIdle { assertEquals(Triple("Honda CB",80,false),saved); assertFalse(shown) }
    }

    @Test fun rejectedComponentIntervalRemainsEditable() {
        compose.setContent { MaterialTheme { ComponentDialog(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL),
            { "Revisa los días del intervalo" },{}) } }
        compose.onNodeWithText("Cada cuántos días (0 desactiva)").performTextReplacement("9999")
        compose.onNodeWithText("Guardar").performClick()
        compose.onNodeWithText("Revisa los días del intervalo").assertExists()
        compose.onNodeWithText("Guardar").assertIsEnabled()
    }

    @Test fun replacementWarnsThatItDoesNotMoveExistingHistory() {
        var saved=false
        compose.setContent { MaterialTheme { VehicleDialog(VehicleProfile(),true,{_,_,_->saved=true;null},{}) } }
        compose.onNodeWithText("La nueva moto empieza ahora.",substring=true).assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertFalse(saved) }
    }

    @Test fun fuelSuggestionPrefillsButDoesNotSaveACostVersionWithoutConfirmation() {
        var saved: OperatingCostInput?=null
        compose.setContent { MaterialTheme { OperatingModelDialog(emptyList(),WorkdayClock(4,ZoneId.of("UTC")),
            { saved=it; null },{},suggestedFuel=30000L to 100000L,suggestedShare=80) } }
        compose.onNodeWithText("300").assertExists()
        compose.onNodeWithText("100").assertExists()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Guardar nueva versión").performClick()
        compose.waitUntil { saved!=null }
        compose.runOnIdle { assertEquals("80",saved!!.looseShare); assertEquals("100",saved!!.kmPerGallon) }
    }

    @Test fun componentHistoryShowsRecordedCostAndExpectedLife() {
        val entry=ExpenseEntry("oil",1000,ExpenseType.OIL,40000,odometerKm=12000,createdAt=1000,updatedAt=1000,expectedLifeKm=2000)
        val plan=ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,2000,90)
        val status=ComponentStatus(plan,listOf(entry),1900,80,false,ReminderLevel.OK)
        val state=ExpensesUiState(loading=false,plans=listOf(plan),components=listOf(status))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehiclePanel(state,{_,_,_->null},{null},{null})
        } } }
        compose.onNodeWithText("Ver 1 cambio").performScrollTo().performClick()
        compose.onNodeWithText("RD$400.00",substring=true).assertExists()
        compose.onNodeWithText("vida prevista 2000 km",substring=true).assertExists()
    }
}
