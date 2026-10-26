package de.smartdata.jobs.actions;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Abstract class for actions that can be executed timed and repeated
 *
 * @author Florian Fehring
 */
public abstract class Action implements Callable<JsonObject> {

    private Long id;
    private JsonObject preJobsInfo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public JsonObject getPreJobsInfo() {
        return preJobsInfo;
    }

    public void setPreJobsInfo(JsonObject preJobsInfo) {
        this.preJobsInfo = preJobsInfo;
    }

    /**
     * Creates a description of params. Must follow the Common Configuration
     * Model (CCM). At least name, type, desciprtion and optional Attribute must
     * exist for every posible param.
     *
     * @return JsonArray with description of possible params.
     */
    public abstract JsonArray getParamsDescription();

    /**
     * Set action params. Params are individual for each action.
     *
     * @param params Actions parameters
     * @throws ActionConfigurationException
     */
    public abstract void setParams(JsonObject params) throws ActionConfigurationException;

    /**
     * Executes the action and returns the result.
     *
     * @return Action execution result
     */
    @Override
    public abstract JsonObject call();

    /**
     * Extracts the result from the previous executed job.
     *
     * @return JsonValue containing the results of the previous job
     */
    protected JsonValue extractPreJobResult() {
        JsonObject preJobInfo = getPreJobsInfo();
        if (preJobInfo != null && preJobInfo.containsKey("result")) {
            return preJobInfo.get("result");
        }
        return null;
    }

    /**
     * Gets the effective payload for a request body combined from the result of
     * a previous job and a given request body JsonObject.
     *
     * @param preJobField Field in the previous result to get data from
     * @param requestBody Content for request body if no previous data could be
     * found
     * @return
     */
    protected JsonValue getEffectivePayload(String preJobField, JsonValue requestBody) {
        JsonValue preJobResult = this.extractPreJobResult();
        JsonValue extracted = null;

        if (preJobResult != null && preJobResult.getValueType() == JsonValue.ValueType.OBJECT) {
            JsonObject resultObj = preJobResult.asJsonObject();
            if (preJobField != null && resultObj.containsKey(preJobField)) {
                extracted = resultObj.get(preJobField);
            } else if (preJobField == null) {
                extracted = resultObj;
            }
        } else if (preJobResult != null && preJobField == null) {
            extracted = preJobResult;
        }

        if (requestBody != null) {
            if (extracted != null) {
                if (extracted.getValueType() == JsonValue.ValueType.OBJECT
                        && requestBody.getValueType() == JsonValue.ValueType.OBJECT) {
                    return mergeJsonObjects(extracted.asJsonObject(), requestBody.asJsonObject());

                } else if (extracted.getValueType() == JsonValue.ValueType.ARRAY
                        && requestBody.getValueType() == JsonValue.ValueType.ARRAY) {
                    return mergeJsonArrays(extracted.asJsonArray(), requestBody.asJsonArray());
                } else {
                    // If there is no way to merge extracted has priority
                    return extracted;
                }
            } else {
                // If no extracted result is available use configured default body
                return requestBody;
            }
        } else if (extracted != null) {
            return extracted;
        }

        throw new IllegalStateException("No body provided and no usable result from previous job.");
    }

    /**
     * Merges two JsonObjects
     *
     * @param base
     * @param override
     * @return
     */
    private JsonObject mergeJsonObjects(JsonObject base, JsonObject override) {
        JsonObjectBuilder merged = Json.createObjectBuilder();
        for (Map.Entry<String, JsonValue> entry : base.entrySet()) {
            merged.add(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, JsonValue> entry : override.entrySet()) {
            merged.add(entry.getKey(), entry.getValue()); // override hat Vorrang
        }
        return merged.build();
    }

    private JsonArray mergeJsonArrays(JsonArray base, JsonArray extra) {
        JsonArrayBuilder builder = Json.createArrayBuilder();
        base.forEach(builder::add);
        extra.forEach(builder::add);
        return builder.build();
    }

}
