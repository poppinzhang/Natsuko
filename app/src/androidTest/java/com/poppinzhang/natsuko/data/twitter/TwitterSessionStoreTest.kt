package com.poppinzhang.natsuko.data.twitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore and filesystem, synthetic cookies only. */
@RunWith(AndroidJUnit4::class)
class TwitterSessionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "natsuko.test.twitter-session.${UUID.randomUUID()}"
    private val file = File(context.noBackupFilesDir, "$alias.enc")
    private fun store() = EncryptedTwitterSessionStore(file, alias)
    private fun session() = checkNotNull(TwitterSession.fromCookieHeader(COOKIES))

    @After fun cleanup() { store().clear() }

    @Test fun encryptedCookiesSurviveNewStoreInstanceAndDoNotExposePlaintext() {
        assertNull(store().read())
        store().write(session())
        assertEquals(COOKIES, store().read()?.cookieHeader)
        val first = file.readBytes()
        assertFalse(first.toString(Charsets.UTF_8).contains("synthetic"))
        assertFalse(first.toString(Charsets.UTF_8).contains("auth_token"))
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile?.canonicalFile)
        val key = keyStore().getKey(alias, null)
        assertNotNull(key)
        assertNull(key.encoded) // The Keystore key cannot be exported to a backup.

        store().write(session())
        assertFalse(first.contentEquals(file.readBytes())) // Fresh GCM nonce for every write.
        assertEquals(COOKIES, store().read()?.cookieHeader)
    }

    @Test fun clearingDeletesFileAndKeyAndCannotBeRestoredByAnotherStore() {
        store().write(session())
        val encrypted = file.readBytes()
        store().clear()
        assertNull(store().read())
        assertFalse(file.exists())
        assertFalse(keyStore().containsAlias(alias))
        file.writeBytes(encrypted) // A stale ciphertext cannot resurrect a disconnected account.
        assertThrows(IOException::class.java) { store().read() }
        assertNull(store().read())
    }

    @Test fun corruptedTruncatedOversizedAndUnknownVersionDataIsRejected() {
        val corruptions: List<(ByteArray) -> ByteArray> = listOf(
            { it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() } },
            { it.copyOf(15) },
            { ByteArray(16_385) },
            { it.copyOf().also { bytes -> bytes[0] = 2 } },
        )
        for (corrupt in corruptions) {
            store().write(session())
            file.writeBytes(corrupt(file.readBytes()))
            assertThrows(IOException::class.java) { store().read() }
            assertNull(store().read())
            assertFalse(file.exists())
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object {
        const val COOKIES = "auth_token=synthetic-auth-cookie; ct0=synthetic-csrf-cookie"
    }
}
