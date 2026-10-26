package de.smartdata.jobs.data;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.fhbielefeld.scl.rest.util.WebTargetCreator;
import de.smartdata.jobs.config.Configuration;
import de.ngi.restutils.DateTimeParser;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import static jakarta.json.JsonValue.ValueType.FALSE;
import static jakarta.json.JsonValue.ValueType.NULL;
import static jakarta.json.JsonValue.ValueType.NUMBER;
import static jakarta.json.JsonValue.ValueType.STRING;
import static jakarta.json.JsonValue.ValueType.TRUE;
import java.net.NetworkInterface;
import java.util.StringJoiner;
import java.sql.Timestamp;
import java.util.regex.Matcher;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Methods for accessing a SmartData instance to get data. Aims to simplify the
 * access of SmartData from other Java programs
 *
 * @author Florian Fehring
 */
public class SmartDataAccessor {

    protected String smartdataurl = null;
    protected String jndi = null;
    protected DataSource ds = null;
    protected String smartdataRequest; // Contains last called URL

    /**
     * Optimized SmartDataAccessor for a samrtdata instance
     *
     * @param smartdataurl URL to SmartData instance
     */
    public SmartDataAccessor(String smartdataurl) {
        this.smartdataurl = smartdataurl;
        // Get SmartData instance name
        int lastSlash = smartdataurl.lastIndexOf("/");
        String smartdataname = smartdataurl;
        if (lastSlash >= 0) {
            smartdataname = smartdataurl.substring(lastSlash + 1);
        }
        // Load configuration for instance
        Configuration conf = new Configuration(smartdataname);
        this.jndi = conf.getProperty("postgres.jndi");
        if (this.jndi == null) {
            this.jndi = "jdbc/SmartData";
        }
        try {
            InitialContext ctx = new InitialContext();
            this.ds = (DataSource) ctx.lookup(this.jndi);
        } catch (NamingException ex) {
            Message msg = new Message("", MessageLevel.ERROR, "Could not access connection pool: " + ex.getLocalizedMessage());
            Logger.addMessage(msg);
        }
    }

    public String getJndi() {
        return this.jndi;
    }

    public Connection getConnection() {
        if (this.ds == null) {
            return null;
        }
        try {
            return this.ds.getConnection();
        } catch (SQLException ex) {
            Message msg = new Message("", MessageLevel.ERROR, "Could not conntect to database: " + ex.getLocalizedMessage());
            Logger.addMessage(msg);
        }
        return null;
    }

    public JsonArray fetchData(JsonObject params, JsonObjectBuilder result) throws SmartDataAccessorException {
        // Parse parameter
        String storage = null;
        String collection = null;
        String includes = null;
        String filter = null;
        String dateattr = null;
        LocalDateTime start = null;
        LocalDateTime end = null;
        String order = null;
        Long limit = null;
        if (params == null || params.isEmpty()) {
            throw new SmartDataAccessorException("Could not parse urlParams for SmartData parameters.");
        }

        storage = params.getString("storage", "public");
        collection = params.getString("collection", null);
        if (collection == null) {
            throw new SmartDataAccessorException("No collection given within urlParms.");
        }

        includes = params.getString("includes", null);
        filter = params.getString("filter", null);
        dateattr = params.getString("dateattr", null);
        String startStr = params.getString("start", null);
        if (startStr != null) {
            start = LocalDateTime.parse(startStr);
        }
        String endStr = params.getString("end", null);
        if (endStr != null) {
            end = LocalDateTime.parse(endStr);
        }
        order = params.getString("order", null);
        JsonNumber limitJson = params.getJsonNumber("size");
        if (limitJson != null) {
            limit = limitJson.longValue();
        } else {
            limit = 100L;
        }

        return this.fetchData(storage, collection, includes, filter, dateattr, start, end, order, limit, result);
    }

