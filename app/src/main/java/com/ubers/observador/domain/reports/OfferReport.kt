package com.ubers.observador.domain.reports

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferLedger
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.lifecycle.OfferCardAction
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.trips.TripStatus
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Fase 8J: «Reportes y exportación». Every offer of a period, offered or taken, with its category,
 * modality and button, and the totals of the period built from those same rows, so each total is
 * the sum of the detail file. Pure Kotlin, tested on the JVM. Money is summed in minor units; the
 * offered price is a reference and never income. Never coordinates.
 */

/** Which button the card had, for the filter. */
enum class ButtonFilter(val label: String, val action: OfferCardAction?) {
    ALL("Todos", null),
    INTEREST("Me interesa", OfferCardAction.INTEREST),
    ACCEPT("Aceptar", OfferCardAction.ACCEPT)
}

/** The filters of the export screen. [product] null = every modality. */
data class ReportFilters(
    val origin: TakenFilter = TakenFilter.ALL,
    val quality: CategoryFilter = CategoryFilter.ALL,
    val button: ButtonFilter = ButtonFilter.ALL,
    val product: String? = null
) {
    val isDefault: Boolean get() = this == ReportFilters()

    /** "Tomados · Buenas · Me interesa · Moto", or "Sin filtros". */
    fun describe(): String = listOfNotNull(
        origin.takeIf { it != TakenFilter.ALL }?.label,
        quality.takeIf { it != CategoryFilter.ALL }?.label,
        button.takeIf { it != ButtonFilter.ALL }?.label,
        product
    ).joinToString(" · ").ifEmpty { "Sin filtros" }
}

enum class OfferOrigin(val label: String) { OFFERED("ofertado"), TAKEN("tomado") }

/** One offer of the detail file. Money in minor units of [currencyCode]. */
data class OfferReportRow(
    val offerId: Long,
    val detectedAt: Long,
    val workday: LocalDate,
    val origin: OfferOrigin,
    val tripId: String?,
    val tripStatus: TripStatus?,
    /** Counts as a trip done (completed, or closed by Uber going back to searching). */
    val tripDone: Boolean,
    /** As Uber wrote it, or [OfferReport.NO_PRODUCT] on rows read before Fase 8J. */
    val product: String,
    val exclusive: Boolean?,
    val button: OfferCardAction?,
    val quality: OfferQuality,
    val scoreToday: Int,
    val scoreAtDetection: Int,
    /** Reference only, never income. */
    val offeredMinor: Long,
    val pickupKm: Double,
    val pickupMin: Int,
    val tripKm: Double,
    val tripMin: Int,
    val totalKm: Double,
    val totalMin: Int,
    val rdpPerKm: Double,
    val rdpPerHour: Double,
    val fuelCostMinor: Long,
    val estimatedProfitMinor: Long,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val riderName: String?,
    /** Null unless the trip's money is CONFIRMED. */
    val realMinor: Long?,
    val tipMinor: Long,
    val differenceMinor: Long?,
    val completeness: FinancialCompleteness?,
    val currencyCode: String
)

object OfferReport {

    const val NO_PRODUCT = "Sin dato"

    /**
     * The detail rows of a period: every offer seen (plus taken trips whose offer row is gone),
     * judged with today's settings like Inicio, newest first.
     */
    fun rows(
        offers: List<OfferEntity>,
        trips: List<Trip>,
        summaries: Map<String, TripFinancialSummary>,
        engine: EngineSettings,
        cuts: QualityThresholds,
        clock: WorkdayClock,
        currencyCode: String = Currencies.DOP
    ): List<OfferReportRow> {
        val ledger = OfferLedger.of(offers, trips)
        val original = ledger.rows.associateBy { it.id }
        return DashboardStats.rescore(ledger.rows, engine).map { o ->
            val trip = ledger.tripOf[o.id]
            val s = trip?.let { summaries[it.id] }
            val offered = minor(o.price, currencyCode)
            val confirmed = s?.takeIf { it.completeness == FinancialCompleteness.CONFIRMED }?.effectiveEarnings
            val difference = s?.copy(offeredAmount = s.offeredAmount ?: MoneyAmount(offered, currencyCode))?.differenceFromOffer()?.first
            OfferReportRow(
                offerId = o.id,
                detectedAt = o.detectedAt,
                workday = clock.workdayOf(o.detectedAt),
                origin = if (o.id in ledger.tripStatus) OfferOrigin.TAKEN else OfferOrigin.OFFERED,
                tripId = trip?.id,
                tripStatus = ledger.tripStatus[o.id],
                tripDone = trip != null && TripOutcome.endedAt(trip) != null,
                product = o.product?.trim()?.takeIf { it.isNotEmpty() } ?: NO_PRODUCT,
                exclusive = o.exclusive,
                button = OfferCardAction.fromStorage(o.cardAction),
                quality = cuts.classify(o.score),
                scoreToday = o.score,
                scoreAtDetection = original[o.id]?.score ?: o.score,
                offeredMinor = offered,
                pickupKm = o.pickupKm,
                pickupMin = o.pickupMin,
                tripKm = o.tripKm,
                tripMin = o.tripMin,
                totalKm = o.totalKm,
                totalMin = o.totalMin,
                rdpPerKm = o.rdpPerKm,
                rdpPerHour = o.rdpPerHour,
                fuelCostMinor = minor(o.fuelCost, currencyCode),
                estimatedProfitMinor = minor(o.netProfit, currencyCode),
                pickupAddress = o.pickupAddress,
                dropoffAddress = o.dropoffAddress,
                riderName = trip?.riderName,
                realMinor = confirmed?.minorUnits,
                tipMinor = s?.tip ?: 0,
                differenceMinor = difference?.minorUnits?.takeIf { confirmed != null },
                completeness = s?.completeness ?: trip?.let { FinancialCompleteness.UNKNOWN },
                currencyCode = currencyCode
            )
        }
    }

