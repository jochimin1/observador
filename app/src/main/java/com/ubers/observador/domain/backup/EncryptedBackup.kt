package com.ubers.observador.domain.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated envelope; contains the complete ordinary ZIP, never a reduced backup. */
object EncryptedBackup {
    private val magic = "OBSENC01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(magic + salt + nonce)
            }
        } finally { bytes.fill(0) }
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Usa una contraseña de al menos 8 caracteres." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        output.write(magic + salt + nonce)
        transform(input, output, cipher(Cipher.ENCRYPT_MODE, password, salt, nonce))
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        fun read(n: Int): ByteArray = ByteArray(n).also { b ->
            var offset = 0
            while (offset < n) {
                val count = input.read(b, offset, n - offset)
                require(count > 0) { "Respaldo cifrado incompleto." }
                offset += count
            }
        }
        require(read(magic.size).contentEquals(magic)) { "Formato de respaldo cifrado desconocido." }
        val salt = read(16)
        val nonce = read(12)
        transform(input, output, cipher(Cipher.DECRYPT_MODE, password, salt, nonce))
    }

    private fun transform(input: InputStream, output: OutputStream, cipher: Cipher) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= BackupLimits.ZIP_BYTES + 64) { "Respaldo demasiado grande." }
            cipher.update(buffer, 0, n)?.let(output::write)
        }
        output.write(cipher.doFinal()) // Authentication MUST finish before a plaintext file is used.
        output.flush()
    }
}
