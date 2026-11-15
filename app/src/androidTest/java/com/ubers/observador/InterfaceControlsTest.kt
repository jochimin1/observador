package com.ubers.observador

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.ubers.observador.domain.*
import com.ubers.observador.ui.settings.OverlayLayoutCard
import com.ubers.observador.ui.settings.SettingsSearch
import com.ubers.observador.ui.earnings.OperatingModelDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/** UI actions and restored drafts, only on the QA emulator. */
class InterfaceControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardButtonsRemoveAddAndReorderAtDoubleFontScale() {
        var layout by mutableStateOf(OverlayLayout())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    OverlayLayoutCard(layout) { layout = it }
                } }
            }
        }
        compose.onNodeWithContentDescription("Quitar Recogida y destino (direcciones)").performScrollTo().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(3, layout.fields.size) }
        compose.onNodeWithText("+ RD$ por hora").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Subir RD$ por hora").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Subir RD$ por hora").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OverlayField.RDP_HOUR, layout.fields[1]) }
        compose.onNodeWithText("Abajo", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Muy grande", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(OverlayPosition.BOTTOM, layout.position)
            assertEquals(OverlayTextSize.XLARGE, layout.textSize)
        }
    }

    @Test fun searchKeepsDraftAndFindsFuelAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var opened: SettingsGroup? = null
        restoration.setContent { MaterialTheme { Column { SettingsSearch { opened = it } } } }
        compose.onNodeWithText("Buscar un ajuste").performTextInput("gasolina")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("gasolina").assertExists()
        compose.onNodeWithText("Precio del combustible").performClick()
        compose.runOnIdle { assertEquals(SettingsGroup.PROFIT, opened) }
    }

    @Test fun unsavedCostDraftSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            OperatingModelDialog(emptyList(), WorkdayClock(4, ZoneId.of("UTC")), { null }, {})
        } }
        compose.onNodeWithText("Precio RD$/galón").performScrollTo().performTextReplacement("345.67")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Precio RD$/galón").performScrollTo()
        compose.onNodeWithText("345.67").assertExists()
    }

    @Test fun profileDialogAndUnsavedNameReturnTogether() {
        val restoration = StateRestorationTester(compose)
        var saved: String? = null
        restoration.setContent { MaterialTheme {
            com.ubers.observador.ui.settings.ProfilesCard(
                com.ubers.observador.ui.settings.ProfilesUiState(), null, {},
                { saved = it }, {}, {}, { _, _ -> }, {}
            )
        } }
        compose.onNodeWithText("Guardar ajustes actuales como perfil").performClick()
        compose.onNodeWithText("Nombre (ej. Noche, Lluvia)").performTextInput("Noche de prueba")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Noche de prueba").assertExists()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Guardar", substring = false).performClick()
        compose.runOnIdle { assertEquals("Noche de prueba", saved) }
    }
}
