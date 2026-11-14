package com.ubers.observador.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.JsonWriter
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.MaintenanceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Streaming, unfiltered export of a consistent DB snapshot and every preference. */
object FullDataExport {
    suspend fun write(context: Context, output: OutputStream) = withContext(Dispatchers.IO) {
        output.use { out ->
            MaintenanceGate.shared.exclusive {
                val temp = File(context.cacheDir, "full-export-${UUID.randomUUID()}.db")
                try {
                    DatabaseFiles.snapshot(context, temp)
                    val preferences = AppSettings(context).backupSnapshot()
                    SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        JsonWriter(out.writer(Charsets.UTF_8)).use { json ->
                            json.beginObject().name("format").value("observador-full-1")
                            json.name("version").value(BuildConfig.VERSION_NAME)
                            json.name("databaseVersion").value(db.version)
                            json.name("exportedAt").value(System.currentTimeMillis())
                            json.name("settingsTypedJson").value(BackupFormat.encodeSettings(BackupFormat.selectAllSettings(preferences)))
                            json.name("tables").beginObject()
                            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name", null)
                                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                            for (table in tables) {
                                json.name(table).beginArray()
                                val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                                db.rawQuery("SELECT * FROM $quoted", null).use { cursor ->
                                    while (cursor.moveToNext()) {
                                        json.beginObject()
                                        cursor.columnNames.forEachIndexed { i, column ->
                                            json.name(column)
                                            when (cursor.getType(i)) {
                                                Cursor.FIELD_TYPE_NULL -> json.nullValue()
                                                Cursor.FIELD_TYPE_INTEGER -> json.value(cursor.getLong(i))
                                                Cursor.FIELD_TYPE_FLOAT -> json.value(cursor.getDouble(i))
                                                Cursor.FIELD_TYPE_BLOB -> json.beginObject().name("base64").value(Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP)).endObject()
                                                else -> json.value(cursor.getString(i))
                                            }
                                        }
                                        json.endObject()
                                    }
                                }
                                json.endArray()
                            }
                            json.endObject().endObject()
                        }
                    }
                } finally { temp.delete() }
            }
        }
    }
}
