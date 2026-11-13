package com.ubers.observador.data.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupLimits
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Durable undo journal. A missing commit marker always restores the complete previous state. */
internal class RecoveryJournal(private val app: Context) {
    val dir = File(app.noBackupFilesDir, "data-maintenance")
    private val state = File(dir, "state")
    private val oldDb = File(dir, "previous.db")
    private val oldSettings = File(dir, "settings.json")
    private val oldDebug = File(dir, "debug.json")
    private val hash = File(dir, "database.sha256")
    private val live get() = app.getDatabasePath(BackupRestorer.DB_NAME)
    private val extras get() = listOf(
        File(app.filesDir, "capturas"), File(app.filesDir, "cierres"),
        File(app.filesDir, BackupRestorer.SAFETY_NAME), File(app.filesDir, BackupRestorer.SAFETY_NAME + ".nuevo"),
        File(app.applicationInfo.dataDir, "cache")
    )

    val exists: Boolean get() = state.exists()

    fun exportPrevious(output: OutputStream) {
        val safety = listOf(File(app.filesDir, BackupRestorer.SAFETY_NAME), File(dir, "extra-2"))
            .firstOrNull { it.isFile && runCatching { BackupVerifier.verify(it) }.isSuccess }
        if (safety != null) {
            output.use { out -> safety.inputStream().use { it.copyTo(out) }; out.flush() }
            return
        }
        check(oldDb.isFile && sha(oldDb) == hash.readText()) { "The recovery database could not be verified" }
        val archive = File(app.cacheDir, "recovery-export.zip")
        try {
            BackupArchive.create(oldDb, BackupFormat.selectSettings(decode(oldSettings)), archive)
            output.use { out -> archive.inputStream().use { it.copyTo(out) }; out.flush() }
        } finally { archive.delete() }
    }

    suspend fun prepare(kind: String) {
        check(!exists) { "An unfinished data operation must be recovered first" }
        if (dir.exists() && !dir.deleteRecursively()) throw BackupException("No se pudo preparar el espacio de recuperación.")
        check(dir.mkdirs())
        try {
            DatabaseFiles.snapshot(app, oldDb)
            sync(oldDb)
            write(oldSettings, BackupFormat.encodeSettings(AppSettings(app).backupSnapshot().filterValues { it != null }.mapValues { it.value!! }))
            write(oldDebug, BackupFormat.encodeSettings(app.getSharedPreferences("debug_tools", Context.MODE_PRIVATE).all.filterValues { it != null }.mapValues { it.value!! }))
            write(File(dir, "settings.sha256"), sha(oldSettings))
            write(File(dir, "debug.sha256"), sha(oldDebug))
            write(hash, sha(oldDb))
            write(state, "PREPARED\n$kind")
        } catch (e: Exception) {
            if (!exists) dir.deleteRecursively()
            throw e
        }
    }

    fun install(copy: File) {
        removeSidecars()
        val stage = File(live.path + ".incoming")
        copy.inputStream().use { source -> FileOutputStream(stage).use { out ->
            BackupLimits.copy(source, out, BackupLimits.DATABASE_BYTES)
            out.fd.sync()
        } }
        check(sha(stage) == sha(copy)) { "The installed database copy changed" }
        move(stage, live)
    }

    /** Only fixed application-owned paths can be staged; recovery does not trust paths from a ZIP. */
    fun stageEraseFiles() {
        extras.forEachIndexed { index, source ->
            if (source.exists()) move(source, File(dir, "extra-$index"))
        }
        check(app.getSharedPreferences("debug_tools", Context.MODE_PRIVATE).edit().clear().commit())
    }

    fun commit(kind: String) { write(state, "COMMITTED\n$kind") }

    /** May leave a committed journal for the next startup to clean; never rolls back a commit. */
    fun cleanup(): Boolean {
        dir.listFiles()?.filter { it != state }?.forEach { if (!it.deleteRecursively()) return false }
        if (!state.delete()) return false
        syncDir(dir)
        return dir.delete()
    }

