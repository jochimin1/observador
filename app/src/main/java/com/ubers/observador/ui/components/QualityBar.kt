package com.ubers.observador.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubers.observador.domain.OfferQuality

/**
 * One bar split MALO / BUENO / EXCELENTE in proportion to [bad], [good] and [excellent]:
 * the score cuts in Settings and the offer mix in the dashboard. Screen readers hear
 * [description] instead of three coloured boxes. Draws nothing when all three are 0.
 */
@Composable
fun QualityBar(
    bad: Int,
    good: Int,
    excellent: Int,
    description: String,
    modifier: Modifier = Modifier
) {
    val total = bad + good + excellent
    if (total <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clearAndSetSemantics { contentDescription = description }
    ) {
        QualitySegment(OfferQuality.BAD, bad, total)
        QualitySegment(OfferQuality.GOOD, good, total)
        QualitySegment(OfferQuality.EXCELLENT, excellent, total)
    }
}

@Composable
private fun RowScope.QualitySegment(quality: OfferQuality, size: Int, total: Int) {
    if (size <= 0) return
    Box(
        modifier = Modifier
            .weight(size.toFloat())
            .fillMaxHeight()
            .background(Color(quality.backgroundArgb)),
        contentAlignment = Alignment.Center
    ) {
        // A label squeezed into a sliver gets cut; the caption under the bar always names it
        if (size * 100 >= total * MIN_LABEL_PERCENT) {
            Text(
                text = quality.label,
                color = Color(quality.foregroundArgb),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private const val MIN_LABEL_PERCENT = 25
