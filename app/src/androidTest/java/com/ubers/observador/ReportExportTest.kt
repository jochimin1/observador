package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.export.ReportData
import com.ubers.observador.data.export.ReportFile
import com.ubers.observador.data.export.SummaryPdf
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.expenses.FuelEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Fase 8D on the device: real files from Room, and a PDF drawn by Android with the note. */
@RunWith(AndroidJUnit4::class)
class ReportExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val clock = WorkdayClock(4, ZoneId.of("America/Santo_Domingo"))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun expensesCsvAndPdf() = runBlocking {
        val now = System.currentTimeMillis()
        ExpenseRepository(db).saveFuel(FuelEntry("TEST-f1", now - 3_600_000L, 1_250, 37_500, fullTank = true, createdAt = now, updatedAt = now))
        val window = EarningsWindows.of(EarningsRange.MONTH, 0, now, clock)
        val data = ReportData(db)
        val csv = data.build(ReportFile.EXPENSES_CSV, window, clock, now, 100)
        val text = csv.bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("﻿"))
        assertTrue(text.contains(",FUEL,FUEL,375.00,DOP,1.250,si,"))
        assertEquals(1, csv.rows)

        val trips = data.build(ReportFile.TRIPS_JSON, window, clock, now, 100)
        assertEquals(0, trips.rows)

        val pdf = SummaryPdf.render(data.periodSummary(window, clock, now, 100))
        assertTrue(pdf.size > 500)
        assertEquals("%PDF", pdf.copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }
}
