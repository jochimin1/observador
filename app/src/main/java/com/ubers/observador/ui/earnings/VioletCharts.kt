package com.ubers.observador.ui.earnings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.ubers.observador.domain.earnings.DayBar
import com.ubers.observador.domain.earnings.EarningsFormat

/**
 * 1.28.0, Violeta: real income per working day as rounded purple columns (Vico), with RD$ on the
 * left and the day below. Same figures as the bars of the other themes; nothing drawn without days.
 */
@Composable
fun EarningsColumnsChart(bars: List<DayBar>, description: String, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    val dense = bars.size > 10
    val labels = remember(bars) {
        bars.mapIndexed { i, b ->
            when {
                !dense -> EarningsFormat.dayShort(b.day)
                i % 5 == 0 || i == bars.lastIndex -> b.day.dayOfMonth.toString()
                else -> ""
            }
        }
    }
    VioletColumns(bars.map { it.incomeMinor / 100.0 }, labels, description, modifier)
}

/**
 * Rounded purple columns of the Violeta theme (Vico): [values] in RD$ (null or zero = an empty
 * slot), one [labels] entry under each. TalkBack reads [description] instead of the drawing.
 */
@Composable
fun VioletColumns(values: List<Double?>, labels: List<String>, description: String, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val model = remember(values) {
        CartesianChartModel(ColumnCartesianLayerModel.build { series(values.map { it ?: 0.0 }) })
    }
    val primary = MaterialTheme.colorScheme.primary
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(
                            fill = Fill(primary),
                            thickness = if (values.size > 10) 10.dp else 20.dp,
                            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        )
                    )
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = remember { CartesianValueFormatter.decimal(decimalCount = 0, thousandsSeparator = ",", prefix = "RD$") }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = remember(labels) { CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } } }
                )
            ),
            model = model,
            modifier = modifier.fillMaxWidth().height(190.dp).clearAndSetSemantics { contentDescription = description }
        )
    }
}
