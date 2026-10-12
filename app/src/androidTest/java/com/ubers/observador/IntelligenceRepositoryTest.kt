package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.IntelligenceRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.IntelligenceTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.intelligence.CalibrationEngine
import com.ubers.observador.domain.intelligence.IntelligenceDatasetBuilder
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.RecommendationStatus
import com.ubers.observador.domain.money.Currencies
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fase 7D on a real Room database: batched loading, append-only versions, shadow dedup, TEST isolation. */
@RunWith(AndroidJUnit4::class)
class IntelligenceRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: IntelligenceRepository
    private val now = 1_800_000_000_000L
    private val defaults = IntelligenceTestSeeder.DEFAULT_SETTINGS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = IntelligenceRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun load(scenario: IntelligenceTestSeeder.Scenario): IntelligenceTestSeeder.Seed {
        val seed = IntelligenceTestSeeder.generate(scenario, now)
        TripRepository(db).replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
        FinancialRepository(db).loadTestData(TripTestSeeder.PREFIX, seed.observations, emptyList(), now)
        return seed
    }

    @Test
    fun datasetFromTheDatabaseEqualsTheInMemoryOne() = runBlocking {
        val seed = load(IntelligenceTestSeeder.Scenario.GOOD_MODEL)
        val fromDb = repo.loadDataset(0, now + 1)
        val inMemory = IntelligenceDatasetBuilder.build(seed.trips, seed.summaries(), Currencies.DOP)
        assertEquals(inMemory.quality, fromDb.quality)
        assertEquals(inMemory.samples.map { it.realizedGrossPerEngagedHour }, fromDb.samples.map { it.realizedGrossPerEngagedHour })
    }

    @Test
    fun ensureCurrentCreatesOneVersionAndThenNothing() = runBlocking {
        val v1 = repo.ensureCurrent(defaults, 1)
        val again = repo.ensureCurrent(defaults, 2)
        assertEquals(v1, again)
        assertEquals(ModelVersionSource.INITIAL, v1.source)
        val edited = repo.ensureCurrent(defaults.copy(minRdpPerHour = 350.0), 3)
        assertEquals(ModelVersionSource.USER_EDIT, edited.source)
        assertEquals(2, repo.versions().size)
        assertEquals(defaults, repo.versions().first().settings) // v1 unchanged
    }

    @Test
    fun recordingACandidateNeverChangesTheActiveModel() = runBlocking {
        load(IntelligenceTestSeeder.Scenario.PICKUP_MATTERS)
        val active = repo.ensureCurrent(defaults, 1)
        val report = CalibrationEngine.calibrate(repo.loadDataset(0, now + 1).samples, defaults, active.id, 300.0, now, { TripTestSeeder.PREFIX + "cand-1" })
        assertEquals(RecommendationStatus.SHADOW_RECOMMENDED, report.status)
        val candidate = repo.recordCandidate(report, null, 100, now, TripTestSeeder.PREFIX)!!
        assertEquals(ModelVersionSource.CALIBRATION_RECOMMENDATION, candidate.source)
        assertEquals(active, ModelVersionRules.active(repo.versions()))
    }

    @Test
    fun theSameOfferIsShadowScoredOnce() = runBlocking {
        val seed = load(IntelligenceTestSeeder.Scenario.PICKUP_MATTERS)
        val active = repo.ensureCurrent(defaults, 1)
        val report = CalibrationEngine.calibrate(repo.loadDataset(0, now + 1).samples, defaults, active.id, 300.0, now, { TripTestSeeder.PREFIX + "cand-1" })
        val candidate = repo.recordCandidate(report, null, 100, now, TripTestSeeder.PREFIX)!!
        val evaluations = IntelligenceTestSeeder.shadow(seed, active, candidate)
        assertTrue(repo.saveShadow(evaluations.first()))
        assertFalse(repo.saveShadow(evaluations.first()))
        repo.saveShadowBatch(evaluations)
        assertEquals(evaluations.size, repo.shadowEvaluations(candidate.id).size)
    }

    @Test
    fun testDataIsRemovedWithoutTouchingRealVersions() = runBlocking {
        load(IntelligenceTestSeeder.Scenario.PICKUP_MATTERS)
        val active = repo.ensureCurrent(defaults, 1)
        val report = CalibrationEngine.calibrate(repo.loadDataset(0, now + 1).samples, defaults, active.id, 300.0, now, { TripTestSeeder.PREFIX + "cand-1" })
        repo.recordCandidate(report, null, 100, now, TripTestSeeder.PREFIX)
        repo.deleteTestData(TripTestSeeder.PREFIX)
        assertEquals(listOf(active), repo.versions())
    }
}
