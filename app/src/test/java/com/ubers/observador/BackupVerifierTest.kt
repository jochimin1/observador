package com.ubers.observador

import com.ubers.observador.data.backup.BackupException
import com.ubers.observador.data.backup.BackupVerifier
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupLimits
import com.ubers.observador.domain.backup.BackupManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val database = "database fixture".toByteArray()
    private val settings = "{}".toByteArray()
    private fun archive(extra: String? = null, format: Int = 1, wrongHash: Boolean = false, omit: String? = null): java.io.File {
        val manifest = BackupManifest(format, "1.37.0", 46, 14, 123, mapOf("trips" to 0), mapOf(
            BackupFormat.ENTRY_DB to if (wrongHash) "0".repeat(64) else BackupFormat.sha256Hex(database),
            BackupFormat.ENTRY_SETTINGS to BackupFormat.sha256Hex(settings)
        ))
        return temp.newFile().also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                BackupFormat.ENTRY_DB to database,
                BackupFormat.ENTRY_SETTINGS to settings,
                BackupFormat.ENTRY_MANIFEST to BackupFormat.encodeManifest(manifest).toByteArray()
            )
            if (extra != null) entries[extra] = byteArrayOf(1)
            entries.filterKeys { it != omit }.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        } }
    }
    private fun refused(file: java.io.File): String {
        try { BackupVerifier.verify(file); fail("invalid archive accepted") }
        catch (e: BackupException) { return e.message.orEmpty() }
        error("unreachable")
    }

    @Test fun previousReleaseFormatRemainsReadable() { assertEquals("1.37.0", BackupVerifier.verify(archive()).appVersion) }
    @Test fun unexpectedPathsAreRefused() { assertTrue(refused(archive(extra = "../observador.db")).isNotEmpty()) }
    @Test fun missingFilesAreRefused() { assertTrue(refused(archive(omit = BackupFormat.ENTRY_DB)).isNotEmpty()) }
    @Test fun aNegativeFormatIsRefused() { assertTrue(refused(archive(format = -1)).contains("inválida")) }
    @Test fun aModifiedDatabaseHashIsRefused() { assertTrue(refused(archive(wrongHash = true)).contains("dañado")) }

    @Test fun anOversizedDeclaredDatabaseIsRefusedBeforeInflation() {
        val file = archive()
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var found = false
        for (offset in 0 until bytes.size - 46) {
            if (buffer.getInt(offset) != 0x02014b50) continue
            val length = buffer.getShort(offset + 28).toInt() and 0xffff
            if (offset + 46 + length > bytes.size) continue
            val name = String(bytes, offset + 46, length, Charsets.UTF_8)
            if (name == BackupFormat.ENTRY_DB) {
                buffer.putInt(offset + 24, (BackupLimits.DATABASE_BYTES + 1).toInt())
                found = true
                break
            }
        }
        assertTrue(found)
        file.writeBytes(bytes)
        assertTrue(refused(file).contains("tamaño"))
    }
}
