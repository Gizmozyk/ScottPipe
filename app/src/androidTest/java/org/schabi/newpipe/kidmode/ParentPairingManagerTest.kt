package org.schabi.newpipe.kidmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.testUtil.TestDatabase

/**
 * Instrumented rather than a plain JVM test because [ParentPairingManager] relies on the real
 * Android Keystore ("AndroidKeyStore" provider), which only exists on-device/on-emulator -- same
 * reason as `KidModePinManagerTest`. Unlike `ParentPairingDAOTest` (which only ever stores opaque
 * `"iv:ciphertext"` strings), this exercises the real Keystore encrypt/decrypt round trip.
 */
class ParentPairingManagerTest {
    private lateinit var manager: ParentPairingManager

    @Before
    fun setUp() {
        TestDatabase.createReplacingNewPipeDatabase()
        manager = ParentPairingManager(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun savedSecretDecryptsBackToThePlaintext() {
        val secret = "a-shared-secret".toByteArray()
        val uid = manager.save("kid-device-1", "Kid's phone", "192.168.1.42", 46821, secret)

        val pairing = manager.getAll().blockingFirst().first { it.uid == uid }

        assertArrayEquals(secret, manager.decryptSecret(pairing))
    }

    @Test
    fun storedSecretIsNotThePlaintextOnDisk() {
        val secret = "a-shared-secret".toByteArray()
        val uid = manager.save("kid-device-1", "Kid's phone", "192.168.1.42", 46821, secret)

        val pairing = manager.getAll().blockingFirst().first { it.uid == uid }

        assertNotEquals(String(secret), pairing.sharedSecret)
    }

    @Test
    fun deleteRemovesThePairing() {
        val uid = manager.save("kid-device-1", "Kid's phone", "192.168.1.42", 46821, "secret".toByteArray())

        assertTrue(manager.delete(uid))
        assertTrue(manager.getAll().blockingFirst().isEmpty())
    }

    @Test
    fun deletingAnAlreadyDeletedPairingReturnsFalse() {
        val uid = manager.save("kid-device-1", "Kid's phone", "192.168.1.42", 46821, "secret".toByteArray())
        manager.delete(uid)

        assertFalse(manager.delete(uid))
    }

    @Test
    fun getAllListsAllSavedPairings() {
        manager.save("kid-device-1", "Kid's phone", "192.168.1.42", 46821, "secret1".toByteArray())
        manager.save("kid-device-2", "Other kid's phone", "192.168.1.43", 46821, "secret2".toByteArray())

        assertEquals(2, manager.getAll().blockingFirst().size)
    }
}
