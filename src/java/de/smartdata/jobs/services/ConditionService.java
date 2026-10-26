package de.smartdata.jobs.services;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

public class ConditionService {

    /**
     * Checks all conditions of a trigger and a group
     *
     * @param conditions a JsonArray containing all conditions of a trigger
     * @return true if all conditions are true (AND-combined)
     */
    public static boolean checkConditions(JsonArray conditions) {

        for (JsonObject condition : conditions.getValuesAs(JsonObject.class)) {

            if (!compare(condition.getJsonNumber("value").doubleValue(), condition.getJsonNumber("threshold").doubleValue(), condition.getString("operator"))) {
                return false;
            }
        }
        return true;
    }

    // Compares double values with a logic operator
    private static boolean compare(double a, double b, String op) {
        if (op == null) {
            return false;
        }
        return switch (op) {
            case ">" ->
                a > b;
            case "<" ->
                a < b;
            case ">=" ->
                a >= b;
            case "<=" ->
                a <= b;
            case "==" ->
                a == b;
            case "!=" ->
                a != b;
            default ->
                throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }
}
