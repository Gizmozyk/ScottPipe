package org.schabi.newpipe.kidmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KidModeQrCodeGeneratorTest {
    @Test
    fun generateReturnsABitmapOfTheRequestedSize() {
        val text = KidModePairingQrCode.encode(
            KidModePairingQrPayload(host = "192.168.7.145", port = 46821, code = "482913")
        )

        val bitmap = KidModeQrCodeGenerator.generate(text, sizePx = 250)

        assertNotNull(bitmap)
        assertEquals(250, bitmap!!.width)
        assertEquals(250, bitmap.height)
    }
}
