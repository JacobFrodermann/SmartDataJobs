package de.smartdata.jobs.actions;

import de.fhbielefeld.scl.rest.util.JSONFormatter;
import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import de.smartdata.jobs.data.SmartDataAccessor;
import de.smartdata.jobs.data.SmartDataAccessorException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

/**
 * This class allows checks of http connections
 *
 * @author Florian Fehring
 */
public class ReadDataAction extends AbstractNetworkAction {

    private String smartdataurl;
    private String url;
    private String requestMethod = "GET";
    private String username;
    private String userpwd;
    private JsonValue requestBody;
    private String preJobField;

    @Override
    public JsonArray getParamsDescription() {
        JsonArrayBuilder jab = Json.createArrayBuilder();

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
        this.username = params.getString("username", null);
        this.userpwd = params.getString("userpwd", null);

        if (params.containsKey("body") && params.get("body").getValueType() == JsonValue.ValueType.OBJECT) {
            this.requestBody = params.getJsonObject("body");
        } else if (params.containsKey("body") && params.get("body").getValueType() == JsonValue.ValueType.STRING) {
            String bodyText = params.getString("body");
            // Remove special chars
            bodyText = bodyText.replace("\\r", "")
                    .replace("\\n", "")
                    .replace("\\t", "");
            try (JsonReader reader = Json.createReader(new StringReader(bodyText))) {
                JsonStructure jsonStruct = reader.read(); // liefert entweder JsonObject oder JsonArray

                if (jsonStruct.getValueType() == JsonValue.ValueType.OBJECT) {
                    this.requestBody = (JsonObject) jsonStruct;
                } else if (jsonStruct.getValueType() == JsonValue.ValueType.ARRAY) {
                    this.requestBody = (JsonArray) jsonStruct;
                } else {
                    this.requestBody = Json.createValue(bodyText);
                }
            } catch (Exception e) {
                throw new ActionConfigurationException("Failed to parse body string as JSON: " + e.getMessage());
            }
        }
        if (params.containsKey("urlparams")) {
            this.urlParams = params.getString("urlparams");
        }

        this.preJobField = params.getString("prejobfield", null);

    }

    @Override
    public JsonObject call() {
        long callStart = System.currentTimeMillis();
        JsonObjectBuilder result = Json.createObjectBuilder();

        // Create combined JsonValue from previous result (if exists) and configured request body (only at POST oder PUT)
        JsonValue effectivePayload = null;
        boolean sendPayload = this.requestMethod.equalsIgnoreCase("POST") || this.requestMethod.equalsIgnoreCase("PUT");
        if (sendPayload) {
            effectivePayload = getEffectivePayload(preJobField, requestBody);
            result.add("calledWithBody", effectivePayload);
        }

        if (this.smartdataurl != null) {
            // Parse parameter
            JsonObject params = null;
            if (this.urlParams != null && !this.urlParams.isEmpty()) {
                JsonParser parser = Json.createParser(new StringReader(this.urlParams));
                parser.next();
                params = parser.getObject();
                if (params == null) {
                    result.add("error", "Could not parse urlParams for SmartData parameters in ReadDataAction.");
                    return result.build();
                }
            }

            // Handling for SmartData API
            SmartDataAccessor sda = new SmartDataAccessor(this.smartdataurl);
            try {
                JsonArray data = sda.fetchData(params, result);
                result.add("records",data);
                result.add("state", "finished");
            } catch (SmartDataAccessorException ex) {
                result.add("state", "failed");
                result.add("stateMsg", ex.getClass().getSimpleName() + "Exception occured:" + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
            }
        } else {
            result.add("fetchMethod", "ReadDataAction/HTTP URL connection");
            // Handling for non SmartData API
            try {
                String fullUrl = this.buildUrl(url, urlParams);
                result.add("calledUrl", fullUrl);

                // Request aufbauen
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/json")
                        .header("User-Agent", getUserAgent());

                if (sendPayload) {
                    builder.header("Content-Type", "application/json");
                    builder.method(requestMethod.toUpperCase(), HttpRequest.BodyPublishers.ofString(effectivePayload.toString()));
                } else {
                    builder.method(requestMethod.toUpperCase(), HttpRequest.BodyPublishers.noBody());
                }

                // Basic Auth (falls vorhanden)
                if (username != null && userpwd != null) {
                    String credentials = username + ":" + userpwd;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + encoded);
                }

                HttpRequest request = builder.build();

                // Gemeinsamen Client verwenden
                long requestStart = System.currentTimeMillis();
                HttpResponse<String> response = HttpClientProvider.getClient().send(request, HttpResponse.BodyHandlers.ofString());
                long requestEnd = System.currentTimeMillis();
                result.add("http_request_time_ms", requestEnd - requestStart);
                int status = response.statusCode();
                result.add("httpStatus", status);

                // Inhalt auswerten
                String body = response.body();
                JsonValue parsed = JSONFormatter.tryParseOrRaw(body);

                if (parsed.getValueType() == JsonValue.ValueType.OBJECT) {
                    parsed.asJsonObject().forEach(result::add);
                } else {
                    result.add("response", parsed);
                }

                if (status == 204 || status == 304) {
                    result.add("state", "nothing todo");
                } else if (status >= 200 && status < 400) {
                    result.add("state", "finished");
                } else {
                    result.add("state", "failed");
                    result.add("statemsg", "Could not get data.");
                }

            } catch (Exception e) {
                result.add("state", "failed");
                result.add("statemsg", "Error occured while ReadData: "
                        + e.getClass().getSimpleName() + ": " + JSONFormatter.escapeForJson(e.getMessage()));
                e.printStackTrace();
            }
        }

        long callEnd = System.currentTimeMillis();
        result.add("call_time_ms", callEnd - callStart);
        return result.build();
    }
}
