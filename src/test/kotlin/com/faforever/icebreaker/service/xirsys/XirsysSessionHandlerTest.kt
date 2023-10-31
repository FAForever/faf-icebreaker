package com.faforever.icebreaker.service.xirsys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XirsysSessionHandlerTest {
    @Test
    fun `it should replace xirsys urls with global one and normalize the protocol`() {
        val input = "stun:fr-turn1.xirsys.com"

        val result = XirsysSessionHandler.normalizeAndReplaceUriWithGlobal(input)

        assertEquals("stun://global.xirsys.net", result)
    }
}