    /**
     * Get data from the SmartData and return it as JSON
     *
     * @param collection Collections name
     * @param storage Storages name
     * @param includes List of attributes that should be returned
     * @param filter Any filter statement accepted by SmartData
     * @param dateattr Attribute that stores date information (if start and end
     * should be used)
     * @param start Startdate to look at
     * @param end Enddate to look at
     * @param order Attribute name to order by
     * @param limit Number of datasets to fetch
     * @return JSON with available data
     * @throws de.smartdata.jobs.data.SmartDataAccessorException
     */
    public JsonArray fetchData(String storage, String collection,
            String includes, String filter,
            String dateattr, LocalDateTime start, LocalDateTime end,
            String order, Long limit, JsonObjectBuilder result) throws SmartDataAccessorException {
        de.ngi.logging.Logger.log();

        // Replace placeholders
        if (collection.contains("{mac}")) {
            try {
                String mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
                collection = collection.replaceAll("\\{mac\\}", Matcher.quoteReplacement(mac));
            } catch (Exception e) {
                throw new SmartDataAccessorException("Could not get MAC-Address: " + e.getMessage());
            }
        }

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            result.add("fetchMethod", "SmartDataAccessor/JDBC direct connection");
            if (includes == null) {
                includes = "*";
            }

            try {
                // Get columns and type info
                Map<String, String> columnTypes = new LinkedHashMap<>();
                PreparedStatement stmt = con.prepareStatement(
                        "SELECT column_name, udt_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position");
                stmt.setString(1, storage);
                stmt.setString(2, collection);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    columnTypes.put(rs.getString("column_name"), rs.getString("udt_name"));
                }

                // Select columns to use
                List<String> selectedColumns;
                if ("*".equals(includes.trim())) {
                    selectedColumns = new ArrayList<>(columnTypes.keySet());
                } else {
                    selectedColumns = Arrays.stream(includes.split(","))
                            .map(String::trim)
                            .filter(columnTypes::containsKey) // validieren
                            .collect(Collectors.toList());
                }

                String cols = selectedColumns.stream()
                        .map(col -> "geometry".equalsIgnoreCase(columnTypes.get(col))
                        ? "ST_AsText(\"" + col + "\") AS \"" + col + "\""
                        : "\"" + col + "\"")
                        .collect(Collectors.joining(", "));

                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT " + cols + " FROM \"" + storage + "\".\"" + collection + "\"";
                List<String> conditions = new ArrayList<>();

                if (dateattr != null && start != null && end != null) {
                    conditions.add(dateattr + " >= '" + start + "'");
                    conditions.add(dateattr + " <= '" + end + "'");
                }

                if (filter != null && !filter.isEmpty()) {
                    try {
                        conditions.add(buildFilterCondition(filter));
                    } catch (SmartDataAccessorException e) {
                        throw new SmartDataAccessorException("Invalid filter: " + e.getMessage());
                    }
                }

                if (!conditions.isEmpty()) {
                    sql += " WHERE " + String.join(" AND ", conditions);
                }

                if (order != null) {
                    sql += " ORDER BY " + order.replace(',', ' ');
                }
                if (limit != null) {
                    sql += " LIMIT " + limit;
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);
                // Abfrage ausführen
                ResultSet resultSet = preparedStatement.executeQuery();

                // Ergebnis in eine Liste von Maps umwandeln
                JsonArrayBuilder newdataarr = Json.createArrayBuilder();

                while (resultSet.next()) {
                    JsonObjectBuilder newdataset = Json.createObjectBuilder();

                    int columnCount = resultSet.getMetaData().getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        String colName = resultSet.getMetaData().getColumnName(i);
                        int colType = resultSet.getMetaData().getColumnType(i);

                        try {
                            switch (colType) {
                                case Types.BOOLEAN:
                                case Types.BIT:
                                    boolean boolVal = resultSet.getBoolean(i);
                                    if (!resultSet.wasNull()) {
                                        newdataset.add(colName, boolVal);
                                    }
                                    break;

                                case Types.INTEGER:
                                case Types.SMALLINT:
                                    int intVal = resultSet.getInt(i);
                                    if (!resultSet.wasNull()) {
                                        newdataset.add(colName, intVal);
                                    }
                                    break;

                                case Types.BIGINT:
                                    long longVal = resultSet.getLong(i);
                                    if (!resultSet.wasNull()) {
                                        newdataset.add(colName, longVal);
                                    }
                                    break;

                                case Types.FLOAT:
                                case Types.REAL:
                                    float floatVal = resultSet.getFloat(i);
                                    if (!resultSet.wasNull()) {
                                        newdataset.add(colName, floatVal);
                                    }
                                    break;

                                case Types.DOUBLE:
                                case Types.NUMERIC:
                                case Types.DECIMAL:
                                    double doubleVal = resultSet.getDouble(i);
                                    if (!resultSet.wasNull()) {
                                        newdataset.add(colName, doubleVal);
                                    }
                                    break;

                                case Types.TIMESTAMP:
                                case Types.TIMESTAMP_WITH_TIMEZONE:
                                    java.sql.Timestamp timestamp = resultSet.getTimestamp(i);
                                    if (timestamp != null) {
                                        newdataset.add(colName, timestamp.toString());
                                    }
                                    break;

                                case Types.DATE:
                                    java.sql.Date date = resultSet.getDate(i);
                                    if (date != null) {
                                        newdataset.add(colName, date.toString());
                                    }
                                    break;

                                case Types.VARCHAR:
                                case Types.CHAR:
                                case Types.LONGVARCHAR:
                                case Types.CLOB:
                                case Types.NVARCHAR:
                                case Types.NCHAR:
                                case Types.LONGNVARCHAR:
                                case Types.NCLOB:
                                    String strVal = resultSet.getString(i);
                                    if (strVal != null) {
                                        newdataset.add(colName, strVal);
                                    }
                                    break;

                                case Types.OTHER:
                                    // Für spezielle Typen wie PostGIS Geometry oder benutzerdefinierte Typen
                                    String otherVal = resultSet.getString(i);
                                    if (otherVal != null) {
                                        newdataset.add(colName, otherVal);
                                    }
                                    break;

                                default:
                                    System.out.println("Unsupported SQL type: " + colType + " for column " + colName);
                                    break;
                            }
                        } catch (SQLException ex) {
                            newdataset.add("error_" + colName, "Could not set value: " + ex.getLocalizedMessage());
                        }
                    }

                    newdataarr.add(newdataset);
                }

