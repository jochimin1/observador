package com.ubers.observador.data.backup

import android.content.Context
import android.util.Log
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

class RestoreRolledBack : Exception(RestoreRules.ROLLED_BACK)
class RecoveryRequired(cause: Throwable) : Exception(
    "No se pudo comprobar la recuperación. La copia anterior se conserva; reinicia para reintentar.", cause
)

/** Validates/migrates only private copies, then installs under the shared exclusive data barrier. */
class BackupRestorer internal constructor(context: Context, private val fault: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val tmpDir = File(app.cacheDir, "restaurar-tmp-${UUID.randomUUID()}")

    class Prepared(
        val manifest: BackupManifest,
        val settings: ValidatedSettings,
        val compat: RestoreCompat,
        internal val zip: File,
        internal val db: File,
        internal val databaseHash: String
    )

    suspend fun prepare(input: InputStream, appDbVersion: Int): Prepared = MaintenanceGate.shared.access { withContext(Dispatchers.IO) {
        discard()
        check(tmpDir.mkdirs())
        val zip = File(tmpDir, "respaldo.zip")
        try {
            input.use { src -> zip.outputStream().use { BackupLimits.copy(src, it, BackupLimits.ZIP_BYTES) } }
            val manifest = BackupVerifier.verify(zip)
            RestoreRules.refusal(manifest.dbVersion, appDbVersion)?.let { throw BackupException(it) }
            val db = File(tmpDir, BackupFormat.ENTRY_DB)
            ZipFile(zip).use { archive ->
                val entry = checkNotNull(archive.getEntry(BackupFormat.ENTRY_DB))
                DatabaseFiles.requireSpace(tmpDir, maxOf(entry.size, 0) * 2)
                archive.getInputStream(entry).use { src -> db.outputStream().use { BackupLimits.copy(src, it, BackupLimits.DATABASE_BYTES) } }
            }
            val inspected = DatabaseFiles.inspect(db)
            if (inspected.version != manifest.dbVersion || inspected.rows != manifest.rows) {
                throw BackupException("La base de datos no coincide con la versión o las filas declaradas en la ficha.")
            }
            // Invalid schemas/migrations are refused before closing or changing the live database.
            DatabaseFiles.migrateAndValidate(app, db)
            val decoded = BackupFormat.decodeSettings(BackupVerifier.readSettingsText(zip))
                ?: throw BackupException("Los ajustes del respaldo no se pueden leer.")
            Prepared(manifest, RestoreRules.validateSettings(decoded), RestoreRules.compat(manifest.dbVersion, appDbVersion), zip, db, sha(db))
        } catch (e: Exception) {
            discard()
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is BackupException) throw e
            if (e is BackupLimits.LimitExceeded) throw BackupException(e.message!!)
            Log.w("BackupRestorer", "Backup preparation failed", e)
            throw BackupException("No se pudo validar o migrar el respaldo elegido. Tus datos no se tocaron.")
        }
    } }

    fun discard() { tmpDir.deleteRecursively() }

    suspend fun restore(p: Prepared, onStep: (RestoreStep) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        try {
            MaintenanceGate.shared.exclusive { session ->
                if (!p.db.isFile || sha(p.db) != p.databaseHash) throw BackupException("La copia preparada cambió; vuelve a elegir el respaldo.")
                val expectedRows = DatabaseFiles.inspect(p.db).rows
                val settings = AppSettings(app)
                val before = settings.backupSnapshot()
                val current = app.getDatabasePath(DB_NAME)
                DatabaseFiles.requireSpace(app.noBackupFilesDir, (current.length() + File(current.path + "-wal").length()) * 3 + p.db.length() * 2)
                onStep(RestoreStep.SAFETY_BACKUP)
                val safety = File(app.filesDir, "$SAFETY_NAME.nuevo")
                try {
                    safety.outputStream().use { BackupWriter(app).write(it, markAsLast = false) }
                    java.io.FileOutputStream(safety, true).use { it.fd.sync() }
                    BackupVerifier.verify(safety)
                    RecoveryJournal.move(safety, File(app.filesDir, SAFETY_NAME))
                } catch (e: Exception) {
                    safety.delete()
                    throw BackupException("No se pudo guardar la copia de seguridad automática, así que no se restauró nada.")
                }
                val journal = RecoveryJournal(app)
                try {
                    journal.prepare("RESTORE")
                    withContext(NonCancellable) {
                        onStep(RestoreStep.PAUSE_READING)
                        settings.updateObserverEnabled(false)
                        onStep(RestoreStep.DRAIN_WRITES) // All reservations were acknowledged before exclusive entry.
                        session.requireRestart()
                        onStep(RestoreStep.CLOSE_DATABASE)
                        closeDatabase(app)
                        onStep(RestoreStep.REPLACE_DATABASE)
                        journal.install(p.db)
                        fault("database-installed")
                        check(DatabaseFiles.migrateAndValidate(app, current).rows == expectedRows) { "Installed rows do not match the prepared copy" }
                        onStep(RestoreStep.REPLACE_SETTINGS)
                        settings.replaceWithRestored(p.settings.values)
                        settings.updateDistanceManual(false)
                        settings.updateObserverEnabled(before["observer_enabled"] as? Boolean ?: true)
                        val applied = settings.backupSnapshot()
                        check(p.settings.values.all { (key, value) -> applied[key] == value }) { "Restored settings were not applied completely" }
                        fault("settings-replaced")
                        settings.setPendingNotice(RestoreRules.restoredText(p.manifest.rows, p.compat == RestoreCompat.OLDER, p.settings.defaulted))
                        fault("before-commit")
                        journal.commit("RESTORE")
                        runCatching { journal.cleanup() }
                        runCatching { discard() }
                    }
                } catch (e: Exception) {
                    if (!journal.exists) throw e
                    session.requireRestart()
                    withContext(NonCancellable) {
                        try {
                            closeDatabase(app)
                            journal.rollback(RestoreRules.ROLLED_BACK)
                            discard()
                        } catch (recovery: Exception) {
                            Log.e("BackupRestorer", "Rollback could not be verified", recovery)
                            throw RecoveryRequired(recovery)
                        }
                    }
                    throw RestoreRolledBack()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw BackupException("Hay datos pendientes que no terminaron de guardarse. No se inició la restauración.")
        }
    }

    companion object {
        const val DB_NAME = "observador.db"
        const val SAFETY_NAME = "respaldo-seguridad.zip"
        private fun sha(file: File) = file.inputStream().use { BackupLimits.sha256(it, BackupLimits.DATABASE_BYTES) }
        internal fun closeDatabase(context: Context) {
            val db = AppDatabase.getInstance(context)
            db.close()
            check(!db.isOpen) { "The live database did not close" }
        }
    }
}
