package de.smartdata.jobs.actions;

import de.smartdata.jobs.services.ConditionService;
import de.smartdata.jobs.services.NotificationService;
import de.smartdata.jobs.services.DataService;
import de.smartdata.jobs.services.XPService;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonArrayBuilder;
import java.util.Map;

public class SendNotificationAction extends Action {

    private int triggerId;

    @Override
    public JsonArray getParamsDescription() {
        return Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "trigger_id")
                        .add("type", "int")
                        .add("description", "Trigger that will be handled in the Job that executes this Action")
                        .add("optional", false)).build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        this.triggerId = params.getInt("trigger_id", 0);
        if (0 == this.triggerId) {
            throw new ActionConfigurationException("Parameter >trigger_id< is missing.");
        }
    }

    @Override
    public JsonObject call() {
        JsonObjectBuilder result = Json.createObjectBuilder();

        JsonArray triggers = DataService.read(
                DataService.STORAGE_GAMIFICATION,
                "view_triggers_with_schedule",
                "eq",
                Map.of("trigger_id", this.triggerId),
                null
        );

        if (triggers == null || triggers.isEmpty()) {
            return result
                    .add("status", 404)
                    .add("message", "No trigger found for trigger_id=" + this.triggerId)
                    .build();
        }
        JsonObject trigger = triggers.getJsonObject(0);

        JsonArray groups = DataService.read(DataService.STORAGE_GAMIFICATION, "view_groups", null, null, null);
        if (groups == null || groups.isEmpty()) {
            return result
                    .add("status", 404)
                    .add("message", "No groups found")
                    .build();
        }

        NotificationService ns = new NotificationService(this.triggerId);
        JsonArrayBuilder sendTo = Json.createArrayBuilder();
        JsonArrayBuilder failed = Json.createArrayBuilder();
        for (JsonObject group : groups.getValuesAs(JsonObject.class)) {
            int groupId = group.getInt("group_id");
            JsonArray conditions = DataService.sendGET(DataService.WEBPUSH_URL + "condition/progress/" + triggerId + "?smartdataurl=/SmartDataAirquality" + "&groupId=" + groupId).getJsonArray("progress");

            boolean conditionsFullfilled = ConditionService.checkConditions(conditions);

            if (!conditionsFullfilled) {
                failed.add(group);
                continue;
            }
            if (trigger.getString("description").equals("Leaderboard Reset") && trigger.getString("cron").equals("0 0 12 1 * ?")) {
                XPService.resetLeaderboard();
            }
            sendTo.add(group);
        }

        ns.sendNotificationToAllGroups(sendTo.build());

        return result.add("status", 200)
                .add("sentTo", sendTo.build().toString())
                .add("failed", failed.build().toString())
                .build();
    }
}