    fun filter(rows: List<OfferReportRow>, f: ReportFilters): List<OfferReportRow> = rows.filter { r ->
        (f.origin == TakenFilter.ALL || (f.origin == TakenFilter.TAKEN) == (r.origin == OfferOrigin.TAKEN)) &&
            (f.quality.quality == null || r.quality == f.quality.quality) &&
            (f.button.action == null || r.button == f.button.action) &&
            (f.product == null || r.product == f.product)
    }

    /** The modalities that appear, most frequent first, "Sin dato" last. */
    fun products(rows: List<OfferReportRow>): List<String> =
        rows.groupingBy { it.product }.eachCount().entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.key == NO_PRODUCT }.thenByDescending { it.value }.thenBy { it.key })
            .map { it.key }

    private fun minor(value: Double, currencyCode: String): Long = MoneyAmount.fromLegacyDouble(value, currencyCode)?.minorUnits ?: 0L

    // ---- the detail file ----

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    val HEADER = listOf(
        "fecha", "hora", "jornada", "origen", "estado_viaje", "modalidad", "exclusivo", "boton", "categoria", "score_hoy",
        "score_al_detectar", "ofertado_referencia", "km_recogida", "min_recogida", "km_viaje", "min_viaje", "km_total", "min_total",
        "rd_por_km", "rd_por_hora", "combustible_estimado", "ganancia_estimada", "recogida", "destino", "pasajero",
        "real_confirmado", "propina", "diferencia_con_ofertado", "cobertura_dinero", "moneda"
    )

    fun statusLabel(s: TripStatus?): String = when (s) {
        null -> ""
        TripStatus.TO_PICKUP -> "yendo a recoger"
        TripStatus.ARRIVED_OR_WAITING -> "esperando al pasajero"
        TripStatus.IN_TRIP -> "en viaje"
        TripStatus.COMPLETED -> "terminado"
        TripStatus.CANCELLED -> "cancelado"
        TripStatus.INTERRUPTED -> "terminó sin confirmar"
        TripStatus.UNKNOWN -> "sin seguimiento"
    }

    private fun yesNo(b: Boolean?) = when (b) { null -> ""; true -> "si"; false -> "no" }
    private fun d2(v: Double) = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun d1(v: Double) = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString()

    fun csv(rows: List<OfferReportRow>, zone: ZoneId): String = Csv.build(HEADER, rows.map { r ->
        val at = Instant.ofEpochMilli(r.detectedAt).atZone(zone)
        val c = r.currencyCode
        listOf(
            at.format(DATE), at.format(TIME), r.workday.format(DATE), r.origin.label, statusLabel(r.tripStatus), r.product,
            yesNo(r.exclusive), r.button?.label ?: "", r.quality.label, r.scoreToday.toString(), r.scoreAtDetection.toString(),
            Decimals.of(r.offeredMinor, c), d1(r.pickupKm), r.pickupMin.toString(), d1(r.tripKm), r.tripMin.toString(),
            d1(r.totalKm), r.totalMin.toString(), d2(r.rdpPerKm), d2(r.rdpPerHour), Decimals.of(r.fuelCostMinor, c),
            Decimals.of(r.estimatedProfitMinor, c), r.pickupAddress ?: "", r.dropoffAddress ?: "", r.riderName ?: "",
            r.realMinor?.let { Decimals.of(it, c) } ?: "", if (r.tripId != null) Decimals.of(r.tipMinor, c) else "",
            r.differenceMinor?.let { Decimals.of(it, c) } ?: "", r.completeness?.storageKey ?: "", c
        )
    })

    fun json(rows: List<OfferReportRow>, zone: ZoneId, exportedAtMs: Long, from: LocalDate, to: LocalDate, filters: ReportFilters): String = Json.obj(
        "exportedAt" to Json.str(Instant.ofEpochMilli(exportedAtMs).atZone(zone).format(DATE_TIME)),
        "from" to Json.str(from.format(DATE)),
        "to" to Json.str(to.format(DATE)),
        "filters" to Json.str(filters.describe()),
        "count" to rows.size.toString(),
        "note" to Json.str(PeriodSummaryText.NOTE),
        "offers" to Json.arr(rows.map { r ->
            val c = r.currencyCode
            Json.obj(
                "detectedAt" to Json.str(Instant.ofEpochMilli(r.detectedAt).atZone(zone).format(DATE_TIME)),
                "workday" to Json.str(r.workday.format(DATE)),
                "origin" to Json.str(r.origin.name),
                "tripStatus" to Json.str(r.tripStatus?.storageKey),
                "product" to Json.str(r.product),
                "exclusive" to Json.bool(r.exclusive),
                "cardAction" to Json.str(r.button?.storageKey),
                "category" to Json.str(r.quality.name),
                "scoreToday" to r.scoreToday.toString(),
                "scoreAtDetection" to r.scoreAtDetection.toString(),
                "offeredReference" to Json.num(Decimals.of(r.offeredMinor, c)),
                "pickupKm" to d1(r.pickupKm),
                "pickupMin" to r.pickupMin.toString(),
                "tripKm" to d1(r.tripKm),
                "tripMin" to r.tripMin.toString(),
                "totalKm" to d1(r.totalKm),
                "totalMin" to r.totalMin.toString(),
                "rdPerKm" to d2(r.rdpPerKm),
                "rdPerHour" to d2(r.rdpPerHour),
                "fuelEstimate" to Json.num(Decimals.of(r.fuelCostMinor, c)),
                "profitEstimate" to Json.num(Decimals.of(r.estimatedProfitMinor, c)),
                "pickupAddress" to Json.str(r.pickupAddress),
                "dropoffAddress" to Json.str(r.dropoffAddress),
                "riderName" to Json.str(r.riderName),
                "realConfirmed" to Json.num(r.realMinor?.let { Decimals.of(it, c) } ?: ""),
                "tip" to Json.num(if (r.tripId != null) Decimals.of(r.tipMinor, c) else ""),
                "differenceFromOffer" to Json.num(r.differenceMinor?.let { Decimals.of(it, c) } ?: ""),
                "moneyCoverage" to Json.str(r.completeness?.storageKey),
                "currency" to Json.str(c)
            )
        })
    )
}

