package com.ubers.observador.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.domain.backup.RestoreCompat
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.backup.ValidatedSettings
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** The restore failed after Room was closed: the old data is back, but the app must restart to open it again. */
class RestoreRolledBack : Exception(RestoreRules.ROLLED_BACK)

/**
 * Restores a backup (1.32.0). Two stages:
 *  1. [prepare]: copies the chosen file to private storage and checks it WITHOUT touching anything live
 *     (zip + SHA-256, database copy opens and passes `integrity_check`, version not newer than this app).
 *  2. [restore]: automatic safety backup of what there is now (only the last one is kept), pause reading,
 *     let queued writes land, close Room, replace the database and the settings, restart. If anything fails
 *     after the replacing began, the previous database and settings go back and the driver is told so.
 */
class BackupRestorer(context: Context) {

    private val app = context.applicationContext
    private val tmpDir get() = File(app.cacheDir, "restaurar-tmp")
    private val safetyFile get() = File(app.filesDir, SAFETY_NAME)

    /** A backup that passed every check and waits for the driver's confirmation. */
    class Prepared(
        val manifest: BackupManifest,
        val settings: ValidatedSettings,
        val compat: RestoreCompat,
        internal val zip: File,
        internal val db: File
    )

    suspend fun prepare(input: InputStream, appDbVersion: Int): Prepared = withContext(Dispatchers.IO) {
        discard()
        tmpDir.mkdirs()
        val zip = File(tmpDir, "respaldo.zip")
        try {
            input.use { src -> zip.outputStream().use { src.copyTo(it) } }
            val manifest = BackupVerifier.verify(zip)
            RestoreRules.refusal(manifest.dbVersion, appDbVersion)?.let { throw BackupException(it) }
            val db = File(tmpDir, BackupFormat.ENTRY_DB)
            ZipFile(zip).use { z ->
                val entry = z.getEntry(BackupFormat.ENTRY_DB) ?: throw BackupException("Al respaldo le falta ${BackupFormat.ENTRY_DB}.")
                z.getInputStream(entry).use { s -> db.outputStream().use { s.copyTo(it) } }
            }
            checkDatabase(db, manifest)
            val decoded = BackupFormat.decodeSettings(BackupVerifier.readSettingsText(zip))
                ?: throw BackupException("Los ajustes del respaldo no se pueden leer: el archivo está dañado.")
            Prepared(manifest, RestoreRules.validateSettings(decoded), RestoreRules.compat(manifest.dbVersion, appDbVersion), zip, db)
        } catch (e: BackupException) {
            discard(); throw e
        } catch (e: Exception) {
            discard(); throw BackupException("No se pudo leer el archivo elegido. Tus datos no se tocaron.")
        }
    }

    /** Throws BackupException if the copy is not a sound database of the version the sheet says. */
    private fun checkDatabase(file: File, manifest: BackupManifest) {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupException("La base de datos del respaldo no se pudo abrir: el archivo está dañado.")
        }
        db.use {
            val ok = it.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            if (!ok) throw BackupException("La base de datos del respaldo no pasó la revisión de integridad.")
            if (it.version != manifest.dbVersion) throw BackupException("La versión de la base de datos no coincide con la ficha: el archivo está dañado.")
        }
    }

    fun discard() { tmpDir.deleteRecursively() }

    /**
     * Replaces everything. Returns normally only if the new data is in place (the caller then restarts the
     * app). Throws [BackupException] if nothing was touched (no restart needed) or [RestoreRolledBack] if it failed
     * after Room was closed and the old data was put back (restart needed).
     */
    suspend fun restore(p: Prepared): Unit = withContext(Dispatchers.IO) {
        val settings = AppSettings(app)
        val before = settings.backupSnapshot()
        val wasEnabled = before["observer_enabled"] as? Boolean ?: true

        // 3. safety backup (only the last one stays)
        val safety = File(app.filesDir, "$SAFETY_NAME.nuevo")
        try {
            safety.outputStream().use { BackupWriter(app).write(it, markAsLast = false) }
            safety.copyTo(safetyFile, overwrite = true)
        } catch (e: Exception) {
            safety.delete()
            throw BackupException("No se pudo guardar la copia de seguridad automática, así que no se restauró nada. Tus datos están como antes.")
        }
        safety.delete()

        // 4-6. pause, let queued writes land, close Room
        var replaced = false
        val dbFile = app.getDatabasePath(DB_NAME)
        val old = File(dbFile.path + ".anterior")
        try {
            settings.updateObserverEnabled(false)
            drainWrites()
            AppDatabase.getInstance(app).close()
            // 7. replace the database (the old one is kept aside until everything worked)
            old.delete()
            if (dbFile.exists() && !dbFile.renameTo(old)) throw java.io.IOException("No se pudo apartar la base de datos actual")
            replaced = true
            for (suffix in listOf("-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
            p.db.copyTo(dbFile, overwrite = true)
            // The copy must open as it should before the settings are touched
            checkDatabase(dbFile, p.manifest)
            // 8. replace the settings in one step, then put back the moment's state (paused or not)
            settings.replaceWithRestored(p.settings.values)
            settings.updateObserverEnabled(wasEnabled)
            settings.setPendingNotice(RestoreRules.restoredText(p.manifest.rows, p.compat == RestoreCompat.OLDER, p.settings.defaulted))
            old.delete()
            discard()
        } catch (e: Exception) {
            rollback(settings, before, dbFile, old, replaced)
            throw RestoreRolledBack()
        }
    }

    private suspend fun rollback(settings: AppSettings, before: Map<String, Any?>, dbFile: File, old: File, replaced: Boolean) {
        runCatching {
            if (replaced && old.exists()) {
                dbFile.delete()
                for (suffix in listOf("-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
                old.renameTo(dbFile)
            }
        }
        runCatching { settings.replaceAll(before) }
        runCatching { settings.setPendingNotice(RestoreRules.ROLLED_BACK) }
        discard()
    }

    private suspend fun drainWrites() {
        val done = CompletableDeferred<Unit>()
        PersistenceQueue.shared().enqueue { done.complete(Unit) }
        withTimeoutOrNull(5_000) { done.await() }
    }

    companion object {
        const val DB_NAME = "observador.db"
        const val SAFETY_NAME = "respaldo-seguridad.zip"
    }
}
