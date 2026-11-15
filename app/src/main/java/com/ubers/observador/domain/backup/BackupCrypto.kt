package com.ubers.observador.domain.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 1.45.0: optional password for a backup or a full export. The file is the same .zip, encrypted
 * with AES-256-GCM in 1 MiB pieces, each with its own nonce and the piece number (and whether it
 * is the last) authenticated, so a changed, reordered or cut file is refused instead of restored.
 * The key comes from the password with PBKDF2-HMAC-SHA256 and a random salt.
 *
 * Without the password nobody, Observador included, can open the file: there is no recovery.
 *
 * Layout: «OBSCIFR1» · version (1 byte) · iterations (int) · salt (16) · nonce prefix (8) ·
 * piece size (int), then pieces of [length (int) · ciphertext + tag].
 */
object BackupCrypto {

    val MAGIC: ByteArray = "OBSCIFR1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val ITERATIONS = 120_000
    const val CHUNK = 1 shl 20
    const val MIN_PASSWORD = 8
    private const val TAG_BITS = 128
    private const val MAX_ITERATIONS = 5_000_000
    private const val HEADER = 8 + 1 + 4 + 16 + 8 + 4

    class WrongPassword : IOException("Contraseña incorrecta o archivo dañado. No se cambió nada.")
    class Damaged(message: String) : IOException(message)

    fun passwordProblem(password: CharArray): String? = when {
        password.size < MIN_PASSWORD -> "La contraseña debe tener al menos $MIN_PASSWORD caracteres."
        password.all { it == password[0] } -> "Usa una contraseña con caracteres distintos."
        else -> null
    }

    /** Whether [head] (the first bytes of a file) starts like an encrypted Observador file. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally { spec.clearPassword() }
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray = ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    private fun aad(header: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (last) 1 else 0).array()

    /** Wraps [out]: everything written is encrypted; [OutputStream.close] writes the last piece. */
    fun encrypting(out: OutputStream, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): OutputStream {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { write(MAGIC); writeByte(VERSION.toInt()); writeInt(iterations); write(salt); write(prefix); writeInt(CHUNK); flush() }
        }.toByteArray()
        val k = key(password, salt, iterations)
        return object : OutputStream() {
            private val data = DataOutputStream(out)
            private val buf = ByteArray(CHUNK)
            private var n = 0
            private var index = 0
            private var closed = false
            init { data.write(header) }

            private fun piece(last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                c.updateAAD(aad(header, index, last))
                val ct = c.doFinal(buf, 0, n)
                data.writeInt(ct.size); data.write(ct)
                index++; n = 0
                if (index < 0) throw IOException("Archivo demasiado grande para cifrar")
            }

            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(!closed)
                var o = off; var l = len
                while (l > 0) {
                    if (n == CHUNK) piece(last = false)
                    val m = minOf(l, CHUNK - n)
                    System.arraycopy(b, o, buf, n, m)
                    n += m; o += m; l -= m
                }
            }
            override fun flush() { data.flush() }
            override fun close() {
                if (closed) return
                closed = true
                try { piece(last = true); data.flush() } finally { buf.fill(0); out.close() }
            }
        }
    }

    /** Decrypts [input] as it is read. Throws [WrongPassword] or [Damaged]; never returns unchecked bytes. */
    fun decrypting(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER)
        try { data.readFully(header) } catch (e: EOFException) { throw Damaged("El archivo cifrado está incompleto.") }
        if (!isEncrypted(header)) throw Damaged("El archivo no está cifrado por Observador.")
        val h = DataInputStream(header.inputStream()).apply { skipBytes(MAGIC.size) }
        if (h.readByte() != VERSION) throw Damaged("Este archivo cifrado es de una versión más nueva de Observador.")
        val iterations = h.readInt()
        if (iterations !in 10_000..MAX_ITERATIONS) throw Damaged("La cabecera del archivo cifrado no es válida.")
        val salt = ByteArray(16).also { h.readFully(it) }
        val prefix = ByteArray(8).also { h.readFully(it) }
        val chunk = h.readInt()
        if (chunk != CHUNK) throw Damaged("La cabecera del archivo cifrado no es válida.")
        val k = key(password, salt, iterations)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val len = try { data.readInt() } catch (e: EOFException) { throw Damaged("El archivo cifrado está cortado.") }
                if (len < 16 || len > CHUNK + 16) throw Damaged("El archivo cifrado está dañado.")
                val ct = ByteArray(len)
                try { data.readFully(ct) } catch (e: EOFException) { throw Damaged("El archivo cifrado está cortado.") }
                // The last piece is the one shorter than a full piece; a full one may still be last
                val candidates = if (len < CHUNK + 16) listOf(true) else listOf(false, true)
                var opened: ByteArray? = null
                var last = false
                for (flag in candidates) {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                    c.updateAAD(aad(header, index, flag))
                    opened = try { c.doFinal(ct) } catch (e: AEADBadTagException) { null }
                    if (opened != null) { last = flag; break }
                }
                if (opened == null) throw if (index == 0) WrongPassword() else Damaged("El archivo cifrado está dañado.")
                index++
                if (last) {
                    done = true
                    if (data.read() != -1) throw Damaged("El archivo cifrado tiene datos de más.")
                }
                plain = opened; pos = 0
                return true
            }

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= plain.size) if (!next()) return -1
                val m = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, m)
                pos += m
                return m
            }

            override fun close() { input.close() }
        }
    }
}
