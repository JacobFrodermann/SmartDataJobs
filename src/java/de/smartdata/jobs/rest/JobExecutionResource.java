package de.smartdata.jobs.rest;

import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.smartdata.jobs.actions.ActionDocumentation;
import de.smartdata.jobs.threads.JobRunner;
import de.smartdata.jobs.threads.JobRunnerException;
import de.smartdata.jobs.data.SmartDataAccessor;
import de.smartdata.jobs.data.SmartDataAccessorException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

@Path("/jobexecution")
public class JobExecutionResource {

    private JobRunner jobrunner;

    public JobExecutionResource() {
        this.jobrunner = JobRunner.getInstance();
    }

    @GET
    @Path("/getActionsDescription")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "getActionsDescription",
            description = "Get the documentation for all available actions and their configuration possibilities. Note: Actual configuration can be accessed over SmartData default methods.")
    @APIResponse(
            responseCode = "200",
            description = "Action documentation",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"actions\" : [{\"name\" :  \"MailAction\", \"description\" : \"Does...\"}]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not get documentation: Because of ... \"]}"))
    public Response getActionsDescription() {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        ActionDocumentation ad = new ActionDocumentation();
        rob.add(ad.getDocumentation());
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("/startAll")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "startAll",
            description = "Starts all defined jobs.")
    @APIResponse(
            responseCode = "200",
            description = "Jobs started",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"jobs\" : [{\"id\" :  1, \"name\" : \"job1\"}]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not get jobs: Because of ... \"]}"))
    public Response startAll(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage) {

        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        if (smartdataurl == null) {
            smartdataurl = "http://localhost:8080/SmartData";
        }
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        if (collection == null) {
            collection = "datajobs";
        }
        if (storage == null) {
            storage = "public";
        }
        try {
            JsonArray jobsinfos = jobrunner.startAll(smartdataurl, collection, storage);
            rob.add("jobs", jobsinfos);
            rob.setStatus(Response.Status.OK);
        } catch (JobRunnerException jrex) {
            rob.addException(jrex);
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
        }
        return rob.toResponse();
    }

    @GET
    @Path("/start")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "start one job",
            description = "Starts the job with the given id.")
    @APIResponse(
            responseCode = "200",
            description = "Job started",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"jobs\" : [{\"id\" :  1, \"name\" : \"job1\"}]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not get jobs: Because of ... \"]}"))
    public Response start(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        if (smartdataurl == null) {
            smartdataurl = "http://localhost:8080/SmartData";
        }
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        if (collection == null) {
            collection = "datajobs";
        }
        if (storage == null) {
            storage = "public";
        }
        if (name != null) {
            try {
                Long foundId = getIdByName(smartdataurl, storage, collection, name);
                if (foundId != null) {
                    id = foundId;
                } else {
                    rob.setStatus(Response.Status.NOT_FOUND);
                    rob.addErrorMessage("No job found with name >" + name + "<.");
                    return rob.toResponseStream();
                }
            } catch (SmartDataAccessorException ex) {
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                rob.addErrorMessage("Error fetching job by name: " + ex.getMessage());
                return rob.toResponseStream();
            }
        }
        try {
            // Guard: refuse to start if already running - prevents parallel duplicate processes.
            if (jobrunner.jobIsRunning(smartdataurl, collection, storage, id)) {
                rob.setStatus(Response.Status.BAD_REQUEST);
                rob.addErrorMessage("Can't start job >" + id + "< because job is already running.");
                return rob.toResponseStream();
            }
            JsonArray jobsinfos = jobrunner.start(smartdataurl, collection, storage, id, null);
            rob.add("jobs", jobsinfos);
            rob.setStatus(Response.Status.OK);
        } catch (JobRunnerException jrex) {
            rob.addException(jrex);
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
        }
        return rob.toResponseStream();
    }

    @GET
    @Path("/stop")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "stop one job",
            description = "Stops one running job.")
    @APIResponse(responseCode = "200", description = "Job stopped")
    @APIResponse(
            responseCode = "400",
            description = "Job is not running",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Job is not running \"]}"))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not stop jobs: Because of ... \"]}"))
    public Response stop(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        if (smartdataurl == null) {
            smartdataurl = "http://localhost:8080/SmartData";
        }
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        if (collection == null) {
            collection = "datajobs";
        }
        if (storage == null) {
            storage = "public";
        }
        if (name != null) {
            try {
                Long foundId = getIdByName(smartdataurl, storage, collection, name);
                if (foundId != null) {
                    id = foundId;
                } else {
                    rob.setStatus(Response.Status.NOT_FOUND);
                    rob.addErrorMessage("No job found with name >" + name + "<.");
                    return rob.toResponse();
                }
            } catch (SmartDataAccessorException ex) {
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                rob.addErrorMessage("Error fetching job by name: " + ex.getMessage());
                return rob.toResponse();
            }
        }
        if (!jobrunner.jobIsRunning(smartdataurl, collection, storage, id)) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("Can't stop job >" + id + "< because job is not running.");
            return rob.toResponse();
        }
        rob.add("result", jobrunner.stopJob(smartdataurl, collection, storage, id));
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("/stopAll")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "stopAll", description = "Stops all running jobs.")
    @APIResponse(responseCode = "200", description = "Jobs stopped")
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not stop jobs: Because of ... \"]}"))
    public Response stopAll() {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        rob.add("result", jobrunner.stopAllJobs());
        return rob.toResponse();
    }

    @GET
    @Path("/activate")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "activate one job", description = "Activates one stopped job.")
    @APIResponse(responseCode = "200", description = "Job activated")
    @APIResponse(
            responseCode = "400",
            description = "Job is already active",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Job is already active \"]}"))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not activate jobs: Because of ... \"]}"))
    public Response activate(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        if (smartdataurl == null) {
            smartdataurl = "http://localhost:8080/SmartData";
        }
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        if (collection == null) {
            collection = "datajobs";
        }
        if (storage == null) {
            storage = "public";
        }
        if (name != null) {
            try {
                Long foundId = getIdByName(smartdataurl, storage, collection, name);
                if (foundId != null) {
                    id = foundId;
                } else {
                    rob.setStatus(Response.Status.NOT_FOUND);
                    rob.addErrorMessage("No job found with name >" + name + "<.");
                    return rob.toResponse();
                }
            } catch (SmartDataAccessorException ex) {
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                rob.addErrorMessage("Error fetching job by name: " + ex.getMessage());
                return rob.toResponse();
            }
        }
        try {
            SmartDataAccessor sda = new SmartDataAccessor(smartdataurl);
            JsonObject params = Json.createObjectBuilder()
                    .add("storage", storage).add("collection", collection)
                    .add("filter", "id,eq," + id).add("size", 1).build();
            JsonObjectBuilder resultBuilder = Json.createObjectBuilder();
            JsonArray data = sda.fetchData(params, resultBuilder);
            if (!data.isEmpty()) {
                JsonObject job = data.getJsonObject(0);
                if (job.containsKey("active") && job.getBoolean("active")) {
                    rob.setStatus(Response.Status.BAD_REQUEST);
                    rob.addErrorMessage("Job with id >" + id + "< is already active.");
                    return rob.toResponse();
                }
            }
        } catch (SmartDataAccessorException ex) {
            rob.addException(ex);
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            return rob.toResponse();
        }
        rob.add("result", jobrunner.setActiveOfJob(smartdataurl, collection, storage, id, true));
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("/deactivate")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "deactivate one job", description = "Deactivates one stopped job.")
    @APIResponse(responseCode = "200", description = "Job deactivated")
    @APIResponse(
            responseCode = "400",
            description = "Job is already inactive",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Job is already inactive \"]}"))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not deactivate jobs: Because of ... \"]}"))
    public Response deactivate(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        if (smartdataurl == null) {
            smartdataurl = "http://localhost:8080/SmartData";
        }
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        if (collection == null) {
            collection = "datajobs";
        }
        if (storage == null) {
            storage = "public";
        }
        if (name != null) {
            try {
                Long foundId = getIdByName(smartdataurl, storage, collection, name);
                if (foundId != null) {
                    id = foundId;
                } else {
                    rob.setStatus(Response.Status.NOT_FOUND);
                    rob.addErrorMessage("No job found with name >" + name + "<.");
                    return rob.toResponse();
                }
            } catch (SmartDataAccessorException ex) {
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                rob.addErrorMessage("Error fetching job by name: " + ex.getMessage());
                return rob.toResponse();
            }
        }
        try {
            SmartDataAccessor sda = new SmartDataAccessor(smartdataurl);
            JsonObject params = Json.createObjectBuilder()
                    .add("storage", storage).add("collection", collection)
                    .add("filter", "id,eq," + id).add("size", 1).build();
            JsonObjectBuilder resultBuilder = Json.createObjectBuilder();
            JsonArray data = sda.fetchData(params, resultBuilder);
            if (!data.isEmpty()) {
                JsonObject job = data.getJsonObject(0);
                if (job.containsKey("active") && !job.getBoolean("active")) {
                    rob.setStatus(Response.Status.BAD_REQUEST);
                    rob.addErrorMessage("Job with id >" + id + "< is already inactive.");
                    return rob.toResponse();
                }
            }
        } catch (SmartDataAccessorException ex) {
            rob.addException(ex);
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            return rob.toResponse();
        }
        rob.add("result", jobrunner.setActiveOfJob(smartdataurl, collection, storage, id, false));
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("/activateAndStart")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "activateAndStart",
            description = "Activates the job with the given id (if not already active) and then starts it. "
            + "Combines /activate and /start in a single call.")
    @APIResponse(
            responseCode = "200",
            description = "Job activated and started",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"activated\": true, \"started\": true, \"jobs\": [{\"id\": 1}]}"
            ))
    @APIResponse(
            responseCode = "400",
            description = "Job is already running",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\": [\"Can't start job >1< because job is already running.\"]}"))
    @APIResponse(
            responseCode = "500",
            description = "Error message",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\": [\"Could not activate or start job: Because of ...\"]}"))
    public Response activateAndStart(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {

        Response activateResponse = activate(smartdataurl, collection, storage, id, name);
        if (activateResponse.getStatus() >= 500) {
            return activateResponse;
        }

        return start(smartdataurl, collection, storage, id, name);
    }

    @GET
    @Path("/deactivateAndStop")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "deactivateAndStop",
            description = "Stops the job with the given id (if currently running) and then deactivates it. "
            + "Combines /stop and /deactivate in a single call.")
    @APIResponse(
            responseCode = "200",
            description = "Job stopped and deactivated",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"stopped\": true, \"deactivated\": true}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error message",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\": [\"Could not stop or deactivate job: Because of ...\"]}"))
    public Response deactivateAndStop(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {

        Response stopResponse = stop(smartdataurl, collection, storage, id, name);
        if (stopResponse.getStatus() >= 500) {
            return stopResponse;
        }

        return deactivate(smartdataurl, collection, storage, id, name);
    }

    @GET
    @Path("/startOnce")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "startOnce",
            description = "Activates and starts the job with the given id, "
            + "then stops and deactivates it. Combines /activateAndStart and /deactivateAndStop in a single call.")
    @APIResponse(
            responseCode = "200",
            description = "Job executed once and deactivated",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"result\": \"Job >1< executed once and deactivated.\"}"
            ))
    @APIResponse(
            responseCode = "400",
            description = "Job is already running",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\": [\"Can't start job >1< because job is already running.\"]}"))
    @APIResponse(
            responseCode = "500",
            description = "Error message",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\": [\"Could not execute job once: Because of ...\"]}"))
    public Response startOnce(
            @Parameter(description = "URL to smartdata instance") @QueryParam("smartdataurl") String smartdataurl,
            @Parameter(description = "Collection name where tasks are defined") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name where tasks are defined") @QueryParam("storage") String storage,
            @Parameter(description = "Jobs id") @QueryParam("id") Long id,
            @Parameter(description = "Jobs name") @QueryParam("name") String name) {

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        // Resolve name -> id once so both subsequent calls use the numeric id
        if (name != null) {
            try {
                Long foundId = getIdByName(smartdataurl, storage, collection, name);
                if (foundId != null) {
                    id = foundId;
                } else {
                    rob.setStatus(Response.Status.NOT_FOUND);
                    rob.addErrorMessage("No job found with name >" + name + "<.");
                    return rob.toResponse();
                }
            } catch (SmartDataAccessorException ex) {
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                rob.addErrorMessage("Error fetching job by name: " + ex.getMessage());
                return rob.toResponse();
            }
        }

        // Step 1: activate and start the job (synchronous - returns after job has finished)
        Response startResponse = activateAndStart(smartdataurl, collection, storage, id, null);
        if (startResponse.getStatus() >= 400) {
            return startResponse;
        }

        // Step 2: stop and deactivate the job
        Response stopResponse = deactivateAndStop(smartdataurl, collection, storage, id, null);
        if (stopResponse.getStatus() >= 500) {
            return stopResponse;
        }

        rob.add("result", "Job >" + id + "< executed once and deactivated.");
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    private Long getIdByName(String smartdataurl, String storage, String collection, String name)
            throws SmartDataAccessorException {
        SmartDataAccessor sda = new SmartDataAccessor(smartdataurl);
        JsonObject params = Json.createObjectBuilder()
                .add("storage", storage).add("collection", collection)
                .add("filter", "name,eq," + name).add("size", 1).build();
        JsonObjectBuilder resultBuilder = Json.createObjectBuilder();
        JsonArray data = sda.fetchData(params, resultBuilder);
        if (!data.isEmpty()) {
            JsonObject record = data.getJsonObject(0);
            if (record.containsKey("id")) {
                return record.getJsonNumber("id").longValue();
            }
        }
        return null;
    }
}
