package de.smartdata.jobs.services;

import de.smartdata.jobs.actions.ActionConfigurationException;
import de.smartdata.jobs.actions.ReadDataAction;
import de.smartdata.jobs.actions.WriteDataAction;
import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

public class DataService {

    private enum RequestType {
        GET,
        POST,
        PUT
    }

    private static final HttpClient client = HttpClientProvider.getClient();

    private static final String HOST = "http://localhost:8080/";
    private static final String SMARTDATA_URL = "SmartDataAirquality";

    public static final String SMARTDATA_RECORDS_URL = HOST + SMARTDATA_URL + "/smartdata/records/";
    public static final String WEBPUSH_URL = HOST + "WebPush/webpush/";

    public static final String STORAGE_GAMIFICATION = "gamification";
    public static final String STORAGE_SMARTMONITORING = "smartmonitoring";

    private static final String SMARTDATALYSER_URL = HOST + "SmartDataLyser/smartdatalyser/";
    public static final String SMARTDATA_STORAGE_PARAMS = "?smartdataurl=/" + SMARTDATA_URL + "&storage=" + STORAGE_SMARTMONITORING;

    public static final String DISTANCE_URL = SMARTDATALYSER_URL + "geo/distance" + SMARTDATA_STORAGE_PARAMS;
    public static final String SPEED_URL = SMARTDATALYSER_URL + "geo/speed" + SMARTDATA_STORAGE_PARAMS;
    public static final String DURATION_URL = SMARTDATALYSER_URL + "geo/duration" + SMARTDATA_STORAGE_PARAMS;
    public static final String STATISTIC_URL = SMARTDATALYSER_URL + "statistic/minmaxspan" + SMARTDATA_STORAGE_PARAMS;

    public static final DateTimeFormatter FMT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd[' ']['T']HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .toFormatter();

    private static JsonArray read(JsonObject readParams) {
        try {
            final ReadDataAction READ_DATA = new ReadDataAction();
            READ_DATA.setParams(readParams);
            return READ_DATA.call().getJsonArray("records");
        } catch (ActionConfigurationException e) {
            return null;
        }
    }

    /**
     * Universial read from a collection of a storage with optional filters on
     * an operator
     *
     * @param storage that should be read from
     * @param collection that should be read from
     * @param operator with whom all filters will be compared, e.g. "eq"
     * [optional]
     * @param filters that are applied on the reading results [optional]
     * @param includes list of the columns that should be included [optional]
     * @return result of the reading as JsonArray
     */
    public static JsonArray read(String storage, String collection, String operator, Map<String, Object> filters, List<String> includes) {

        JsonObjectBuilder urlParams = Json.createObjectBuilder()
                .add("storage", storage)
                .add("collection", collection);

        if (filters != null && !filters.isEmpty() && operator != null && !"".equals(operator)) {
            String filterExpression = filters.entrySet().stream()
                    .map(e -> e.getKey() + "," + operator + "," + e.getValue())
                    .collect(Collectors.joining(";"));

            urlParams.add("filter", filterExpression);
        }

        if (includes != null && !includes.isEmpty()) {
            JsonArrayBuilder includeArray = Json.createArrayBuilder();
            includes.forEach(includeArray::add);
            urlParams.add("includes", includeArray);
        }

        JsonObject params = Json.createObjectBuilder()
                .add("smartdataurl", SMARTDATA_URL)
                .add("requestmethod", "GET")
                .add("urlparams", urlParams.build().toString())
                .build();

        return read(params);
    }

    private static JsonObject write(JsonObject writeParams, JsonObject readParams) {
        try {
            final WriteDataAction WRITE_DATA = new WriteDataAction();
            WRITE_DATA.setParams(writeParams);
            JsonObject written = WRITE_DATA.call();
            JsonObjectBuilder result = Json.createObjectBuilder().addNull("created");
            if (written.containsKey("state") && "finished".equals(written.getString("state"))) {
                JsonObject created = read(readParams).getJsonObject(0);
                result.add("created", created.getInt("id"));
            }
            return result.build();
        } catch (ActionConfigurationException e) {
            return null;
        }
    }

    /**
     * Universial write to a collection of a storage
     *
     * @param storage that should be written to
     * @param collection that should be written to
     * @param body data that will be written
     * @return id in <code>JsonObject</code> of the written entry
     */
    public static JsonObject write(String storage, String collection, JsonObject body) {

        JsonObject urlParams = Json.createObjectBuilder()
                .add("storage", storage)
                .add("collection", collection)
                .build();

        String bodyStr = body != null && !body.isEmpty() ? body.toString() : "";

        JsonObject writeParams = Json.createObjectBuilder()
                .add("smartdataurl", SMARTDATA_URL)
                .add("requestmethod", "POST")
                .add("urlparams", urlParams.toString())
                .add("body", bodyStr)
                .build();

        JsonObject urlParamsExtended = Json.createObjectBuilder()
                .add("storage", storage)
                .add("collection", collection)
                .add("includes", "id")
                .add("order", "id,DESC")
                .add("size", 1)
                .build();

        JsonObject readParams = Json.createObjectBuilder()
                .add("smartdataurl", SMARTDATA_URL)
                .add("requestmethod", "GET")
                .add("urlparams", urlParamsExtended.toString())
                .build();

        return write(writeParams, readParams);
    }

    /**
     * Send a GET-Request to the given URL
     *
     * @param targetUrl the GET-Request should be send to
     * @return response as <code>JsonObject</code>
     */
    public static JsonObject sendGET(String targetUrl) {
        JsonObject resp = send(targetUrl, null, RequestType.GET);
        return resp.getJsonObject("response");
    }

    /**
     * Send a POST-Request to the given URL with the given body
     *
     * @param targetUrl the POST-Request should be send to
     * @param body that should be send
     * @return response as <code>JsonObject</code>
     */
    public static JsonObject sendPOST(String targetUrl, JsonObject body) {
        JsonObject resp = send(targetUrl, body, RequestType.POST);
        return resp.getJsonObject("response");
    }

    /**
     * Send a PUT-Request to the given URL with the given body
     *
     * @param targetUrl the PUT-Request should be send to
     * @param body that should be send
     * @return response as <code>JsonObject</code>
     */
    public static JsonObject sendPUT(String targetUrl, JsonObject body) {
        JsonObject resp = send(targetUrl, body, RequestType.PUT);
        return resp.getJsonObject("response");
    }

    private static JsonObject send(String targetUrl, JsonObject body, RequestType type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json");

            switch (type) {
                case GET ->
                    builder.GET();
                case POST ->
                    builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                default ->
                    builder.PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
            }

            HttpRequest request = builder.build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            String responseBody = resp.body();
            JsonValue value;

            try (JsonReader r = Json.createReader(new StringReader(responseBody))) {
                value = r.readValue(); // <-- universell (Object, Array, Number, String, true/false/null)
            }

            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("status", resp.statusCode());

            if (value.getValueType() == JsonValue.ValueType.OBJECT) {
                // reguläres JSON Objekt --> addAll
                response.addAll(Json.createObjectBuilder((JsonObject) value));
            } else {
                // primitive oder Array --> als "data" reinpacken
                response.add("data", value);
            }

            JsonObjectBuilder result = Json.createObjectBuilder()
                    .add("response", response);

            if (type != RequestType.GET) {
                result.add("body", body);
            }

            return result.build();

        } catch (IOException | InterruptedException e) {
            return Json.createObjectBuilder()
                    .add("error", type.name() + " request failed: " + e.getMessage())
                    .build();
        }
    }
}
