package com.faforever.icebreaker.service

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class GameResultMessage private constructor(
    val gameId: Long,
) {
    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonProperty("game_id") gameId: JsonNode?): GameResultMessage {
            require(gameId != null && gameId.isIntegralNumber && gameId.canConvertToLong()) {
                "game_id must be a 64-bit integer"
            }
            return GameResultMessage(gameId.longValue())
        }
    }
}
