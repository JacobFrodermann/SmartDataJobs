package de.smartdata.jobs.actions;

import de.fhbielefeld.scl.rest.util.JSONFormatter;
import de.ngi.logging.Logger;
import de.smartdata.jobs.actions.networkactions.HttpClientProvider;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.net.http.HttpClient;

/**
 * This class allows checks of http connections
 *
 * @author Florian Fehring
 */
public class CheckNetAction extends AbstractNetworkAction {

    private static final Set<Integer> VALID_HTTP_STATUS_CODES = new HashSet<>(Arrays.asList(
            100, 101, 102, 103, // Informational
            200, 201, 202, 203, 204, 205, 206, 207, 208, 226, // Success
            300, 301, 302, 303, 304, 305, 306, 307, 308, // Redirection
            400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 421, 422, 423, 424, 425, 426, 428, 429, 431, 451, // Client Errors
            500, 501, 502, 503, 504, 505, 506, 507, 508, 510, 511 // Server Errors
    ));

    private String url;
    private String requestMethod;
    private String username;
    private String userpwd;
    private Integer statuscode;
    private Integer checklastseconds;
    // Calculated on runtime
    private LocalDateTime thresholdTime = null;
    private LocalDateTime newestFileTime = null;
    private long clientServerSecondsDiff = 0;

    @Override
    public JsonArray getParamsDescription() {
        Logger.log();
        JsonArrayBuilder jab = Json.createArrayBuilder();

        JsonObjectBuilder p1 = Json.createObjectBuilder();
        p1.add("name", "url");
        p1.add("type", "url");
        p1.add("description", "URL where the check shoud be done. Currently supported are http(s) and ftp.");
        p1.add("optional", false);
        jab.add(p1);
        JsonObjectBuilder p2 = Json.createObjectBuilder();
        p2.add("name", "statuscode");
        p2.add("type", "int");
        p2.add("description", "Expected HTTP status code. Checked on http(s) connections.");
        p2.add("optional", true);
        jab.add(p2);
        JsonObjectBuilder p3 = Json.createObjectBuilder();
        p3.add("name", "requestmethod");
        p3.add("type", "string");
        p3.add("description", "HTTP Request method to use for the check.");
        p3.add("optional", true);
        jab.add(p3);
        JsonObjectBuilder p4 = Json.createObjectBuilder();
        p4.add("name", "username");
        p4.add("type", "string");
        p4.add("description", "Username for the connection.");
        p4.add("optional", true);
        jab.add(p4);
        JsonObjectBuilder p5 = Json.createObjectBuilder();
        p5.add("name", "userpwd");
        p5.add("type", "string");
        p5.add("description", "Users password for the connection.");
        p5.add("optional", true);
        jab.add(p5);
        JsonObjectBuilder p6 = Json.createObjectBuilder();
        p6.add("name", "checklastseconds");
        p6.add("type", "int");
        p6.add("description", "On FTP check time in seconds where a new file should be created on the target.");
        p6.add("optional", true);
        jab.add(p6);
        jab.add(Json.createObjectBuilder()
                .add("name", "urlparams")
                .add("type", "string")
                .add("description", "Optional JSON object with key-value pairs for URL parameters.")
                .add("optional", true));

        return jab.build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        Logger.log();
        this.url = params.getString("url", null);
        if (this.url == null) {
            throw new ActionConfigurationException("Parameter >url< is missing. Please check your action configuration.");
        }
        // Check url validity
        try {
            new URL(params.getString("url"));
        } catch (MalformedURLException e) {
            throw new ActionConfigurationException(e.getLocalizedMessage());
        }
        // Prechecks for HTTP check
        if (this.url.startsWith("http")) {
            // Check HTTP Status code
            if (!VALID_HTTP_STATUS_CODES.contains(params.getInt("statuscode"))) {
                throw new ActionConfigurationException("Given StatusCode >" + params.getInt("statuscode") + "< is not a valind HTTP Statuscode.");
            }
            this.statuscode = params.getInt("statuscode");

            this.requestMethod = params.getString("requestmethod");
        }

        this.username = params.getString("username", null);
        this.userpwd = params.getString("userpwd", null);
        if (params.containsKey("checklastseconds")) {
            this.checklastseconds = params.getInt("checklastseconds");
        }

        if (params.containsKey("urlparams")) {
            this.urlParams = params.getString("urlparams");
        }
    }

