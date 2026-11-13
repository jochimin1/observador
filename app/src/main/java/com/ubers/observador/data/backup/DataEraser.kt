package com.ubers.observador.data.backup

import android.content.Context
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.backup.ResetRules
import com.ubers.observador.domain.backup.ResetStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class EraseRolledBack : Exception(ResetRules.ROLLED_BACK)

/** Database, settings and auxiliary files share the same verified recovery journal as restore. */
class DataEraser internal constructor(context: Context, private val fault: (String) -> Unit = {}) {
    private val app = context.applicationContext

    suspend fun erase(notice: String, onStep: (ResetStep) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        try {
            MaintenanceGate.shared.exclusive { session ->
                val live = app.getDatabasePath(BackupRestorer.DB_NAME)
                DatabaseFiles.requireSpace(app.noBackupFilesDir, live.length() + File(live.path + "-wal").length())
                val empty = File(app.cacheDir, "empty-${UUID.randomUUID()}.db")
                val journal = RecoveryJournal(app)
                try {
                    DatabaseFiles.migrateAndValidate(app, empty)
                    journal.prepare("ERASE")
                    withContext(NonCancellable) {
                        val settings = AppSettings(app)
                        onStep(ResetStep.PAUSE_READING)
                        settings.updateObserverEnabled(false)
                        onStep(ResetStep.DRAIN_WRITES)
                        session.requireRestart()
                        onStep(ResetStep.CLOSE_DATABASE)
                        BackupRestorer.closeDatabase(app)
                        onStep(ResetStep.DELETE_DATA)
                        journal.install(empty)
                        journal.stageEraseFiles()
                        fault("files-staged")
                        onStep(ResetStep.RESET_SETTINGS)
                        settings.replaceAll(emptyMap())
                        settings.updateOnboardingDone(false)
                        fault("settings-replaced")
                        val inspected = DatabaseFiles.migrateAndValidate(app, live)
                        check(inspected.rows.values.all { it == 0 }) { "The database was not emptied" }
                        settings.setPendingNotice(notice)
                        fault("before-commit")
                        journal.commit("ERASE")
                        runCatching { journal.cleanup() }
                    }
                } catch (e: Exception) {
                    if (!journal.exists) throw e
                    session.requireRestart()
                    withContext(NonCancellable) {
                        try {
                            BackupRestorer.closeDatabase(app)
                            journal.rollback(ResetRules.ROLLED_BACK)
                        } catch (recovery: Exception) { throw RecoveryRequired(recovery) }
                    }
                    throw EraseRolledBack()
                } finally { runCatching { empty.delete() } }
            }
        } catch (e: TimeoutCancellationException) {
            throw BackupException("Hay datos pendientes que no terminaron de guardarse. No se inició el borrado.")
        }
    }
}
