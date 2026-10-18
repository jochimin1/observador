package com.ubers.observador.data.backup

import android.content.Context
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.ResetRules
import com.ubers.observador.domain.backup.ResetStep
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The erase failed after Room was closed: the old data is back, but the app must restart to open it again. */
class EraseRolledBack : Exception(ResetRules.ROLLED_BACK)

/**
 * «Borrar todos los datos» (1.33.0). Same care as a restore: the current database is set aside first and
 * only deleted when everything else worked; the settings are snapshotted so a failure puts everything back.
 * The accessibility permission is a system setting and is not touched.
 */
class DataEraser(context: Context) {

    private val app = context.applicationContext

    /** [notice] is the line shown when the app opens again. Returns normally when the app is empty (caller restarts it). */
    suspend fun erase(notice: String, onStep: (ResetStep) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        val settings = AppSettings(app)
        val before = settings.backupSnapshot()
        val dbFile = app.getDatabasePath(BackupRestorer.DB_NAME)
        val old = File(dbFile.path + ".anterior")
        var aside = false
        try {
            onStep(ResetStep.PAUSE_READING)
            settings.updateObserverEnabled(false)
            onStep(ResetStep.DRAIN_WRITES)
            drainWrites()
            onStep(ResetStep.CLOSE_DATABASE)
            AppDatabase.getInstance(app).close()
            onStep(ResetStep.DELETE_DATA)
            old.delete()
            if (dbFile.exists()) {
                if (!dbFile.renameTo(old)) throw java.io.IOException("No se pudo apartar la base de datos actual")
                aside = true
            }
            for (suffix in listOf("-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
            onStep(ResetStep.RESET_SETTINGS)
            // Factory state: no settings at all, and the welcome shows again. What the system keeps (the
            // accessibility permission) is not ours to touch.
            settings.replaceAll(emptyMap())
            settings.updateOnboardingDone(false)
            settings.setPendingNotice(notice)
            // Only now the old data goes, with the safety backup and the temporary files
            old.delete()
            File(app.filesDir, BackupRestorer.SAFETY_NAME).delete()
            File(app.filesDir, BackupRestorer.SAFETY_NAME + ".nuevo").delete()
            File(app.cacheDir, "respaldo-tmp").deleteRecursively()
            File(app.cacheDir, "restaurar-tmp").deleteRecursively()
        } catch (e: Exception) {
            runCatching {
                if (aside && old.exists()) {
                    dbFile.delete()
                    for (suffix in listOf("-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
                    old.renameTo(dbFile)
                }
            }
            runCatching { settings.replaceAll(before) }
            runCatching { settings.setPendingNotice(ResetRules.ROLLED_BACK) }
            throw EraseRolledBack()
        }
    }

    private suspend fun drainWrites() {
        val done = CompletableDeferred<Unit>()
        PersistenceQueue.shared().enqueue { done.complete(Unit) }
        withTimeoutOrNull(5_000) { done.await() }
    }
}
