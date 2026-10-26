package de.smartdata.jobs.actions;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;

public class CreateFlagFileAction extends Action {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private String filePath;

    @Override
    public JsonArray getParamsDescription() {
        return Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "file_path")
                        .add("type", "string")
                        .add("description", "Path where the flag file should be created")
                        .add("optional", false))
                .build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        this.filePath = params.getString("file_path", null);
        if (this.filePath == null || this.filePath.isEmpty()) {
            throw new ActionConfigurationException("Parameter >file_path< is missing.");
        }
    }

    @Override
    public JsonObject call() {
        JsonObjectBuilder result = Json.createObjectBuilder();

        try {
            String measurementId = generateMeasurementId();
            System.out.print("1");
            writeJsonFile(measurementId);
            System.out.print("11");

            return result
                    .add("status", 200)
                    .add("message", "Flag file created successfully")
                    .add("measurementProcessId", measurementId)
                    .add("file_Path", filePath)
                    .build();

        } catch (IOException e) {
            Logger.addMessage(new Message("Error creating flag file: " + e.getLocalizedMessage(), MessageLevel.ERROR));
            return result
                    .add("status", 500)
                    .add("message", "Error creating flag file: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Creates or overwrites the JSON file with the measurement ID and
     * timestamps.
     */
    private void writeJsonFile(String data) throws IOException {
        Path path = Paths.get(filePath);
        Path directories = path.getParent();

        if (directories != null) {
            Files.createDirectories(directories);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        String timestamp = ZonedDateTime.now(ZONE).format(formatter);

        Jsonb jsonb = JsonbBuilder.create();

        JsonObject finalJsonObject = Json.createObjectBuilder()
                .add("measurementProcessId", data)
                .add("timestamp", timestamp)
                .add("last_use", timestamp)
                .build();

        String finalJson = jsonb.toJson(finalJsonObject);
        Files.writeString(path, finalJson, StandardCharsets.UTF_8);
    }

    /**
     * Generates a new measurement ID in the format MAC_DATE_COUNTER (e.g.
     * 00a0c9_20251201_001). If measurements already exist for today, the
     * counter is incremented.
     */
    private String generateMeasurementId() throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        int newNumber = 1;

        Optional<String> lastIdOpt = readLastMeasurementId();
        if (lastIdOpt.isPresent()) {
            String lastId = lastIdOpt.get();
            String[] parts = lastId.split("_");
            if (parts.length == 3) {
                String datePart = parts[1];
                String numPart = parts[2];

                LocalDate lastDate = LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE);

                if (lastDate.equals(today)) {
                    int lastNumber = Integer.parseInt(numPart);
                    newNumber = lastNumber + 1;
                }
            }
        }

        String mac = getMacAddress().orElse("000000000000").replace(":", "");
        String number = String.format("%03d", newNumber);

        return mac + "_" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "_" + number;
    }

    /**
     * Retrieves the MAC address of the first available and active network
     * interface.
     */
    private Optional<String> getMacAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }

                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }

                StringBuilder sb = new StringBuilder();
                for (byte b : mac) {
                    sb.append(String.format("%02x", b));
                }
                return Optional.of(sb.toString());
            }
        } catch (Exception e) {
            Logger.addMessage(new Message("Error MAC: " + e.getLocalizedMessage(), MessageLevel.ERROR));
        }
        return Optional.empty();
    }

    /**
     * Reads the measurement ID from the existing JSON file, if it exists.
     */
    private Optional<String> readLastMeasurementId() {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(path);
            Jsonb jsonb = JsonbBuilder.create();
            Map<String, Object> map = jsonb.fromJson(json, Map.class);

            if (map.containsKey("measurementProcessId")) {
                return Optional.of(map.get("measurementProcessId").toString());
            }
        } catch (Exception ignored) {
        }

        return Optional.empty();
    }
}
