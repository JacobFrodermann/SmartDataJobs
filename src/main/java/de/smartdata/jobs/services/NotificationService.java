package de.smartdata.jobs.services;

import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import de.smartdata.jobs.data.Placeholder;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonArrayBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.io.IOException;

/**
 * Service for selecting, personalizing and sending notifications to members or
 * groups.
 */
public class NotificationService {

    private final int triggerId;
    private final HttpClient client = HttpClientProvider.getClient();

    /**
     * Creates a new NotificationService for a specific trigger.
     *
     * @param triggerId trigger identifier used to select notifications
     */
    public NotificationService(int triggerId) {
        this.triggerId = triggerId;
    }

    /**
     * Picks a random notification for the configured trigger and enriches it
     * with its associated actions.
     *
     * @return notification JSON or {@code null} if none exist
     */
    public JsonObject pickRandomNotification() {
        JsonArray data = DataService.read(DataService.STORAGE_GAMIFICATION, "notification", "eq", Map.of("trigger_id", this.triggerId), null);

        if (data == null || data.isEmpty()) {
            return null;
        }

        int randomIndex = ThreadLocalRandom.current().nextInt(data.size());
        return addActions(data.getJsonObject(randomIndex));
    }

    /**
     * Replaces all placeholders in title and body using member data.
     *
     * @param notification base notification
     * @param member member data
     * @return personalized notification
     */
    public JsonObject personalize(JsonObject notification, JsonObject member) {
        Map<Placeholder, String> placeholders = resolveMember(member);

        return Json.createObjectBuilder(notification)
                .add("title",
                        Placeholder.replaceAll(notification.getString("title", ""), placeholders))
                .add("body",
                        Placeholder.replaceAll(notification.getString("body", ""), placeholders))
                .build();
    }

    /**
     * Sends a notification to a single member and writes a history entry.
     *
     * @param member target member
     * @return response JSON
     */
    public JsonObject sendNotificationToMember(JsonObject member) {
        JsonObject notification = pickRandomNotification();
        if (notification == null) {
            return error("No notification available");
        }
        notification = updateHistory(notification);
        return sendNotificationToMemberWithoutHistory(notification, member);
    }

    /**
     * Sends a notification to all members and writes a history entry.
     *
     * @return response JSON
     */
    public JsonObject sendNotificationToAllMember() {
        try {
            JsonObject notification = pickRandomNotification();
            if (notification == null) {
                return error("No notification available");
            }

            notification = updateHistory(notification);
            JsonArray members = DataService.read(DataService.STORAGE_GAMIFICATION, "view_group_members", null, null, null);

            for (JsonObject member : members.getValuesAs(JsonObject.class)) {
                JsonObject response = sendNotificationToMemberWithoutHistory(personalize(notification, member), member);
                if (response.containsKey("error")) {
                    return response;
                }
            }

            return success("All notifications sent");
        } catch (Exception e) {
            return error("Failed to send notifications: " + e.getMessage());
        }
    }

    /**
     * Sends a notification to all members of a group and writes a history
     * entry.
     *
     * @param group group JSON
     * @return response JSON
     */
    public JsonObject sendNotificationToAllGroupMember(JsonObject group) {
        JsonObject notification = pickRandomNotification();
        if (notification == null) {
            return error("No notification available");
        }

        return sendNotificationToAllGroupMemberWithoutHistory(group, updateHistory(notification));
    }

    /**
     * Sends a notification to all provided groups and writes a history entry.
     *
     * @param groups array of groups
     * @return response JSON
     */
    public JsonObject sendNotificationToAllGroups(JsonArray groups) {
        try {
            JsonObject notification = pickRandomNotification();
            if (notification == null) {
                return error("No notification available");
            }

            notification = updateHistory(notification);
            for (JsonObject group : groups.getValuesAs(JsonObject.class)) {
                sendNotificationToAllGroupMemberWithoutHistory(group, notification);
            }
            return success("All notifications sent");
        } catch (Exception e) {
            return error("Failed to send notifications: " + e.getMessage());
        }
    }

    /**
     * Resolves all placeholders from member data.
     */
    private Map<Placeholder, String> resolveMember(JsonObject member) {
        return Map.of(
                Placeholder.NAME, member.getString("member_name", "User"),
                Placeholder.GROUP, member.getString("group_name", "Group"),
                Placeholder.STREAK, String.valueOf(member.getInt("streak", 0)),
                Placeholder.LEVEL, String.valueOf(member.getInt("level", 0)),
                Placeholder.XP, String.valueOf(member.getInt("xp", 0)),
                Placeholder.EARNED_XP, String.valueOf(member.getInt("last_xp", 0))
        );
    }

