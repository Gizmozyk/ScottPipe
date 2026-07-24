package org.schabi.newpipe.kidmode.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidModeHmacTest {
    private val secret = "a secret".toByteArray()
    private val message = KidModeHmac.message("POST", "/approve/1", "")

    @Test
    fun `a signature verifies against the message it was signed for`() {
        val signature = KidModeHmac.sign(secret, message)
        assertTrue(KidModeHmac.verify(secret, message, signature))
    }

    @Test
    fun `a signature does not verify against a different message`() {
        val signature = KidModeHmac.sign(secret, message)
        val otherMessage = KidModeHmac.message("POST", "/approve/2", "")
        assertFalse(KidModeHmac.verify(secret, otherMessage, signature))
    }

    @Test
    fun `a signature does not verify with the wrong secret`() {
        val signature = KidModeHmac.sign(secret, message)
        assertFalse(KidModeHmac.verify("wrong secret".toByteArray(), message, signature))
    }

    @Test
    fun `verification is case-insensitive on the provided signature`() {
        val signature = KidModeHmac.sign(secret, message)
        assertTrue(KidModeHmac.verify(secret, message, signature.uppercase()))
    }

    @Test
    fun `signing is deterministic`() {
        assertTrue(KidModeHmac.sign(secret, message) == KidModeHmac.sign(secret, message))
    }
}
