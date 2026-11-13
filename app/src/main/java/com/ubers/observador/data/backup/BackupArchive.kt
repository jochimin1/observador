package com.ubers.observador.data.backup

import com.ubers.observador.BuildConfig
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupLimits
import com.ubers.observador.domain.backup.BackupManifest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object BackupArchive {
    fun create(database: File, settings: Map<String, Any>, target: File): BackupManifest {
        val inspected = DatabaseFiles.inspect(database)
        val bytes = BackupFormat.encodeSettings(settings).toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupLimits.TEXT_BYTES) throw BackupException("Los ajustes superan el tamaño permitido.")
        val manifest = BackupManifest(
            BackupFormat.FORMAT_VERSION, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            inspected.version, System.currentTimeMillis(), inspected.rows,
            mapOf(
                BackupFormat.ENTRY_DB to database.inputStream().use { BackupLimits.sha256(it, BackupLimits.DATABASE_BYTES) },
                BackupFormat.ENTRY_SETTINGS to BackupFormat.sha256Hex(bytes)
            )
        )
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
            zip.write(BackupFormat.encodeManifest(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_SETTINGS)); zip.write(bytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_DB))
            database.inputStream().use { BackupLimits.copy(it, zip, BackupLimits.DATABASE_BYTES) }
            zip.closeEntry()
        }
        BackupVerifier.verify(target)
        return manifest
    }
}
