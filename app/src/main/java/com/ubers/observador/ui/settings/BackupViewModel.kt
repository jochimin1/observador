package com.ubers.observador.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.backup.BackupException
import com.ubers.observador.data.backup.BackupWriter
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
    val message: String? = null,
    val lastBackupAt: Long? = null,
    val offers: Int = 0,
    val trips: Int = 0,
    val expenses: Int = 0
)

class BackupViewModel(private val app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
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
            _state.update { it.copy(lastBackupAt = last, offers = counts.first, trips = counts.second, expenses = counts.third) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** [uri] is where the driver chose to save (null = they closed the picker). */
    fun create(uri: Uri?) {
        if (uri == null || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
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

    /** A failed backup leaves no empty or half-written file in the chosen place. */
    private fun discard(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
    }

    private companion object { const val TAG = "BackupViewModel" }
}
