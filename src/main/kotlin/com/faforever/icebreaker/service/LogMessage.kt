package com.faforever.icebreaker.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.ZonedDateTime

@JsonDeserialize(using = LogMessageDeserializer::class)
data class LogMessage(
    val timestamp: ZonedDateTime,
    val message: String,
    val metaData: Map<String, Any?>,
)

class LogMessageDeserializer : JsonDeserializer<LogMessage>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): LogMessage {
        val node = p.codec.readTree<ObjectNode>(p)

        val timestamp = ZonedDateTime.parse(node["timestamp"].asText())
        val message = node["message"].asText()

        // Remove known fields to isolate metadata
        node.remove("timestamp")
        node.remove("message")

        val metaData = mutableMapOf<String, Any?>()
        node.fields().forEachRemaining { entry ->
            metaData[entry.key] = entry.value
        }

        return LogMessage(timestamp, message, metaData)
    }
}
