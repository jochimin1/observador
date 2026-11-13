package com.ubers.observador.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.lifecycle.UberScreenClassifier
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.sectors.CloseConfiguration
import com.ubers.observador.domain.sectors.CloseLog
import com.ubers.observador.domain.sectors.CloseOutcome
import com.ubers.observador.domain.sectors.CloseRules
import com.ubers.observador.domain.sectors.CloseTarget
import com.ubers.observador.domain.sectors.PendingSectorCloses
import com.ubers.observador.domain.sectors.DailyCloseBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * Main-thread timers backed by the tested pending registry. Settings are read atomically again
 * before execution. The live card must match the appearance's full content and original window.
 * Actual touches remain disabled while CloseTarget.VALIDATED is false.
 */
class SectorCloser(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val overlayVisible: () -> Boolean
) {
    private val registry = PendingSectorCloses()
    private val timers = LinkedHashMap<String, Runnable>()
    val revision: Long get() = registry.revision

    fun configure(configuration: CloseConfiguration) {
        cancel(registry.configure(configuration), "Cambió la configuración")
    }

    fun observe(offers: List<UberOffer>, windowId: Int?) {
        cancel(registry.observe(offers, windowId), "La tarjeta dejó de estar visible")
    }

    fun schedule(offer: UberOffer, windowId: Int?, places: String, expectedRevision: Long) {
        if (!overlayVisible()) return
        val ticket = registry.add(offer, windowId, places, expectedRevision) ?: return
        val timer = Runnable { fire(ticket) }
        timers[offer.offerHash()] = timer
        handler.postDelayed(timer, CloseRules.COUNTDOWN_MS)
    }

    fun cancelAll() = cancel(registry.cancelAll(), "Cancelado por ti")

    fun clear() {
        registry.clear()
        timers.values.forEach(handler::removeCallbacks)
        timers.clear()
    }

    private fun cancel(tickets: List<PendingSectorCloses.Ticket>, reason: String) {
        tickets.forEach { ticket ->
            timers.remove(ticket.offer.offerHash())?.let(handler::removeCallbacks)
            CloseLogStore.append(service, "${CloseLog.NOT_CLOSED} ($reason)", ticket.places, ticket.offer.price)
        }
    }

    private fun fire(ticket: PendingSectorCloses.Ticket) {
        // The ticket remains registered during the IO read: a tap, pause or new snapshot can cancel it.
        scope.launch {
            try {
                val latest = settings.closeConfiguration.first()
                withContext(Dispatchers.Main.immediate) {
                    configure(latest)
                    if (registry.take(ticket) == null) return@withContext
                    timers.remove(ticket.offer.offerHash())
                    val outcome = registry.outcome(ticket, CloseLogStore.usedToday(service), overlayVisible())
                    val result = if (outcome == CloseOutcome.NONE) {
                        "${CloseLog.NOT_CLOSED} (Las condiciones ya no permiten cerrar)"
                    } else {
                        attempt(ticket, simulate = outcome == CloseOutcome.SIMULATE)
                    }
                    CloseLogStore.append(service, result, ticket.places, ticket.offer.price)
                    if (result == CloseLog.REQUESTED) verifyLater(ticket)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Close attempt failed", e)
                withContext(Dispatchers.Main.immediate) {
                    registry.take(ticket)
                    timers.remove(ticket.offer.offerHash())
                    CloseLogStore.append(service, "${CloseLog.NOT_CLOSED} (No se pudo verificar)", ticket.places, ticket.offer.price)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun attempt(ticket: PendingSectorCloses.Ticket, simulate: Boolean): String {
        // Do not act on a background Uber window while another app is in front.
        val active = service.rootInActiveWindow ?: return "${CloseLog.NOT_CLOSED} (Sin ventana activa)"
        try {
            if (active.packageName?.toString() != UBER) return "${CloseLog.NOT_CLOSED} (Uber no está al frente)"
        } finally {
            active.recycle()
        }
        val roots = uberRoots()
        try {
            val root = roots.singleOrNull { it.windowId == ticket.windowId }
                ?: return "${CloseLog.NOT_CLOSED} (La ventana cambió)"
            if (!root.refresh()) return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
            val snapshot = ScreenSnapshotFactory.from(root, System.currentTimeMillis())
            val found = CloseTarget.find(snapshot, ticket.offer, ticket.windowId)
            if (found is CloseTarget.Result.Refused) return "${CloseLog.NOT_CLOSED} (${found.reason})"
            found as CloseTarget.Result.Found
            if (simulate) return "${CloseLog.SIMULATED} (botón «${found.label}»)"
            // Even an accidental direct call cannot bypass the global validation gate.
            if (!CloseTarget.VALIDATED) return "${CloseLog.NOT_CLOSED} (X sin validar)"
            val node = ScreenSnapshotFactory.nodeAt(root, found.index)
                ?: return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
            try {
                if (!node.refresh() || !node.isClickable || !node.isEnabled || !node.isVisibleToUser) return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
                val before = snapshot.nodes[found.index]
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                if (node.text?.toString() != before.text || node.contentDescription?.toString() != before.contentDescription ||
                    node.viewIdResourceName != before.viewId ||
                    bounds.left != before.left || bounds.top != before.top || bounds.right != before.right || bounds.bottom != before.bottom
                ) return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
                if (!root.refresh()) return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
                val finalCheck = CloseTarget.find(
                    ScreenSnapshotFactory.from(root, System.currentTimeMillis()), ticket.offer, ticket.windowId
                )
                if (finalCheck !is CloseTarget.Result.Found || finalCheck.index != found.index) {
                    return "${CloseLog.NOT_CLOSED} (La tarjeta cambió antes del toque)"
                }
                // Main-thread serialized, durably reserved BEFORE touching; a failed log cannot bypass the cap.
                if (!CloseLogStore.reserve(service)) return "${CloseLog.NOT_CLOSED} (Límite diario o registro no disponible)"
                return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) CloseLog.REQUESTED
                else "${CloseLog.NOT_CLOSED} (Android no aceptó el toque)"
            } finally {
                node.recycle()
            }
        } finally {
            // All roots were acquired up front; early return must recycle the unvisited ones too.
            roots.forEach { it.recycle() }
        }
    }

    private fun verifyLater(ticket: PendingSectorCloses.Ticket) {
        val verify = Runnable {
            timers.remove(ticket.offer.offerHash())
            val gone = runCatching { verifyGone(ticket) }.getOrDefault(false)
            CloseLogStore.append(
                service, if (gone) CloseLog.CLOSED else "${CloseLog.NOT_CLOSED} (Toque solicitado; cierre no verificado)",
                ticket.places, ticket.offer.price
            )
        }
        timers[ticket.offer.offerHash()] = verify
        handler.postDelayed(verify, 500L)
    }

    @Suppress("DEPRECATION")
    private fun verifyGone(ticket: PendingSectorCloses.Ticket): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != UBER || root.windowId != ticket.windowId) return false
            val snapshot = ScreenSnapshotFactory.from(root, System.currentTimeMillis())
            val offers = OfferParser.parseAll(snapshot.texts)
            if (offers.isNotEmpty()) return offers.none { it.contentFingerprint() == ticket.offer.contentFingerprint() }
            return UberScreenClassifier.spanish(UBER).classify(snapshot).type == UberScreenType.SEARCHING
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun uberRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val active = service.rootInActiveWindow
            if (active != null) {
                if (active.packageName?.toString() == UBER) out += active else active.recycle()
            }
            val activeId = out.firstOrNull()?.windowId
            service.windows.forEach { w ->
                if (w.id == activeId) return@forEach
                val root = w.root ?: return@forEach
                if (root.packageName?.toString() == UBER) out += root else root.recycle()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not list windows", e)
        }
        return out
    }

    private companion object {
        const val TAG = "SectorCloser"
        const val UBER = "com.ubercab.driver"
    }
}

/** Daily close results and a separate durable budget for actual touch attempts. */
object CloseLogStore {
    private fun dir(context: Context) = File(context.applicationContext.filesDir, "cierres")
    private fun today(context: Context): File =
        File(dir(context), CloseLog.fileName(System.currentTimeMillis(), ZoneId.systemDefault()))
    private fun budget(context: Context) = File(dir(context), "cupos-" + today(context).name)

    fun append(context: Context, result: String, places: String, price: Double) {
        try {
            dir(context).mkdirs()
            today(context).appendText(CloseLog.line(System.currentTimeMillis(), ZoneId.systemDefault(), result, places, price) + "\n")
        } catch (e: Exception) {
            Log.w("CloseLogStore", "Could not write the close log", e)
        }
    }

    fun todayLines(context: Context): List<String> =
        try {
            today(context).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        } catch (e: Exception) { emptyList() }

    fun closedToday(context: Context): Int = CloseLog.closedCount(todayLines(context).joinToString("\n"))

    /** Legacy confirmed closes also consume the budget. Read failures refuse further attempts. */
    @Synchronized
    fun usedToday(context: Context): Int = try {
        DailyCloseBudget.used(budget(context), today(context).takeIf { it.exists() }?.readText()?.let(CloseLog::closedCount) ?: 0)
    } catch (e: Exception) { CloseRules.DAILY_CAP }

    @Synchronized
    fun reserve(context: Context): Boolean = try {
        val legacy = today(context).takeIf { it.exists() }?.readText()?.let(CloseLog::closedCount) ?: 0
        DailyCloseBudget.reserve(budget(context), legacy)
    } catch (e: Exception) {
        Log.w("CloseLogStore", "Could not reserve the daily budget", e)
        false
    }
}
