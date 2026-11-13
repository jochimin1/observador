package com.ubers.observador

import com.ubers.observador.domain.finance.FinancialDetector
import com.ubers.observador.domain.finance.FinancialScreenType
import com.ubers.observador.domain.lifecycle.LifecycleReplay
import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.ReplayStep
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.38.0: the versioned, sanitized reading corpus (test resources, reading-corpus/v1). Every case is
 * checked, and the result is reported per screen type: errors and coverage are counted apart, so a
 * new Uber layout shows up as «this kind of screen stopped being read», not as plausible numbers.
 */
class ReadingCorpusTest {

    data class Case(
        val id: String,
        val origin: String,
        val sequence: String?,
        val screen: UberScreenType?,
        val state: RideLifecycleState?,
        val reading: OfferParseStep?,
        val price: Double?,
        val money: FinancialScreenType?,
        val nodes: List<ScreenNode>
    ) {
        fun snapshot(atMs: Long = 0L) = ScreenSnapshot(atMs, LifecycleFixtures.UBER, nodes)
        val texts: List<String> get() = snapshot().texts
    }

    private val corpus: List<Case> by lazy { load("/reading-corpus/v1/corpus.txt") }

    private fun load(path: String): List<Case> {
        val text = javaClass.getResourceAsStream(path)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = ArrayList<Case>()
        var id: String? = null
        val fields = HashMap<String, String>()
        val nodes = ArrayList<ScreenNode>()
        fun flush() {
            val current = id ?: return
            cases += Case(
                id = current,
                origin = fields["origen"] ?: error("$current: falta origen"),
                sequence = fields["secuencia"],
                screen = fields["pantalla"]?.let(UberScreenType::valueOf),
                state = fields["estado"]?.let(RideLifecycleState::valueOf),
                reading = fields["lectura"]?.let(OfferParseStep::valueOf),
                price = fields["precio"]?.toDouble(),
                money = fields["dinero"]?.let(FinancialScreenType::valueOf),
                nodes = nodes.toList()
            )
            fields.clear(); nodes.clear()
        }
        text.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            when {
                line.startsWith("=== ") -> { flush(); id = line.removePrefix("=== ").trim() }
                line.startsWith("T: ") -> nodes += LifecycleFixtures.text(line.removePrefix("T: "))
                line.startsWith("B: ") -> nodes += LifecycleFixtures.button(line.removePrefix("B: "))
                ':' in line -> line.split(':', limit = 2).let { (k, v) -> fields[k.trim()] = v.trim() }
                else -> error("Línea no reconocida en el corpus: $line")
            }
        }
        flush()
        return cases
    }

    @Test fun corpusIsWellFormed() {
        assertTrue(corpus.size >= 20)
        assertEquals("ids únicos", corpus.size, corpus.map { it.id }.toSet().size)
        assertTrue(corpus.all { it.origin in setOf("sintetico", "captura") })
        assertTrue(corpus.all { it.nodes.isNotEmpty() })
        // Every stage the roadmap asks for is represented at least once
        val screens = corpus.mapNotNull { it.screen }.toSet()
        listOf(
            UberScreenType.OFFER, UberScreenType.PICKUP_NAVIGATION, UberScreenType.ARRIVED_OR_WAITING,
            UberScreenType.IN_TRIP, UberScreenType.TRIP_SUMMARY, UberScreenType.CANCELLED
        ).forEach { assertTrue("falta $it", it in screens) }
        assertTrue(corpus.any { it.money != null })
    }

    @Test fun everyScreenIsClassifiedAsExpected() {
        val failures = corpus.filter { it.screen != null }.mapNotNull { c ->
            val got = LifecycleFixtures.classifier.classify(c.snapshot()).type
            "${c.id}: esperado ${c.screen}, obtenido $got".takeIf { got != c.screen }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyCardIsReadOrExplicitlyUnreadable() {
        val failures = corpus.filter { it.reading != null }.mapNotNull { c ->
            val reading = OfferCardParser.diagnoseAll(c.texts)
            val card = reading.cards.singleOrNull() ?: return@mapNotNull "${c.id}: ${reading.cards.size} tarjetas"
            when {
                card.step != c.reading -> "${c.id}: esperado ${c.reading}, obtenido ${card.step} ${card.problems}"
                c.reading == OfferParseStep.OK && c.price != null && card.offer?.price != c.price ->
                    "${c.id}: precio ${card.offer?.price} en vez de ${c.price}"
                // «No pude leer» never carries numbers that could be scored
                c.reading != OfferParseStep.OK && reading.offers.isNotEmpty() -> "${c.id}: devolvió una oferta"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun sequencesReplayFromOfferToMoney() {
        val sequences = corpus.filter { it.sequence != null }.groupBy { it.sequence!! }
        assertTrue("completa" in sequences && "cancelada" in sequences)
        sequences.forEach { (name, steps) ->
            val replay = LifecycleReplay.run(
                steps.mapIndexed { i, c ->
                    val hash = OfferCardParser.parseAll(c.texts).firstOrNull()?.offerHash()
                    ReplayStep(1_000_000L + i * 10_000L, c.snapshot(1_000_000L + i * 10_000L), hash)
                },
                LifecycleFixtures.classifier
            )
            steps.zip(replay.states).forEach { (c, state) ->
                if (c.state != null) assertEquals("$name/${c.id}", c.state, state)
            }
            steps.filter { it.money != null }.forEach { c ->
                val type = LifecycleFixtures.classifier.classify(c.snapshot()).type
                assertEquals(c.id, c.money, FinancialDetector().classify(c.snapshot(), type).type)
            }
        }
    }

    /** The numbers published with the release: per screen type, how many cases and how many failed. */
    @Test fun metricsPerScreenType() {
        data class Tally(var cases: Int = 0, var errors: Int = 0)
        val tallies = sortedMapOf<String, Tally>()
        corpus.forEach { c ->
            val kind = c.screen?.name ?: "TARJETA_SUELTA"
            val tally = tallies.getOrPut(kind) { Tally() }
            tally.cases++
            val screenOk = c.screen == null || LifecycleFixtures.classifier.classify(c.snapshot()).type == c.screen
            val readingOk = c.reading == null || OfferCardParser.diagnoseAll(c.texts).cards.singleOrNull()?.step == c.reading
            if (!screenOk || !readingOk) tally.errors++
        }
        val report = tallies.entries.joinToString("\n") { (k, t) -> "$k: ${t.cases} casos, ${t.errors} errores" }
        println("Corpus de lectura v1 (${corpus.size} casos, ${corpus.count { it.origin == "captura" }} de capturas reales)\n$report")
        assertEquals(report, 0, tallies.values.sumOf { it.errors })
    }
}
