package com.faforever.icebreaker.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue

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
    override val recipientId: Long,
    val candidates: List<CandidateDescriptor>,
) : EventMessage {

    enum class CandidateType(@JsonValue val jsonValue: String) {
        PEER_REFLEXIVE_CANDIDATE("prflx"),
        SERVER_REFLEXIVE_CANDIDATE("srflx"),
        RELAYED_CANDIDATE("relay"),
        HOST_CANDIDATE("host"),
        LOCAL_CANDIDATE("local"),
        STUN_CANDIDATE("stun"),
    }

    @JvmRecord
    data class CandidateDescriptor(
        val foundation: String,
        val protocol: String,
        val priority: Long,
        val ip: String?,
        val port: Int,
        val type: CandidateType,
        val generation: Int,
        val id: String,
        val relAddr: String?,
        val relPort: Int,
    ) : Comparable<CandidateDescriptor> {
        override operator fun compareTo(other: CandidateDescriptor) = (other.priority - priority).toInt()
    }
}
