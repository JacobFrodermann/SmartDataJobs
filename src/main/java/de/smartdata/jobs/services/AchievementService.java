package de.smartdata.jobs.services;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import java.util.Map;

public class AchievementService {

    /**
     * Checks if the given trigger is an achievement
     *
     * @param triggerId of the trigger that should be checked
     * @return <code>true</code> if the trigger is an achievement, else
     * <code>false</code>
     */
    public static boolean isAchievement(int triggerId) {
        JsonArray achievement = getAchievement(triggerId);
        return !(achievement == null || achievement.isEmpty());
    }

    /**
     * Checks if the given group has an achievement of a trigger
     *
     * @param groupId of the group that should be checked
     * @param triggerId of the trigger that is an achievement
     * @return <code>true</code> if the group has this achievement, else
     * <code>false</code>
     */
    public static boolean hasAchievement(int groupId, int triggerId) {
        JsonObject achievement = getAchievement(triggerId).getJsonObject(0);
        int achievementId = achievement.getInt("tier_id");
        JsonArray achievements = DataService.read(DataService.STORAGE_GAMIFICATION, "group_achievement", "eq", Map.of("group_id", groupId, "achievement_id", achievementId), null);
        return !(achievements == null || achievements.isEmpty());
    }

    // Gets the achievement of a trigger
    private static JsonArray getAchievement(int triggerId) {
        return DataService.read(DataService.STORAGE_GAMIFICATION, "view_achievement_to_send", "eq", Map.of("trigger_id", triggerId), null);
    }

    /**
     * Sends an achievement to all group members that are part of the group that
     * has gained the achievement
     *
     * @param groupId of the group that has gained the achievement
     * @param achievement that should be send
     * @return the members the achievement was sent to and failed to sent to
     */
    public static JsonObject sendAchievement(int groupId, JsonObject achievement) {
        JsonArray members = DataService.read(DataService.STORAGE_GAMIFICATION, "view_group_members", "eq", Map.of("group_id", groupId), null);
        if (members == null || members.isEmpty()) {
            return Json.createObjectBuilder().build();
        }
        JsonArrayBuilder sent = Json.createArrayBuilder();
        JsonArrayBuilder failed = Json.createArrayBuilder();
        for (JsonObject member : members.getValuesAs(JsonObject.class)) {
            JsonObject resp = DataService.sendPOST(DataService.WEBPUSH_URL + "push/send/" + member.getInt("member_id"), achievement);
            if (resp.getInt("status", 500) == 200) {
                sent.add(member);
            } else {
                failed.add(member);
            }
        }
        return Json.createObjectBuilder()
                .add("sent", sent)
                .add("failed", failed)
                .build();
    }

    /**
     * Adds the achievement from a trigger to a group
     * 
     * @param triggerId of the achievement
     * @param groupId of the group that has accomplished the achievement
     * @return achievement
     */
    public static JsonObject addAchievementToGroup(int triggerId, int groupId) {
        JsonObject achievement = getAchievement(triggerId).getJsonObject(0);
        JsonObject body = Json.createObjectBuilder()
                .add("group_id", groupId)
                .add("achievement_id", achievement.getInt("tier_id"))
                .build();
        String groupAchievementUrl = DataService.SMARTDATA_RECORDS_URL + "group_achievement" + "?storage=" + DataService.STORAGE_GAMIFICATION;
        JsonObject resp = DataService.sendPOST(groupAchievementUrl, body);
        if (resp.getInt("status") != 201) {
            return resp;
        }
        String groupUrl = DataService.SMARTDATA_RECORDS_URL + "group/" + groupId + "?storage=" + DataService.STORAGE_GAMIFICATION;
        JsonObject group = DataService.sendGET(groupUrl).getJsonArray("records").getJsonObject(0);
        int rewardXP = achievement.getInt("reward_xp");
        int levelXP = group.getInt("level_xp");
        int currentXP = group.getInt("current_xp");
        JsonObject groupBody = Json.createObjectBuilder()
                .add("level_xp", (levelXP + rewardXP))
                .add("current_xp", (currentXP + rewardXP))
                .build();
        DataService.sendPUT(groupUrl, groupBody);
        return achievement;
    }
}