    suspend fun rollback(notice: String) {
        verifyOrRepairPreviousDatabase()
        DatabaseFiles.inspect(oldDb)
        val settings = decode(oldSettings)
        val debug = decode(oldDebug)
        install(oldDb)
        check(sha(live) == hash.readText()) { "The previous database was not recovered completely" }
        DatabaseFiles.inspect(live)
        AppSettings(app).replaceAll(settings)
        check(AppSettings(app).backupSnapshot() == settings) { "The previous settings were not recovered completely" }
        val prefs = app.getSharedPreferences("debug_tools", Context.MODE_PRIVATE)
        val edit = prefs.edit().clear()
        debug.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                else -> error("Unsupported recovery preference")
            }
        }
        check(edit.commit() && prefs.all == debug) { "The previous debug settings could not be recovered" }
        extras.forEachIndexed { index, target ->
            val staged = File(dir, "extra-$index")
            if (staged.exists()) {
                // Android may recreate its empty/SDK cache before Application.onCreate on restart.
                // It is disposable; the staged cache contains the driver's previous local exports.
                if (index == 4 && target.exists()) check(target.deleteRecursively())
                if (target.isDirectory && target.list()?.isEmpty() == true) check(target.delete())
                check(!target.exists()) { "A recovery path is already in use" }
                move(staged, target)
            }
        }
        AppSettings(app).setPendingNotice(notice)
        // A rollback is a committed state too. A cleanup interruption must not undo later writes.
        commit("ROLLBACK")
        cleanup()
    }

    suspend fun recover() {
        if (!exists) {
            if (dir.exists()) check(dir.deleteRecursively())
            return
        }
        val phase = state.readText().lineSequence().first()
        when (phase) {
            "COMMITTED" -> check(cleanup()) { "No se pudieron eliminar los archivos de la operación anterior." }
            "PREPARED" -> rollback("La operación se interrumpió. Se recuperaron y comprobaron tus datos anteriores.")
            else -> error("Unknown recovery journal state")
        }
    }

    private fun decode(file: File): Map<String, Any> {
        check(file.length() <= BackupLimits.TEXT_BYTES)
        check(sha(file) == File(dir, file.nameWithoutExtension + ".sha256").readText()) { "Recovery preferences could not be verified" }
        val decoded = checkNotNull(BackupFormat.decodeSettings(file.readText()))
        check(decoded.rejected.isEmpty())
        return decoded.values
    }

    private fun verifyOrRepairPreviousDatabase() {
        val expected = hash.readText()
        if (oldDb.isFile && runCatching { sha(oldDb) == expected }.getOrDefault(false)) return
        // The independent safety ZIP is a second verified source, never a guessed older database.
        val safety = listOf(File(app.filesDir, BackupRestorer.SAFETY_NAME), File(dir, "extra-2"))
            .firstOrNull { file -> file.isFile && runCatching {
                BackupVerifier.verify(file).sha256[BackupFormat.ENTRY_DB] == expected
            }.getOrDefault(false) }
        checkNotNull(safety) { "The recovery database could not be verified" }
        val repaired = File(dir, "repaired.db")
        java.util.zip.ZipFile(safety).use { zip ->
            zip.getInputStream(zip.getEntry(BackupFormat.ENTRY_DB)).use { source ->
                FileOutputStream(repaired).use { out -> BackupLimits.copy(source, out, BackupLimits.DATABASE_BYTES); out.fd.sync() }
            }
        }
        check(sha(repaired) == expected)
        DatabaseFiles.inspect(repaired)
        move(repaired, oldDb)
    }

    private fun removeSidecars() {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(live.path + suffix)
            check(!file.exists() || file.delete()) { "SQLite auxiliary file could not be removed" }
        }
    }

    companion object {
        private fun sha(file: File) = file.inputStream().use { BackupLimits.sha256(it, BackupLimits.DATABASE_BYTES) }
        private fun sync(file: File) = FileOutputStream(file, true).use { it.fd.sync() }
        private fun syncDir(dir: File) {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        internal fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDir(checkNotNull(target.parentFile))
            if (source.parentFile != target.parentFile) syncDir(checkNotNull(source.parentFile))
        }
        private fun write(file: File, text: String) {
            val stage = File(file.path + ".new")
            FileOutputStream(stage).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            move(stage, file)
        }
    }
}
