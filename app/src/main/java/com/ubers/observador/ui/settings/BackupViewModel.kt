package com.ubers.observador.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.backup.BackupException
import com.ubers.observador.data.backup.AppRestart
import com.ubers.observador.data.backup.BackupRestorer
import com.ubers.observador.data.backup.BackupWriter
import com.ubers.observador.data.backup.RestoreRolledBack
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.backup.RestoreStep
import com.ubers.observador.domain.backup.ResetRules
import com.ubers.observador.domain.backup.ResetStep
import com.ubers.observador.data.backup.DataEraser
import com.ubers.observador.data.backup.EraseRolledBack
import com.ubers.observador.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ubers.observador.domain.backup.BackupFormat

/** The «Respaldo y datos» card of Ajustes (1.31.0): what there is now, the last backup, and making one. */
data class BackupUiState(
    val busy: Boolean = false,
    val busyLabel: String = "Creando respaldo…",
    val message: String? = null,
    val lastBackupAt: Long? = null,
    val offers: Int = 0,
    val trips: Int = 0,
    val expenses: Int = 0,
    /** A chosen backup that passed every check and waits for «Restaurar». */
    val preview: RestorePreview? = null,
    /** Set while restoring or erasing: the step being done (for the progress dialog). */
    val progress: ProgressUi? = null
)

/** The progress dialog: [index] is the step being done among [steps]. */
data class ProgressUi(val title: String, val steps: List<String>, val index: Int)

/** What the confirmation dialog shows. */
data class RestorePreview(val text: String, val warning: String?)

class BackupViewModel(private val app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val restorer = BackupRestorer(app)
    private var prepared: BackupRestorer.Prepared? = null
    private var nowRows: Map<String, Int> = emptyMap()
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(app).openHelper.readableDatabase
                    fun n(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    Triple(n("detected_offers"), n("trips"), n("expense_entries") + n("fuel_entries"))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not count rows", e)
                    Triple(0, 0, 0)
                }
            }
            val last = settings.lastBackupAt.first()
            nowRows = mapOf("detected_offers" to counts.first, "trips" to counts.second, "expense_entries" to counts.third)
            _state.update { it.copy(lastBackupAt = last, offers = counts.first, trips = counts.second, expenses = counts.third) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** [uri] is where the driver chose to save (null = they closed the picker). */
    fun create(uri: Uri?) {
        if (uri == null || _state.value.busy) return
        _state.update { it.copy(busy = true, busyLabel = "Creando respaldo…", message = null) }
        viewModelScope.launch {
            val message = try {
                val out = app.contentResolver.openOutputStream(uri, "w") ?: throw BackupException("No se pudo abrir el lugar elegido para guardar.")
                val r = BackupWriter(app).write(out)
                BackupFormat.createdText(r.manifest, r.bytes)
            } catch (e: BackupException) {
                discard(uri)
                e.message ?: "No se pudo crear el respaldo."
            } catch (e: Exception) {
                Log.w(TAG, "Backup failed", e)
                discard(uri)
                "No se pudo crear el respaldo. Tus datos no se tocaron."
            }
            _state.update { it.copy(busy = false, message = message) }
            refresh()
        }
    }

    /** [uri] is the file the driver chose (null = they closed the picker). Checks it; nothing live is touched. */
    fun choose(uri: Uri?) {
        if (uri == null || _state.value.busy) return
        _state.update { it.copy(busy = true, busyLabel = "Revisando el archivo…", message = null, preview = null) }
        viewModelScope.launch {
            try {
                val input = app.contentResolver.openInputStream(uri) ?: throw BackupException("No se pudo abrir el archivo elegido.")
                val dbVersion = withContext(Dispatchers.IO) { AppDatabase.getInstance(app).openHelper.readableDatabase.version }
                val p = restorer.prepare(input, dbVersion)
                prepared = p
                val date = RESTORE_DATE.format(java.time.Instant.ofEpochMilli(p.manifest.createdAtMs).atZone(java.time.ZoneId.systemDefault()))
                _state.update {
                    it.copy(busy = false, preview = RestorePreview(
                        RestoreRules.comparisonText(date, p.manifest.rows, nowRows) +
                            "\nAntes de reemplazar se guarda una copia de seguridad automática y la app se reinicia.",
                        RestoreRules.defaultedText(p.settings.defaulted)
                    ))
                }
            } catch (e: BackupException) {
                _state.update { it.copy(busy = false, message = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check backup", e)
                _state.update { it.copy(busy = false, message = "No se pudo leer el archivo elegido. Tus datos no se tocaron.") }
            }
        }
    }

    private fun restoreProgress(step: RestoreStep, previous: ProgressUi? = null): ProgressUi {
        val i = RestoreStep.SHOWN.indexOf(step)
        return ProgressUi("Restaurando respaldo", RestoreStep.SHOWN.map { it.label }, if (i >= 0) i else previous?.index ?: 0)
    }

    private fun eraseProgress(step: ResetStep, previous: ProgressUi? = null): ProgressUi {
        val i = ResetStep.SHOWN.indexOf(step)
        return ProgressUi("Borrando datos", ResetStep.SHOWN.map { it.label }, if (i >= 0) i else previous?.index ?: 0)
    }

    /** «Borrar todos los datos», after both confirmations. Empties the app and restarts it. */
    fun eraseAll() {
        if (_state.value.busy) return
        val rows = nowRows
        _state.update { it.copy(busy = true, busyLabel = "Borrando…", message = null, progress = eraseProgress(ResetStep.PAUSE_READING)) }
        viewModelScope.launch {
            try {
                DataEraser(app).erase(ResetRules.erasedText(rows)) { step -> _state.update { it.copy(progress = eraseProgress(step, it.progress)) } }
                _state.update { it.copy(progress = eraseProgress(ResetStep.RESTART)) }
                kotlinx.coroutines.delay(900)
                AppRestart.now(app)
            } catch (e: EraseRolledBack) {
                AppRestart.now(app)
            } catch (e: Exception) {
                Log.w(TAG, "Erase failed", e)
                _state.update { it.copy(busy = false, progress = null, message = ResetRules.ROLLED_BACK) }
            }
        }
    }

    fun cancelRestore() {
        prepared = null
        _state.update { it.copy(preview = null) }
        viewModelScope.launch(Dispatchers.IO) { restorer.discard() }
    }

    fun confirmRestore() {
        val p = prepared ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyLabel = "Restaurando…", preview = null, message = null, progress = restoreProgress(RestoreStep.SAFETY_BACKUP)) }
        viewModelScope.launch {
            try {
                restorer.restore(p) { step -> _state.update { it.copy(progress = restoreProgress(step, it.progress)) } }
                _state.update { it.copy(progress = restoreProgress(RestoreStep.RESTART)) }
                kotlinx.coroutines.delay(900) // long enough to see the last step before the app closes
                AppRestart.now(app)
            } catch (e: RestoreRolledBack) {
                AppRestart.now(app)
            } catch (e: BackupException) {
                _state.update { it.copy(busy = false, progress = null, message = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Restore failed", e)
                _state.update { it.copy(busy = false, progress = null, message = RestoreRules.ROLLED_BACK) }
            }
            prepared = null
        }
    }

    /** A failed backup leaves no empty or half-written file in the chosen place. */
    private fun discard(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
    }

    private companion object {
        const val TAG = "BackupViewModel"
        val RESTORE_DATE: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy h:mm a", java.util.Locale.forLanguageTag("es-DO"))
    }
}
