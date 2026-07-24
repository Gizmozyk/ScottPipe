package org.schabi.newpipe.kidmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KidModePairingQrCodeTest {
    private val payload = KidModePairingQrPayload(host = "192.168.7.145", port = 46821, code = "482913")

    @Test
    fun `encoding then decoding returns the original payload`() {
        val decoded = KidModePairingQrCode.decode(KidModePairingQrCode.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun `decoding non-JSON garbage returns null`() {
        assertNull(KidModePairingQrCode.decode("not json at all"))
    }

    @Test
    fun `decoding valid JSON with a different shape returns null`() {
        assertNull(KidModePairingQrCode.decode("""{"foo": "bar"}"""))
    }

    @Test
    fun `decoding a payload with a mismatched type returns null`() {
        val json = """{"type": "something-else", "host": "192.168.7.145", "port": 46821, "code": "482913"}"""
        assertNull(KidModePairingQrCode.decode(json))
    }
}
