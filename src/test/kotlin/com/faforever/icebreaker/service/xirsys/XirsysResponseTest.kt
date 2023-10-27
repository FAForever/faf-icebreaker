package com.faforever.icebreaker.service.xirsys

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XirsysResponseTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun parseError() {
        val result: XirsysResponse.Error<List<String>> = objectMapper.readValue("""{"v":"unauthorized","s":"error"}""")
        assertEquals("unauthorized", result.code)

        val interfaced: XirsysResponse<List<String>> = objectMapper.readValue("""{"v":"unauthorized","s":"error"}""")
        assertEquals(result, interfaced)
    }

    @Test
    fun parseSuccess() {
        val result: XirsysResponse.Success<List<String>> = objectMapper.readValue(
            """
            {
             "v":[
                "my",
                "my/channel",
                "my/channel/path"
              ],
             "s": "ok"
            }
            """.trimIndent(),
        )
        assertEquals(listOf("my", "my/channel", "my/channel/path"), result.data)
    }
}
