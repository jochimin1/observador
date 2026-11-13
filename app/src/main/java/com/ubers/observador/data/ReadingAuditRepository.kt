package com.ubers.observador.data

import android.util.JsonWriter
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.MaintenanceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter

class ReadingAuditRepository(db: AppDatabase) {
    private val dao = db.readingAuditDao()
    fun observeLatest() = dao.observeLatest()

    suspend fun export(output: OutputStream): Int = withContext(Dispatchers.IO) {
        output.use { stream -> MaintenanceGate.shared.access {
            val rows = dao.latest(10_000)
            JsonWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { json ->
                json.beginObject().name("formatVersion").value(1).name("retentionLimit").value(10_000)
                    .name("count").value(rows.size).name("readings").beginArray()
                rows.forEach { row ->
                    json.beginObject().name("id").value(row.id).name("observedAt").value(row.observedAt)
                        .name("parserVersion").value(row.parserVersion).name("classifierVersion").value(row.classifierVersion)
                        .name("uberVersion").value(row.uberVersion).name("screenType").value(row.screenType)
                        .name("confidence").value(row.confidence).name("provisional").value(row.provisional)
                        .name("reasonCodes").value(row.reasonCodes).name("acceptedCards").value(row.acceptedCards)
                        .name("rejectedCards").value(row.rejectedCards).name("lifecycleState").value(row.lifecycleState)
                        .name("lifecycleSessionId").value(row.lifecycleSessionId).name("eventCodes").value(row.eventCodes)
                        .name("financialType").value(row.financialType).name("financialConfidence").value(row.financialConfidence)
                        .endObject()
                }
                json.endArray().endObject()
            }
            rows.size
        } }
    }
}
