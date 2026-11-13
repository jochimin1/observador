package com.ubers.observador.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.backup.BackupLimits
import java.io.File

internal object DatabaseFiles {
    data class Inspection(val version: Int, val rows: Map<String, Int>)

    fun requireSpace(dir: File, required: Long) {
        if (dir.usableSpace < required + BackupLimits.FREE_MARGIN_BYTES) {
            throw BackupException("No hay espacio libre suficiente para guardar una copia y recuperar los datos si algo falla.")
        }
    }

    fun snapshot(context: Context, target: File): Inspection {
        check(!target.exists()) { "Snapshot already exists" }
        AppDatabase.getInstance(context).openHelper.writableDatabase.execSQL(
            "VACUUM INTO '" + target.absolutePath.replace("'", "''") + "'"
        )
        if (target.length() > BackupLimits.DATABASE_BYTES) throw BackupException("La base de datos supera el límite de 512 MB.")
        return inspect(target)
    }

    fun inspect(file: File): Inspection {
        if (!file.isFile || file.length() > BackupLimits.DATABASE_BYTES) throw BackupException("La base de datos no está disponible o supera el tamaño permitido.")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            if (!integrity) throw BackupException("La base de datos no pasó la revisión de integridad.")
            val foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() }
            if (!foreignKeys) throw BackupException("La base de datos tiene registros relacionados incompletos.")
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata','room_master_table') ORDER BY name", null
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val rows = tables.associateWith { table ->
                val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                db.rawQuery("SELECT COUNT(*) FROM $quoted", null).use { c ->
                    check(c.moveToFirst())
                    val count = c.getLong(0)
                    if (count > Int.MAX_VALUE) throw BackupException("La tabla contiene demasiados registros.")
                    count.toInt()
                }
            }
            return Inspection(db.version, rows)
        }
    }

    /** Force Room schema validation and every needed migration BEFORE installing the selected file. */
    fun migrateAndValidate(context: Context, file: File): Inspection {
        val room = AppDatabase.openFile(context, file.absolutePath)
        try {
            val db = room.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                if (!c.moveToFirst() || c.getInt(0) != 0) throw BackupException("No se pudo terminar de guardar la base de datos.")
            }
        } finally { room.close() }
        return inspect(file)
    }
}
