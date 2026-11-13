package com.ubers.observador

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.backup.*
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Run each method in a fresh instrumentation process: successful replacement seals old references. */
@RunWith(AndroidJUnit4::class)
class DataMaintenanceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings(context)
    private val live get() = context.getDatabasePath(BackupRestorer.DB_NAME)

    private suspend fun seed(id: String = "original", cost: Double = 9.25) {
        ExpenseRepository(AppDatabase.getInstance(context)).saveExpense(
            ExpenseEntry(id, 1000, ExpenseType.OIL, 50000, odometerKm = 1200, createdAt = 1000, updatedAt = 1000)
        )
        settings.updateCostPerKm(cost)
        settings.updateObserverEnabled(false)
    }
    private suspend fun backup(): ByteArray {
        val out = ByteArrayOutputStream()
        BackupWriter(context).write(out, markAsLast = false)
        return out.toByteArray()
    }
    private fun ids(): List<String> = SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT id FROM expense_entries ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }
    private fun originals() {
        File(context.filesDir, "capturas").mkdirs()
        File(context.filesDir, "capturas/proof.txt").writeText("private capture")
        File(context.filesDir, "cierres").mkdirs()
        File(context.filesDir, "cierres/proof.txt").writeText("private close budget")
        File(context.cacheDir, "exports").mkdirs()
        File(context.cacheDir, "exports/proof.txt").writeText("private report")
        context.getSharedPreferences("debug_tools", Context.MODE_PRIVATE).edit().putBoolean("capture_recording", true).commit()
    }

    @Test fun restoresTheSelectedHistoryAndSettings() = runBlocking<Unit> {
        seed()
        val bytes = backup()
        seed("later", 22.5)
        val restorer = BackupRestorer(context)
        val prepared = restorer.prepare(bytes.inputStream(), 14)
        restorer.restore(prepared)
        assertEquals(listOf("original"), ids())
        assertEquals(9.25, settings.engineSettings.first().costPerKm, 0.0)
        assertFalse(settings.observerEnabled.first())
        assertFalse(settings.backupSnapshot()["distance_manual"] as Boolean)
        assertTrue(MaintenanceGate.shared.isClosed)
        assertEquals(2, BackupVerifier.verify(File(context.filesDir, BackupRestorer.SAFETY_NAME)).rows["expense_entries"])
    }

    private suspend fun failedRestore(point: String) {
        seed()
        val bytes = backup()
        seed("later", 22.5)
        val before = settings.backupSnapshot()
        val restorer = BackupRestorer(context) { if (it == point) throw IOException("injected $point") }
        val prepared = restorer.prepare(bytes.inputStream(), 14)
        try { restorer.restore(prepared); fail("must fail") } catch (_: RestoreRolledBack) { }
        assertEquals(listOf("later", "original"), ids())
        assertEquals(before.filterKeys { it != "pending_notice" }, settings.backupSnapshot().filterKeys { it != "pending_notice" })
        assertFalse(RecoveryJournal(context).exists)
        assertTrue(MaintenanceGate.shared.isClosed)
    }
    @Test fun restoresPreviousDataWhenDatabaseInstallationFails() = runBlocking<Unit> { failedRestore("database-installed") }
    @Test fun restoresPreviousDataWhenSettingsReplacementFails() = runBlocking<Unit> { failedRestore("settings-replaced") }
    @Test fun restoresPreviousDataWhenCommitFails() = runBlocking<Unit> { failedRestore("before-commit") }

    @Test fun changedPreparedCopyIsRefusedWithoutClosingLiveRoom() = runBlocking<Unit> {
        seed()
        val restorer = BackupRestorer(context)
        val prepared = restorer.prepare(backup().inputStream(), 14)
        prepared.db.appendText("changed")
        try { restorer.restore(prepared); fail("must refuse changed copy") } catch (_: BackupException) { }
        assertTrue(AppDatabase.getInstance(context).isOpen)
        assertFalse(MaintenanceGate.shared.isClosed)
        assertEquals(listOf("original"), ids())
        restorer.discard()
    }

    @Test fun preflightMigratesAnOlderBackupBeforeReplacingLiveData() = runBlocking<Unit> {
        seed()
        helper.createDatabase("legacy-13.db", 13).apply {
            execSQL("INSERT INTO expense_entries (id,at,type,amount_minor,currency_code,odometer_km,created_at,updated_at) VALUES ('legacy',1000,'OIL',70000,'DOP',900,1000,1000)")
            close()
        }
        val archive = File(context.cacheDir, "legacy.zip")
        BackupArchive.create(context.getDatabasePath("legacy-13.db"), mapOf("cost_per_km" to 4.25), archive)
        val restorer = BackupRestorer(context)
        val p = restorer.prepare(archive.inputStream(), 14)
        assertEquals(13, p.manifest.dbVersion)
        assertEquals(14, DatabaseFiles.inspect(p.db).version)
        assertEquals(listOf("original"), ids())
        assertTrue(AppDatabase.getInstance(context).isOpen)
        restorer.restore(p)
        assertEquals(listOf("legacy"), ids())
        assertEquals(4.25, settings.engineSettings.first().costPerKm, 0.0)
    }

    @Test fun aStructurallyInvalidDatabaseIsRefusedBeforeLiveRoomCloses() = runBlocking<Unit> {
        seed()
        val invalid = File(context.cacheDir, "invalid.db")
        SQLiteDatabase.openOrCreateDatabase(invalid, null).use { db ->
            db.execSQL("CREATE TABLE unexpected (id INTEGER PRIMARY KEY)")
            db.version = 14
        }
        val archive = File(context.cacheDir, "invalid.zip")
        BackupArchive.create(invalid, emptyMap(), archive)
        try { BackupRestorer(context).prepare(archive.inputStream(), 14); fail("must refuse schema") }
        catch (_: BackupException) { }
        assertTrue(AppDatabase.getInstance(context).isOpen)
        assertEquals(listOf("original"), ids())
        assertFalse(MaintenanceGate.shared.isClosed)
    }

    @Test fun insufficientSpaceIsRefusedBeforeReplacement() = runBlocking<Unit> {
        seed()
        try { DatabaseFiles.requireSpace(context.noBackupFilesDir, Long.MAX_VALUE / 2); fail("space") }
        catch (_: BackupException) { }
        assertEquals(listOf("original"), ids())
        assertTrue(AppDatabase.getInstance(context).isOpen)
    }

    @Test fun failureToPublishSafetyCopyDoesNotStartReplacement() = runBlocking<Unit> {
        seed()
        val restorer = BackupRestorer(context)
        val p = restorer.prepare(backup().inputStream(), 14)
        assertTrue(File(context.filesDir, BackupRestorer.SAFETY_NAME).mkdir())
        try { restorer.restore(p); fail("safety archive") } catch (_: BackupException) { }
        assertEquals(listOf("original"), ids())
        assertTrue(AppDatabase.getInstance(context).isOpen)
        assertFalse(MaintenanceGate.shared.isClosed)
    }

    @Test fun clearsHistorySettingsCapturesBudgetsAndCachedExports() = runBlocking<Unit> {
        seed(); originals()
        File(context.filesDir, BackupRestorer.SAFETY_NAME).writeBytes(backup())
        DataEraser(context).erase("Datos borrados")
        assertTrue(DatabaseFiles.inspect(live).rows.values.all { it == 0 })
        assertEquals(mapOf("onboarding_done" to false, "pending_notice" to "Datos borrados"), settings.backupSnapshot())
        assertFalse(File(context.filesDir, "capturas").exists())
        assertFalse(File(context.filesDir, "cierres").exists())
        assertFalse(File(context.cacheDir, "exports/proof.txt").exists())
        assertFalse(File(context.filesDir, BackupRestorer.SAFETY_NAME).exists())
        assertTrue(context.getSharedPreferences("debug_tools", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(MaintenanceGate.shared.isClosed)
    }

    private suspend fun failedErase(point: String) {
        seed(); originals()
        val before = settings.backupSnapshot()
        try { DataEraser(context) { if (it == point) throw IOException("injected $point") }.erase("Done"); fail("must fail") }
        catch (_: EraseRolledBack) { }
        assertEquals(listOf("original"), ids())
        assertEquals(before, settings.backupSnapshot().filterKeys { it != "pending_notice" })
        assertEquals("private capture", File(context.filesDir, "capturas/proof.txt").readText())
        assertEquals("private close budget", File(context.filesDir, "cierres/proof.txt").readText())
        assertEquals("private report", File(context.cacheDir, "exports/proof.txt").readText())
        assertTrue(context.getSharedPreferences("debug_tools", Context.MODE_PRIVATE).getBoolean("capture_recording", false))
    }
    @Test fun eraseFailureRestoresStagedFiles() = runBlocking<Unit> { failedErase("files-staged") }
    @Test fun eraseFailureRestoresPreferences() = runBlocking<Unit> { failedErase("settings-replaced") }
    @Test fun eraseFailureBeforeCommitRestoresEverything() = runBlocking<Unit> { failedErase("before-commit") }

    @Test fun rollbackFailureKeepsTheJournalAndDoesNotClaimRecovery() = runBlocking<Unit> {
        seed()
        val restorer = BackupRestorer(context) {
            if (it == "database-installed") {
                File(context.noBackupFilesDir, "data-maintenance/previous.db").writeText("injected damaged recovery file")
                File(context.filesDir, BackupRestorer.SAFETY_NAME).writeText("injected damaged second copy")
                throw IOException("injected")
            }
        }
        val prepared = restorer.prepare(backup().inputStream(), 14)
        try { restorer.restore(prepared); fail("must require recovery") } catch (_: RecoveryRequired) { }
        assertTrue(RecoveryJournal(context).exists)
        assertTrue(MaintenanceGate.shared.isClosed)
        assertNull(settings.pendingNotice.first())
        assertTrue(File(context.filesDir, BackupRestorer.SAFETY_NAME).exists())
    }

    @Test fun recoveryUsesTheIndependentSafetyArchiveWhenUndoCopyIsDamaged() = runBlocking<Unit> {
        seed()
        val restorer = BackupRestorer(context) {
            if (it == "database-installed") {
                File(context.noBackupFilesDir, "data-maintenance/previous.db").writeText("injected damaged undo copy")
                throw IOException("injected")
            }
        }
        val prepared = restorer.prepare(backup().inputStream(), 14)
        try { restorer.restore(prepared); fail("must roll back") } catch (_: RestoreRolledBack) { }
        assertEquals(listOf("original"), ids())
        assertEquals(9.25, settings.engineSettings.first().costPerKm, 0.0)
        assertFalse(RecoveryJournal(context).exists)
    }

    @Test fun verifiesUnrecoverableJournalBlocksDataAccess() {
        assertNotNull(DataRecovery.failure)
        assertTrue(MaintenanceGate.shared.isClosed)
        assertTrue(RecoveryJournal(context).exists)
        try { AppDatabase.getInstance(context); fail("uncertain data must stay closed") }
        catch (_: IllegalStateException) { }
    }

    /** Stage 1: the harness stops this process after the method, without a commit or rollback. */
    @Test fun stageInterruptedReplacement() = runBlocking<Unit> {
        seed(); originals()
        MaintenanceGate.shared.exclusive {
            val journal = RecoveryJournal(context)
            journal.prepare("ERASE")
            BackupRestorer.closeDatabase(context)
            val empty = File(context.cacheDir, "interrupted-empty.db")
            DatabaseFiles.migrateAndValidate(context, empty)
            journal.install(empty)
            journal.stageEraseFiles()
            settings.replaceAll(mapOf("cost_per_km" to 45.0))
            assertTrue(journal.exists)
        }
    }

    /** Stage 2: ObservadorApplication must recover before this fresh process starts the test. */
    @Test fun verifiesRecoveryAfterProcessRestart() = runBlocking<Unit> {
        assertEquals(listOf("original"), ids())
        assertEquals(9.25, settings.engineSettings.first().costPerKm, 0.0)
        assertEquals("private capture", File(context.filesDir, "capturas/proof.txt").readText())
        assertEquals("private close budget", File(context.filesDir, "cierres/proof.txt").readText())
        assertEquals("private report", File(context.cacheDir, "exports/proof.txt").readText())
        assertTrue(settings.pendingNotice.first()!!.contains("interrumpió"))
        assertNull(DataRecovery.failure)
        assertFalse(RecoveryJournal(context).exists)
    }
}
