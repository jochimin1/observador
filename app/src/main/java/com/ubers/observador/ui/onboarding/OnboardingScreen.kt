package com.ubers.observador.ui.onboarding

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.Onboarding
import com.ubers.observador.domain.OnboardingStep
import com.ubers.observador.ui.AppPermissions
import com.ubers.observador.ui.components.rememberLocationPermissionRequest

/**
 * The first-launch welcome (1.29.0): what Observador does and the three permissions it uses,
 * one page each. Every page only explains and opens Android's own screen; the status shown is the
 * real one, read again each time the app comes back. «Saltar» is always there.
 */
@Composable
fun OnboardingScreen(
    accessibilityOn: Boolean,
    notificationsOn: Boolean,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val steps = remember { Onboarding.steps(Build.VERSION.SDK_INT) }
    var stepName by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME.name) }
    val step = OnboardingStep.valueOf(stepName)
    val locationOn by AppPermissions.location.collectAsState()
    val requestLocation = rememberLocationPermissionRequest { }
    val previous = Onboarding.previous(step, steps)
    val next = Onboarding.next(step, steps)

    BackHandler(enabled = previous != null) { previous?.let { stepName = it.name } }

    Scaffold(containerColor = com.ubers.observador.ui.theme.screenBackground()) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                StepDots(current = steps.indexOf(step), total = steps.size, modifier = Modifier.weight(1f))
                if (step != OnboardingStep.READY) TextButton(onClick = onFinish) { Text("Saltar") }
            }
            AnimatedContent(targetState = step, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (s) {
                        OnboardingStep.WELCOME -> IntroPage(
                            title = "Bienvenido a Observador",
                            body = "Lee las ofertas que Uber te muestra, las califica al momento y lleva tus " +
                                "ganancias, gastos y kilómetros del día. Nunca toca Uber: solo mira y anota.",
                            done = false
                        )
                        OnboardingStep.ACCESSIBILITY -> PermissionPage(
                            icon = R.drawable.ic_sensors,
                            title = "Leer las ofertas",
                            body = "Observador usa el servicio de accesibilidad de Android para leer lo que Uber " +
                                "muestra en pantalla. Solo lee: no acepta, no rechaza ni toca nada por ti.",
                            note = "En la lista de accesibilidad busca «Observador» y actívalo. Sin esto la app no ve las ofertas.",
                            granted = accessibilityOn,
                            action = "Abrir accesibilidad",
                            onAction = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        )
                        OnboardingStep.LOCATION -> PermissionPage(
                            icon = R.drawable.ic_route,
                            title = "Kilómetros y recorrido",
                            body = "Con la ubicación precisa Observador puede medir los kilómetros de cada viaje " +
                                "y guardar su recorrido. Es opcional: lo enciendes cuando quieras en Ajustes.",
                            note = "Si Android ya no muestra el aviso, actívala desde los ajustes del teléfono › Apps › Observador.",
                            granted = locationOn,
                            action = "Permitir ubicación",
                            onAction = requestLocation
                        )
                        OnboardingStep.NOTIFICATIONS -> PermissionPage(
                            icon = R.drawable.ic_notifications_active,
                            title = "Avisos",
                            body = "Para avisarte de cada oferta en modo Asistido, de la meta alcanzada, del " +
                                "cambio de aceite y del resumen de la semana.",
                            note = "Puedes apagar cada aviso por separado en Ajustes.",
                            granted = notificationsOn,
                            action = "Permitir avisos",
                            onAction = onRequestNotifications
                        )
                        OnboardingStep.READY -> {
                            val (ok, total) = Onboarding.granted(accessibilityOn, locationOn, notificationsOn, steps)
                            IntroPage(
                                title = "Todo listo",
                                body = if (ok == total) "Los $total permisos están activos. Abre Uber y Observador hará el resto."
                                else "$ok de $total permisos activos. Lo que falte lo puedes activar luego desde Inicio o Ajustes.",
                                done = true
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (previous != null) {
                    TextButton(onClick = { stepName = previous.name }) { Text("Atrás") }
                }
                Button(
                    onClick = { if (next == null) onFinish() else stepName = next.name },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text(
                        when {
                            next == null -> "Empezar"
                            step == OnboardingStep.WELCOME -> "Comenzar"
                            else -> "Siguiente"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "Paso ${current + 1} de $total" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (i == current) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
            )
        }
    }
}

@Composable
private fun IntroPage(title: String, body: String, done: Boolean) {
    Illustration(done = done, modifier = Modifier.size(230.dp))
    Spacer(Modifier.height(28.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PermissionPage(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    note: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(44.dp)
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
            StatusChip(granted)
            if (!granted) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusChip(granted: Boolean) {
    val color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(if (granted) R.drawable.ic_check_circle else R.drawable.ic_pending),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (granted) "Activado" else "Pendiente",
                style = MaterialTheme.typography.labelLarge,
                color = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Observador's own drawing: a phone with a ring and columns, the moto in a bubble and a few
 * sparkles. Drawn with the theme's colours, so it fits every theme.
 */
@Composable
private fun Illustration(done: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.primaryContainer
    val card = MaterialTheme.colorScheme.surfaceContainerLowest
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Soft halo
            drawCircle(color = soft, radius = w * 0.46f, center = Offset(w / 2, h / 2))
            drawCircle(color = primary.copy(alpha = 0.08f), radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f))
            // Phone
            val pw = w * 0.42f
            val ph = h * 0.66f
            val px = (w - pw) / 2
            val py = (h - ph) / 2
            drawRoundRect(color = card, topLeft = Offset(px, py), size = Size(pw, ph), cornerRadius = CornerRadius(w * 0.06f))
            drawRoundRect(
                color = primary, topLeft = Offset(px, py), size = Size(pw, ph),
                cornerRadius = CornerRadius(w * 0.06f), style = Stroke(width = w * 0.012f)
            )
            drawRoundRect(color = outline, topLeft = Offset(w / 2 - pw * 0.15f, py + ph * 0.04f), size = Size(pw * 0.3f, h * 0.012f), cornerRadius = CornerRadius(h))
            // Ring (73 %) on the phone
            val r = pw * 0.26f
            val c = Offset(w / 2, py + ph * 0.3f)
            drawCircle(color = primary.copy(alpha = 0.15f), radius = r, center = c, style = Stroke(width = r * 0.28f))
            drawArc(
                color = primary, startAngle = -90f, sweepAngle = if (done) 360f else 263f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(width = r * 0.28f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            // Columns
            val heights = listOf(0.35f, 0.6f, 0.45f, 0.8f, 0.55f)
            val bw = pw * 0.1f
            val gap = (pw * 0.7f - bw * heights.size) / (heights.size - 1)
            val base = py + ph * 0.88f
            heights.forEachIndexed { i, f ->
                val bh = ph * 0.26f * f
                drawRoundRect(
                    color = if (i == 3) primary else primary.copy(alpha = 0.35f),
                    topLeft = Offset(px + pw * 0.15f + i * (bw + gap), base - bh),
                    size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2)
                )
            }
            // Sparkles
            listOf(Offset(w * 0.12f, h * 0.22f), Offset(w * 0.86f, h * 0.3f), Offset(w * 0.18f, h * 0.8f)).forEachIndexed { i, o ->
                drawCircle(color = if (i == 1) tertiary else primary, radius = w * (0.018f + 0.008f * i), center = o)
            }
        }
        // The moto (or the check when everything is ready) in a bubble at the lower right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 18.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_two_wheeler),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}
