package de.smartdata.jobs.actions;

import de.fhbielefeld.scl.rest.util.JSONFormatter;
import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import de.smartdata.jobs.data.SmartDataAccessor;
import de.smartdata.jobs.data.SmartDataAccessorException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Action for writeing data to a REST-API
 *
 * @author Florian Fehring
 */
public class WriteDataAction extends AbstractNetworkAction {

    private String url;
    private String smartdataurl;
    private String requestMethod = "POST";
    private String username;
    private String userpwd;
    private JsonObject requestBody;
    private String preJobField;
    private String syncedField;

    @Override
    public JsonArray getParamsDescription() {
        return Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "url")
                        .add("type", "url")
                        .add("description", "Target REST API URL (http/https).")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "smartdataurl")
                        .add("type", "url")
                        .add("description", "Target SmartData instance")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "collection")
                        .add("type", "collection")
                        .add("description", "Target collection (when useing Smartdata).")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "storage")
                        .add("type", "storage")
                        .add("description", "Target storage (when using SmartData).")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "requestmethod")
                        .add("type", "string")
                        .add("description", "HTTP method to use (POST or PUT). Default is POST.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "username")
                        .add("type", "string")
                        .add("description", "Optional username for Basic Auth.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "userpwd")
                        .add("type", "string")
                        .add("description", "Optional password for Basic Auth.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "body")
                        .add("type", "json")
                        .add("description", "JSON body for the request.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "urlparams")
                        .add("type", "string")
                        .add("description", "Optional JSON object with key-value pairs for URL parameters.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "prejobfield")
                        .add("type", "string")
                        .add("description", "Optional name of the field in previous job result to use as input body or for merging.")
                        .add("optional", true))
                .add(Json.createObjectBuilder()
                        .add("name", "syncedField")
                        .add("type", "string")
                        .add("description", "Name of the field in the original datasets that should be set to true when dataset was succsessfully written.")
                        .add("optional", true))
                .build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        this.url = params.getString("url", null);
        this.smartdataurl = params.getString("smartdataurl", null);
        if (this.url == null && this.smartdataurl == null) {
            throw new ActionConfigurationException("Parameter >url< is missing.");
        }

        this.requestMethod = params.getString("requestmethod", "POST").toUpperCase();
        if (!this.requestMethod.equals("POST") && !this.requestMethod.equals("PUT")) {
            throw new ActionConfigurationException("Only POST or PUT methods are allowed for WriteDataAction.");
        }

        this.username = params.getString("username", null);
        this.userpwd = params.getString("userpwd", null);

        if (params.containsKey("body")) {
            String bodyRaw = params.getString("body", "").trim();
            if (!bodyRaw.isEmpty()) {
                try (JsonReader reader = Json.createReader(new StringReader(bodyRaw))) {
                    JsonStructure parsed = reader.read();
                    if (parsed.getValueType() != JsonValue.ValueType.OBJECT) {
                        throw new ActionConfigurationException("Provided body must be a valid JSON object.");
                    }
                    this.requestBody = parsed.asJsonObject();
                } catch (JsonException e) {
                    throw new ActionConfigurationException("Invalid JSON in body: " + e.getMessage());
                }
            }
        }

        if (params.containsKey("urlparams")) {
            urlParams = params.getString("urlparams");
        }

        this.preJobField = params.getString("prejobfield", null);
        this.syncedField = params.getString("syncedField", null);
    }

    @Override
    public JsonObject call() {
        long callStart = System.currentTimeMillis();
        JsonObjectBuilder result = Json.createObjectBuilder();

        // Create combined JsonValue from previous result (if exists) and configured request body
        JsonValue effectivePayload;
        try {
            effectivePayload = getEffectivePayload(preJobField, requestBody);
        } catch (IllegalStateException ex) {
            result.add("error", ex.getLocalizedMessage());
            return result.build();
        }
        // Mark sets as syced sets
        if (syncedField != null) {
            effectivePayload = markAsSynced(effectivePayload, syncedField);
        }

        if (this.smartdataurl != null) {
            result.add("saveMethod", "WriteDataAction/SmartDataAccessor");
            // Parse parameter
            JsonObject params = null;
            if (this.urlParams != null && !this.urlParams.isEmpty()) {
                JsonParser parser = Json.createParser(new StringReader(this.urlParams));
                parser.next();
                params = parser.getObject();
                if (params == null) {
                    result.add("error", "Could not parse urlParams for SmartData parameters in WriteDataAction.");
                    return result.build();
                }
            }
            // Handling for SmartData API
            SmartDataAccessor sda = new SmartDataAccessor(this.smartdataurl);
            try {
                result.add("calledWithBody", effectivePayload);
                sda.saveData(params, effectivePayload, result);
                result.add("state", "finished");
            } catch (SmartDataAccessorException ex) {
                result.add("state", "failed");
                result.add("stateMsg", ex.getClass().getSimpleName() + "Exception occured:" + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
            }
        } else {
            result.add("saveMethod", "WriteDataAction/HTTP URL connection");
            // Handling for URL API
            try {
                String fullUrl = buildUrl(this.url, this.urlParams);
                result.add("calledUrl", fullUrl);

                result.add("calledWithBody", effectivePayload);

                // Erstelle HttpClient
                HttpClient client = HttpClientProvider.getClient();

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", getUserAgent())
                        .method(requestMethod.toUpperCase(), HttpRequest.BodyPublishers.ofString(effectivePayload.toString()));

                // Basic Auth
                if (username != null && userpwd != null) {
                    String credentials = Base64.getEncoder().encodeToString((username + ":" + userpwd).getBytes(StandardCharsets.UTF_8));
                    requestBuilder.header("Authorization", "Basic " + credentials);
                }
                long t1 = System.currentTimeMillis();
                HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                long t2 = System.currentTimeMillis();
                result.add("http_request_time_ms", t2 - t1);
                int status = response.statusCode();
                result.add("httpStatus", status);

                String responseBody = response.body();
                String contentType = Optional.ofNullable(response.headers().firstValue("Content-Type").orElse("")).orElse("");

                if (contentType.contains("application/json")) {
                    JsonValue parsed = JSONFormatter.tryParseOrRaw(responseBody);
                    if (parsed.getValueType() == JsonValue.ValueType.OBJECT) {
                        parsed.asJsonObject().forEach(result::add);
                    } else {
                        result.add("response", parsed);
                    }
                } else {
                    result.add("response", Json.createValue(responseBody));
                }
                // Status-Auswertung
                if (status == 304 || status == 204) {
                    result.add("state", "nothing todo");
                } else if (status < 200 || status >= 400) {
                    result.add("state", "failed");
                    result.add("statemsg", "Could not write data. API returned: " + status);
                } else {
                    result.add("state", "finished");

                    // Rückgabe an ursprüngliche Datenquelle
                    JsonObject prevJob = getPreJobsInfo();
                    if (prevJob != null && syncedField != null) {
                        long tA1 = System.currentTimeMillis();
                        JsonObject params = prevJob.getJsonObject("params");
                        if (params != null) {
                            String baseUrl = params.getString("url");
                            String syncUrl = buildUrl(baseUrl, params.getString("urlparams"));

                            HttpRequest syncRequest = HttpRequest.newBuilder()
                                    .uri(URI.create(syncUrl))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Accept", "application/json")
                                    .header("Content-Type", "application/json")
                                    .header("User-Agent", getUserAgent())
                                    .method("PUT", HttpRequest.BodyPublishers.ofString(effectivePayload.toString()))
                                    .build();

                            try {
                                HttpResponse<String> syncResponse = client.send(syncRequest, HttpResponse.BodyHandlers.ofString());
                                result.add("returnStatus", syncResponse.statusCode());

                                String syncBody = syncResponse.body();
                                String syncType = syncResponse.headers().firstValue("Content-Type").orElse("");

                                JsonValue syncParsed = syncType.contains("application/json")
                                        ? JSONFormatter.tryParseOrRaw(syncBody)
                                        : Json.createValue(syncBody);

                                result.add("returnBody", syncParsed);
                            } catch (Exception e) {
                                result.add("returnError", "Failed to post back to source: "
                                        + e.getClass().getSimpleName() + ": " + JSONFormatter.escapeForJson(e.getLocalizedMessage()));
                            }
                        } else {
                            System.out.println("TEST WriteDataAction did not get params from prev");
                            for (String key : prevJob.keySet()) {
                                JsonValue value = prevJob.get(key);
                                System.out.println(key + ": " + value);
                            }

                            result.add("state", "finished without backwrite");
                            result.add("statemsg", "There was no params in the prevjob so its unkown where to write the answer.");
                        }
                        long tA2 = System.currentTimeMillis();
                        result.add("timing_marking_ms", tA2 - tA1);
                    }
                }

            } catch (ActionPerformanceException | IOException | InterruptedException e) {
                result.add("state", "failed");
                result.add("statemsg", e.getClass().getSimpleName() + ": " + JSONFormatter.escapeForJson(e.getLocalizedMessage()));
                System.err.println("WriteDataActionException: " + e.getMessage());
                e.printStackTrace();
            }
        }
        long callEnd = System.currentTimeMillis();
        result.add("call_time_ms", callEnd - callStart);
        return result.build();
    }

    /**
     * Markes the datasets as synced by setting the synced attribute on each
     * dataset to true
     *
     * @param payload JsonObject or JsonArray with datasets
     * @param fieldName Name of the field to set to true as synced flag
     * @return
     */
    private JsonValue markAsSynced(JsonValue payload, String fieldName) {
        switch (payload.getValueType()) {
            case OBJECT:
                JsonObject obj = payload.asJsonObject();
                JsonObjectBuilder objBuilder = Json.createObjectBuilder(obj);
                objBuilder.add(fieldName, true);
                return objBuilder.build();

            case ARRAY:
                JsonArrayBuilder arrBuilder = Json.createArrayBuilder();
                for (JsonValue item : payload.asJsonArray()) {
                    if (item.getValueType() == JsonValue.ValueType.OBJECT) {
                        JsonObject base = item.asJsonObject();
                        JsonObjectBuilder updated = Json.createObjectBuilder(base);
                        updated.add(fieldName, true);
                        arrBuilder.add(updated.build());
                    } else {
                        arrBuilder.add(item);
                    }
                }
                JsonArray jar = arrBuilder.build();
                return jar;
            default:
                return payload;
        }
    }

}
