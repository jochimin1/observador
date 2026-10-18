package com.ubers.observador.data.backup

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Makes the backup file (1.31.0). The database is copied with `VACUUM INTO`, which gives one
 * consistent picture even while the services are writing; the copy is checked (`integrity_check`),
 * counted, zipped with the settings and the control sheet, and the zip is read back and verified
 * BEFORE it is handed to the driver's chosen place. Nothing in the live database is changed.
 */
class BackupWriter(context: Context) {

    private val app = context.applicationContext

    data class Result(val manifest: BackupManifest, val bytes: Long)

    /** Writes the backup to [out] and closes it. Throws [BackupException] with a message for the driver. */
    suspend fun write(out: OutputStream): Result = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "respaldo-tmp").apply { deleteRecursively(); mkdirs() }
        try {
            flushPendingWrites()
            val dbCopy = File(tmp, BackupFormat.ENTRY_DB)
            try {
                AppDatabase.getInstance(app).openHelper.writableDatabase
                    .execSQL("VACUUM INTO '" + dbCopy.absolutePath.replace("'", "''") + "'")
            } catch (e: Exception) {
                throw BackupException("No se pudo copiar la base de datos. Tus datos no se tocaron.")
            }
            val (dbVersion, rows) = inspect(dbCopy)

            val settings = BackupFormat.selectSettings(AppSettings(app).backupSnapshot())
            val settingsBytes = BackupFormat.encodeSettings(settings).toByteArray(Charsets.UTF_8)
            val now = System.currentTimeMillis()
            val manifest = BackupManifest(
                format = BackupFormat.FORMAT_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                dbVersion = dbVersion,
                createdAtMs = now,
                rows = rows,
                sha256 = mapOf(
                    BackupFormat.ENTRY_DB to dbCopy.inputStream().use { BackupFormat.sha256Hex(it) },
                    BackupFormat.ENTRY_SETTINGS to BackupFormat.sha256Hex(settingsBytes)
                )
            )

            val zipFile = File(tmp, "respaldo.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
                zip.write(BackupFormat.encodeManifest(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_SETTINGS))
                zip.write(settingsBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_DB))
                dbCopy.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // Read back what was just written: only a file that passes is handed over
            BackupVerifier.verify(zipFile)

            val size = zipFile.length()
            zipFile.inputStream().use { it.copyTo(out) }
            out.flush()
            AppSettings(app).updateLastBackupAt(now)
            Result(manifest, size)
        } finally {
            tmp.deleteRecursively()
            runCatching { out.close() }
        }
    }

    /** Lets the trips and money already queued reach the database before the picture is taken. */
    private suspend fun flushPendingWrites() {
        val done = CompletableDeferred<Unit>()
        PersistenceQueue.shared().enqueue { done.complete(Unit) }
        withTimeoutOrNull(5_000) { done.await() }
    }

    /** Checks the copy and returns its Room version and the rows of every table. */
    private fun inspect(copy: File): Pair<Int, Map<String, Int>> {
        val db = try {
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupException("La copia de la base de datos no se pudo abrir. Tus datos no se tocaron.")
        }
        db.use {
            val ok = it.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            if (!ok) throw BackupException("La base de datos no pasó la revisión de integridad. No se creó el respaldo.")
            val tables = ArrayList<String>()
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata','room_master_table') ORDER BY name",
                null
            ).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            val rows = LinkedHashMap<String, Int>()
            for (t in tables) rows[t] = DatabaseUtils.queryNumEntries(it, t).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return it.version to rows
        }
    }
}
