package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.route.RouteClose
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RouteStatus
import com.ubers.observador.domain.route.RouteUpdate
import com.ubers.observador.domain.route.RouteWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8K on a real database: 13 -> 14 only adds the two route tables (the trip from DB 13 comes
 * through intact), validated against schemas/14.json; the chain 4 -> 14 opens; a route is written
 * every 30 s, closed, simplified, and deleted with its trip.
 */
@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun tripsSurviveAndTheRouteTablesAreEmpty() {
        helper.createDatabase(DB, 13).apply {
            execSQL(INSERT_TRIP)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 14, true, AppDatabase.MIGRATION_13_14)
        db.query("SELECT rider_name, offer_pickup_address FROM trips").use { c ->
            c.moveToFirst()
            assertEquals("stefany", c.getString(0))
            assertEquals("Los Ríos", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM trip_route_points").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM trip_routes").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    @Test
    fun theChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14
            )
            .build()
        assertEquals(0, room.routeDao().observeRouteCount().first())
        room.close()
    }

    @Test
    fun aRouteIsWrittenClosedAndDeletedWithItsTrip() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        room.openHelper.writableDatabase.execSQL(INSERT_TRIP)
        val repo = RouteRepository(room)
        fun pt(i: Int, phase: RoutePhase) = RoutePoint(atMs = 1_000L + i * 5_000, latE7 = 184_861_000 + i * 1_000, lngE7 = -699_312_000, accuracyDm = 50, phase = phase)
        repo.save(RouteUpdate(listOf(RouteWrite("session-1", openAt = 1_000L))))
        repo.save(RouteUpdate(listOf(RouteWrite("session-1", points = (0 until 6).map { pt(it, RoutePhase.PICKUP) }, receivedDelta = 7))))
        repo.save(RouteUpdate(listOf(RouteWrite("session-1", points = (6 until 12).map { pt(it, RoutePhase.PASSENGER) }, receivedDelta = 6,
            close = RouteClose(70_000L, RouteCoverage.FULL)))))
        val route = repo.getRoute("trip-1")!!
        assertEquals(RouteStatus.CLOSED, route.status)
        assertEquals(13, route.pointsReceived)
        assertTrue(route.startWithPassenger)
        val points = repo.getPoints("trip-1")
        assertEquals(4, points.size) // a straight line: the ends of each phase
        assertEquals(points.map { it.seq }, points.map { it.seq }.sorted())
        // A closed route is not reopened by a stray write of another session
        repo.save(RouteUpdate(listOf(RouteWrite("no-such-session", openAt = 2_000L))))
        // «Borrar recorrido» keeps the summary without coordinates
        repo.deleteRoute("trip-1", 80_000L)
        assertEquals(0, repo.getPoints("trip-1").size)
        val deleted = repo.getRoute("trip-1")!!
        assertEquals(RouteStatus.DELETED, deleted.status)
        assertNull(deleted.startLatE7)
        // Deleting the trip takes the route with it
        room.tripDao().deleteTrip("trip-1")
        assertNull(repo.getRoute("trip-1"))
        room.close()
    }

    private companion object {
        const val DB = "migration-13-14-test.db"
        const val CHAIN_DB = "migration-4-14-test.db"
        const val INSERT_TRIP =
            "INSERT INTO trips (id, lifecycle_session_id, related_offer_hash, status, recovered, started_from_known_offer, " +
                "accepted_at, completed_at, created_at, updated_at, has_anomalies, offer_seen_at, offered_amount, " +
                "estimated_pickup_km, estimated_pickup_minutes, estimated_trip_km, estimated_trip_minutes, score_at_detection, " +
                "setting_min_rdp_per_km, setting_min_rdp_per_hour, setting_max_pickup_km, setting_max_pickup_minutes, " +
                "setting_good_from, setting_excellent_from, setting_weight_km_pct, setting_weight_hour_pct, " +
                "setting_weight_pickup_km_pct, setting_weight_pickup_min_pct, offer_pickup_address, offer_dropoff_address, rider_name, " +
                "offer_product, offer_exclusive, offer_card_action) " +
                "VALUES ('trip-1', 'session-1', '50.01-1.9-5-2.2-6', 'COMPLETED', 0, 1, 1000, 5000, 1000, 5000, 0, 900, 50.01, " +
                "1.9, 5, 2.2, 6, 55, 6.0, 300.0, 6.0, 12, 60, 80, 40, 30, 15, 15, 'Los Ríos', 'Los Prados', 'stefany', 'Moto', 0, 'ACCEPT')"
    }
}
