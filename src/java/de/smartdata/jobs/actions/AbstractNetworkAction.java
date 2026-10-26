package de.smartdata.jobs.actions;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Exteends the Action class with methods specific for network cases
 *
 * @author Florian Fehring
 */
public abstract class AbstractNetworkAction extends Action {

    protected String urlParams;

    public String buildQueryParams(String urlParamsJson) throws UnsupportedEncodingException {
        Map<String, List<String>> paramMap = new LinkedHashMap<>();

        JsonReader reader = Json.createReader(new StringReader(urlParamsJson));
        JsonObject obj = reader.readObject();

        for (Map.Entry<String, JsonValue> entry : obj.entrySet()) {
            String key = entry.getKey();
            JsonValue value = entry.getValue();

            if (!paramMap.containsKey(key)) {
                paramMap.put(key, new ArrayList<>());
            }

            switch (value.getValueType()) {
                case STRING:
                    String rawValue = ((JsonString) value).getString();

                    // 🔍 Neue Logik: Suche nach eingebetteten Subparametern (&key=value)
                    String[] splitParts = rawValue.split("&");
                    boolean first = true;

                    for (String part : splitParts) {
                        if (first) {
                            paramMap.get(key).add(part);
                            first = false;
                        } else if (part.contains("=")) {
                            String[] subParts = part.split("=", 2);
                            String subKey = subParts[0];
                            String subVal = subParts.length > 1 ? subParts[1] : "";

                            // 📌 In separater Map speichern
                            if (!paramMap.containsKey(subKey)) {
                                paramMap.put(subKey, new ArrayList<>());
                            }
                            paramMap.get(subKey).add(subVal);
                        } else {
                            // Falls kein '=' enthalten ist: als leerer Eintrag unter aktuellem Schlüssel
                            paramMap.get(key).add(part);
                        }
                    }
                    break;

                case NUMBER:
                    paramMap.get(key).add(((JsonNumber) value).toString());
                    break;

                case TRUE:
                case FALSE:
                    paramMap.get(key).add(value.toString());
                    break;

                case OBJECT:
                    paramMap.get(key).add(value.toString());
                    break;

                case ARRAY:
                    JsonArray array = value.asJsonArray();
                    for (JsonValue v : array) {
                        paramMap.get(key).add(v.toString().replaceAll("^\"|\"$", ""));
                    }
                    break;

                case NULL:
                    paramMap.get(key).add("");
                    break;
            }
        }

        // 🔐 URL-Parameter zusammenbauen
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : paramMap.entrySet()) {
            String key = URLEncoder.encode(entry.getKey(), "UTF-8");
            for (String v : entry.getValue()) {
                String val = URLEncoder.encode(v, "UTF-8");
                if (sb.length() > 0) {
                    sb.append("&");
                }
                sb.append(key).append("=").append(val);
            }
        }

        return sb.toString();
    }

    /**
     * Builds an url useing the given base url, adding params an replaceing
     * placeholders. Supported placeholders: {mac} - Replaced by MAC-Address,
     * {hostname} - Replaced by Hostname
     *
     * @param baseurl Base url
     * @param urlParamsJson Json containing url params
     * @return Url string
     * @throws ActionPerformanceException
     */
    protected String buildUrl(String baseurl, String urlParamsJson) throws ActionPerformanceException {
        try {
            // Build url
            String fullUrl = baseurl;
               
            if (urlParamsJson != null && !urlParamsJson.isEmpty()) {
                if(fullUrl.contains("?")) {
                    fullUrl += "&";
                } else {
                    fullUrl += "?";
                }
                fullUrl += this.buildQueryParams(urlParamsJson);
            }

            if (fullUrl.contains("{mac}")) {
                String mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
                fullUrl = fullUrl.replaceAll("\\{mac\\}", Matcher.quoteReplacement(mac));
            }
            if (fullUrl.contains("{hostname}")) {
                String hostname = getLocalHostname();
                fullUrl = fullUrl.replaceAll("\\{hostname\\}", Matcher.quoteReplacement(hostname));
            }
            return fullUrl;
        } catch (UnsupportedEncodingException ex) {
            throw new ActionPerformanceException("Could not build request url: " + ex.getLocalizedMessage());
        }
    }

    public static String getUserAgent() {
        String hostname = "unknown";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            System.getLogger(AbstractNetworkAction.class.getName()).log(System.Logger.Level.ERROR, "Could not get Hostname.", ex);

        }
        String mac = null;
        try {
            mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
        } catch (Exception ex) {
            System.getLogger(AbstractNetworkAction.class.getName()).log(System.Logger.Level.ERROR, "Could not get MAC address.", ex);
        }

        String user_agent = "SmartDataJobs on " + hostname;
        if (mac != null) {
            user_agent += " MAC(" + mac + ")";
        }

        return user_agent;
    }

    public static String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            System.getLogger(AbstractNetworkAction.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
        return null;
    }

    /**
     * Retrieves the MAC address of the first available and active network
     * interface.
     */
    private static Optional<String> getLocalMacAddress() {
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
}
