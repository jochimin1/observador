package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ubers.observador.MainActivity
import com.ubers.observador.data.backup.BackupVerifier
import com.ubers.observador.data.backup.BackupWriter
import com.ubers.observador.domain.backup.AutoBackupRules
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 1.56.0: a full backup (everything, as the manual one) once a week into a folder the driver chose,
 * with enough battery and storage, read back and verified after writing. The folder's permission
 * belongs to this phone, so it lives here and not in the settings that a restore carries.
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val WORK = "auto_backup_weekly"
    private const val WORK_NOW = "auto_backup_now"
    private const val CHANNEL = "auto_backup"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(c: Context): Uri? = prefs(c).getString("tree", null)?.let(Uri::parse)
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false) && folder(c) != null
    fun status(c: Context): String = prefs(c).let {
        AutoBackupRules.status(it.getLong("ok_at", 0L).takeIf { v -> v > 0 }, it.getString("error", null),
            it.getLong("error_at", 0L).takeIf { v -> v > 0 }, System.currentTimeMillis())
    }

    /** The driver picked a folder (ACTION_OPEN_DOCUMENT_TREE): keep its permission and start the weekly work. */
    fun setFolder(c: Context, tree: Uri) {
        val app = c.applicationContext
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(app).edit().putString("tree", tree.toString()).putBoolean("enabled", true).apply()
        schedule(app)
    }

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on && folder(c) != null) schedule(c) else WorkManager.getInstance(c.applicationContext).cancelUniqueWork(WORK)
    }

    private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

    fun schedule(c: Context) {
        WorkManager.getInstance(c.applicationContext).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(AutoBackupRules.PERIOD_DAYS, TimeUnit.DAYS).setConstraints(constraints()).build())
    }

    fun runNow(c: Context) {
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    /** Observador's backups in the chosen folder, newest first. */
    fun list(c: Context): List<AutoBackupRules.Entry> {
        val tree = folder(c) ?: return emptyList()
        val app = c.applicationContext
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val out = ArrayList<AutoBackupRules.Entry>()
            app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cur ->
                while (cur.moveToNext()) out += AutoBackupRules.Entry(cur.getString(0) ?: "", if (cur.isNull(1)) 0 else cur.getLong(1), if (cur.isNull(2)) 0 else cur.getLong(2))
            }
            AutoBackupRules.list(out)
        } catch (e: Exception) { Log.w("AutoBackup", "list", e); emptyList() }
    }

    /** The document of a listed backup, to restore it. */
    fun documentUri(c: Context, name: String): Uri? {
        val tree = folder(c) ?: return null
        val app = c.applicationContext
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
            var found: Uri? = null
            while (cur.moveToNext()) if (cur.getString(1) == name) { found = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)); break }
            found
        }
    }

    internal fun recordOk(c: Context, at: Long) = prefs(c).edit().putLong("ok_at", at).apply()
    internal fun recordError(c: Context, message: String) {
        prefs(c).edit().putString("error", message).putLong("error_at", System.currentTimeMillis()).apply()
        try {
            val app = c.applicationContext
            val manager = app.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Respaldo automático", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(7_400, android.app.Notification.Builder(app, CHANNEL).setContentTitle("No se hizo el respaldo automático")
                .setContentText(message).setStyle(android.app.Notification.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) { }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val tree = AutoBackup.folder(app) ?: return Result.success()
        if (com.ubers.observador.data.backup.DataRecovery.failure != null) return Result.retry()
        val now = System.currentTimeMillis()
        var doc: Uri? = null
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            doc = DocumentsContract.createDocument(app.contentResolver, parent, "application/zip", AutoBackupRules.fileName(now, ZoneId.systemDefault()))
                ?: throw IllegalStateException("No se pudo crear el archivo en la carpeta elegida")
            val out = app.contentResolver.openOutputStream(doc, "w") ?: throw IllegalStateException("La carpeta no permite escribir")
            BackupWriter(app).write(out)
            // Read back what the folder holds now and check it like any restore would
            val tmp = File.createTempFile("auto-verify-", ".zip", app.cacheDir)
            try {
                app.contentResolver.openInputStream(doc)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("No se pudo leer el respaldo escrito")
                BackupVerifier.verify(tmp)
            } finally { tmp.delete() }
            AutoBackup.recordOk(app, now)
            Result.success()
        } catch (e: SecurityException) {
            AutoBackup.recordError(app, "Observador perdió el permiso de la carpeta. Elígela de nuevo en Ajustes › Respaldo.")
            Result.failure()
        } catch (e: Exception) {
            Log.w("AutoBackup", "Automatic backup failed", e)
            // A half-written file is not a backup: removed so the list never offers it
            doc?.let { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) } }
            AutoBackup.recordError(app, e.message ?: "Error desconocido")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