    @Override
    public JsonObject call() {
        Logger.log();
        JsonObjectBuilder result = Json.createObjectBuilder();

        if (this.url.startsWith("http")) {
            try {
                Logger.log("Check as http connection");

                String fullUrl = buildUrl(url, urlParams);
                result.add("calledUrl", fullUrl);
                
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(50))
                        .method(requestMethod.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", getUserAgent())
                        .header("Accept", "application/json");

                if (username != null && userpwd != null) {
                    String credentials = Base64.getEncoder()
                            .encodeToString((username + ":" + userpwd).getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + credentials);
                }
                HttpRequest request = builder.build();
                HttpClient client = HttpClientProvider.getClient();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                //System.out.println("Response: " + response.body());
                int responseCode = response.statusCode();
                result.add("recivedStatusCode", responseCode);

                if (responseCode == this.statuscode) {
                    result.add("state", "finished");
                } else {
                    result.add("state", "failed");
                    result.add("statemsg", "Statuscode >" + this.statuscode + "< was expected but got >" + responseCode + "<");
                }
            } catch (IOException e) {
                result.add("state", "failed");
                result.add("statemsg", "IOException: " + JSONFormatter.escapeForJson(e.getLocalizedMessage()));
                if (e.getMessage() == null) {
                    // DNS-Check
                    try {
                        InetAddress address = InetAddress.getByName(this.url);
                        result.add("stateHint", "DNS delivered ip: " + address.getHostAddress() + ". Check your url and if the port needed is given. If so check connection.");
                    } catch (UnknownHostException dnsEx) {
                        result.add("statemsg", "DNS resolution failed: " + dnsEx.getMessage());
                    }
                    e.printStackTrace();
                }
            } catch (Exception ex) {
                result.add("state", "failed");
                result.add("statemsg", ex.getClass().getSimpleName() + ": " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
                ex.printStackTrace();
            }
        } else if (this.url.startsWith("ftp")) {
            try {
                Logger.log("Check as ftp connection");
                // FTP-Verbindung prüfen
                String ftpHost = new URL(this.url).getHost();
                String directoryPath = new URL(this.url).getPath();
                FTPClient ftpClient = new FTPClient();
                ftpClient.connect(ftpHost);
                int replyCode = ftpClient.getReplyCode();
                if (FTPReply.isPositiveCompletion(replyCode)) {
                    // Anmelden mit Benutzername und Passwort
                    boolean loginSuccess = ftpClient.login(this.username, this.userpwd);
                    if (!loginSuccess) {
                        Logger.log("ftp login failed");
                        result.add("state", "failed");
                        result.add("statemsg", "Authentication failed for FTP server");
                        return result.build();
                    } else {
                        Logger.log("ftp login succsessfull");
                    }

                    result.add("state", "connected");
                    ftpClient.enterLocalPassiveMode();

                    // Calculate server client time diff
                    this.calculateFTPServerClientSecondsDiff(ftpClient);
                    // Calculate check timepoint for checks that reley on the last x seconds
                    if (this.checklastseconds != null) {
                        LocalDateTime local_now = LocalDateTime.now();
                        Logger.log("Local now: " + local_now);
                        LocalDateTime server_now = local_now.minusSeconds(this.clientServerSecondsDiff);
                        Logger.log("Server now: " + server_now);
                        // Substract checktime from server time and substract one second delay too
                        this.thresholdTime = server_now.minusSeconds(this.checklastseconds).minusSeconds(1);
                        Logger.log("ThresholdTime: " + this.thresholdTime);
                    }

                    // Set start working directory
                    if (!ftpClient.changeWorkingDirectory(directoryPath)) {
                        result.add("state", "directory >" + ftpClient.printWorkingDirectory() + directoryPath + "< not found");
                        return result.build();
                    }
                    // Check files in this directory
                    result = this.checkFiles(ftpClient, directoryPath, result);
                    // Check files in sub directories
                    result = this.checkDirectories(ftpClient, directoryPath, result);
                    ftpClient.logout();
                } else {
                    result.add("state", "failed");
                    result.add("statemsg", "Failed to connect to FTP server");
                }

                ftpClient.disconnect();
            } catch (IOException e) {
                result.add("state", "failed");
                result.add("statemsg", "IOException: " + e.getMessage());
                e.printStackTrace();
//            result.add("statemsg", "IOException: " + JSONFormatter.escapeForJson(e.getLocalizedMessage()));
            } catch (Exception ex) {
                result.add("state", "failed");
                result.add("statemsg", ex.getClass().getSimpleName() + ": " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
            }
        } else {
            result.add("state", "failed");
            result.add("statemsg", "Unsupported protocol: " + this.url);
        }

        return result.build();
    }

    /**
     * Check files if there is a directories. Calls itself the search for files.
     *
     * @param ftpClient Client to access FTP server
     * @param directoryPath Directory to search directories at
     * @param result Result object for storing found informations
     * @return Modified result object
     */
    public JsonObjectBuilder checkDirectories(FTPClient ftpClient, String directoryPath, JsonObjectBuilder result) {
//        Logger.log("Check directories at >" + directoryPath + "<");
        try {
            ftpClient.changeWorkingDirectory(directoryPath);
            FTPFile[] files = ftpClient.listDirectories();
            List<FTPFile> dirs = Arrays.stream(files)
                    .filter(file -> file.isDirectory())
                    .toList();
            for (FTPFile dir : dirs) {
                result = this.checkFiles(ftpClient, directoryPath + "/" + dir.getName(), result);
                // Recursive call
                result = this.checkDirectories(ftpClient, directoryPath + "/" + dir.getName(), result);
            }
        } catch (IOException ex) {
            result.add("state", "Error checking directory >" + directoryPath + "<: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
        }
        return result;
    }

    /**
     * Check files if there is a file within the timebounds.
     *
     * @param ftpClient Client to access FTP server
     * @param directoryPath Directory to search files at
     * @param result Result object for storing found informations
     * @return Modified result object
     */
    public JsonObjectBuilder checkFiles(FTPClient ftpClient, String directoryPath, JsonObjectBuilder result) {
//        Logger.log("Check files at >" + directoryPath + "<");
        try {
            ftpClient.changeWorkingDirectory(directoryPath);

            FTPFile[] files = ftpClient.listFiles();
            List<FTPFile> realfiles = Arrays.stream(files)
                    .filter(file -> file.isFile())
                    .toList();

            boolean newFileFound = false;
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            for (FTPFile file : realfiles) {
                SimpleDateFormat formatter2 = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
                Date fileTimestamp = file.getTimestamp().getTime();
                String formattedDate = formatter2.format(fileTimestamp);
                LocalDateTime fileDateTime = LocalDateTime.ofInstant(fileTimestamp.toInstant(), ZoneId.systemDefault());
                if (this.newestFileTime == null || fileDateTime.isAfter(this.newestFileTime)) {
                    this.newestFileTime = fileDateTime;
                    result.add("newestFile", file.getName());
                    result.add("newestFileTime", fileDateTime.plusSeconds(this.clientServerSecondsDiff).format(formatter));
                    result.add("newestFileSize", file.getSize());
                }

                // If a file is empty it does not count
                if (file.getSize() == 0) {
//                    Logger.log("Ignoring file because filesize is 0.");
                    continue;
                } else {
//                    Logger.log("Found filesize: " + file.getSize());
                }

//                Logger.log("Check if >" + formattedDate + "< is after >" + this.thresholdTime + "<");
                if (this.thresholdTime != null && fileDateTime.isAfter(this.thresholdTime)) {
//                    Logger.log("This is a new file!");
                    newFileFound = true;
                }
            }

            if (newFileFound) {
                result.add("state", "new file found");
            } else {
                result.add("state", "no new file in the last " + this.checklastseconds + " seconds");
            }
        } catch (IOException ex) {
            result.add("checkFiles() error", "Could not check files in >" + directoryPath + "< because of: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
        } catch (Exception ex) {
            Logger.log("Exception: " + ex.getClass().getSimpleName() + ": " + ex.getLocalizedMessage());
            ex.printStackTrace();
            result.add("checkFiles() error", "Could not check files in >" + directoryPath + "< because of: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));

        }
        return result;
    }

    /**
     * Calculates the difference between the time on the FTP server and the
     * host.
     *
     * @param ftpClient
     * @return Time difference in secnods. e.g. Server: 11:00 - Client: 12:00 ->
     * diff = 7251
     */
    public Long calculateFTPServerClientSecondsDiff(FTPClient ftpClient) {
        Logger.log();
        String testfileName = "ftpTimeCoordinator";
        try {
            // Step 1: Craate test file
            String fileContent = "Testdatei zur Zeitzonenprüfung.";
            ByteArrayInputStream inputStream = new ByteArrayInputStream(fileContent.getBytes(StandardCharsets.UTF_8));
            boolean uploaded = ftpClient.storeFile(testfileName, inputStream);
            inputStream.close();

            if (uploaded) {
                // Step 2: Get server time from timestamp of new file
                FTPFile[] files = ftpClient.listFiles(testfileName);
                if (files.length > 0) {
                    Date serverTimestamp = files[0].getTimestamp().getTime();
                    Instant serverInstant = serverTimestamp.toInstant();

                    // Step 3: Get local time
                    Instant localInstant = Instant.now();

                    // Step 4: Calculate difference
                    Duration difference = Duration.between(serverInstant, localInstant);
                    long hoursDiff = difference.toHours();
                    long minutesDiff = difference.toMinutesPart();
                    this.clientServerSecondsDiff = difference.getSeconds();
                    Logger.log("Client-Server time differnce: " + hoursDiff + " Stunden und " + minutesDiff + " Minuten");

                    // Step 5: Delete test file
                    boolean deleted = ftpClient.deleteFile(testfileName);
                    if (!deleted) {
                        Logger.log("Warning: Test file could not be deleted.");
                    }
                } else {
                    Logger.log("Fehler: Datei-Zeitstempel konnte nicht abgerufen werden.");
                }
            } else {
                Logger.log("Fehler beim Hochladen der Datei.");
            }
        } catch (IOException ex) {
            Logger.log("Fehler" + ex.getLocalizedMessage());
        }
        return null;
    }
}
