package com.faforever.icebreaker.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = CandidatesMessage::class, name = "candidates"),
    JsonSubTypes.Type(value = ConnectedMessage::class, name = "connected"),
)
@JsonInclude(JsonInclude.Include.ALWAYS)
interface EventMessage {
    val gameId: Long
    val senderId: Long
    val recipientId: Long?
}

@JvmRecord
data class ConnectedMessage(
    override val gameId: Long,
    override val senderId: Long,
    override val recipientId: Long? = null,
) : EventMessage

@JvmRecord
data class CandidatesMessage(
    override val gameId: Long,
    override val senderId: Long,
    override val recipientId: Long?,
    // Could be specified more, but these fields are just passed through
    val session: ObjectNode,
    val candidates: ArrayNode,
) : EventMessage
