package com.ubers.observador

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.*
import com.ubers.observador.data.backup.FullDataExport
import com.ubers.observador.domain.backup.MaintenanceGate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class FullDataTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun additiveMigrationRetainsExistingVehicleAndCreatesEvidenceLog() {
        val name = "complete-data-${UUID.randomUUID()}"
        helper.createDatabase(name, 18).apply {
            execSQL("INSERT INTO vehicle_profiles VALUES ('motorcycle','Moto conservada',75,1,NULL,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 19, true, AppDatabase.MIGRATION_18_19).use { db ->
            db.query("SELECT name,workSharePct FROM vehicle_profiles WHERE id='motorcycle'").use {
                assertTrue(it.moveToFirst()); assertEquals("Moto conservada", it.getString(0)); assertEquals(75, it.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM observation_log").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    @Test fun fullExportIncludesUnredactedEvidenceAndEveryDatabaseTable() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val id = "TEST-export-${UUID.randomUUID()}"
        MaintenanceGate.shared.access {
            db.observationLogDao().insert(ObservationLog(id, "PLACE_LOOKUP", 1, "{\"name\":\"Ana\",\"address\":\"Calle 1\",\"latitude\":18.1234567}"))
        }
        try {
            val output = ByteArrayOutputStream()
            FullDataExport.write(context, output)
            val json = JSONObject(output.toString("UTF-8"))
            assertEquals(19, json.getInt("databaseVersion"))
            val tables = json.getJSONObject("tables")
            assertTrue(tables.has("trip_route_points"))
            assertTrue(tables.has("financial_observations"))
            val rows = tables.getJSONArray("observation_log")
            val record = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id }
            val payload = JSONObject(record.getString("payload"))
            assertEquals("Ana", payload.getString("name"))
            assertEquals("Calle 1", payload.getString("address"))
            assertEquals(18.1234567, payload.getDouble("latitude"), 0.0)
        } finally {
            MaintenanceGate.shared.access { db.openHelper.writableDatabase.execSQL("DELETE FROM observation_log WHERE id=?", arrayOf(id)) }
        }
    }

    @Test fun encryptedBackupCanBeVerifiedAndPreparedForRestore() = runBlocking {
        val out = ByteArrayOutputStream()
        val original = com.ubers.observador.data.backup.BackupWriter(context).write(out, false)
        val encrypted = ByteArrayOutputStream()
        val password = "Prueba-no-personal-123".toCharArray()
        com.ubers.observador.domain.backup.EncryptedBackup.encrypt(out.toByteArray().inputStream(), encrypted, password)
        val clear = ByteArrayOutputStream()
        com.ubers.observador.domain.backup.EncryptedBackup.decrypt(encrypted.toByteArray().inputStream(), clear, password)
        val restorer = com.ubers.observador.data.backup.BackupRestorer(context)
        try {
            val prepared = restorer.prepare(clear.toByteArray().inputStream(), 19)
            assertEquals(original.manifest.rows, prepared.manifest.rows)
            assertEquals(original.manifest.sha256, prepared.manifest.sha256)
        } finally { restorer.discard(); password.fill('\u0000') }
    }
}
