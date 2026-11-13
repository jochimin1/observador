package com.ubers.observador.data.backup

import android.content.Context
import android.util.Log
import com.ubers.observador.domain.backup.MaintenanceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object DataRecovery {
    @Volatile var failure: String? = null
        private set

    /** Application calls this before any activity, receiver or service can open the database. */
    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        MaintenanceGate.shared.exclusive { session ->
            try { RecoveryJournal(context.applicationContext).recover() }
            catch (e: Exception) {
                Log.e("DataRecovery", "Recovery could not be verified; data access stays closed", e)
                session.requireRestart()
                failure = "No se pudo terminar la recuperación o la limpieza de datos. Se conservan los archivos disponibles. " +
                    "Puedes guardar la copia anterior si está disponible, liberar espacio y volver a intentarlo."
            }
        }
    }

    suspend fun exportPrevious(context: Context, output: OutputStream) = withContext(Dispatchers.IO) {
        output.use { RecoveryJournal(context.applicationContext).exportPrevious(it) }
    }
}
