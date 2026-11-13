package com.ubers.observador.data.backup

import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.domain.backup.BackupLimits
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** The backup file is not usable; [message] says why, in the driver's words. */
class BackupException(message: String) : Exception(message)

/**
 * Opens a backup .zip and checks it: the three files are there, the sheet is Observador's and the
 * SHA-256 of the database and the settings are the ones the sheet lists. Used right after a backup
 * is written (the file is read back before it is handed over) and, later, before any restore.
 * Reads the database only to hash it, never into memory.
 */
object BackupVerifier {

    fun verify(file: File): BackupManifest {
        if (file.length() > BackupLimits.ZIP_BYTES) throw BackupException("El respaldo supera el límite de 256 MB.")
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw BackupException("El archivo no es un respaldo de Observador (no se puede abrir como .zip).")
        } catch (e: java.io.IOException) {
            throw BackupException("No se pudo leer el archivo.")
        }
        zip.use {
            val names = it.entries().asSequence().take(4).map { e -> e.name }.toList()
            val expected = setOf(BackupFormat.ENTRY_MANIFEST, BackupFormat.ENTRY_SETTINGS, BackupFormat.ENTRY_DB)
            if (names.size != expected.size || names.toSet() != expected) {
                throw BackupException("El respaldo tiene archivos repetidos, inesperados o incompletos.")
            }
            val manifestEntry = it.getEntry(BackupFormat.ENTRY_MANIFEST)
                ?: throw BackupException("El archivo no es un respaldo de Observador (falta ${BackupFormat.ENTRY_MANIFEST}).")
            val manifest = BackupFormat.decodeManifest(readText(it, manifestEntry))
                ?: throw BackupException("La ficha del respaldo no se puede leer: el archivo está dañado o no es de Observador.")
            if (manifest.format < 1 || manifest.dbVersion < 1 || manifest.rows.values.any { count -> count < 0 }) {
                throw BackupException("La ficha del respaldo contiene una versión o cantidad de registros inválida.")
            }
            if (manifest.format > BackupFormat.FORMAT_VERSION) {
                throw BackupException("Este respaldo es de una versión más nueva de Observador. Actualiza la app antes de usarlo.")
            }
            val actual = LinkedHashMap<String, String>()
            for (name in listOf(BackupFormat.ENTRY_DB, BackupFormat.ENTRY_SETTINGS)) {
                val entry = it.getEntry(name) ?: throw BackupException("Al respaldo le falta $name.")
                val limit = if (name == BackupFormat.ENTRY_DB) BackupLimits.DATABASE_BYTES else BackupLimits.TEXT_BYTES
                if (entry.size > limit) throw BackupException("Un archivo del respaldo supera el tamaño permitido.")
                actual[name] = try { it.getInputStream(entry).use { s -> BackupLimits.sha256(s, limit) } }
                catch (e: BackupLimits.LimitExceeded) { throw BackupException(e.message!!) }
            }
            val bad = BackupFormat.mismatches(manifest, actual)
            if (bad.isNotEmpty()) throw BackupException("El respaldo está dañado o incompleto (${bad.joinToString()}).")
            return manifest
        }
    }

    fun readSettingsText(file: File): String = ZipFile(file).use {
        val entry = it.getEntry(BackupFormat.ENTRY_SETTINGS) ?: throw BackupException("Al respaldo le falta ${BackupFormat.ENTRY_SETTINGS}.")
        readText(it, entry)
    }

    private fun readText(zip: ZipFile, entry: java.util.zip.ZipEntry): String {
        if (entry.size > BackupLimits.TEXT_BYTES) throw BackupException("Un archivo del respaldo es demasiado grande para ser de Observador.")
        val bytes = zip.getInputStream(entry).use { s ->
            val out = java.io.ByteArrayOutputStream()
            try { BackupLimits.copy(s, out, BackupLimits.TEXT_BYTES) }
            catch (e: BackupLimits.LimitExceeded) { throw BackupException(e.message!!) }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
