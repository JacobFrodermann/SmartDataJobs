package de.smartdata.jobs.actions;

import de.smartdata.jobs.data.SmartDataAccessor;
import de.smartdata.jobs.data.SmartDataAccessorException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Deletes synchronized data older than a configured number of days
 * using SmartDataAccessor.deleteData().
 */
public class DeleteDataAction extends Action {

    private String smartdataurl;
    private String storage;
    private String collection;
    private int days;
    private String filter;

    private static final String DATE_ATTRIBUTE = "ts";

    @Override
    public JsonArray getParamsDescription() {
        return Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "smartdataurl")
                        .add("type", "url")
                        .add("optional", false))
                .add(Json.createObjectBuilder()
                        .add("name", "storage")
                        .add("type", "string")
                        .add("optional", false))
                .add(Json.createObjectBuilder()
                        .add("name", "collection")
                        .add("type", "string")
                        .add("optional", false))
                .add(Json.createObjectBuilder()
                        .add("name", "days")
                        .add("type", "int")
                        .add("optional", false))
                 .add(Json.createObjectBuilder()
                        .add("name", "filter")
                        .add("type", "string")
                        .add("optional", false))
                .build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        smartdataurl = params.getString("smartdataurl", null);
        storage = params.getString("storage", null);
        collection = params.getString("collection", null);
        days = params.getInt("days", -1);
        filter = params.getString("filter", null);

        if (smartdataurl == null || storage == null || collection == null || days <= 0 || filter == null) {
            throw new ActionConfigurationException("Missing or invalid parameters for DeleteDataAction");
        }
    }

    @Override
    public JsonObject call() {
        JsonObjectBuilder result = Json.createObjectBuilder();

        try {
            SmartDataAccessor accessor = new SmartDataAccessor(smartdataurl);

            JsonObject deleteResult = accessor.deleteData(
                    storage,
                    collection,
                    filter,
                    DATE_ATTRIBUTE,
                    days,
                    null,
                    null,
                    result
            );

            result.add("state", "finished");
            result.add("deleteResult", deleteResult);

        } catch (SmartDataAccessorException e) {
            result.add("state", "failed");
            result.add("stateMsg", e.getMessage());
        } catch (Exception e) {
            result.add("state", "failed");
            result.add("stateMsg", "Unexpected error: " + e.getMessage());
        }

        return result.build();
    }
}