    /**
     * Writes a history entry and merges the history_id into data.
     */
    private JsonObject updateHistory(JsonObject notification) {
        int id = notification.getInt("id");

        JsonObject historyBody = Json.createObjectBuilder().add("notification_id", id).build();
        int historyId = DataService.write(DataService.STORAGE_GAMIFICATION, "history", historyBody).getInt("created", 0);

        JsonObject existingData = notification.getJsonObject("data");

        JsonObjectBuilder dataBuilder = Json.createObjectBuilder();
        if (existingData != null) {
            existingData.forEach(dataBuilder::add);
        }
        if (historyId > 0) {
            dataBuilder.add("history_id", historyId);
        }

        return Json.createObjectBuilder(notification)
                .add("data", dataBuilder.build())
                .build();
    }

    /**
     * Sends a notification to a member without creating a history entry.
     */
    private JsonObject sendNotificationToMemberWithoutHistory(JsonObject notification, JsonObject member) {
        try {
            int memberId = member.getInt("member_id");
            JsonObject memberNotification = addMember(notification, memberId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DataService.WEBPUSH_URL + "push/send/" + memberId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(memberNotification.toString()))
                    .build();

            HttpResponse<String> resp = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            
            JsonObject cleanup = MemberCleanupService.cleanupFromPushResult(resp.body());
            
            return Json.createObjectBuilder()
                    .add("response", Json.createObjectBuilder()
                            .add("status", resp.statusCode())
                            .add("body", resp.body()))
                    .add("cleanup", cleanup.getJsonObject("cleanup"))
                    .add("notification", memberNotification)
                    .add("member", member).build();
        } catch (IOException | InterruptedException e) {
            return error("POST request failed: " + e.getMessage());
        }
    }

    /**
     * Adds a member_id to the notification data object.
     */
    private JsonObject addMember(JsonObject notification, int memberId) {
        JsonObject data = notification.getJsonObject("data");

        JsonObjectBuilder dataBuilder = Json.createObjectBuilder();
        if (data != null) {
            data.forEach(dataBuilder::add);
        }
        dataBuilder.add("member_id", memberId);

        return Json.createObjectBuilder(notification)
                .add("data", dataBuilder.build())
                .build();
    }

    /**
     * ends a notification to all members of a group without creating a history
     * entry.
     */
    private JsonObject sendNotificationToAllGroupMemberWithoutHistory(JsonObject group, JsonObject notification) {
        try {
            int groupId = group.getInt("group_id");
            JsonArray members = DataService.read(DataService.STORAGE_GAMIFICATION, "view_group_members", "eq", Map.of("group_id", groupId), null);

            for (JsonObject member : members.getValuesAs(JsonObject.class)) {
                JsonObject response = sendNotificationToMemberWithoutHistory(personalize(notification, member), member);
                if (response.containsKey("error")) {
                    return response;
                }
            }

            return success("All notifications sent");
        } catch (Exception e) {
            return error("Failed to send notifications: " + e.getMessage());
        }
    }

    /**
     * Enriches a notification with its available actions.
     */
    private JsonObject addActions(JsonObject notification) {
        JsonArray joinRows = DataService.read(DataService.STORAGE_GAMIFICATION, "notification_action", "eq", Map.of("notification_id", notification.getInt("id")), null);
        JsonArrayBuilder actions = Json.createArrayBuilder();

        for (JsonObject row : joinRows.getValuesAs(JsonObject.class)) {
            JsonArray actionRows = DataService.read(DataService.STORAGE_GAMIFICATION, "action", "eq", Map.of("id", row.getInt("action_id")), null);

            if (!actionRows.isEmpty()) {
                JsonObject action = actionRows.getJsonObject(0);

                actions.add(Json.createObjectBuilder()
                        .add("action", action.getString("action_type"))
                        .add("title", action.getString("title"))
                        .add("icon", action.getString("icon", "")));
            }
        }

        return Json.createObjectBuilder(notification)
                .add("actions", actions)
                .build();
    }

    private JsonObject success(String message) {
        return Json.createObjectBuilder()
                .add("response",
                        Json.createObjectBuilder()
                                .add("status", 200)
                                .add("body", message))
                .build();
    }

    private JsonObject error(String message) {
        return Json.createObjectBuilder()
                .add("error", message)
                .build();
    }
}
