package com.ubers.observador

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.backup.BackupException
import com.ubers.observador.data.backup.BackupRestorer
import com.ubers.observador.data.backup.BackupWriter
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.RestoreCompat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 1.32.0, on the phone: a backup of the real database is made, checked and prepared for restoring, and the
 * copy that would replace the data has the same rows, table by table, as the live one. A damaged file and a
 * file from a newer version are refused. The step that closes Room and restarts the app is NOT run here
 * (it would break the other tests); it is checked by hand on the phone.
 * Run with: .\gradlew.bat connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val restorer = BackupRestorer(context)

    @After
    fun tearDown() = restorer.discard()

    private fun backupBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { BackupWriter(context).write(out, markAsLast = false) }
        return out.toByteArray()
    }

    private fun tables(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata','room_master_table') ORDER BY name", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun appDbVersion(): Int = AppDatabase.getInstance(context).openHelper.readableDatabase.version

    @Test fun preparedCopyHasTheSameRowsAsTheLiveDatabase() = runBlocking {
        val bytes = backupBytes()
        val p = restorer.prepare(bytes.inputStream(), appDbVersion())
        assertEquals(RestoreCompat.SAME, p.compat)
        val live = AppDatabase.getInstance(context).openHelper.readableDatabase
        SQLiteDatabase.openDatabase(p.db.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            val names = tables(copy)
            assertTrue("the backup has tables", names.isNotEmpty())
            for (t in names) {
                val a = live.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); c.getInt(0) }
                val b = android.database.DatabaseUtils.queryNumEntries(copy, t).toInt()
                assertEquals("rows of $t", a, b)
                assertEquals("sheet rows of $t", b, p.manifest.rows[t])
            }
        }
    }

    @Test fun aDamagedFileIsRefusedAndNothingIsLeftBehind() = runBlocking {
        val bytes = backupBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
        try {
            restorer.prepare(bytes.inputStream(), appDbVersion())
            fail("a damaged backup must be refused")
        } catch (e: BackupException) {
            assertTrue(File(context.cacheDir, "restaurar-tmp").let { !it.exists() || it.list().isNullOrEmpty() })
        }
    }

    @Test fun aBackupFromANewerDatabaseIsRefused() = runBlocking {
        val bytes = backupBytes()
        try {
            restorer.prepare(bytes.inputStream(), appDbVersion() - 1)
            fail("a newer backup must be refused")
        } catch (e: BackupException) {
            assertEquals("Actualiza Observador antes de restaurar este respaldo.", e.message)
        }
    }

    @Test fun somethingThatIsNotAZipIsRefused() = runBlocking {
        try {
            restorer.prepare("esto no es un respaldo".byteInputStream(), appDbVersion())
            fail("not a zip")
        } catch (e: BackupException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
