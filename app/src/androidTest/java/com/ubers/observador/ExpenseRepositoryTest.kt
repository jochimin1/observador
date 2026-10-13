package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.DeletedExpense
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.ExpenseTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.expenses.FuelEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/** Fase 8B on a real Room database: edit in place, delete and undo, a confirmed reset, TEST isolation. */
@RunWith(AndroidJUnit4::class)
class ExpenseRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: ExpenseRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ExpenseRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun fill(id: String, amount: Long, odo: Long?) =
        FuelEntry(id, 1_000, 2_000, amount, odometerKm = odo, fullTank = true, createdAt = 1_000, updatedAt = 1_000)

    @Test
    fun anEntryIsEditedInPlaceAndDeleteCanBeUndone() = runBlocking {
        repo.saveFuel(fill("f", 55_000, 10_000))
        repo.saveFuel(fill("f", 56_000, 10_000).copy(updatedAt = 2_000))
        assertEquals(listOf(56_000L), repo.observeAll().first().fuel.map { it.amountMinor })
        val deleted = DeletedExpense.Fuel(repo.observeAll().first().fuel.single())
        repo.delete(deleted)
        assertTrue(repo.observeAll().first().fuel.isEmpty())
        repo.restore(deleted)
        assertEquals(deleted.entry, repo.observeAll().first().fuel.single())
    }

    @Test
    fun aConfirmedResetIsWrittenWithTheEntry() = runBlocking {
        repo.saveFuel(fill("a", 55_000, 50_000))
        repo.saveFuel(fill("b", 55_000, 120).copy(atMs = 5_000), resetAtKm = 120)
        val readings = repo.observeAll().first().readings
        assertEquals(1, readings.size)
        assertTrue(readings.single().isReset)
        assertEquals(4_999L, readings.single().atMs)
    }

    @Test
    fun testDataNeverTouchesRealRows() = runBlocking {
        repo.saveFuel(fill("real", 55_000, null))
        val clock = WorkdayClock(4, ZoneOffset.UTC)
        repo.replaceTestData(ExpenseTestSeeder.PREFIX, ExpenseTestSeeder.generate(1_800_000_000_000L, clock))
        assertEquals(6, repo.observeAll().first().fuel.size)
        repo.deleteTestData(ExpenseTestSeeder.PREFIX)
        val left = repo.observeAll().first()
        assertEquals(listOf("real"), left.fuel.map { it.id })
        assertTrue(left.expenses.isEmpty() && left.recurring.isEmpty() && left.readings.isEmpty())
    }
}
