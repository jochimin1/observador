package com.ubers.observador.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ubers.observador.domain.lifecycle.TextNormalizer
import com.ubers.observador.domain.sectors.CloseLog
import com.ubers.observador.domain.sectors.CloseRules
import com.ubers.observador.domain.sectors.CloseTarget
import java.io.File
import java.time.ZoneId

/**
 * 1.37.0, «Automático» mode only: closes with Uber's X an offer card that falls in a sector set to
 * «Cerrar», 3 seconds after it appears, unless the driver taps the floating card. Before touching,
 * the screen is read again and the X is looked up by the card's price; anything unclear is refused.
 * It never touches «Aceptar» or «Me interesa». While [CloseTarget.VALIDATED] is false nothing is
 * touched: the attempt is only simulated and logged.
 */
class SectorCloser(private val service: AccessibilityService, private val handler: Handler) {

    private class Pending(val runnable: Runnable, val places: String, val price: Double)

    private val pending = LinkedHashMap<String, Pending>()

    /** Main thread. [hash] is the offer; [places] «Destino: Engombe»; [price] finds its X. */
    fun schedule(hash: String, price: Double, places: String, simulate: Boolean) {
        if (pending.containsKey(hash)) return
        val r = Runnable { fire(hash, price, places, simulate) }
        pending[hash] = Pending(r, places, price)
        handler.postDelayed(r, CloseRules.COUNTDOWN_MS)
    }

    /** The driver tapped the floating card: nothing pending is closed. Main thread. */
    fun cancelAll() {
        pending.values.forEach {
            handler.removeCallbacks(it.runnable)
            CloseLogStore.append(service, "${CloseLog.NOT_CLOSED} (Cancelado por ti)", it.places, it.price)
        }
        pending.clear()
    }

    /** Observer paused or service gone: drop everything without logging. */
    fun clear() {
        pending.values.forEach { handler.removeCallbacks(it.runnable) }
        pending.clear()
    }

    private fun fire(hash: String, price: Double, places: String, simulate: Boolean) {
        pending.remove(hash)
        val result = try {
            attempt(price, simulate)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Close attempt failed", e)
            "${CloseLog.NOT_CLOSED} (Error al leer la pantalla)"
        }
        CloseLogStore.append(service, result, places, price)
    }

    @Suppress("DEPRECATION")
    private fun attempt(price: Double, simulate: Boolean): String {
        for (root in uberRoots()) {
            try {
                val snapshot = ScreenSnapshotFactory.from(root, System.currentTimeMillis())
                when (val found = CloseTarget.find(snapshot, price)) {
                    is CloseTarget.Result.Refused -> if (found.reason.startsWith("La oferta ya no")) continue
                        else return "${CloseLog.NOT_CLOSED} (${found.reason})"
                    is CloseTarget.Result.Found -> {
                        if (simulate) return "${CloseLog.SIMULATED} (botón «${found.label}»)"
                        val node = ScreenSnapshotFactory.nodeAt(root, found.index)
                            ?: return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
                        try {
                            // The node must still be the X that was found, not whatever took its place
                            val now = TextNormalizer.normalize(
                                listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
                            )
                            val was = snapshot.nodes[found.index].let {
                                TextNormalizer.normalize(listOfNotNull(it.text, it.contentDescription).joinToString(" "))
                            }
                            if (now != was || !node.isClickable) return "${CloseLog.NOT_CLOSED} (La pantalla cambió)"
                            return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) CloseLog.CLOSED
                            else "${CloseLog.NOT_CLOSED} (Android no aceptó el toque)"
                        } finally {
                            node.recycle()
                        }
                    }
                }
            } finally {
                root.recycle()
            }
        }
        return "${CloseLog.NOT_CLOSED} (La oferta ya no estaba)"
    }

    /** Every Uber window, the active one first; the caller recycles each root. */
    @Suppress("DEPRECATION")
    private fun uberRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val active = service.rootInActiveWindow
        if (active != null) {
            if (active.packageName?.toString() == UBER) out += active else active.recycle()
        }
        val activeId = out.firstOrNull()?.windowId
        try {
            service.windows.forEach { w ->
                if (w.id == activeId) return@forEach
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() == UBER) out += r else r.recycle()
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

/** 1.37.0: the daily close log in filesDir/cierres (deleted with «Borrar todos los datos»). */
object CloseLogStore {

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "cierres")

    private fun today(context: Context): File =
        File(dir(context), CloseLog.fileName(System.currentTimeMillis(), ZoneId.systemDefault()))

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
        } catch (e: Exception) {
            emptyList()
        }

    fun closedToday(context: Context): Int = CloseLog.closedCount(todayLines(context).joinToString("\n"))
}
