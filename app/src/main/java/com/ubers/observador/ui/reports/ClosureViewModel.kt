package com.ubers.observador.ui.reports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.*
import com.ubers.observador.data.db.*
import com.ubers.observador.data.export.*
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.reports.*
import com.ubers.observador.ui.components.ReportExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId

class ClosureViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ClosureRepository(AppDatabase.getInstance(app))
    private val settings = AppSettings(app)
    val history = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val selected = MutableStateFlow<ClosureEntity?>(null)
    val exporter = ReportExporter(app, viewModelScope)

    fun close(window: EarningsWindow?, test: Boolean, profit: String, hours: String, previous: ClosureEntity? = null) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                selected.value = withContext(Dispatchers.IO) {
                    val pg = if (previous == null) ClosureText.parseGoal(profit, 100) else previous.profitGoal
                    val tg = if (previous == null) ClosureText.parseGoal(hours, 3_600_000) else previous.timeGoal
                    val clock = previous?.let { WorkdayClock(it.startHour, ZoneId.of(it.zone)) }
                        ?: WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
                    val selectedWindow = previous?.let {
                        EarningsWindow(com.ubers.observador.domain.earnings.EarningsRange.DAY, 0,
                            clock.workdayOf(it.startMs), clock.workdayOf(it.endMs - 1), it.startMs, it.endMs)
                    } ?: requireNotNull(window) { "Selecciona un periodo." }
                    repo.close(selectedWindow, clock, test, pg, tg, previous)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                error.value = e.message ?: "No se pudo guardar el cierre. Los datos anteriores se conservan."
            } finally { busy.value = false }
        }
    }

    fun save(entity: ClosureEntity) = exporter.save {
        withContext(Dispatchers.Default) {
            val lines = ClosureRepository.lines(entity)
            val name = "cierre-${entity.scope}-r${entity.revision}"
            val zip = ZipPack.of(listOf(
                "$name.csv" to ClosureText.csv(lines).toByteArray(Charsets.UTF_8),
                "$name.json" to entity.payload.toByteArray(Charsets.UTF_8),
                "$name.pdf" to ClosurePdf.render(lines)
            ))
            ReportOutput("$name.zip", "application/zip", zip, lines.size)
        }
    }
}
