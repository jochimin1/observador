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
import com.ubers.observador.domain.sectors.LearnedX
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
 * 1.59.0: real touches only on the X learned from the driver's own closes (LearnedX) for the Uber
 * installed now; without it the close stays a simulation.
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

    /** 1.59.0: when Observador itself last touched Uber, so its own click is not learned as the driver's. */
    var ownTouchAtMs = 0L
        private set

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
                    val learned = LearnedXStore.signature(service)
                    // 1.63.0: the X of the captures is confirmed for the Uber versions they came from
                    val rule = com.ubers.observador.domain.sectors.UberCardX.confirmedFor(UberWatchStore.uberVersion(service))
                    val outcome = registry.outcome(ticket, CloseLogStore.usedToday(service), overlayVisible(), learned != null || rule)
                    val result = if (outcome == CloseOutcome.NONE) {
                        "${CloseLog.NOT_CLOSED} (Las condiciones ya no permiten cerrar)"
                    } else {
                        attempt(ticket, simulate = outcome == CloseOutcome.SIMULATE, learned = learned, rule = rule)
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
    private fun attempt(ticket: PendingSectorCloses.Ticket, simulate: Boolean, learned: LearnedX.Signature?, rule: Boolean = false): String {
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
            val found = CloseTarget.find(snapshot, ticket.offer, ticket.windowId, learned)
            // 1.56.0: the whole screen and the decision, to confirm where Uber's X is
            CloseLogStore.evidence(service, snapshot, ticket.offer.price, ticket.places, when (found) {
                is CloseTarget.Result.Found -> "X encontrada «${found.label}» en el nodo ${found.index}" + if (simulate) " (simulación)" else ""
                is CloseTarget.Result.Refused -> "Sin X: ${found.reason}" + if (simulate) " (simulación)" else ""
            })
            if (found is CloseTarget.Result.Refused) return "${CloseLog.NOT_CLOSED} (${found.reason})"
            found as CloseTarget.Result.Found
            if (simulate) return "${CloseLog.SIMULATED} (botón «${found.label}»)"
            // Even an accidental direct call cannot touch a button that was neither learned from the driver
            // nor found by the rule of the captures for this Uber version (a word match alone never touches).
            if (learned == null && !(rule && found.label == com.ubers.observador.domain.sectors.UberCardX.LABEL)) {
                return "${CloseLog.NOT_CLOSED} (X sin confirmar para esta versión de Uber)"
            }
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
                    ScreenSnapshotFactory.from(root, System.currentTimeMillis()), ticket.offer, ticket.windowId, learned
                )
                if (finalCheck !is CloseTarget.Result.Found || finalCheck.index != found.index) {
                    return "${CloseLog.NOT_CLOSED} (La tarjeta cambió antes del toque)"
                }
                // Main-thread serialized, durably reserved BEFORE touching; a failed log cannot bypass the cap.
                return com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess {
                    if (!CloseLogStore.reserve(service)) "${CloseLog.NOT_CLOSED} (Límite diario o registro no disponible)"
                    else if (run { ownTouchAtMs = System.currentTimeMillis(); node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) CloseLog.REQUESTED
                    else "${CloseLog.NOT_CLOSED} (Android no aceptó el toque)"
                } ?: "${CloseLog.NOT_CLOSED} (Datos en mantenimiento)"
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
            val gone = runCatching { cardGone(ticket.offer, ticket.windowId) }.getOrDefault(false)
            CloseLogStore.append(
                service, if (gone) CloseLog.CLOSED else "${CloseLog.NOT_CLOSED} (Toque solicitado; cierre no verificado)",
                ticket.places, ticket.offer.price
            )
        }
        timers[ticket.offer.offerHash()] = verify
        handler.postDelayed(verify, 500L)
    }

    /**
     * Whether [offer] left the screen with Uber back to searching (or other cards still there): a
     * card that was closed, not accepted. Also used to learn the X from the driver's own closes.
     */
    @Suppress("DEPRECATION")
    fun cardGone(offer: UberOffer, windowId: Int?): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != UBER || root.windowId != windowId) return false
            val snapshot = ScreenSnapshotFactory.from(root, System.currentTimeMillis())
            val offers = OfferParser.parseAll(snapshot.texts)
            if (offers.isNotEmpty()) return offers.none { it.contentFingerprint() == offer.contentFingerprint() }
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
        com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess { try {
            dir(context).mkdirs()
            today(context).appendText(CloseLog.line(System.currentTimeMillis(), ZoneId.systemDefault(), result, places, price) + "\n")
        } catch (e: Exception) {
            Log.w("CloseLogStore", "Could not write the close log", e)
        } }
    }

    /** 1.56.0: one evidence block per «Cerrar» countdown (see CloseEvidence), kept until the driver erases data. */
    fun evidence(context: Context, snapshot: com.ubers.observador.domain.lifecycle.ScreenSnapshot, price: Double, places: String, decision: String) {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo("com.ubercab.driver", 0).versionName }.getOrNull()
        val text = com.ubers.observador.domain.sectors.CloseEvidence.format(snapshot, price, places, decision, version, ZoneId.systemDefault())
        com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess { try {
            dir(app).mkdirs()
            File(dir(app), com.ubers.observador.domain.sectors.CloseEvidence.fileName(snapshot.timestampMs, ZoneId.systemDefault())).appendText(text)
        } catch (e: Exception) {
            Log.w("CloseLogStore", "Could not write close evidence", e)
        } }
    }

    fun evidenceFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith(com.ubers.observador.domain.sectors.CloseEvidence.FILE_PREFIX) }?.sortedBy { it.name }.orEmpty()

    fun evidenceCount(context: Context): Int = evidenceFiles(context).sumOf { f ->
        runCatching { com.ubers.observador.domain.sectors.CloseEvidence.count(f.readText()) }.getOrDefault(0)
    }

    /** Every evidence file and every daily close log, in one .zip. */
    fun exportEvidence(context: Context, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            (dir(context).listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()).forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    /** 1.60.0: every close line of the last [days] days (today included), oldest first. */
    fun linesOfDays(context: Context, days: Int, now: Long = System.currentTimeMillis()): List<String> {
        val zone = ZoneId.systemDefault()
        return (days - 1 downTo 0).flatMap { back ->
            val at = java.time.Instant.ofEpochMilli(now).atZone(zone).minusDays(back.toLong()).toInstant().toEpochMilli()
            try { File(dir(context), CloseLog.fileName(at, zone)).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty() }
            catch (e: Exception) { emptyList() }
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
    fun reserve(context: Context): Boolean = com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess { try {
        val legacy = today(context).takeIf { it.exists() }?.readText()?.let(CloseLog::closedCount) ?: 0
        DailyCloseBudget.reserve(budget(context), legacy)
    } catch (e: Exception) {
        Log.w("CloseLogStore", "Could not reserve the daily budget", e)
        false
    } } ?: false
}

/**
 * 1.59.0: the X learned from the driver's own closes (see [LearnedX]), in cierres/x-aprendida.txt.
 * Lines are only added: «Olvidar» writes a line too. The file travels in backups and in the
 * evidence export with the rest of cierres/.
 */
object LearnedXStore {
    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "cierres"), LearnedX.FILE)

    @Volatile private var cached: Pair<String?, LearnedX.State>? = null

    fun uberVersion(context: Context): String? =
        runCatching { context.applicationContext.packageManager.getPackageInfo("com.ubercab.driver", 0).versionName }.getOrNull()

    @Synchronized
    fun state(context: Context): LearnedX.State {
        val version = uberVersion(context)
        cached?.let { (v, s) -> if (v == version) return s }
        val text = try { file(context).takeIf { it.isFile }?.readText().orEmpty() } catch (e: Exception) { "" }
        return LearnedX.state(text, version).also { cached = version to it }
    }

    fun signature(context: Context): LearnedX.Signature? = (state(context) as? LearnedX.State.Learned)?.signature

    @Synchronized
    fun add(context: Context, observation: LearnedX.Observation): Boolean = write(context, LearnedX.encode(observation))

    @Synchronized
    fun forget(context: Context): Boolean = write(context, LearnedX.forgetLine(System.currentTimeMillis()))

    private fun write(context: Context, line: String): Boolean =
        com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess {
            try {
                file(context).parentFile?.mkdirs()
                file(context).appendText(line + "\n")
                cached = null
                true
            } catch (e: Exception) {
                Log.w("LearnedXStore", "Could not write the learned X", e)
                false
            }
        } ?: false
}
