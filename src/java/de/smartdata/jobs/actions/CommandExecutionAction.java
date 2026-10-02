package de.smartdata.jobs.actions;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Action for executing command line commands.
 *
 * @author Florian Fehring
 */
public class CommandExecutionAction extends Action {

    private JsonObject params;

    @Override
    public JsonArray getParamsDescription() {
        JsonArrayBuilder jab = Json.createArrayBuilder();

        JsonObjectBuilder p1 = Json.createObjectBuilder();
        p1.add("name", "command");
        p1.add("type", "string");
        p1.add("description", "Command line command (e.g. sh or powershell call)");
        p1.add("optional", false);
        jab.add(p1);
        JsonObjectBuilder p2 = Json.createObjectBuilder();
        p2.add("name", "parameter");
        p1.add("type", "string");
        p2.add("description", "List of parameters for the command.");
        p2.add("optional", true);
        jab.add(p2);

        return jab.build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        if (params.getString("command", null) == null) {
            throw new ActionConfigurationException("Parameter >command< is missing.");
        }
        this.params = params;
    }

    @Override
    public JsonObject call() {
        JsonObjectBuilder result = Json.createObjectBuilder();

        List<String> commandWithArgs = new ArrayList<>();
        commandWithArgs.add(this.params.getString("command"));
        for (Entry<String, JsonValue> curEntry : this.params.entrySet()) {
            // Add parameters
            if (curEntry.getKey().equalsIgnoreCase("parameter")) {
                String val = curEntry.getValue().toString().replaceAll("\"", "");
                // Check absolute paths
                if (val.startsWith("/")) {
                    File file = new File(val);
                    if (file.exists()) {
                        // Parameter ist ein existierender Pfad, in absoluten Pfad umwandeln
                        val = file.getAbsolutePath();
                    }
                }

                commandWithArgs.add(val);
            }
        }
        result.add("cmdstr", String.join(";", commandWithArgs));
        ProcessBuilder processBuilder = new ProcessBuilder(commandWithArgs);
        processBuilder.redirectErrorStream(true); // Standard- und Fehlerausgabe zusammenführen

        try {
            Process process = processBuilder.start();

            // Output des Prozesses in einem separaten Thread lesen
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> outputFuture = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                    }
                    return output.toString();
                }
            });

            // Timeout für den Prozess definieren
            boolean finished = process.waitFor(1, TimeUnit.MINUTES);
            if (!finished) {
                process.destroy(); // Beendet den Prozess
                process.destroyForcibly(); // Falls erforderlich, Prozess erzwingen
                result.add("state", "failed");
                result.add("statemsg", "Process reached timeout.");
                return result.build();
            }

            // Ausgabe auslesen
            String msg = outputFuture.get(5, TimeUnit.SECONDS); // Timeout für das Lesen der Ausgabe
            if (msg.contains("Traceback")) {
                result.add("state", "failed");
            } else {
                result.add("state", "finished");
            }
            try {
                if(msg.startsWith("[")) {
                    JsonReader reader = Json.createReader(new StringReader(msg));
                    JsonArray jarr = reader.readArray();
                    result.add("records", jarr);
                } else if(msg.startsWith("{")) {
                    JsonReader reader = Json.createReader(new StringReader(msg));
                    JsonObject jobj = reader.readObject();
                    JsonArrayBuilder records = Json.createArrayBuilder();
                    records.add(jobj);
                    result.add("records", records);
                } else {
                    msg = this.escapeString(msg);
                    result.add("statemsg", msg);
                }
            } catch (Exception ex) {
//                msg = this.escapeString(msg);
                result.add("statemsg", "Error parsing JSON >" + msg + "<: " + ex.getLocalizedMessage());
            }

            executor.shutdown();
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException ex) {
            String msg = this.escapeString(ex.getLocalizedMessage());
            result.add("state", "failed");
            msg = this.escapeString(msg);
            result.add("statemsg", msg);
        }
        return result.build();
    }

    /**
     * Escapes strings to be useable in Json response.
     *
     * @param inputString Original string
     * @return String with escaped characters
     */
    private String escapeString(String inputString) {
        String escapedString = inputString
                .replace("\\", "\\\\") // Backslashes escapen
                .replace("\n", "\\n") // Zeilenumbrüche escapen
                .replace("\r", "\\r") // Wagenrückläufe escapen
                .replace("\t", "\\t")
                .replace("\"", ""); // Tabs escapen
        return escapedString;
    }
}
