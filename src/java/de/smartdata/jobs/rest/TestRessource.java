/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package de.smartdata.jobs.rest;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import de.smartdata.jobs.services.NotificationService;
/**
 *
 * @author steidlemax
 */
@Path("/test")
public class TestRessource {
    @POST
    @Path("/subscribe/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response subscribe(@PathParam("id") String id) {
        NotificationService service = new NotificationService(Integer.parseInt(id));
        JsonObject jo = service.sendNotificationToAllMember();
        return Response.status(Response.Status.CREATED)
                .entity(jo)
                .build();
    }
    
}
