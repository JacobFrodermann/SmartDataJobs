package de.smartdata.jobs.services;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class XPService {

    private static double BASE_XP_PER_KM;
    private static double MIN_SPEED_ALLOWED;
    private static double MAX_SPEED_ALLOWED;
    private static double MIN_DENSITY_PER_KM;
    private static double MAX_DENSITY_PER_KM;
    private static double MAX_DURATION_MULTIPLIER;
    private static double MIN_DISTANCE;
    private static double MIN_DURATION;

    // Read a entry of the Settings Table as double
    private static double readDoubleSetting(String key) {
        JsonArray settings = DataService.read(
                DataService.STORAGE_GAMIFICATION,
                "settings",
                "eq",
                Map.of("key", key),
                null
        );
        if (settings == null || settings.isEmpty()) {
            return 0.0;
        }
        return Double.parseDouble(settings.getJsonObject(0).getString("value"));
    }

    // Load all needed Settings
    private static void loadSettings(String travelType) {
        BASE_XP_PER_KM = readDoubleSetting("base_xp_per_km");
        MIN_SPEED_ALLOWED = readDoubleSetting("min_speed_allowed_" + travelType);
        MAX_SPEED_ALLOWED = readDoubleSetting("max_speed_allowed_" + travelType);
        MIN_DENSITY_PER_KM = readDoubleSetting("min_density_per_km");
        MAX_DENSITY_PER_KM = readDoubleSetting("max_density_per_km");
        MAX_DURATION_MULTIPLIER = readDoubleSetting("max_duration_multiplier");
        MIN_DISTANCE = readDoubleSetting("min_distance_km");
        MIN_DURATION = readDoubleSetting("min_duration_h");
    }

    // Gets the next last Activity of a group's sensor table
    private static String getNextLastActivity(String sensorTable, String groupLastActivity) {
        LocalDateTime lastActivity = (groupLastActivity == null) ? LocalDateTime.MIN : LocalDateTime.parse(groupLastActivity, DataService.FMT);
        String nextMeasurementProcess = getNextMeasurementProcess(sensorTable, lastActivity.toString());
        String lastTsUrl = DataService.SMARTDATA_RECORDS_URL + sensorTable + "?storage=" + DataService.STORAGE_SMARTMONITORING + "&filter=ts,gt," + lastActivity + "&filter=measurement_process,eq," + nextMeasurementProcess + "&includes=ts" + "&order=ts,DESC" + "&size=1";
        JsonArray records = DataService.sendGET(lastTsUrl).getJsonArray("records");
        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.getJsonObject(0).getString("ts");
    }

    // Gets the next <code>measurement_process</code> of a group's sensor table depending on the last Activity
    private static String getNextMeasurementProcess(String sensorTable, String lastActivity) {
        String measurementProcessUrl = DataService.SMARTDATA_RECORDS_URL + sensorTable + "?storage=" + DataService.STORAGE_SMARTMONITORING + "&filter=ts,gt," + lastActivity + "&includes=measurement_process" + "&order=ts,ASC" + "&size=1";
        JsonArray records = DataService.sendGET(measurementProcessUrl).getJsonArray("records");
        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.getJsonObject(0).getString("measurement_process");
    }

    /**
     * Update the group entry in DB <br>
     * Updated columns: <code>streak, level_xp, current_xp, last_activity</code>
     *
     * @param group that should be updated
     * @param xp the group has earned due to a route
     * @return <code>JsonObject</code> with new values
     */
    public static JsonObject updateGroup(JsonObject group, int xp) {
        String lastActivity = getNextLastActivity(group.getString("data_table"), group.getString("last_activity", null));
        if (lastActivity == null) {
            return Json.createObjectBuilder().build();
        }
        int groupId = group.getInt("group_id");
        int streak = group.getInt("streak");
        int levelXP = group.getInt("level_xp");
        int currentXP = group.getInt("xp");
        String groupUrl = DataService.SMARTDATA_RECORDS_URL + "group/" + groupId + "?storage=" + DataService.STORAGE_GAMIFICATION;
        JsonObject updates = Json.createObjectBuilder()
                .add("streak", updateStreak(group.getString("last_activity", null), lastActivity, streak))
                .add("level_xp", (levelXP + xp))
                .add("current_xp", (currentXP + xp))
                .add("last_activity", lastActivity)
                .add("last_xp", xp)
                .build();

        JsonObject result = DataService.sendPUT(groupUrl, updates);
        if (result.getInt("status") != 200) {
            return Json.createObjectBuilder()
                    .add("error", result)
                    .build();
        }
        JsonObjectBuilder merged = merge(group, updates);
        merged.add("xp", (currentXP + xp));

        return merged.build();
    }

    private static JsonObjectBuilder merge(JsonObject original, JsonObject updates) {
        JsonObjectBuilder builder = Json.createObjectBuilder();

        original.forEach(builder::add);
        updates.forEach(builder::add);

        return builder;
    }

    /**
     * Gets all data of a route a group has completed
     *
     * @param sensorTable of the group
     * @param lastActivity of the group
     * @return <code>JsonObject</code> in the following format:      <pre>
     * {@code
     * {
     *      "count": 0,
     *      "distance": 0.0,
     *      "duration": 0.0,
     *      "speed": 0.0
     * }
     * }
     * </pre>
     */
    public static JsonObject getRouteData(String sensorTable, String lastActivity) {
        String measurementProcess = getNextMeasurementProcess(sensorTable, lastActivity);

        String distanceUrl = DataService.DISTANCE_URL + "&collection=" + sensorTable + "&start=" + lastActivity + "&filter=measurement_process,eq," + measurementProcess;
        String speedUrl = DataService.SPEED_URL + "&collection=" + sensorTable + "&start=" + lastActivity + "&filter=measurement_process,eq," + measurementProcess;
        String durationUrl = DataService.DURATION_URL + "&collection=" + sensorTable + "&start=" + lastActivity + "&filter=measurement_process,eq," + measurementProcess;
        String countUrl = DataService.SMARTDATA_RECORDS_URL + sensorTable + "?storage=" + DataService.STORAGE_SMARTMONITORING + "&countonly=true" + "&filter=ts,gt," + lastActivity + "&filter=measurement_process,eq," + measurementProcess;
        String travelTypeUrl = DataService.SMARTDATA_RECORDS_URL + "means_of_travel_join_process" + "?storage=" + DataService.STORAGE_SMARTMONITORING + "&filter=measurement_process_id,eq," + measurementProcess + "&join=means_of_travel";

        double distance = DataService.sendGET(distanceUrl).getJsonNumber("totalKM").doubleValue();
        double speed = DataService.sendGET(speedUrl).getJsonNumber("avgSpeedKMH").doubleValue();
        double duration = DataService.sendGET(durationUrl).getJsonNumber("durationH").doubleValue();
        int count = DataService.sendGET(countUrl)
                .getJsonArray("records")
                .getJsonObject(0)
                .getInt("count");
        String travelType = DataService.sendGET(travelTypeUrl)
                .getJsonArray("records")
                .getJsonObject(0)
                .getJsonObject("means_of_travel_id")
                .getString("name");

        return Json.createObjectBuilder()
                .add("travel_type", travelType)
                .add("count", count)
                .add("distance", distance)
                .add("duration", duration)
                .add("speed", speed)
                .build();
    }

    // Calculate a quality that has a decreasing effect
    private static double qualityDecreasing(double v, double minOK, double maxOK) {
        if (v <= minOK) {
            return 1.0;
        }
        if (v >= maxOK) {
            return 0.0;
        }
        return 1.0 - ((v - minOK) / (maxOK - minOK));
    }

    // Calculate a quality that has a increasing effect
    private static double qualityIncreasing(double x, double minOK, double maxOK) {
        if (x <= minOK) {
            return 0.0;
        }
        if (x >= maxOK) {
            return 1.0;
        }

        return (x - minOK) / (maxOK - minOK);
    }

    // Scale a quality from an intervall [0,1] to [1,2]
    private static double scale01to12(double q) {
        return 1.0 + q; // q ∈ [0,1] → [1,2]
    }

    /**
     * Calculate the XP depending on the data of a route a group has taken
     *
     * @param travelType of the route
     * @param count of the datapoints of the route
     * @param duration of the route
     * @param distance of the route
     * @param speed (avg) of the route
     * @return the XP that the route is worth
     */
    public static int calcXP(String travelType, int count, double duration, double distance, double speed) {

        loadSettings(travelType);

        if (distance < MIN_DISTANCE || duration <= MIN_DURATION) {
            return 0;
        }

        double qSpeed = scale01to12(
                qualityDecreasing(
                        speed,
                        MIN_SPEED_ALLOWED,
                        MAX_SPEED_ALLOWED));

        double density = Math.min(count / distance, MAX_DENSITY_PER_KM);
        double qDensity = scale01to12(
                qualityIncreasing(
                        density,
                        MIN_DENSITY_PER_KM,
                        MAX_DENSITY_PER_KM));

        double expectedHours = distance / MIN_SPEED_ALLOWED;
        double qDuration = scale01to12(
                qualityDecreasing(
                        duration,
                        expectedHours,
                        expectedHours * MAX_DURATION_MULTIPLIER
                )
        );

        double xpRaw = BASE_XP_PER_KM * distance * (qSpeed * qDensity * qDuration);

        return (int) Math.floor(xpRaw);
    }

    // Reset the xp of a group
    private static JsonObject resetXP(int groupId) {
        String groupUrl = DataService.SMARTDATA_RECORDS_URL + "group/" + groupId + "?storage=" + DataService.STORAGE_GAMIFICATION;
        JsonObject body = Json.createObjectBuilder()
                .add("current_xp", 0)
                .build();
        return DataService.sendPUT(groupUrl, body);
    }

    /**
     * Resets the Leaderboard by setting all <code>current_xp</code> of all
     * groups to zero
     *
     * @return reseted and failed groups
     */
    public static JsonObject resetLeaderboard() {
        JsonArrayBuilder reseted = Json.createArrayBuilder();
        JsonArrayBuilder failed = Json.createArrayBuilder();
        JsonArray groups = DataService.read(DataService.STORAGE_GAMIFICATION, "group", null, null, null);
        if(groups == null){
            return Json.createObjectBuilder().add("failed", "all").build();
        }
        for (JsonObject group : groups.getValuesAs(JsonObject.class)) {
            JsonObject resp = resetXP(group.getInt("id"));
            if (resp.getInt("status", 500) == 200) {
                reseted.add(group);
            } else {
                failed.add(group);
            }
        }
        return Json.createObjectBuilder().add("reseted", reseted).add("failed", failed).build();
    }

    // Updates the streak by compyring the activity
    private static int updateStreak(String oldLastActivity, String newLastActivity, int streak) {
        LocalDate oldDate = LocalDateTime.parse(oldLastActivity, DataService.FMT).toLocalDate();
        LocalDate newDate = LocalDateTime.parse(newLastActivity, DataService.FMT).toLocalDate();

        long daysBetween = ChronoUnit.DAYS.between(oldDate, newDate);

        if (streak == 0) {
            return 1;
        } else if (daysBetween == 0) {
            return streak;
        } else if (daysBetween == 1) {
            return streak + 1;
        } else {
            return 0;
        }
    }
}