                resultSet.close();
                preparedStatement.close();

                JsonArray data = newdataarr.build();
                return data;
            } catch (SmartDataAccessorException | SQLException ex) {
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());

            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        } else if (con != null) {
            try {
                con.close();
            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
            }
        } else {
            de.ngi.logging.Logger.log("Skip trying to use local db connection. Filter: " + filter + " found connection: " + con);
        }

        result.add("storeMethod", "SmartDataAccessor/REST-API connection");

        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "records")
                .path(collection)
                .queryParam("storage", storage);
        if (filter != null) {
            webTarget = webTarget.queryParam("filter", filter);
        }
        if (includes != null) {
            webTarget = webTarget.queryParam("includes", includes);
        }
        if (start != null && end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }
        if (order != null) {
            webTarget = webTarget.queryParam("order", order);
        }
        if (limit != null) {
            webTarget = webTarget.queryParam("size", limit);
        }
        de.ngi.logging.Logger.log("Try get data over REST API...");
        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();
        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray records = parser.getObject().getJsonArray("records");
            if (records == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >records<");
            }
            return records;
        } else if (response.getStatus() == 404) {
            throw new SmartDataAccessorException("Target >" + webTarget.getUri() + "< was not found.: ");
        } else {
            throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + response.getStatus());
        }
    }

    /**
     * Gets a list of available collections on the smartdata
     *
     * @param storage Name of the storage to look at
     * @return List of collection names
     * @throws de.smartdata.jobs.data.SmartDataAccessorException
     */
    public List<String> fetchCollectons(String storage) throws SmartDataAccessorException {
        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                this.smartdataurl + "/smartdata", "storage")
                .path("getCollections")
                .queryParam("name", storage);

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray list = parser.getObject().getJsonArray("list");
            if (list == null) {
                throw new SmartDataAccessorException("Could not get storages from >" + webTarget.getUri() + "< retuned no >list<");
            }
            List<String> collections = new ArrayList<>();
            for (JsonValue curVal : list) {
                collections.add(curVal.asJsonObject().getString("name"));
            }
            return collections;
        } else {
            throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + responseText);
        }
    }

    /**
     * Get the size of the collection in byte
     *
     * @param collection Collections name
     * @param storage Storages name
     * @return bytes of sotrage useage (estimated)
     * @throws SmartDataAccessorException
     */
    public long fetchSize(String collection, String storage) throws SmartDataAccessorException {

        // Replace placeholders
        if (collection.contains("{mac}")) {
            try {
                String mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
                collection = collection.replaceAll("\\{mac\\}", Matcher.quoteReplacement(mac));
            } catch (Exception e) {
                throw new SmartDataAccessorException("Could not get MAC-Address: " + e.getMessage());
            }
        }

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT pg_relation_size('\"" + storage + "\".\"" + collection + "\"')";
                long bytes;
                // Abfrage ausführen
                try (PreparedStatement preparedStatement = con.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
                    // Ergebnis verarbeiten
                    bytes = 0L;
                    if (resultSet.next()) {
                        bytes = resultSet.getLong(1);
                    }
                }
                return bytes;
            } catch (Exception ex) {
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());

            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        throw new SmartDataAccessorException("Could not get size for >" + this.smartdataurl + "<: Size fetching is currently not supported for databases accessable only over SmartData.");
    }

    public String getSmartdataRequest() {
        return this.smartdataRequest;
    }

    /**
     * Saves data to the database
     *
     * @deprecated Should be replaced by useage of saveData()
     *
     * @param smartdataurl
     * @param collection
     * @param storage
     * @param datajob_id
     * @param data
     * @param haserror
     * @throws SmartDataAccessorException
     */
    public void saveDataSpecial(String smartdataurl, String collection, String storage, Long datajob_id, JsonObject data, boolean haserror, boolean wastimeout) throws SmartDataAccessorException {
        de.ngi.logging.Logger.log();
        Connection con = this.getConnection();
        if (con != null) {
            String insertQuery = "INSERT INTO smartmonitoring.datajobs_log (datajob_id, result, iserror, wastimeout) VALUES (?, ?, ?, ?)";

            try {
                PreparedStatement preparedStatement = con.prepareStatement(insertQuery);
                // Parameter setzen
                preparedStatement.setLong(1, datajob_id);
                preparedStatement.setString(2, data.toString());
                preparedStatement.setBoolean(3, haserror);
                preparedStatement.setBoolean(4, wastimeout);

                int rowsInserted = preparedStatement.executeUpdate();
                if (rowsInserted > 0) {
                    de.ngi.logging.Logger.log("Succsessfull added dataset");
                } else {
                    de.ngi.logging.Logger.log("No new dataset created");
                }
            } catch (SQLException ex) {
                de.ngi.logging.Logger.log("Error saveing in database: " + ex.getLocalizedMessage());
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not create new dataset: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        } else {
            de.ngi.logging.Logger.log("Could not save dataset, no connection available.");
        }
    }

    /**
     * Saves data to the SmartData API uses direct database access if
     * avaialable, otherwise it uses the specified SmartData REST-API
     *
     * @param params Parameters (storae, collection) in JsonObject
     * @param data JsonObject (single dataset) or JsonArray (multiple datasets)
     * to save
     * @param result A JsonObjectBuilder where messages can be stored
     * @throws SmartDataAccessorException
     */
    public void saveData(JsonObject params, JsonValue data, JsonObjectBuilder result) throws SmartDataAccessorException {
        // Parse parameter
        String storage;
        String collection;
        if (params == null || params.isEmpty()) {
            throw new SmartDataAccessorException("Could not parse urlParams for SmartData parameters.");
        }

        storage = params.getString("storage", "public");
        collection = params.getString("collection", null);
        if (collection == null) {
            throw new SmartDataAccessorException("No collection given within urlParms.");
        }

        this.saveData(storage, collection, data, result);
    }

    /**
     * Saves data to the SmartData API uses direct database access if
     * avaialable, otherwise it uses the specified SmartData REST-API
     *
     * @param storage Storage to save in
     * @param collection Collection to save in
     * @param data Data to save
     * @param result Result object applicable to add new result information
     * @throws SmartDataAccessorException
     */
    public void saveData(String storage, String collection, JsonValue data, JsonObjectBuilder result) throws SmartDataAccessorException {
        de.ngi.logging.Logger.log("saveJsonDataUnified()");

        // Replace placeholders
        if (collection.contains("{mac}")) {
            try {
                String mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
                collection = collection.replaceAll("\\{mac\\}", Matcher.quoteReplacement(mac));
            } catch (Exception e) {
                throw new SmartDataAccessorException("Could not get MAC-Address: " + e.getMessage());
            }
        }

        // Convert single dataset to list of datasets if neccessery
        JsonArray dataArray;
        if (data instanceof JsonObject) {
            dataArray = Json.createArrayBuilder().add((JsonObject) data).build();
        } else if (data instanceof JsonArray) {
            dataArray = (JsonArray) data;
        } else {
            throw new SmartDataAccessorException("Invalid JSON datasets given.");
        }

        if (dataArray.isEmpty()) {
            throw new SmartDataAccessorException("There was no data given.");
        }

        // Get connection
        Connection con = this.getConnection();
        if (con != null) {
            result.add("storeMethod", "SmartDataAccessor/JDBC direct connection");
            try {
                // Get all columns
                Set<String> allColumns = new LinkedHashSet<>();
                for (int i = 0; i < dataArray.size(); i++) {
                    JsonObject obj = dataArray.getJsonObject(i);
                    allColumns.addAll(obj.keySet());
                }

                // Create prepared statement with all columns
                StringJoiner columnJoiner = new StringJoiner(", ");
                StringJoiner placeholderJoiner = new StringJoiner(", ");
                for (String col : allColumns) {
                    columnJoiner.add(col);
                    if (col.equals("pos")) {
                        placeholderJoiner.add("ST_GeomFromText(?)");
                    } else {
                        placeholderJoiner.add("?");
                    }
                }
                String sql = "INSERT INTO " + storage + "." + collection + " (" + columnJoiner + ") VALUES (" + placeholderJoiner + ")";
                PreparedStatement stmt = con.prepareStatement(sql);
//                System.out.println("TEST sql: " + sql);

                for (int i = 0; i < dataArray.size(); i++) {
                    JsonObject obj = dataArray.getJsonObject(i);
                    int paramIndex = 1;
                    for (String col : allColumns) {
                        if (obj.containsKey(col)) {
                            JsonValue val = obj.getOrDefault(col, JsonValue.NULL);

                            if (val.getValueType() == JsonValue.ValueType.NULL) {
                                stmt.setNull(paramIndex, java.sql.Types.NULL);
                                continue;
                            }

                            switch (val.getValueType()) {
                                case STRING:
                                    String str = ((JsonString) val).getString();
                                    if (str.startsWith("POINT(")) {
                                        stmt.setString(paramIndex, str);
                                        break;
                                    }

                                    Timestamp ts = DateTimeParser.parseTimestamp(str);
                                    if (ts != null) {
                                        stmt.setTimestamp(paramIndex, ts);
                                        break;
                                    }

                                    stmt.setString(paramIndex, str);
                                    break;
                                case NUMBER:
                                    stmt.setObject(paramIndex, ((JsonNumber) val).numberValue());
                                    break;

                                case TRUE:
                                    stmt.setBoolean(paramIndex, true);
                                    break;

                                case FALSE:
                                    stmt.setBoolean(paramIndex, false);
                                    break;

                                default:
                                    stmt.setObject(paramIndex, val.toString()); // z. B. verschachteltes JSON
                            }
                        } else {
                            stmt.setObject(paramIndex, null);
                        }
                        paramIndex++;
                    }
                    stmt.addBatch();
                }
                stmt.executeBatch();
                stmt.close();
            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Error on insert " + ex.getMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Connection could not be closed: " + ex.getMessage());
                }
            }
        } else {
            result.add("storeMethod", "SmartDataAccessor/REST-API connection");
            // Use SmartData REST-API if no local connection is available
            throw new UnsupportedOperationException("save data over SmartData REST-API is currently not implemented.");
        }
    }

    /**
     * Updated data to the database
     *
     * @param collection Collection to write in
     * @param storage Storage to write in
     * @param dataSet Dataset to use for update (only containing updateable
     * attributes and id)
     * @throws SmartDataAccessorException
     */
    public void updateData(String collection, String storage, JsonObject dataSet) throws SmartDataAccessorException {
        de.ngi.logging.Logger.log();
        Connection con = this.getConnection();
        if (con != null) {
            StringBuilder updateQuery = new StringBuilder("UPDATE " + storage + "." + collection + " SET ");
            List<Object> values = new ArrayList<>();

            int index = 0;
            for (Map.Entry<String, JsonValue> curEntry : dataSet.entrySet()) {
                String column = curEntry.getKey();
                if (column.equals("id")) {
                    continue;
                }
                JsonValue value = curEntry.getValue();
                if (index > 0) {
                    updateQuery.append(", ");
                }
                updateQuery.append(column).append(" = ?");
                values.add(convertJsonValue(value)); // Umwandlung von JsonValue zu Java-Wert (z. B. String, Boolean, etc.)

                index++;
            }
            updateQuery.append(" WHERE id = ?");
            long setId = dataSet.getJsonNumber("id").longValue();

            String query = updateQuery.toString();

            try {
                PreparedStatement preparedStatement = con.prepareStatement(query);

                // Add data
                for (int i = 0; i < values.size(); i++) {
                    preparedStatement.setObject(i + 1, values.get(i));
                }
                // Set id
                preparedStatement.setObject(values.size() + 1, setId);

                int rowsInserted = preparedStatement.executeUpdate();
                if (rowsInserted > 0) {
                    de.ngi.logging.Logger.log("Succsessfull added dataset");
                } else {
                    de.ngi.logging.Logger.log("No new dataset created");
                }
            } catch (SQLException ex) {
                de.ngi.logging.Logger.log("Error saveing in database: " + ex.getLocalizedMessage());
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not create new dataset: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        } else {
            throw new UnsupportedOperationException("Not implemented yet");
        }
    }

    /**
     * Deletes data from the SmartData source, either using a direct database
     * connection if available, or via the SmartData REST API otherwise.
     *
     * @param storage The storage (schema) name in the database or SmartData API
     * @param collection The collection (table) name from which to delete data
     * @param filter Optional additional filter conditions as a string (e.g.,
     * "synced,eq,true")
     * @param dateAttr Optional date attribute used for filtering records by
     * date
     * @param olderThanDays Optional number of days to delete records older than
     * the cutoff date
     * @param start Optional start date for date range filtering
     * @param end Optional end date for date range filtering
     * @param result JsonObjectBuilder used to store messages and metadata about
     * the deletion
     * @return JsonObject containing deletion results, including "deletedCount"
     * and "success" status
     * @throws SmartDataAccessorException If any error occurs during deletion or
     * connection issues
     */
    public JsonObject deleteData(
            String storage,
            String collection,
            String filter,
            String dateAttr,
            Integer olderThanDays,
            LocalDateTime start,
            LocalDateTime end,
            JsonObjectBuilder result
    ) throws SmartDataAccessorException {

        de.ngi.logging.Logger.log("deleteData()");

        JsonObjectBuilder deleteResult = Json.createObjectBuilder();

        // Validate collection parameter
        if (collection == null || collection.isEmpty()) {
            throw new SmartDataAccessorException("No collection given.");
        }

        // Set default storage if null
        if (storage == null || storage.isEmpty()) {
            storage = "public";
        }

        // Replace {mac} placeholder in collection name
        if (collection.contains("{mac}")) {
            try {
                String mac = getLocalMacAddress().orElse("000000000000").replace(":", "");
                collection = collection.replaceAll("\\{mac\\}", Matcher.quoteReplacement(mac));
            } catch (Exception e) {
                throw new SmartDataAccessorException("Could not get MAC-Address: " + e.getMessage());
            }
        }

        // Try to get a local DB connection
        Connection con = this.getConnection();

        if (con != null) {
            result.add("deleteMethod", "SmartDataAccessor/JDBC");

            try {
                // Build DELETE SQL statement
                String sql = "DELETE FROM \"" + storage + "\".\"" + collection + "\"";
                List<String> conditions = new ArrayList<>();

                // Add condition for olderThanDays
                if (olderThanDays != null && dateAttr != null) {
                    LocalDate cutoffDate = LocalDate.now().minusDays(olderThanDays);
                    conditions.add("\"" + dateAttr + "\"::date < '" + cutoffDate + "'");
                    deleteResult.add("cutoffDate", cutoffDate.toString());
                } else if (dateAttr != null && start != null && end != null) {
                    conditions.add("\"" + dateAttr + "\"::date >= '" + start.toLocalDate() + "'");
                    conditions.add("\"" + dateAttr + "\"::date <= '" + end.toLocalDate() + "'");
                }

                // Add additional filter condition
                if (filter != null && !filter.isEmpty()) {
                    conditions.add(buildFilterCondition(filter));
                }

                // Safety check: refuse to delete all rows if no condition
                if (conditions.isEmpty()) {
                    throw new SmartDataAccessorException("No deletion conditions specified. Refusing to delete all data.");
                }

                // Append WHERE clause to SQL
                sql += " WHERE " + String.join(" AND ", conditions);
                deleteResult.add("sql", sql);
        
                // Execute deletion
                PreparedStatement stmt = con.prepareStatement(sql);
                int deletedRows = stmt.executeUpdate();
                stmt.close();

                deleteResult.add("deletedCount", deletedRows);
                deleteResult.add("success", true);

                de.ngi.logging.Logger.log("Successfully deleted " + deletedRows + " rows from " + collection);

                return deleteResult.build();

            } catch (SQLException ex) {
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not delete data from >" + collection + "<. SQL error: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak: " + ex.getLocalizedMessage());
                }
            }
        } else {
            result.add("deleteMethod", "SmartDataAccessor/REST-API connection");

            // Build REST API target
            WebTarget webTarget = WebTargetCreator.createWebTarget(
                    smartdataurl + "/smartdata", "records")
                    .path(collection)
                    .queryParam("storage", storage);

            // Add filter query param
            if (filter != null) {
                webTarget = webTarget.queryParam("filter", filter);
            }

            // Add date filter for olderThanDays
            if (olderThanDays != null && dateAttr != null) {
                LocalDate cutoffDate = LocalDate.now().minusDays(olderThanDays);
                webTarget = webTarget.queryParam("filter", dateAttr + ",lt," + cutoffDate);
                deleteResult.add("cutoffDate", cutoffDate.toString());
            } else if (dateAttr != null && start != null && end != null) {
                webTarget = webTarget.queryParam("filter", dateAttr + ",gte," + start.toLocalDate());
                webTarget = webTarget.queryParam("filter", dateAttr + ",lte," + end.toLocalDate());
            }

            this.smartdataRequest = webTarget.getUri().toString();

            // Send DELETE request via REST API
            Response response = webTarget.request(MediaType.APPLICATION_JSON).delete();
            String responseText = response.readEntity(String.class);

            if (Response.Status.OK.getStatusCode() == response.getStatus()
                    || Response.Status.NO_CONTENT.getStatusCode() == response.getStatus()) {

                // Parse returned deleted count if available
                if (responseText != null && !responseText.isEmpty()) {
                    JsonParser parser = Json.createParser(new StringReader(responseText));
                    parser.next();
                    JsonObject responseObj = parser.getObject();
                    deleteResult.add("deletedCount", responseObj.getInt("deleted", 0));
                }

                deleteResult.add("success", true);
                return deleteResult.build();

            } else if (response.getStatus() == 404) {
                throw new SmartDataAccessorException("Target >" + webTarget.getUri() + "< was not found.");
            } else {
                throw new SmartDataAccessorException("Could not delete from >" + webTarget.getUri() + "< returned status: "
                        + response.getStatus() + " - " + responseText);
            }
        }
    }

    private Object convertJsonValue(JsonValue jsonValue) {
        switch (jsonValue.getValueType()) {
            case STRING:
                String strValue = ((JsonString) jsonValue).getString();

                // Versuch, das Format ISO 8601 automatisch zu erkennen
                try {
                    Instant instant = Instant.parse(strValue); // z. B. "2025-07-04T07:45:00Z"
                    return Timestamp.from(instant); // korrekt für PostgreSQL
                } catch (DateTimeParseException e) {
                    // Kein gültiger Timestamp – bleibt String
                    return strValue;
                }
            case NUMBER:
                JsonNumber num = (JsonNumber) jsonValue;
                // Ganzzahl oder Fließkommazahl je nach Inhalt
                return num.isIntegral() ? num.longValue() : num.doubleValue();
            case TRUE:
                return true;
            case FALSE:
                return false;
            case NULL:
                return null;
            default:
                throw new IllegalArgumentException("Unsupported JSON type: " + jsonValue.getValueType());
        }
    }

    private String buildFilterCondition(String filter) throws SmartDataAccessorException {
        StringBuilder condition = new StringBuilder();
        String[] filters = filter.split(";"); // mehrere Filter durch Semikolon getrennt

        for (String f : filters) {
            String[] parts = f.split(",");
            if (parts.length != 3) {
                throw new SmartDataAccessorException("Invalid filter format: " + f);
            }

            String column = parts[0].trim();
            String operator = parts[1].trim().toLowerCase();
            String value = parts[2].trim();

            String sqlOperator;
            switch (operator) {
                case "gt":
                    sqlOperator = ">";
                    break;
                case "lt":
                    sqlOperator = "<";
                    break;
                case "eq":
                    sqlOperator = "=";
                    break;
                case "neq":
                    sqlOperator = "!=";
                    break;
                case "ge":
                    sqlOperator = ">=";
                    break;
                case "le":
                    sqlOperator = "<=";
                    break;
                // Erweiterbar: z.B. "like", "in", "neq", etc.
                default:
                    throw new SmartDataAccessorException("Unsupported filter operator: " + operator);
            }

            // Wert in einfache Anführungszeichen setzen, falls es sich um einen String handelt
            if (!isNumeric(value)) {
                value = "'" + value.replace("'", "''") + "'";
            }

            if (condition.length() > 0) {
                condition.append(" AND ");
            }
            condition.append(column).append(" ").append(sqlOperator).append(" ").append(value);
        }

        return condition.toString();
    }

    private boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

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
