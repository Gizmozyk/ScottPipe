package org.schabi.newpipe.kidmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumented rather than a plain JVM test because [KidModePinManager] relies on the real
 * Android Keystore ("AndroidKeyStore" provider), which only exists on-device/on-emulator.
 */
class KidModePinManagerTest {
    private lateinit var pinManager: KidModePinManager

    @Before
    fun createPinManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pinManager = KidModePinManager(context)
        pinManager.clearPin()
    }

    @After
    fun tearDown() {
        pinManager.clearPin()
    }

    @Test
    fun noPinSetInitially() {
        assertFalse(pinManager.isPinSet())
    }

    @Test
    fun settingAPinMakesItSet() {
        pinManager.setPin("1234")
        assertTrue(pinManager.isPinSet())
    }

    @Test
    fun correctPinVerifies() {
        pinManager.setPin("1234")
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun wrongPinDoesNotVerify() {
        pinManager.setPin("1234")
        assertFalse(pinManager.verifyPin("0000"))
    }

    @Test
    fun verifyingWithNoPinSetReturnsFalse() {
        assertFalse(pinManager.verifyPin("1234"))
    }

    @Test
    fun clearingPinRemovesIt() {
        pinManager.setPin("1234")
        pinManager.clearPin()

        assertFalse(pinManager.isPinSet())
        assertFalse(pinManager.verifyPin("1234"))
    }
}
