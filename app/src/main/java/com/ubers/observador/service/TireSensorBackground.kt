package com.ubers.observador.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.ubers.observador.data.TireSensorRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.vehicle.Advertisement
import com.ubers.observador.domain.vehicle.BleFrame
import com.ubers.observador.domain.vehicle.FrameDeduper
import com.ubers.observador.domain.vehicle.TpmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 1.54.0: «Escuchar sensores mientras trabajas». While Observador's service runs and the driver
 * turned it on, listens in low power only for advertisements of the sensors' service (0xFBB0) — a
 * filtered search, which Android keeps running with the screen off — and keeps the frames of the
 * associated sensors. Nothing is connected and no extra GPS is started.
 */
object TireSensorBackground {
    private const val TAG = "TireSensorBackground"
    private const val FLUSH_MS = 10_000L

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deduper = FrameDeduper()
    private val buffer = ArrayList<BleFrame>()
    @Volatile private var ids: Set<String> = emptySet()
    private var callback: ScanCallback? = null
    private var job: Job? = null

    /** Starts, updates or stops listening for [sensorIds] (empty or [on] false stops). */
    @Synchronized
    fun apply(context: Context, on: Boolean, sensorIds: Set<String>) {
        ids = sensorIds
        if (on && sensorIds.isNotEmpty()) start(context) else stop(context)
    }

    @SuppressLint("MissingPermission")
    private fun start(context: Context) {
        if (callback != null) return
        val app = context.applicationContext
        TireSensorScanner.problem(app)?.let { _problem.value = it; return }
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: run { _problem.value = "Bluetooth no disponible."; return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                _problem.value = "Android rechazó la escucha de sensores (código $errorCode)."
                synchronized(this@TireSensorBackground) { callback = null; _listening.value = false }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TpmsParser.SERVICE_UUID)).build()
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb)
        } catch (e: SecurityException) {
            _problem.value = "Falta el permiso de Bluetooth."; return
        }
        callback = cb
        _problem.value = null
        _listening.value = true
        val repo = TireSensorRepository(AppDatabase.getInstance(app))
        job = scope.launch { while (isActive) { delay(FLUSH_MS); flush(repo) } }
    }

    @SuppressLint("MissingPermission")
    private fun onResult(r: ScanResult) {
        val raw = r.scanRecord?.bytes ?: return
        val frame = BleFrame(System.currentTimeMillis(), r.device?.address ?: "?", runCatching { r.scanRecord?.deviceName }.getOrNull(), r.rssi, raw)
        if (ids.none { Advertisement.mentions(frame, it) }) return
        synchronized(buffer) { if (deduper.accept(frame)) buffer += frame }
    }

    private suspend fun flush(repo: TireSensorRepository) {
        val batch = synchronized(buffer) { ArrayList(buffer).also { buffer.clear() } }
        if (batch.isEmpty()) return
        try { repo.saveFrames("fondo-" + LocalDate.now(), batch, ids) } catch (e: Exception) {
            Log.w(TAG, "Could not save frames", e)
            synchronized(buffer) { buffer.addAll(0, batch) }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        try {
            context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) { Log.w(TAG, "stopScan", e) }
        _listening.value = false
        val app = context.applicationContext
        val j = job; job = null
        scope.launch { j?.cancel(); flush(TireSensorRepository(AppDatabase.getInstance(app))) }
    }
}