// ---- totals ----

data class CountSplit(val all: Int, val offered: Int, val taken: Int)

data class ButtonTotals(val label: String, val all: Int, val taken: Int)

data class ProductTotals(
    val product: String,
    val count: CountSplit,
    val avgOfferedMinor: Long?,
    val avgRdpPerKm: Double?,
    val medianRdpPerKm: Double?
)

data class DayTotals(
    val day: LocalDate,
    val count: CountSplit,
    val offeredRefMinor: Long,
    val realMinor: Long,
    val confirmedTrips: Int,
    val doneTrips: Int
)

/** Everything the totals file, the PDF and the screen show. Built only from the detail rows. */
data class PeriodTotals(
    val count: CountSplit,
    /** Taken over all offers seen, rounded; null without offers. */
    val takenPercent: Int?,
    val completed: Int,
    val unconfirmed: Int,
    val cancelled: Int,
    val inProgress: Int,
    val byQuality: Map<OfferQuality, CountSplit>,
    val byButton: List<ButtonTotals>,
    val byProduct: List<ProductTotals>,
    /** Sum of the prices offered: a reference, never income. */
    val offeredRefMinor: Long,
    val realMinor: Long,
    val confirmedTrips: Int,
    val doneTrips: Int,
    val tipsMinor: Long,
    /** Estimated km of the taken offers, in tenths (exact sums). */
    val takenKmTenths: Long,
    val takenMinutes: Long,
    /** Only for weeks and months, like Ganancias; null for a day. */
    val expensesWorkMinor: Long?,
    val netMinor: Long?,
    val days: List<DayTotals>,
    val currencyCode: String
)

