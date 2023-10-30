package com.faforever.icebreaker.web

import com.faforever.icebreaker.service.Session

data class JsonApiResponse(
    val data: DataObject<*>,
) {
    data class DataObject<T>(
        val type: String,
        val id: String,
        val attributes: T,
    )
}

fun Session.toJsonApiResponse() =
    JsonApiResponse(
        data = JsonApiResponse.DataObject(
            type = "iceSession",
            id = id,
            attributes = mapOf<String, Any>(
                "servers" to servers,
            ),
        ),
    )
