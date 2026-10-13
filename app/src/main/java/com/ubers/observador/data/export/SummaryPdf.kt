package com.ubers.observador.data.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.ubers.observador.domain.reports.PeriodSummary
import com.ubers.observador.domain.reports.PeriodSummaryText
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One A4 page with the totals of a period, drawn with Android's own PdfDocument (no new
 * libraries). The lines come from [PeriodSummaryText], tested on the JVM; this only draws them.
 * The note "no es un documento oficial de Uber" is always printed.
 */
object SummaryPdf {

    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 48f
    private val GENERATED = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.forLanguageTag("es-DO"))

    fun render(s: PeriodSummary, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, 1).create())
            val c = page.canvas
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(0, 0x63, 0x9B) }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.DKGRAY }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.DKGRAY }
            val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
            val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f }

            var y = MARGIN + 20f
            c.drawText(PeriodSummaryText.title(s), MARGIN, y, title)
            y += 18f
            c.drawText("Generado el " + Instant.ofEpochMilli(s.generatedAtMs).atZone(zone).format(GENERATED).replace(".", ""), MARGIN, y, small)
            y += 16f
            c.drawLine(MARGIN, y, WIDTH - MARGIN, y, rule)
            y += 22f

            val valueX = MARGIN + 210f
            val valueWidth = WIDTH - MARGIN - valueX
            PeriodSummaryText.lines(s).forEach { (l, v) ->
                c.drawText(l, MARGIN, y, label)
                wrap(v, value, valueWidth).forEachIndexed { i, part ->
                    c.drawText(part, valueX, y + i * 15f, value)
                }
                y += 15f * wrap(v, value, valueWidth).size + 8f
            }

            // The note, always, at the bottom of the page
            var noteY = HEIGHT - MARGIN - 34f
            c.drawLine(MARGIN, noteY - 14f, WIDTH - MARGIN, noteY - 14f, rule)
            (wrap(PeriodSummaryText.NOTE, small, WIDTH - 2 * MARGIN) + wrap(PeriodSummaryText.OFFICIAL, small, WIDTH - 2 * MARGIN)).forEach {
                c.drawText(it, MARGIN, noteY, small)
                noteY += 13f
            }
            doc.finishPage(page)
            return ByteArrayOutputStream().use { out ->
                doc.writeTo(out)
                out.toByteArray()
            }
        } finally {
            doc.close()
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = ""
        words.forEach { w ->
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(candidate) <= width || current.isEmpty()) current = candidate
            else { lines += current; current = w }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
