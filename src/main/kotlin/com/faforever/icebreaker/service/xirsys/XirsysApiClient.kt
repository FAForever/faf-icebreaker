package com.faforever.icebreaker.service.xirsys

import io.quarkus.rest.client.reactive.ClientQueryParam
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@ApplicationScoped
@RegisterRestClient
@Consumes(MediaType.APPLICATION_JSON)
interface XirsysApiClient {
    @GET
    @Path("/_ns/{namespace}/{environment}")
    @ClientQueryParam(name = "depth", value = ["10"])
    fun listChannel(
        @PathParam("namespace") namespace: String,
        @PathParam("environment") environment: String,
    ): String

    @PUT
    @Path("/_ns/{namespace}/{environment}/{channelName}")
    fun createChannel(
        @PathParam("namespace") namespace: String,
        @PathParam("environment") environment: String,
        @PathParam("channelName") channelName: String,
    ): String

    @DELETE
    @Path("/_ns/{namespace}/{environment}/{channelName}")
    fun deleteChannel(
        @PathParam("namespace") namespace: String,
        @PathParam("environment") environment: String,
        @PathParam("channelName") channelName: String,
    ): String

    @PUT
    @Path("/_turn/{namespace}/{environment}/{channelName}")
    fun requestIceServers(
        @PathParam("namespace") namespace: String,
        @PathParam("environment") environment: String,
        @PathParam("channelName") channelName: String,
        turnRequest: TurnRequest,
    ): String
}