object PeriodTotalsBuilder {

    fun of(rows: List<OfferReportRow>, expensesWorkMinor: Long? = null, currencyCode: String = Currencies.DOP): PeriodTotals {
        fun split(list: List<OfferReportRow>) = CountSplit(list.size, list.count { it.origin == OfferOrigin.OFFERED }, list.count { it.origin == OfferOrigin.TAKEN })
        val taken = rows.filter { it.origin == OfferOrigin.TAKEN }
        val real = rows.sumOf { it.realMinor ?: 0L }
        return PeriodTotals(
            count = split(rows),
            takenPercent = if (rows.isEmpty()) null else (taken.size * 200 + rows.size) / (rows.size * 2),
            completed = taken.count { it.tripStatus == TripStatus.COMPLETED },
            unconfirmed = taken.count { it.tripStatus == TripStatus.INTERRUPTED },
            cancelled = taken.count { it.tripStatus == TripStatus.CANCELLED },
            inProgress = taken.count { it.tripStatus != null && it.tripStatus.isOpen },
            byQuality = listOf(OfferQuality.EXCELLENT, OfferQuality.GOOD, OfferQuality.BAD).associateWith { q -> split(rows.filter { it.quality == q }) },
            byButton = listOf(OfferCardAction.INTEREST, OfferCardAction.ACCEPT).map { a ->
                rows.filter { it.button == a }.let { ButtonTotals(a.label, it.size, it.count { r -> r.origin == OfferOrigin.TAKEN }) }
            } + rows.filter { it.button == null }.let { ButtonTotals(OfferReport.NO_PRODUCT, it.size, it.count { r -> r.origin == OfferOrigin.TAKEN }) },
            byProduct = OfferReport.products(rows).map { p ->
                val list = rows.filter { it.product == p }
                ProductTotals(
                    product = p,
                    count = split(list),
                    avgOfferedMinor = if (list.isEmpty()) null else divHalfUp(list.sumOf { it.offeredMinor }, list.size.toLong()),
                    avgRdpPerKm = list.takeIf { it.isNotEmpty() }?.map { it.rdpPerKm }?.average(),
                    medianRdpPerKm = median(list.map { it.rdpPerKm })
                )
            },
            offeredRefMinor = rows.sumOf { it.offeredMinor },
            realMinor = real,
            confirmedTrips = rows.count { it.realMinor != null },
            doneTrips = taken.count { it.tripDone },
            tipsMinor = rows.sumOf { if (it.tripId != null) it.tipMinor else 0L },
            takenKmTenths = taken.sumOf { Math.round(it.totalKm * 10) },
            takenMinutes = taken.sumOf { it.totalMin.toLong() },
            expensesWorkMinor = expensesWorkMinor,
            netMinor = expensesWorkMinor?.let { real - it },
            days = rows.groupBy { it.workday }.toSortedMap().map { (day, list) ->
                DayTotals(
                    day, split(list), list.sumOf { it.offeredMinor }, list.sumOf { it.realMinor ?: 0L },
                    list.count { it.realMinor != null }, list.count { it.origin == OfferOrigin.TAKEN && it.tripDone }
                )
            },
            currencyCode = currencyCode
        )
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun divHalfUp(a: Long, b: Long): Long = if (b == 0L) 0 else (a * 2 + b) / (b * 2)
}

/**
 * The totals as sections of (concept, value). The same list feeds the CSV, the JSON, the PDF and
 * the screen, so they can never disagree. Values are plain: counts, decimals from minor units, %.
 */
object PeriodTotalsText {

    data class Section(val title: String, val lines: List<Pair<String, String>>)

    private fun d2(v: Double?) = v?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toPlainString() } ?: ""

