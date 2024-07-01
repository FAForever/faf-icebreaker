package com.faforever.icebreaker.web

import com.fasterxml.jackson.annotation.JsonValue

const val MEDIA_TYPE_JSON_API = "application/vnd.api+json"

sealed interface JsonListOrObject

data class JsonApiList(
    @JsonValue val jsonList: List<JsonApiObject<*>>,
) : JsonListOrObject

data class JsonApiObject<T>(
    val type: String,
    val id: String,
    val attributes: T,
) : JsonListOrObject

data class JsonApiResponse(
    val data: JsonListOrObject,
) {
    companion object {
        fun fromList(jsonList: List<JsonApiObject<*>>) = JsonApiResponse(JsonApiList(jsonList))

        fun fromObject(jsonObject: JsonApiObject<*>) = JsonApiResponse(jsonObject)
    }
}
