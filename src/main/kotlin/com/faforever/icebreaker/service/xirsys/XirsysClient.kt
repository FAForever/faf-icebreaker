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
interface XirsysClient {
    @GET
    @Path("/_ns")
    @ClientQueryParam(name = "depth", value = ["10"])
    fun listChannel(): XirsysResponse<List<String>>

    @PUT
    @Path("/_ns/{channelpath}")
    fun createChannel(@PathParam("channelpath") channelName: String): XirsysResponse<Map<String, String>>

    @DELETE
    @Path("/_ns/{channelpath}")
    fun deleteChannel(@PathParam("channelpath") channelName: String): XirsysResponse<Int>

    @PUT
    @Path("/_turn/{channelpath}")
    fun requestIceServers(@PathParam("channelpath") channelName: String, turnRequest: TurnRequest): XirsysResponse<TurnResponse>
}
