package com.ubers.observador.data.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.ubers.observador.domain.reports.ClosureLine
import com.ubers.observador.domain.reports.ClosureText
import java.io.ByteArrayOutputStream

/** Paginated ledger; even long names, evidence and references are wrapped without truncation. */
object ClosurePdf {
    fun render(lines: List<ClosureLine>): ByteArray {
        val doc = PdfDocument()
        try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        var number = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
        var y = 40f
        fun draw(text: String) {
            text.split('\n').forEach { paragraph ->
                var remaining = paragraph.ifEmpty { " " }
                while (remaining.isNotEmpty()) {
                    if (y > 800f) {
                        doc.finishPage(page)
                        page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                        y = 40f
                    }
                    val count = paint.breakText(remaining, true, 515f, null).coerceAtLeast(1)
                    page.canvas.drawText(remaining.take(count), 40f, y, paint)
                    remaining = remaining.drop(count)
                    y += 15f
                }
            }
        }
        draw("Observador · Cierre de jornada / periodo")
        lines.forEach { line ->
            draw("${line.section} · ${line.label}: ${ClosureText.display(line)}")
            // Exact stored units are printed as well as the driver-friendly representation.
            if (line.unit in setOf("ms", "mm", "DOP_centavos")) draw("Valor exacto: ${line.value ?: "Sin dato"} ${line.unit}")
            if (line.reference.isNotEmpty()) draw("Referencia: ${line.reference}")
            y += 4f
        }
        doc.finishPage(page)
        return ByteArrayOutputStream().use { out -> doc.writeTo(out); out.toByteArray() }
        } finally { doc.close() }
    }
}
