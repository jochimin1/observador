package com.ubers.observador.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.ubers.observador.data.TireSensorRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.vehicle.Advertisement
import com.ubers.observador.domain.vehicle.BleFrame
import com.ubers.observador.domain.vehicle.FrameDeduper
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
import java.util.UUID

/** What the recorder is doing, for the screen. */
data class SensorScanState(
    val listening: Boolean = false,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val devices: Int = 0,
    val frames: Int = 0,
    /** Frames that mention one of the associated sensors. */
    val mine: Int = 0,
    val lastMineAt: Long? = null,
    val problem: String? = null
)

/**
 * 1.54.0: the single Bluetooth LE listener of Observador. It runs only while the driver has the
 * recorder open (foreground, at most [MAX_MS]), never connects to anything and writes every
 * advertisement it hears to the database (repeats within 2 s of the same device are kept once).
 * Listening in the background, with its own service type, comes after the protocol is validated.
 */
object TireSensorScanner {
    private const val TAG = "TireSensorScanner"
    const val MAX_MS = 5 * 60_000L
    private const val FLUSH_MS = 2_000L

    private val _state = MutableStateFlow(SensorScanState())
    val state: StateFlow<SensorScanState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deduper = FrameDeduper()
    private val buffer = ArrayList<BleFrame>()
    private val addresses = HashSet<String>()
    @Volatile private var sensorIds: Set<String> = emptySet()
    private var callback: ScanCallback? = null
    private var job: Job? = null

    /** The permissions Android asks before listening: Bluetooth scan (12+) and precise location (all). */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPermissions(context: Context): List<String> =
        permissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Why listening cannot start now, in the driver's words, or null. */
    fun problem(context: Context): String? {
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "Este teléfono no tiene Bluetooth LE."
        if (missingPermissions(app).isNotEmpty()) return "Falta el permiso de Bluetooth/ubicación para escuchar los sensores."
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return "Este teléfono no tiene Bluetooth."
        if (!adapter.isEnabled) return "Bluetooth apagado: actívalo para escuchar los sensores."
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == false)
            return "En este Android la búsqueda Bluetooth necesita la ubicación del teléfono activada."
        return null
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, ids: Set<String>): String? {
        if (_state.value.listening) { sensorIds = ids; return null }
        problem(context)?.let { _state.value = _state.value.copy(problem = it); return it }
        val app = context.applicationContext
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: return "Bluetooth no disponible ahora.".also { _state.value = _state.value.copy(problem = it) }
        sensorIds = ids
        deduper.reset(); addresses.clear(); synchronized(buffer) { buffer.clear() }
        val session = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        _state.value = SensorScanState(listening = true, sessionId = session, startedAt = started)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                stop(app, "Android rechazó la búsqueda Bluetooth (código $errorCode). Apaga y enciende Bluetooth e inténtalo otra vez.")
            }
        }
        callback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: SecurityException) {
            callback = null
            return "Falta el permiso de Bluetooth.".also { _state.value = SensorScanState(problem = it) }
        }
        val repo = TireSensorRepository(AppDatabase.getInstance(app))
        job = scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                flush(repo, session)
                if (System.currentTimeMillis() - started >= MAX_MS) { stop(app, null); break }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun onResult(r: ScanResult) {
        val raw = r.scanRecord?.bytes ?: return
        val frame = BleFrame(System.currentTimeMillis(), r.device?.address ?: "?", runCatching { r.scanRecord?.deviceName }.getOrNull(), r.rssi, raw)
        synchronized(buffer) {
            if (!deduper.accept(frame)) return
            buffer += frame
            addresses += frame.address
            val mine = sensorIds.any { Advertisement.mentions(frame, it) }
            val s = _state.value
            _state.value = s.copy(devices = addresses.size, frames = s.frames + 1,
                mine = s.mine + if (mine) 1 else 0, lastMineAt = if (mine) frame.receivedAt else s.lastMineAt)
        }
    }

    private suspend fun flush(repo: TireSensorRepository, session: String) {
        val batch = synchronized(buffer) { ArrayList(buffer).also { buffer.clear() } }
        if (batch.isEmpty()) return
        try { repo.saveFrames(session, batch, sensorIds) } catch (e: Exception) {
            Log.w(TAG, "Could not save frames", e)
            synchronized(buffer) { buffer.addAll(0, batch) }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop(context: Context, problem: String? = null) {
        val cb = callback
        callback = null
        val app = context.applicationContext
        if (cb != null) try {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) { Log.w(TAG, "stopScan", e) }
        val session = _state.value.sessionId
        val j = job; job = null
        _state.value = _state.value.copy(listening = false, problem = problem)
        // What was still in the buffer is written, never dropped
        if (session != null) scope.launch {
            j?.cancel()
            flush(TireSensorRepository(AppDatabase.getInstance(app)), session)
        }
    }
}
