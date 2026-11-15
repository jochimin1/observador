package com.ubers.observador.domain.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Sanity bounds against a damaged or hostile file, enforced while streaming even when ZIP metadata
 * omits its size. 1.45.0: raised so a real history is never left out (the driver's rule: backups
 * carry everything); in practice the limit is the phone's free space, checked before writing.
 */
object BackupLimits {
    const val ZIP_BYTES = 4L * 1024 * 1024 * 1024
    /** Under 2^31 so it still fits ZIP's 32-bit size fields. */
    const val DATABASE_BYTES = 2_000_000_000L
    /** One data file (a day of screen recordings, of GPS signal…). */
    const val DATA_FILE_BYTES = 512L * 1024 * 1024
    const val TEXT_BYTES = 8L * 1024 * 1024
    const val FREE_MARGIN_BYTES = 16L * 1024 * 1024

    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0)
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            if (n == 0) continue
            if (n.toLong() > maxBytes - total) throw LimitExceeded()
            output.write(buffer, 0, n)
            total += n
        }
    }

    fun sha256(input: InputStream, maxBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        copy(input, object : OutputStream() {
            override fun write(b: Int) { digest.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
        }, maxBytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    class LimitExceeded : java.io.IOException("El respaldo supera el tamaño permitido.")
}
