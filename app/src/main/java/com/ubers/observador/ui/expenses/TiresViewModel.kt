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

/** 1.53.0 «Mi moto y neumáticos». Recomputed from what is written; writes go through TireRepository. */
class TiresViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val tires = TireRepository(db)
    private val vehicles = VehicleRepository(db)
    private val expenses = ExpenseRepository(db)
    private val settings = AppSettings(app)

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