    fun sections(t: PeriodTotals): List<Section> {
        val c = t.currencyCode
        fun m(minor: Long) = Decimals.of(minor, c)
        fun split(s: CountSplit) = "${s.all} (ofertados ${s.offered}, tomados ${s.taken})"
        val out = ArrayList<Section>()
        out += Section("Origen", listOf(
            "Ofertas vistas" to t.count.all.toString(),
            "Ofertados (no tomados)" to t.count.offered.toString(),
            "Tomados" to t.count.taken.toString(),
            "% tomados" to (t.takenPercent?.toString() ?: "")
        ))
        out += Section("Tomados por estado", listOf(
            "Terminados" to t.completed.toString(),
            "Terminó sin confirmar" to t.unconfirmed.toString(),
            "Cancelados" to t.cancelled.toString(),
            "En curso" to t.inProgress.toString()
        ))
        out += Section("Por categoría (ajustes de hoy)", t.byQuality.map { (q, s) -> q.label to split(s) })
        out += Section("Por botón", t.byButton.map { b -> b.label to "${b.all} (tomados ${b.taken})" })
        t.byProduct.forEach { p ->
            out += Section("Modalidad: ${p.product}", listOf(
                "Ofertas" to split(p.count),
                "Precio ofertado promedio ($c, referencia)" to (p.avgOfferedMinor?.let { m(it) } ?: ""),
                "RD$/km promedio" to d2(p.avgRdpPerKm),
                "RD$/km mediana" to d2(p.medianRdpPerKm)
            ))
        }
        out += Section("Sumas del periodo", listOfNotNull(
            "Ofertado ($c, referencia, no es ingreso)" to m(t.offeredRefMinor),
            "Ingreso real confirmado ($c)" to m(t.realMinor),
            "Cobertura del dinero" to "${t.confirmedTrips} de ${t.doneTrips} viajes con dinero confirmado",
            "Propinas ($c)" to m(t.tipsMinor),
            "Km estimados de lo tomado" to BigDecimal.valueOf(t.takenKmTenths, 1).toPlainString(),
            "Minutos estimados de lo tomado" to t.takenMinutes.toString(),
            t.expensesWorkMinor?.let { "Gastos de trabajo ($c)" to m(it) },
            t.netMinor?.let { "Neto: real menos gastos ($c)" to m(it) }
        ))
        out += Section("Por jornada", t.days.map { d ->
            d.day.toString() to "${split(d.count)} · ofertado ${m(d.offeredRefMinor)} · real ${m(d.realMinor)} · " +
                "${d.confirmedTrips} de ${d.doneTrips} con dinero"
        })
        return out
    }

    val CSV_HEADER = listOf("seccion", "concepto", "valor")

    fun csv(t: PeriodTotals, from: LocalDate, to: LocalDate, filters: ReportFilters): String {
        val head = listOf(
            listOf("Periodo", "Desde", from.toString()),
            listOf("Periodo", "Hasta", to.toString()),
            listOf("Periodo", "Filtros", filters.describe()),
            listOf("Periodo", "Nota", "Agrupado por la hora en que se vio cada oferta. " + PeriodSummaryText.NOTE)
        )
        return Csv.build(CSV_HEADER, head + sections(t).flatMap { s -> s.lines.map { (k, v) -> listOf(s.title, k, v) } })
    }

    fun json(t: PeriodTotals, from: LocalDate, to: LocalDate, filters: ReportFilters): String = Json.obj(
        "from" to Json.str(from.toString()),
        "to" to Json.str(to.toString()),
        "filters" to Json.str(filters.describe()),
        "currency" to Json.str(t.currencyCode),
        "note" to Json.str(PeriodSummaryText.NOTE),
        "sections" to Json.arr(sections(t).map { s ->
            Json.obj(
                "title" to Json.str(s.title),
                "items" to s.lines.joinToString(",", "[", "]") { (k, v) -> Json.obj("concept" to Json.str(k), "value" to Json.str(v)) }
            )
        })
    )
}

/** Several files in one .zip (Fase 8J), in the order given. */
object ZipPack {
    fun of(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** One line of «Últimas exportaciones». Only the record: the files are not kept. */
data class ExportRecord(val atMs: Long, val period: String, val filters: String, val files: String)

/** The last [MAX] exports, stored as text in DataStore. Fields cannot contain the separators. */
object ExportHistory {
    const val MAX = 5
    private const val FIELD = '\u001F'
    private const val ENTRY = '\u001E'

    fun decode(text: String?): List<ExportRecord> = text.orEmpty().split(ENTRY).mapNotNull { e ->
        val p = e.split(FIELD)
        if (p.size != 4) null else p[0].toLongOrNull()?.let { ExportRecord(it, p[1], p[2], p[3]) }
    }

    fun encode(records: List<ExportRecord>): String = records.joinToString(ENTRY.toString()) { r ->
        listOf(r.atMs.toString(), clean(r.period), clean(r.filters), clean(r.files)).joinToString(FIELD.toString())
    }

    /** Newest first, at most [MAX]. */
    fun add(text: String?, record: ExportRecord): String = encode((listOf(record) + decode(text)).take(MAX))

    private fun clean(s: String) = s.replace(FIELD, ' ').replace(ENTRY, ' ')
}
