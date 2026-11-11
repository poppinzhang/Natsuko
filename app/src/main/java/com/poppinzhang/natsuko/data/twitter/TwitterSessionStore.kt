package com.poppinzhang.natsuko.data.twitter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Blocking storage operations; callers dispatch these off the UI thread. */
interface TwitterSessionStore {
    fun read(): TwitterSession?
    fun write(session: TwitterSession)
    fun clear()
}

/** Only the two native metadata cookies are saved, encrypted and excluded from backup/transfer. */
class EncryptedTwitterSessionStore internal constructor(
    private val file: File,
    private val keyAlias: String,
) : TwitterSessionStore {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "twitter-session.enc"),
        "natsuko.twitter-session.v1",
    )

    private val atomicFile = AtomicFile(file)

    override fun read(): TwitterSession? = synchronized(storageLock) {
        if (!file.exists() && !File("${file.path}.bak").exists()) return@synchronized null
        val bytes = atomicFile.openRead().use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size !in MIN_BYTES..MAX_BYTES || bytes[0] != VERSION) {
            discardUnreadableSession()
        }
        val key = keyStore().getKey(keyAlias, null) as? SecretKey
        if (key == null) {
            discardUnreadableSession()
        }
        val session = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
            try {
                TwitterSession.fromCookieHeader(plaintext.toString(Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            null // An authentication failure proves that this ciphertext cannot be used.
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
        session ?: discardUnreadableSession()
    }

    override fun write(session: TwitterSession) = synchronized(storageLock) {
        val store = keyStore()
        val key = store.getKey(keyAlias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(
                    keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val plaintext = session.cookieHeader.toByteArray(Charsets.UTF_8)
        val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        check(cipher.iv.size == 12)
        val output = atomicFile.startWrite()
        try {
            output.write(byteArrayOf(VERSION))
            output.write(cipher.iv)
            output.write(ciphertext)
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    override fun clear() = synchronized(storageLock) {
        // Remove the key first: even a failed filesystem deletion cannot restore this session.
        keyStore().deleteEntry(keyAlias)
        atomicFile.delete()
        if (listOf(file, File("${file.path}.bak"), File("${file.path}.new")).any(File::exists)) {
            throw IOException("Could not remove saved X session")
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun discardUnreadableSession(): Nothing {
        clear()
        throw IOException("Saved X session is unreadable")
    }

    private companion object {
        // Serializes independent store instances during Activity/ViewModel replacement.
        val storageLock = Any()
        const val VERSION: Byte = 1
        const val MIN_BYTES = 1 + 12 + 16 + 1
        const val MAX_BYTES = 16_384
        val AAD = "Natsuko/X-session/v1".toByteArray(Charsets.UTF_8)
    }
}
