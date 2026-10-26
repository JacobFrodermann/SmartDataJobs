package de.smartdata.jobs.services;

import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import jakarta.json.*;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Diese Klasse führt einen Cleanup in der gamification.Member Tabelle durch, um Ghost-User zu entfernen
 * -> Ghost-User = Nutzer wo keine Nachricht zugestellt werden konnte
 * 
 */
public class MemberCleanupService {

    private static final HttpClient CLIENT = HttpClientProvider.getClient();

    /**
     * Erwartet JSON der Push-API: {"sent":[...],"deleted":[...],"failed":[...]}
     * und löscht alle Member/Subscriptions, deren Endpoint im deleted-Array steht.
     */
    public static JsonObject cleanupFromPushResult(String pushApiBody) {
        if (pushApiBody == null || pushApiBody.isBlank()) {
            return Json.createObjectBuilder()
                    .add("cleanup", Json.createObjectBuilder()
                            .add("status", "skipped")
                            .add("reason", "empty body"))
                    .build();
        }

        final JsonObject result;
        try (JsonReader reader = Json.createReader(new StringReader(pushApiBody))) {
            result = reader.readObject();
        } catch (Exception e) {
            return Json.createObjectBuilder()
                    .add("cleanup", Json.createObjectBuilder()
                            .add("status", "error")
                            .add("reason", "invalid json")
                            .add("message", String.valueOf(e.getMessage())))
                    .build();
        }

        JsonArray deleted = result.getJsonArray("deleted");
        if (deleted == null || deleted.isEmpty()) {
            return Json.createObjectBuilder()
                    .add("cleanup", Json.createObjectBuilder()
                            .add("status", "ok")
                            .add("deletedEndpoints", 0)
                            .add("deletedSubscriptions", 0)
                            .add("deleteApiCalls", 0)
                            .add("errors", 0))
                    .build();
        }

        int numberOfInvalidSubscriptions = deleted.size();
        int deletedSubscriptions = 0;
        int errors = 0;

        JsonArrayBuilder cleanedIds = Json.createArrayBuilder();
        JsonArrayBuilder cleanupErrors = Json.createArrayBuilder();

        for (int i = 0; i < numberOfInvalidSubscriptions; i++) {
            String endpoint = deleted.getString(i, null);
            if (endpoint == null || endpoint.isBlank()) continue;

            try {
                int subscriptionId = findSubscriptionIdByEndpoint(endpoint);
                if (subscriptionId < 0) {
                    //subscription by endpoint wurde nicht gefunden -> überspringen
                    continue;
                }

                deletedSubscriptions++;

                boolean ok = deleteUserById(subscriptionId);

                if (ok) {
                    cleanedIds.add(subscriptionId);
                    System.out.println("[Cleanup] deleted User '" + subscriptionId + "'");
                } else {
                    errors++;
                    cleanupErrors.add(Json.createObjectBuilder()
                            .add("endpoint", endpoint)
                            .add("id", subscriptionId)
                            .add("error", "delete api failed"));
                }
            } catch (Exception e) {
                errors++;
                cleanupErrors.add(Json.createObjectBuilder()
                        .add("endpoint", endpoint)
                        .add("error", String.valueOf(e.getMessage())));
            }
        }

        return Json.createObjectBuilder()
                .add("cleanup", Json.createObjectBuilder()
                        .add("status", errors == 0 ? "ok" : "partial")
                        .add("sizeInvalidSubscriptions", numberOfInvalidSubscriptions)
                        .add("deletedSubscriptions", deletedSubscriptions)
                        .add("errors", errors)
                        .add("cleanedIds", cleanedIds)
                        .add("errorDetails", cleanupErrors))
                .build();
    }

    
    /**
     * Holt die Subscription/Member-ID per endpoint aus der Records API.
     *
     * @return id >= 0 wenn gefunden, sonst -1
     */
    public static int findSubscriptionIdByEndpoint(String endpoint) throws Exception {
        String encodedEndpoint = URLEncoder.encode(endpoint, StandardCharsets.UTF_8);

        String url = DataService.SMARTDATA_RECORDS_URL
                + "member"
                + "?storage=" + DataService.STORAGE_GAMIFICATION
                + "&filter=endpoint,eq," + encodedEndpoint;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("findSubscriptionIdByEndpoint failed: HTTP " + resp.statusCode() + " body=" + resp.body());
        }

        try (JsonReader reader = Json.createReader(new StringReader(resp.body()))) {
            JsonObject body = reader.readObject();
            JsonArray records = body.getJsonArray("records");
            if (records == null || records.isEmpty()) return -1;

            JsonObject first = records.getJsonObject(0);
            return first.getInt("id", -1);
        }
    }

    /**
     * Löscht Member/Subscription per ID über Records API.
     */
    public static boolean deleteUserById(int memberId) throws Exception {
        String deleteUrl = DataService.SMARTDATA_RECORDS_URL
                + "member/" + memberId
                + "?storage=" + DataService.STORAGE_GAMIFICATION;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(deleteUrl))
                .header("Accept", "application/json")
                .DELETE()
                .build();

        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }
}
