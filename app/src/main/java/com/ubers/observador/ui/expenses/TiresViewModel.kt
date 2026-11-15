package com.ubers.observador.ui.expenses

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.TireMoney
import com.ubers.observador.data.TireRepository
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.VehicleProfile
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class TiresUiState(
    val loading: Boolean = true,
    val vehicle: VehicleProfile = VehicleProfile(),
    val wheels: List<WheelStatus> = emptyList(),
    val summary: String = "",
    val unit: PressureUnit = PressureUnit.DEFAULT,
    val data: TireData = TireData(),
    /** The motorbike's expenses, to offer linking and to show what an event paid. */
    val expenses: List<ExpenseEntry> = emptyList()
)

/** 1.54.0: the sensors of the active motorbike and what the recorder heard. */
data class SensorUi(
    val bindings: List<SensorBinding> = emptyList(),
    /** Newest recorded frame of each associated sensor ID. */
    val latest: Map<String, com.ubers.observador.data.db.TireSensorFrameEntity> = emptyMap(),
    val frameCount: Int = 0,
    val scan: com.ubers.observador.service.SensorScanState = com.ubers.observador.service.SensorScanState()
)

/** 1.53.0 «Mi moto y neumáticos». Recomputed from what is written; writes go through TireRepository. */
class TiresViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val tires = TireRepository(db)
    private val vehicles = VehicleRepository(db)
    private val expenses = ExpenseRepository(db)
    private val settings = AppSettings(app)
    private val sensorRepo = com.ubers.observador.data.TireSensorRepository(db)

    val sensors: StateFlow<SensorUi> = combine(sensorRepo.observeBindings(), sensorRepo.observeLatest(), sensorRepo.observeFrameCount(),
        com.ubers.observador.service.TireSensorScanner.state) { b, latest, count, scan ->
        SensorUi(b, latest.filter { it.sensorId != null }.associateBy { it.sensorId!! }, count, scan)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorUi())

    /** IDs of the sensors associated with the active motorbike, for the recorder to recognise. */
    private fun activeIds(): Set<String> = sensors.value.bindings.filter { it.active && it.vehicleId == state.value.vehicle.id }.map { it.sensorId }.toSet()

    fun startListening(): String? = com.ubers.observador.service.TireSensorScanner.start(getApplication(), activeIds())
    fun stopListening() = com.ubers.observador.service.TireSensorScanner.stop(getApplication())

    /** «13AE32» for [position], or the card's «13AE32&23A434» for both wheels. */
    suspend fun bindCard(position: WheelPosition, text: String): String? {
        val now = System.currentTimeMillis()
        val v = state.value.vehicle.id
        val card = SensorIds.parseCard(text).getOrElse { return it.message }
        return when (card) {
            is SensorIds.Card.Single -> sensorRepo.bind(v, position, card.id, now)
            is SensorIds.Card.Pair -> sensorRepo.bindPair(v, card.front, card.rear, now)
        }.also { if (it == null && com.ubers.observador.service.TireSensorScanner.state.value.listening) startListening() }
    }

    suspend fun unbind(position: WheelPosition) = sensorRepo.unbind(state.value.vehicle.id, position, System.currentTimeMillis())

    /** Every recorded frame as CSV (all columns, raw advertisement included). */
    suspend fun exportFrames(out: java.io.OutputStream) {
        val frames = sensorRepo.frames()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFFsession_id,received_at,address,name,rssi,raw_hex,sensor_id,parser_version,parsed_id,pressure_pa,temperature_centi,battery_pct,leak,problem\r\n")
            frames.forEach { f ->
                w.write(listOf(f.sessionId, f.receivedAt, f.address, f.name, f.rssi, f.rawHex, f.sensorId, f.parserVersion, f.parsedId,
                    f.pressurePa, f.temperatureCenti, f.batteryPct, f.leak, f.problem)
                    .joinToString(",") { v -> (v?.toString() ?: "").let { t -> if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t } })
                w.write("\r\n")
            }
        }
    }

    val state: StateFlow<TiresUiState> = combine(tires.observe(), vehicles.observe(), expenses.observeAll(), settings.tirePressureUnit) { data, care, money, unit ->
        val vehicle = care.active
        val mine = money.expenses.filter { it.vehicleId == vehicle.id }
        val timeline = Odometer.timeline(money.fuel.filter { it.vehicleId == vehicle.id }, mine, money.readings.filter { it.vehicleId == vehicle.id })
        val now = System.currentTimeMillis()
        val wheels = WheelPosition.entries.map { TireRules.status(data, vehicle.id, it, timeline, now, ZoneId.systemDefault(), unit) }
        TiresUiState(false, vehicle, wheels, TireRules.summary(wheels), unit, data, mine)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TiresUiState())

    fun linkable(kind: TireEventKind, atMs: Long, editingEventId: String? = null): List<ExpenseEntry> = state.value.let {
        TireRules.linkable(it.expenses, it.data, it.vehicle.id, kind, atMs, editingEventId)
    }

    fun setUnit(unit: PressureUnit) { viewModelScope.launch { settings.updateTirePressureUnit(unit) } }

    suspend fun install(position: WheelPosition, brand: String, model: String, size: String, target: Pressure?, at: Long,
        odometerKm: Long?, pay: TireMoney, note: String): String? =
        tires.install(state.value.vehicle.id, position, brand, model, size, target, at, odometerKm, pay, note, System.currentTimeMillis())

    suspend fun editTire(tire: Tire, brand: String, model: String, size: String, target: Pressure?): String? =
        tires.editTire(tire.id, brand, model, size, target, System.currentTimeMillis())

    suspend fun saveEvent(event: TireEvent, pay: TireMoney): String? = tires.saveEvent(event, pay, System.currentTimeMillis())

    suspend fun deleteEvent(event: TireEvent): String? = tires.deleteEvent(event.id)

    suspend fun deleteTire(tire: Tire): String? = tires.deleteTire(tire.id, System.currentTimeMillis())
}
