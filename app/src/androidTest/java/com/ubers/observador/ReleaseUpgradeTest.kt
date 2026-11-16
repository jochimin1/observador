package com.ubers.observador

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.ObservationLog
import com.ubers.observador.domain.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two independent instrumentation invocations separated by install -r. Emulator-only script. */
class ReleaseUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun seed() = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.openHelper.writableDatabase.execSQL("DELETE FROM observation_log WHERE id = ?", arrayOf(ID))
        db.observationLogDao().insert(ObservationLog(ID, KIND, 1234, PAYLOAD))
        AppSettings(context).updateTheme(AppTheme.NIGHT)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
    }

    @Test fun verify() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val row = db.observationLogDao().latest(KIND).first()
        assertEquals(ID, row?.id)
        assertEquals(PAYLOAD, row?.payload)
        assertEquals(1234L, row?.atMs)
        assertEquals(AppTheme.NIGHT, AppSettings(context).themeSettings.first().theme)
        assertEquals(24, db.openHelper.readableDatabase.version)
        db.openHelper.readableDatabase.query("PRAGMA quick_check").use {
            assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
        }
        db.openHelper.writableDatabase.execSQL("DELETE FROM observation_log WHERE id = ?", arrayOf(ID))
    }

    companion object {
        private const val ID = "TEST-release-update-1.52"
        private const val KIND = "TEST_RELEASE_UPDATE"
        private const val PAYLOAD = "{\"name\":\"Ana QA\",\"address\":\"Calle de prueba 1\",\"route\":[[18.1234567,-69.1234567]],\"search\":\"Taller QA\"}"
    }
}
