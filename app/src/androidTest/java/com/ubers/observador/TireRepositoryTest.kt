package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.TireMoney
import com.ubers.observador.data.TireRepository
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** 1.53.0 «Mi moto y neumáticos»: migration, persistence, money once, deletion that keeps money. */
class TireRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repo = TireRepository(db)
    private val day = 86_400_000L
    private val t0 = System.currentTimeMillis() - 60 * day
    private val v = INITIAL_VEHICLE
    @After fun close() = db.close()

    private fun psi(t: String) = TirePressure.parse(t, PressureUnit.PSI).getOrThrow()

    @Test fun migrationAddsTheTablesAndKeepsEverything() {
        val name = "tires-${UUID.randomUUID()}"
        helper.createDatabase(name, 21).apply {
            execSQL("INSERT INTO expense_entries (id,at,type,amount_minor,currency_code,odometer_km,created_at,updated_at,vehicle_id,expected_life_km,expected_life_days) VALUES ('TEST-gomas',2000,'TIRES',250000,'DOP',12000,2000,2000,'vehicle-initial',NULL,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 22, true, AppDatabase.MIGRATION_21_22).use { sql ->
            sql.query("SELECT amount_minor FROM expense_entries WHERE id='TEST-gomas'").use { assertTrue(it.moveToFirst()); assertEquals(250000, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM tire_components").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM tire_events").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    @Test fun installCheckChangeAndMoneyOnce() = runBlocking {
        val now = System.currentTimeMillis()
        assertNull(repo.install(v, WheelPosition.FRONT, "Pirelli", "Diablo", "90/90-18", psi("29"), t0, 10_000, TireMoney.New(250_000), "", now))
        var data = repo.observe().first()
        val first = data.tires.single()
        val install = data.events.single()
        assertEquals(TireEventKind.INSTALL, install.kind)
        val expense = db.expenseDao().getExpenses().single()
        assertEquals(install.expenseId, expense.id)
        assertEquals("TIRES", expense.type)
        assertEquals(10_000L, expense.odometerKm)

        // A check is free
        val check = TireEvent("TEST-check", first.id, TireEventKind.CHECK, t0 + day, psi("28.5"), TireTemperature.COLD, createdAt = now, updatedAt = now)
        assertNull(repo.saveEvent(check, TireMoney.None, now))
        assertEquals(1, db.expenseDao().getExpenses().size)

        // A repair with money; editing its amount keeps the same expense
        val repair = TireEvent("TEST-repair", first.id, TireEventKind.REPAIR, t0 + 2 * day, note = "Pinchazo", createdAt = now, updatedAt = now)
        assertNull(repo.saveEvent(repair, TireMoney.New(30_000), now))
        val repairExpense = repo.observe().first().events.single { it.id == "TEST-repair" }.expenseId!!
        assertNull(repo.saveEvent(repo.observe().first().events.single { it.id == "TEST-repair" }, TireMoney.New(35_000), now))
        val all = db.expenseDao().getExpenses()
        assertEquals(2, all.size)
        assertEquals(35_000L, all.single { it.id == repairExpense }.amountMinor)
        assertEquals("MAINTENANCE_OTHER", all.single { it.id == repairExpense }.type)

        // The tyres' expense cannot be linked twice
        val other = TireEvent("TEST-repair-2", first.id, TireEventKind.REPAIR, t0 + 3 * day, createdAt = now, updatedAt = now)
        assertNotNull(repo.saveEvent(other, TireMoney.Link(repairExpense), now))
        // A lower odometer is refused
        assertNotNull(repo.saveEvent(other.copy(odometerKm = 9_000), TireMoney.None, now))

        // Changing the tyre keeps the old one
        assertNull(repo.install(v, WheelPosition.FRONT, "Michelin", null, null, psi("29"), t0 + 30 * day, 14_000, TireMoney.None, "", now))
        data = repo.observe().first()
        assertEquals(2, data.tires.size)
        val old = data.tires.single { it.id == first.id }
        assertEquals(t0 + 30 * day, old.removedAt)
        assertEquals(14_000L, old.removedOdometerKm)
        assertEquals(4_000L, TireRules.lifeKm(old, emptyList()).first)

        // Deleting the new tyre (registered by mistake) reopens the old one; money stays
        val newer = data.tires.single { it.active }
        assertNull(repo.deleteTire(newer.id, now))
        data = repo.observe().first()
        assertTrue(data.tires.single().active)
        assertNull(repo.deleteEvent("TEST-repair"))
        assertEquals(2, db.expenseDao().getExpenses().size)
        // The installation goes only with its tyre
        assertNotNull(repo.deleteEvent(install.id))
    }

    @Test fun datesAndTextsAreChecked() = runBlocking {
        val now = System.currentTimeMillis()
        assertNotNull(repo.install(v, WheelPosition.REAR, null, null, null, null, now + 10 * day, null, TireMoney.None, "", now))
        assertNotNull(repo.install(v, WheelPosition.REAR, "x".repeat(41), null, null, null, t0, null, TireMoney.None, "", now))
        assertNotNull(repo.install(v, WheelPosition.REAR, null, null, null, null, t0, null, TireMoney.New(10), "", now))
        assertTrue(repo.observe().first().tires.isEmpty())
        assertTrue(db.expenseDao().getExpenses().isEmpty())
    }
}
