package com.example.medvoicetrainer.export

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Small password-encrypted envelope around the ZIP payload; no device-bound key is involved. */
object BackupCrypto {
    private val MAGIC = byteArrayOf(0x42, 0x53, 0x45, 0x42, 0x41, 0x43, 0x4b, 0x31) // BSEBACK1
    private const val CONTAINER_VERSION = 1
    private const val PBKDF2_ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /**
     * Buffer inserted on both sides of the cipher.
     *
     * [CipherOutputStream] has no buffer of its own: every call turns straight into one
     * `cipher.update()` (a JNI hop plus a fresh output array) and one write on whatever is below —
     * for a backup that is a SAF file descriptor, often a pipe to another process. ZipOutputStream
     * emits deflate output in 512-byte pieces, so an unbuffered chain cost ~2,000 syscalls and
     * 2,000 JNI calls per megabyte and made exports feel hung. Buffering on both sides of the
     * cipher collapses that to ~16 per megabyte.
     */
    private const val BUFFER_BYTES = 64 * 1024

    fun encrypt(password: CharArray, destination: OutputStream, writePlaintext: (OutputStream) -> Unit) {
        if (password.size < 8) {
            throw UserBackupException(
                UserBackupFailure.WEAK_PASSWORD,
                "Backup password must contain at least 8 characters",
            )
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val header = headerBytes(PBKDF2_ITERATIONS, salt, iv)
        val sink = BufferedOutputStream(destination, BUFFER_BYTES)
        sink.write(header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
        }
        // Closing order matters: the inner buffer flushes into the cipher, the cipher's close()
        // writes the GCM tag, and only then does `sink` flush the tail to the real destination.
        CipherOutputStream(sink, cipher).use { ciphertext ->
            BufferedOutputStream(ciphertext, BUFFER_BYTES).use(writePlaintext)
        }
    }

    fun decrypt(source: InputStream, password: CharArray, readPlaintext: (InputStream) -> Unit) {
        val data = DataInputStream(BufferedInputStream(source, BUFFER_BYTES))
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (e: EOFException) {
            throw UserBackupException(
                UserBackupFailure.NOT_A_BACKUP,
                "This is not a Bedside English backup file",
                e,
            )
        }
        if (!magic.contentEquals(MAGIC)) {
            throw UserBackupException(
                UserBackupFailure.NOT_A_BACKUP,
                "This is not a Bedside English backup file",
            )
        }
        val version = readHeaderInt(data)
        if (version > CONTAINER_VERSION) {
            throw UserBackupException(
                UserBackupFailure.NEWER_VERSION,
                "Unsupported encrypted backup version $version",
            )
        }
        if (version != CONTAINER_VERSION) {
            throw UserBackupException(
                UserBackupFailure.NOT_A_BACKUP,
                "Unsupported encrypted backup version $version",
            )
        }
        val iterations = readHeaderInt(data)
        if (iterations !in 100_000..1_000_000) {
            throw UserBackupException(UserBackupFailure.DAMAGED, "Invalid backup key parameters")
        }
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        try {
            data.readFully(salt)
            data.readFully(iv)
        } catch (e: EOFException) {
            throw UserBackupException(UserBackupFailure.DAMAGED, "Backup file is incomplete", e)
        }
        val header = headerBytes(iterations, salt, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
        }
        CipherInputStream(data, cipher).use { ciphertext ->
            val plaintext = BufferedInputStream(ciphertext, BUFFER_BYTES)
            // A wrong password only becomes visible when GCM finalizes, which happens the first
            // time the stream reports EOF — either inside readPlaintext or in the drain below. Both
            // surface as IOException(AEADBadTagException); translate either into a message the
            // learner can act on, and never let readPlaintext's own failures be relabelled.
            try {
                readPlaintext(plaintext)
                while (plaintext.read() != -1) Unit
            } catch (error: Throwable) {
                if (UserBackupException.failureOf(error) == UserBackupFailure.WRONG_PASSWORD) {
                    throw UserBackupException(
                        UserBackupFailure.WRONG_PASSWORD,
                        "The backup password is wrong, or the file was modified",
                        error,
                    )
                }
                throw error
            }
        }
    }

    private fun readHeaderInt(data: DataInputStream): Int = try {
        data.readInt()
    } catch (e: EOFException) {
        throw UserBackupException(
            UserBackupFailure.NOT_A_BACKUP,
            "This is not a Bedside English backup file",
            e,
        )
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun headerBytes(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC)
            data.writeInt(CONTAINER_VERSION)
            data.writeInt(iterations)
            data.write(salt)
            data.write(iv)
        }
        return out.toByteArray()
    }
}
