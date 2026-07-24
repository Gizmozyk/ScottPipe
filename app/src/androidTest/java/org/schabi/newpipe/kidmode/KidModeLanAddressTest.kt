package org.schabi.newpipe.kidmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class KidModeLanAddressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun currentIpv4AddressReturnsAPlausibleNonLoopbackAddress() {
        // Smoke test, not a correctness proof -- the emulator's own NAT'd interface (typically
        // 10.0.2.15) is the only address this process can observe about itself.
        val address = KidModeLanAddress.currentIpv4Address(context)

        assertNotNull(address)
        assertFalse(address!!.startsWith("127."))
        assertFalse(address.startsWith("169.254."))
    }
}
