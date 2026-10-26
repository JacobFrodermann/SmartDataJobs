package de.smartdata.jobs.actions;

import de.smartdata.jobs.services.ConditionService;
import de.smartdata.jobs.services.DataService;
import de.smartdata.jobs.services.AchievementService;
import de.smartdata.jobs.services.NotificationService;
import de.smartdata.jobs.services.XPService;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.time.LocalDateTime;
import java.util.Map;

public class CheckEventTriggers extends Action {

    private int groupId;
    private String sensorTable;

    @Override
    public JsonArray getParamsDescription() {
        return Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "group_id")
                        .add("type", "int")
                        .add("description", "Group-id of the gamification.group")
                        .add("optional", false)
                )
                .add(Json.createObjectBuilder()
                        .add("name", "sensor_table")
                        .add("type", "string")
                        .add("description", "Sensor-Table name of the checked Object")
                        .add("optional", false)
                )
                .build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        this.groupId = params.getInt("group_id", 0);
        if (0 == this.groupId) {
            throw new ActionConfigurationException("Parameter >group_id< is missing.");
        }
        this.sensorTable = params.getString("sensor_table", null);
        if (null == this.sensorTable) {
            throw new ActionConfigurationException("Parameter >sensor_table< is missing.");
        }
    }

    @Override
    public JsonObject call() {

        JsonObject group = DataService.read(DataService.STORAGE_GAMIFICATION, "view_groups", "eq", Map.of("group_id", this.groupId), null).getJsonObject(0);
        LocalDateTime lastActivity = getLastActivity(this.sensorTable);
        LocalDateTime groupLastActivity = (group.getString("last_activity", null) == null) ? LocalDateTime.MIN : LocalDateTime.parse(group.getString("last_activity"), DataService.FMT);
        while (!groupLastActivity.equals(lastActivity)) {
            JsonObject data = XPService.getRouteData(this.sensorTable, groupLastActivity.toString());

            if (data == null || data.isEmpty()) {
                return Json.createObjectBuilder().add("status", 100).add("Info", "No data since last activity").build();
            }

            int xp = XPService.calcXP(data.getString("travel_type"),
                    data.getInt("count"),
                    data.getJsonNumber("duration").doubleValue(),
                    data.getJsonNumber("distance").doubleValue(),
                    data.getJsonNumber("speed").doubleValue());

            group = XPService.updateGroup(group, xp);
            
            groupLastActivity = LocalDateTime.parse(group.getString("last_activity"), DataService.FMT);

            JsonArray triggers = DataService.read(DataService.STORAGE_GAMIFICATION, "view_triggers_without_schedule", null, null, null);

            if (triggers != null && !triggers.isEmpty()) {

                for (JsonObject trigger : triggers.getValuesAs(JsonObject.class)) {

                    int triggerId = trigger.getInt("trigger_id");
                    JsonArray conditions = DataService.sendGET(DataService.WEBPUSH_URL + "condition/progress/" + triggerId + "?smartdataurl=/SmartDataAirquality" + "&groupId=" + this.groupId).getJsonArray("progress");

                    boolean conditionsFullfilled = ConditionService.checkConditions(conditions);
                    if (!conditionsFullfilled) {
                        continue;
                    }

                    if (AchievementService.isAchievement(triggerId)) {
                        if (!AchievementService.hasAchievement(this.groupId, triggerId)) {
                            JsonObject achievement = AchievementService.addAchievementToGroup(triggerId, this.groupId);
                            AchievementService.sendAchievement(this.groupId, achievement);
                        }
                    } else {
                        NotificationService ns = new NotificationService(triggerId);
                        ns.sendNotificationToAllGroupMember(group);
                    }
                }
            }
        }

        return Json.createObjectBuilder().add("group_id", this.groupId)
                .add("sensor_table", this.sensorTable).build();
    }

    // Gets the last Activity timestamp of a group from its sensor table
    private LocalDateTime getLastActivity(String sensorTable) {
        String lastTsUrl = DataService.SMARTDATA_RECORDS_URL + sensorTable + "?storage=" + DataService.STORAGE_SMARTMONITORING + "&includes=ts" + "&order=ts,DESC" + "&size=1";
        JsonArray records = DataService.sendGET(lastTsUrl).getJsonArray("records");
        if (records == null || records.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(records.getJsonObject(0).getString("ts"));
    }
}
