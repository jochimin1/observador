package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.ExpenseTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.service.MaintenanceNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Debug builds only (Fase 8B): TEST expenses, never touching the driver's own. */
class ExpenseDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ExpenseRepository(AppDatabase.getInstance(app))
    private val settings = AppSettings(app)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load() = viewModelScope.launch {
        _message.value = try {
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            repository.replaceTestData(ExpenseTestSeeder.PREFIX, ExpenseTestSeeder.generate(System.currentTimeMillis(), clock))
            MaintenanceNotifier.check(getApplication())
            "Gastos TEST cargados: 5 tanqueos, aceite, sueltos, 2 fijos y un odómetro. Con aceite cada 2,000 km sale «pronto»."
        } catch (e: Exception) {
            Log.e("ExpenseDebug", "Could not load TEST expenses", e)
            "No se pudieron cargar los gastos TEST."
        }
    }

    fun clear() = viewModelScope.launch {
        _message.value = try {
            "${repository.deleteTestData(ExpenseTestSeeder.PREFIX)} registros TEST borrados. Los tuyos no se tocan."
        } catch (e: Exception) {
            Log.e("ExpenseDebug", "Could not delete TEST expenses", e)
            "No se pudieron borrar los gastos TEST."
        }
    }
}

@Composable
fun ExpenseDebugCard(viewModel: ExpenseDebugViewModel = viewModel()) {
    val message by viewModel.message.collectAsState()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_receipt_long), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  Gastos (prueba)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            Text(
                "Carga registros TEST- para ver la pestaña Gastos con datos. Se borran sin tocar los reales.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.load() }) { Text("Cargar TEST") }
                OutlinedButton(onClick = { viewModel.clear() }) { Text("Borrar TEST") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
