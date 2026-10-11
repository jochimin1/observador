package com.ubers.observador

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.BestHours
import com.ubers.observador.domain.BestHoursStats
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TimeSlotStats
import com.ubers.observador.ui.dashboard.BestHoursSection
import com.ubers.observador.ui.dashboard.BestHoursUiState
import com.ubers.observador.ui.dashboard.SlotDetailSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Mejores horas" on screen, fed with the known test pattern. Nodes are found by what TalkBack
 * reads, which does not depend on the phone's 12/24-hour setting. The numbers themselves are
 * checked by the unit tests (BestHoursStatsTest); these only check what the driver sees.
 */
@RunWith(AndroidJUnit4::class)
class BestHoursSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone)
    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)
    private val seeded = BestHours.calculate(TestOfferSeeder.generate(now), EngineSettings(), cuts, zone)

    private fun show(stats: BestHoursStats, selectedStartHour: Int? = null, onSelect: (TimeSlotStats) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    BestHoursSection(
                        state = BestHoursUiState(StatsPeriod.MONTH, stats, minRdpPerHour = 300.0, loading = false),
                        selectedPeriod = StatsPeriod.MONTH,
                        selectedStartHour = selectedStartHour,
                        onSelectPeriod = {},
                        onSelectSlot = onSelect
                    )
                }
            }
        }
    }

    @Test
    fun withDataTheBestSlotAndTheTop3AreShown() {
        show(seeded)
        compose.onNodeWithText("Mejores horas para salir").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mejor franja observada. De 6 de la tarde a 8 de la noche.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Puesto 1. De 6 de la tarde a 8 de la noche.", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("Puesto 2. De 8 a 10 de la noche.", substring = true).assertExists()
        compose.onNodeWithContentDescription("Puesto 3. De 4 a 6 de la tarde.", substring = true).assertExists()
    }

    @Test
    fun withoutOffersTheEmptyMessageIsShown() {
        show(BestHours.calculate(emptyList(), EngineSettings(), cuts, zone))
        compose.onNodeWithText("Necesitamos más ofertas para identificar tus mejores horas.").assertIsDisplayed()
    }

    @Test
    fun withFewOffersEveryBarSaysPocosDatos() {
        show(BestHours.calculate(TestOfferSeeder.generate(now).take(3), EngineSettings(), cuts, zone))
        compose.onNodeWithText("Pocos datos todavía.", substring = true).assertIsDisplayed()
        val fewData = compose.onAllNodesWithContentDescription("Pocos datos.", substring = true).fetchSemanticsNodes()
        assertTrue(fewData.isNotEmpty())
    }

    @Test
    fun eachBarIsItsOwnTouchTargetAndTapSelectsItsSlot() {
        var tapped: Int? = null
        show(seeded) { tapped = it.startHour }
        compose.onNodeWithContentDescription("De 6 de la tarde a 8 de la noche. Mejor franja observada.", substring = true)
            .performScrollTo()
            .performClick()
        assertEquals(18, tapped)
    }

    @Test
    fun theSelectedBarIsAnnounced() {
        show(seeded, selectedStartHour = 18)
        compose.onNode(hasContentDescription("De 6 de la tarde a 8 de la noche.", substring = true) and isSelectable())
            .assertIsSelected()
        compose.onNode(hasContentDescription("De 8 a 10 de la noche.", substring = true) and isSelectable())
            .assertIsNotSelected()
    }

    @Test
    fun theDetailShowsEveryFigure() {
        compose.setContent {
            MaterialTheme {
                SlotDetailSheet(slot = seeded.slots.single { it.startHour == 18 }, isBest = true, onDismiss = {})
            }
        }
        listOf(
            "RD$/hora mediano", "Rango típico (P25–P75)", "RD$/km mediano", "Score mediano",
            "Buenas + excelentes", "Excelentes", "Recogida típica", "Frecuencia", "Confianza",
            "Índice de oportunidad"
        ).forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }
}
