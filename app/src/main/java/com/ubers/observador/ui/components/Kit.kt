package com.ubers.observador.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Observador's small set of building blocks (Fase 8). Every screen uses the same card, header,
 * tile and bar, so the app reads as one thing. Colors always come from MaterialTheme (light and
 * dark), icons are decorative (the text next to them says the same), and every tile can grow
 * with large fonts: values wrap instead of being cut.
 */

/** A round, tinted icon: the visual anchor of a card header or a tile. */
@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 36.dp
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.56f))
    }
}

/**
 * The standard card: icon badge + title (a heading for TalkBack) + optional subtitle and action,
 * then the content. Filled with the theme's container color, no heavy shadows.
 */
@Composable
fun SectionCard(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, container = badgeContainer, content = badgeContent)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() }
                    )
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                action?.invoke()
            }
            content()
        }
    }
}

/** One number with its label and icon. TalkBack reads "label: value, detail" as one item. */
@Composable
fun StatTile(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    detailColor: Color = Color.Unspecified
) {
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = listOfNotNull("$label: $value", detail).joinToString(", ")
        },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor
                )
            }
        }
    }
}

/** A small line with an icon: notes and hints under a card's numbers. */
@Composable
fun InfoLine(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two tiles side by side that share the width (a 2-column grid row). */
@Composable
fun StatRow(content: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        content(Modifier.weight(1f))
    }
}

/**
 * A bar split in two shares (e.g. ocupado / esperando). [first] is 0..1; nothing is drawn for an
 * unknown share. [description] is what TalkBack reads.
 */
@Composable
fun SplitBar(
    first: Float?,
    description: String,
    modifier: Modifier = Modifier,
    firstColor: Color = MaterialTheme.colorScheme.primary,
    secondColor: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(secondColor)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        if (first != null && first > 0f) {
            Box(Modifier.fillMaxWidth(first.coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(50)).background(firstColor))
        }
    }
}

/** A small legend dot + text, for bars and charts. */
@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Section title with its icon, for long scrolling screens such as Ajustes. */
@Composable
fun IconSectionHeader(text: String, @DrawableRes icon: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 14.dp, bottom = 2.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A bar split in several shares (e.g. km with passenger / pickup / empty / offline). Zero parts
 * are left out; an all-zero bar is just the track. [description] is what TalkBack reads.
 */
@Composable
fun StackedBar(parts: List<Pair<Long, Color>>, description: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        parts.filter { it.first > 0 }.forEach { (value, color) ->
            Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(color))
        }
    }
}

/** Swipe left to delete, with a red "Borrar" behind. Callers offer "Deshacer" right after. */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    // confirmValueChange works on every Material 3 version this project may resolve to;
    // newer ones prefer SwipeToDismissBox(onDismiss = …) and only warn about this overload
    @Suppress("DEPRECATION")
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(text = "Borrar", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        content()
    }
}

/**
 * 1.30.0: the filter chip of every screen. In Violeta it is a borderless pill, purple when
 * selected; elsewhere Material's own chip, as before.
 */
@Composable
fun AppFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    if (com.ubers.observador.ui.theme.LocalPastelBackground.current) {
        androidx.compose.material3.FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            shape = RoundedCornerShape(50),
            border = null,
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    } else {
        androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier)
    }
}

/**
 * 1.30.0: one group of a detail sheet (Recorrido, Dinero, Ficha del viaje…). In Violeta a white
 * rounded card on the pastel sheet; elsewhere the plain column it always was.
 */
@Composable
fun SheetGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (com.ubers.observador.ui.theme.LocalPastelBackground.current) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** 1.30.0: a 0..1 bar. In Violeta a rounded purple pill on a soft track; elsewhere Material's bar. */
@Composable
fun AppProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    if (com.ubers.observador.ui.theme.LocalPastelBackground.current) {
        val primary = MaterialTheme.colorScheme.primary
        Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(primary.copy(alpha = 0.12f))) {
            if (p > 0f) Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.04f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(primary))
        }
    } else {
        androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = modifier.fillMaxWidth())
    }
}
