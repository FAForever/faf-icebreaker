package com.faforever.icebreaker.service.loki

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@ApplicationScoped
@RegisterRestClient(configKey = "loki")
@Consumes(MediaType.APPLICATION_JSON)
interface LokiApiClient {

    data class LokiLogPushRequest(val streams: List<Log>) {
        data class Log(val stream: Map<String, String>, val values: List<Line>) {
            @JsonSerialize(using = LogLineSerializer::class) // Apply the custom serializer
            data class Line(
                @JsonFormat(shape = JsonFormat.Shape.STRING) val timestamp: Long,
                val message: String,
                val metaData: Map<String, Any?>,
            )
        }
    }

    class LogLineSerializer : JsonSerializer<LokiLogPushRequest.Log.Line>() {
        override fun serialize(
            value: LokiLogPushRequest.Log.Line,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            val mapper = gen.codec as ObjectMapper

            val arrayNode = ArrayNode(JsonNodeFactory.instance)

            // The order is crucial!
            arrayNode.add(value.timestamp.toString())
            arrayNode.add(value.message)
            arrayNode.add(mapper.valueToTree<ObjectNode>(value.metaData))

            gen.writeTree(arrayNode)
        }
    }

    @POST @Path("/loki/api/v1/push") fun push(request: LokiLogPushRequest)
}
