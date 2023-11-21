package com.faforever.icebreaker.service.xirsys

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "s")
@JsonSubTypes(
    JsonSubTypes.Type(value = XirsysResponse.Success::class, name = "ok"),
    JsonSubTypes.Type(value = XirsysResponse.Error::class, name = "error"),
)
sealed interface XirsysResponse<T : Any> {
    data class Success<T : Any> (@JsonProperty("v") val data: T) : XirsysResponse<T>
    data class Error<T : Any>(@JsonProperty("v") val code: String) : XirsysResponse<T>
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TurnRequest(
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val expire: Long = 30,
) {
    val format: String = "urls"
}

data class TurnResponse(
    val iceServers: IceServers,
) {
    data class IceServers(
        val username: String,
        val urls: List<String>,
        val credential: String,
    )
}
