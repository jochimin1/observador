package com.ubers.observador.data.backup

import android.content.Context
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.domain.backup.BackupLimits
import com.ubers.observador.domain.backup.MaintenanceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** A database and preferences snapshot taken at one admitted-work boundary, verified before export. */
class BackupWriter(context: Context) {
    private val app = context.applicationContext
    data class Result(val manifest: BackupManifest, val bytes: Long)

    suspend fun write(out: OutputStream, markAsLast: Boolean = true): Result = withContext(Dispatchers.IO) {
        try {
            MaintenanceGate.shared.exclusive {
                val live = app.getDatabasePath(BackupRestorer.DB_NAME)
                val estimated = live.length() + File(live.path + "-wal").length()
                DatabaseFiles.requireSpace(app.cacheDir, estimated * 2)
                val tmp = File(app.cacheDir, "respaldo-tmp-${UUID.randomUUID()}")
                check(tmp.mkdirs())
                try {
                    val copy = File(tmp, BackupFormat.ENTRY_DB)
                    DatabaseFiles.snapshot(app, copy)
                    val settings = BackupFormat.selectSettings(AppSettings(app).backupSnapshot())
                    val archive = File(tmp, "respaldo.zip")
                    val manifest = BackupArchive.create(copy, settings, archive)
                    archive.inputStream().use { BackupLimits.copy(it, out, BackupLimits.ZIP_BYTES) }
                    out.flush()
                    if (markAsLast) AppSettings(app).updateLastBackupAt(manifest.createdAtMs)
                    Result(manifest, archive.length())
                } finally { tmp.deleteRecursively() }
            }
        } catch (e: TimeoutCancellationException) {
            throw BackupException("No se terminó de guardar lo pendiente. No se creó el respaldo; vuelve a intentarlo.")
        } finally { runCatching { out.close() } }
    }
}
