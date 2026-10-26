package de.smartdata.jobs.threads;

import de.smartdata.jobs.actions.*;
import de.smartdata.jobs.data.SmartDataAccessor;
import de.smartdata.jobs.data.SmartDataAccessorException;
import de.fhbielefeld.scl.rest.util.JSONFormatter;
import de.ngi.logging.Logger;
import de.ngi.restutils.DateTimeParser;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages and runs jobs.
 *
 * @author Florian Fehring
 */
public class JobRunner {

    // CHANGED from ManagedScheduledExecutorService to ScheduledExecutorService
    private ScheduledExecutorService executorService;
    private ScheduledExecutorService priorityExecutor;

    private Map<String, Future<?>> futures = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean stopped = false;

    // Singleton instance
    private static volatile JobRunner instance;

    // Private constructor
    private JobRunner() {
        // Empty - initialization happens in JobRunnerInitializer
    }

    // getInstance method
    public static JobRunner getInstance() {
        if (instance == null) {
            synchronized (JobRunner.class) {
                if (instance == null) {
                    instance = new JobRunner();
                }
            }
        }
        return instance;
    }

    /**
     * Set the executor service (called by JobRunnerInitializer)
     *
     * @param executorService the executor service to use
     */
    public void setExecutorService(ScheduledExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * Set the priority executor (called by JobRunnerInitializer)
     *
     * @param priorityExecutor the priority executor to use
     */
    public void setPriorityExecutor(ScheduledExecutorService priorityExecutor) {
        this.priorityExecutor = priorityExecutor;
    }

    /**
     * Start all defined jobs
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @return JsonArray with information for every job
     * @throws JobRunnerException
     */
    public JsonArray startAll(String smartdataurl, String collection, String storage) throws JobRunnerException {
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        this.stopped = false;
        try {
            // Jobs array from database
            JsonObjectBuilder result = Json.createObjectBuilder();
            JsonArray jobs = acc.fetchData(storage, collection, null, null, null, null, null, null, null, result);
            // Jobs array for output (because fetched one is not modifyable)
            JsonArrayBuilder jobinfos = Json.createArrayBuilder();
            for (JsonObject job : jobs.getValuesAs(JsonObject.class)) {
                // Do not start jobs without start time
                Boolean active = job.getBoolean("active", false);
                if (active == false) {
                    continue;
                }

                JsonObject jobinfo = this.executeJob(smartdataurl, collection, storage, job, acc, null);
                if (jobinfo.containsKey("result")) {
                    this.startJobChain(smartdataurl, collection, storage, jobinfo);
                }
                jobinfos.add(jobinfo);
            }
            return jobinfos.build();
        } catch (SmartDataAccessorException ex) {
            JobRunnerException jrex = new JobRunnerException(ex.getLocalizedMessage());
            jrex.initCause(ex);
            throw jrex;
        }
    }

    /**
     * Starts one single job
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param jobid Jobs id
     * @return Job execution result
     * @throws JobRunnerException
     */
    public JsonArray start(String smartdataurl, String collection, String storage, Long jobid, JsonObject preJobInfo) throws JobRunnerException {
        Logger.log("->start()");
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        this.stopped = false;
        try {
            // Jobs array from database
            JsonObjectBuilder result = Json.createObjectBuilder();
            JsonArray jobs = acc.fetchData(storage, collection, null, "id,eq," + jobid, null, null, null, null, null, result);
            // Jobs array for output (because fetched one is not modifyable)
            JsonArrayBuilder jobinfos = Json.createArrayBuilder();
            for (JsonObject job : jobs.getValuesAs(JsonObject.class)) {
                JsonObject jobinfo = this.executeJob(smartdataurl, collection, storage, job, acc, preJobInfo);
                JsonObjectBuilder jobinfo2 = Json.createObjectBuilder();
                jobinfo.forEach(jobinfo2::add);
                if (jobinfo.containsKey("result")) {
                    jobinfo2.add("chainedJobs", this.startJobChain(smartdataurl, collection, storage, jobinfo));
                }
                jobinfos.add(jobinfo2.build());
            }
            return jobinfos.build();
        } catch (SmartDataAccessorException ex) {
            JobRunnerException jrex = new JobRunnerException(ex.getLocalizedMessage());
            jrex.initCause(ex);
            throw jrex;
        }
    }

    /**
     * Exeutes a single job
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param job Job information object
     * @param acc SmartDataAccessor
     * @return JsonObject with job info and execution results
     */
    private JsonObject executeJob(String smartdataurl, String collection, String storage, JsonObject job, SmartDataAccessor acc, JsonObject preJobInfo) {
        Logger.log("executeJob(" + job.getInt("id") + ")");

        // Create Json for returning info about executed (onc time execution) or sheduled (recurring execution) job
        JsonObjectBuilder returnJson = Json.createObjectBuilder(job);
        Long jobid = job.getJsonNumber("id").longValue();

        // Check if job is active
        boolean active = job.getBoolean("active", false);
        if (!active) {
            returnJson.add("state", "Job >" + job.getString("name") + "< is currently inactive.");
            return returnJson.build();
        }

        try {
            // Get jobs action
            final Action jobsaction = getJobAction(job);

            // Jump to next job if this one is misconfigured
            if (jobsaction == null) {
                Json.createValue("failed");
                returnJson.add("state", "failed");
                returnJson.add("statemsg", "There is no action with name >" + job.getString("action") + "< implemented.");
                JsonObject result = returnJson.build();
                saveResultLog(smartdataurl, collection, storage, jobid, result, true, false);
                return result;
            }

            jobsaction.setId(jobid);
            jobsaction.setPreJobsInfo(preJobInfo);

            // Get job parameters
            JsonObjectBuilder result1 = Json.createObjectBuilder();
            JsonArray jobparams = acc.fetchData(storage, collection + "_params", null, "datajob_id,eq," + job.getInt("id"), null, null, null, null, null, result1);
            JsonObject jobparamobj = loadJobParams(jobparams, returnJson);

            try {
                jobsaction.setParams(jobparamobj);
                returnJson.add("params", jobparamobj);
            } catch (ActionConfigurationException acex) {
                returnJson.add("state", "failed");
                returnJson.add("statemsg", "Missconfigured action >" + job.getString("action") + "< Action reported: " + acex.getLocalizedMessage());
                JsonObject result = returnJson.build();
                saveResultLog(smartdataurl, collection, storage, jobid, result, true, false);
                return result;
            }

            // Pase Job configuration
            LocalDateTime now = LocalDateTime.now();

            final LocalDateTime start = job.getString("start", null) != null
                    ? DateTimeParser.parseTimestamp(job.getString("start")).toLocalDateTime()
                    : now;

            final LocalDateTime end = job.getString("stop", null) != null
                    ? DateTimeParser.parseTimestamp(job.getString("stop")).toLocalDateTime()
                    : null;

            long repeatsecs = job.getInt("repeatsecs", 0);
            long delay = 0;
            if (start != null) {
                delay = this.calculateDelay(start, repeatsecs);
            }

            if (delay < 0) {
                returnJson.add("state", "Job not started because start time lies in the past.");
                return returnJson.build();
            }

            returnJson.add("firstexecution", LocalDateTime.now().plusSeconds(delay).toString());

            int priority = job.getInt("priority", 0);

            Future<?> future;
            Runnable runnableTask = () -> runJobAsync(jobsaction, smartdataurl, collection, storage, job, jobid, end, jobparamobj);
            if (repeatsecs == 0) {
                returnJson.add("executionmode", "once");

                if (priority > 0) {
                    future = this.priorityExecutor.schedule(runnableTask, delay, TimeUnit.SECONDS);
                } else {
                    future = executorService.schedule(runnableTask, delay, TimeUnit.SECONDS);
                }

                Logger.log("Job >" + job.getInt("id") + "< was scheduled one Time. Delay: " + delay);
            } else {
                returnJson.add("executionmode", "repeating");
                returnJson.add("executiondelay", delay);

                if (priority > 0) {
                    future = this.priorityExecutor.scheduleAtFixedRate(runnableTask, delay, repeatsecs, TimeUnit.SECONDS);
                } else {
                    future = executorService.scheduleWithFixedDelay(runnableTask, delay, repeatsecs, TimeUnit.SECONDS);
                }

                Logger.log("Job >" + job.getInt("id") + "< was scheduled with fixed rate. Delay: " + delay + " repeatsecs: " + repeatsecs);
            }

            String jobCode = this.getJobCode(smartdataurl, collection, storage, jobid);
            this.futures.put(jobCode, future);
        } catch (SmartDataAccessorException ex) {
            returnJson.add("error", "Error accessing job actions: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
        } catch (JobRunnerException ex) {
            returnJson.add("error", "Error accessing job actions: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
        }
        JsonObject jobuild = returnJson.build();
        return jobuild;
    }

    /**
     * Calculates the seconds until next start
     *
     * @param start Configured start time
     * @return
     */
    private long calculateDelay(LocalDateTime start, long seconds) {
        LocalDateTime now = LocalDateTime.now();
        Duration sec = Duration.ofSeconds(seconds);

        // Prüfen, ob start vor now liegt
        if (start.isBefore(now)) {
            if (seconds != 0) {
                // Berechne die Zeitdifferenz zwischen start und now in Sekunden
                long elapsedSeconds = Duration.between(start, now).getSeconds();

                // Berechne die nächste start-Zeit in der Zukunft
                long missedIntervals = (elapsedSeconds + sec.getSeconds() - 1) / sec.getSeconds(); // Aufrunden
                start = start.plusSeconds(missedIntervals * sec.getSeconds());
            }
        }

        return now.until(start, ChronoUnit.SECONDS);
    }

    /**
     * Saves the result in database
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param datajob_id datajobs id
     * @param result JsonObject with results
     * @param haserror If an error was detected
     * @param wastimeout If an timeout occured
     */
    private void saveResultLog(String smartdataurl, String collection, String storage, Long datajob_id, JsonObject result, boolean haserror, boolean wastimeout) throws JobRunnerException {
        Logger.log();
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        try {
            acc.saveDataSpecial(smartdataurl, collection + "_params", storage, datajob_id, result, haserror, wastimeout);
        } catch (SmartDataAccessorException ex) {
            JobRunnerException jrex = new JobRunnerException(ex.getLocalizedMessage());
            jrex.initCause(ex);
            throw jrex;
        }
    }

    /**
     * Gets and starts a jobchain from a job with its result
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param jobinfo Jobs information including a "result" object
     * @return List of chained jobs and their results
     * @throws JobRunnerException
     */
    public JsonArrayBuilder startJobChain(String smartdataurl, String collection, String storage, JsonObject jobinfo) throws JobRunnerException {
        Logger.log();
        JsonArrayBuilder results = Json.createArrayBuilder();

        JsonNumber pre_job_id_json = jobinfo.getJsonNumber("id");
        if (pre_job_id_json == null) {
            throw new JobRunnerException("Could not start job chain: Id of previous job is missing.");
        }
        Long pre_job_id = pre_job_id_json.longValue();

        // Get job chain for job
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        try {
            JsonObjectBuilder result = Json.createObjectBuilder();
            // Jobs array from database
            JsonArray chainedjobs = acc.fetchData(storage, collection + "_chain", null, "pre_job_id,eq," + pre_job_id, null, null, null, null, null, result);
            for (int i = 0; i < chainedjobs.size(); i++) {
                JsonObject jobparam = chainedjobs.getJsonObject(i);
                Long post_job_id = jobparam.getJsonNumber("post_job_id").longValue();
                if (!jobparam.getBoolean("active")) {
                    results.add("Did not execute chained job >" + post_job_id + "< because chain element is deactivated.");
                    continue;
                }
                String checkkey = jobparam.getString("checkkey", null);
                String checkvalue = jobparam.getString("checkvalue", null);
                String checkchanged = jobparam.getString("checkchanged", null);
                Logger.log("Checking checkkey >" + checkkey + "< for checkvalue >" + checkvalue + "< and checkchanged >" + checkchanged + "<");
                JsonObject resultObj = jobinfo.getJsonObject("result");

                // Check condition
                if (checkkey != null) {
                    if (resultObj.containsKey(checkkey)) {
                        JsonValue resultValue = resultObj.get(checkkey);
                        if (resultValue != null && resultValue.toString().replace("\"", "").equals(checkvalue)) {
                            // Check if state has changed
                            if (checkchanged != null && !this.checkChanged(smartdataurl, collection, storage, checkchanged, jobinfo)) {
                                Logger.log("Did not execute chained job >" + post_job_id + "< because result >" + checkchanged + "< has not changed.");
                                results.add("Did not execute chained job >" + post_job_id + "< because result >" + checkchanged + "< has not changed.");
                                continue;
                            }
                            Logger.log("Starting chained job >" + post_job_id + "< because of checkkey and statechange");
                            JsonArray chainjobinfos = this.start(smartdataurl, collection, storage, post_job_id, jobinfo);
                            results.add(chainjobinfos);
                        } else {
                            Logger.log("Did not execute chained job >" + post_job_id + "< because result >" + checkkey + "< has not expected value >" + checkvalue + "< but >" + resultValue.toString() + "<.");
                            results.add("Did not execute chained job >" + post_job_id + "< because result >" + checkkey + "< has not expected value >" + checkvalue + "< but >" + resultValue.toString() + "<.");
                        }
                    }
                } // Check state change
                else if (checkchanged != null) {
                    // Check if state has changed
                    if (this.checkChanged(smartdataurl, collection, storage, checkchanged, jobinfo)) {
                        Logger.log("Starting chained job >" + post_job_id + "< because of statechange");
                        JsonArray chainjobinfos = this.start(smartdataurl, collection, storage, post_job_id, jobinfo);
                        results.add(chainjobinfos);
                    } else {
                        Logger.log("Did not execute chained job >" + post_job_id + "< because result >" + checkchanged + "< has not changed.");
                        results.add("Did not execute chained job >" + post_job_id + "< because result >" + checkchanged + "< has not changed.");
                    }
                } else {
                    Logger.log("Starting chained job >" + post_job_id + "< without dependency in checkkey and statechange");
                    JsonArray chainjobinfos = this.start(smartdataurl, collection, storage, post_job_id, jobinfo);
                    results.add(chainjobinfos);
                    results.add("Executed chained job >" + post_job_id + "< because there is no condition configured.");
                }
            }
        } catch (SmartDataAccessorException ex) {
            JobRunnerException jrex = new JobRunnerException(ex.getLocalizedMessage());
            jrex.initCause(ex);
            throw jrex;
        }
        return results;
    }

    /**
     * Check if a field has changed in compare to last run of the job
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     */
    private boolean checkChanged(String smartdataurl, String collection, String storage, String checkchanged, JsonObject jobinfo) throws SmartDataAccessorException {
        // Get job chain for job
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        JsonObjectBuilder result = Json.createObjectBuilder();
        // Get past result
        JsonArray jobresults = acc.fetchData(storage, collection + "_log", null, "datajob_id,eq," + jobinfo.getInt("id"), null, null, null, "ts,DESC", 2L, result);
        // Check if there is a previous execution
        if (!jobresults.isEmpty() && jobresults.size() > 1) {
            // Get prelast result
            String jobresultstr = jobresults.getJsonObject(1).getString("result", null);
            JsonObject jobresultobj;
            // JSON in ein Java-Objekt umwandeln
            try (JsonReader jsonReader = Json.createReader(new StringReader(jobresultstr))) {
                jobresultobj = jsonReader.readObject();
            }

            String lastvalue = jobresultobj.getString(checkchanged, null);
            String curvalue = jobinfo.getJsonObject("result").getString(checkchanged, null);
            // Compare last value and current value
            if (curvalue != null && curvalue.equals(lastvalue)) {
                Logger.log("Attribute >" + checkchanged + "< has not changed from value >" + lastvalue + "<");
                return false;
            } else {
                Logger.log("Attribute >" + checkchanged + "< has changed from value >" + lastvalue + "< to value >" + curvalue + "<");
            }
        } else {
            Logger.log("There was no previous execution. So: Attribute >" + checkchanged + "< has changed.");
        }
        return true;
    }

    public void shutdownSmartDataJobs() {
        this.stopAllJobs();

        // Proper executor shutdown
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (priorityExecutor != null) {
            priorityExecutor.shutdown();
            try {
                if (!priorityExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    priorityExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                priorityExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops the execution of all jobs and schedules
     *
     * @return State information
     */
    public JsonObject stopAllJobs() {
        Logger.log("Shutting down schedules jobs...");
        JsonObjectBuilder result = Json.createObjectBuilder();

        // Cancel all futures
        for (Entry<String, Future<?>> future : futures.entrySet()) {
            if (!future.getValue().isDone()) {
                future.getValue().cancel(true);
                result.add("Job >" + future.getKey() + "<", "stopped");
            }
        }
        this.futures = new java.util.concurrent.ConcurrentHashMap<>();

        result.add("state", "stopped");
        result.add("statemsg", "All running jobs are gracefully stopped.");
        return result.build();
    }

    /**
     * Shutdown a single job
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param job_id Id of the job do shutdown
     * @return
     */
    public JsonObject stopJob(String smartdataurl, String collection, String storage, Long job_id) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        String jobCode = this.getJobCode(smartdataurl, collection, storage, job_id);
        for (Entry<String, Future<?>> future : futures.entrySet()) {
            if (future.getKey().equals(jobCode)) {
                if (!future.getValue().isDone()) {
                    future.getValue().cancel(true);
                }
                futures.remove(future.getKey());
                result.add("Job >" + future.getKey() + "<", "stopped");
            }
        }
        result.add("state", "stopped");
        return result.build();
    }

    /**
     * Checks if a job is running
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param job_id Jobs id
     * @return true if job with id is running, false otherwise
     */
    public boolean jobIsRunning(String smartdataurl, String collection, String storage, Long job_id) {
        boolean isRunning = false;
        String jobCode = this.getJobCode(smartdataurl, collection, storage, job_id);
        for (Entry<String, Future<?>> future : futures.entrySet()) {
            if (future.getKey().equals(jobCode)) {
                if (!future.getValue().isDone()) {
                    isRunning = true;
                }
            }
        }
        return isRunning;
    }

    /**
     * Generates a job identifier (id is not enough because jobs can be defined
     * in more instances of SmartData)
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @param job_id
     * @return
     */
    public String getJobCode(String smartdataurl, String collection, String storage, Long job_id) {
        return smartdataurl + collection + storage + job_id;
    }

    /**
     * Lists the states of all currently registred jobs
     *
     * @param smartdataurl URL to the SmartData Instance that contains job
     * informations
     * @param collection Name of the collection contianing job informations
     * @param storage Storage containing job informations
     * @return JsonArray with one entry for each job
     */
    public JsonArray getJobStates(String smartdataurl, String collection, String storage) {
        JsonArrayBuilder jab = Json.createArrayBuilder();
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);

        for (Entry<String, Future<?>> future : futures.entrySet()) {
            JsonObjectBuilder job = Json.createObjectBuilder();

            // Get actual state
            if (future.getValue().isDone()) {
                job.add("Job >" + future.getKey() + "<", "stopped");
            } else {
                job.add("Job >" + future.getKey() + "<", "running");
            }

            // Get last activity log
            try {
                JsonObjectBuilder result = Json.createObjectBuilder();
                JsonArray data = acc.fetchData(storage, collection + "_log", null, null, "ts", null, null, "ts,DESC", 1L, result);
                if (data.isEmpty()) {
                    job.add("state", "There was no previous execution");
                } else {
                    job.add("lastlog", data);
                }
            } catch (SmartDataAccessorException ex) {
                job.add("error", "Could not fetch last activity: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
            }
            jab.add(job);
        }

        return jab.build();
    }

    public JsonObject setActiveOfJob(String smartdataurl, String collection, String storage, Long job_id, boolean active) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        JsonObject updateJob = Json.createObjectBuilder()
                .add("id", job_id)
                .add("active", active).build();
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        result.add("job_id", job_id);
        try {
            acc.updateData(collection, storage, updateJob);
            result.add("status", "updated")
                    .add("active", active);
        } catch (SmartDataAccessorException ex) {
            result.add("error", "Could not update column active to >" + active + "< of Job >" + job_id + "<");
        }
        return result.build();
    }

    private Action getJobAction(JsonObject job) {
        Action jobsaction;
        switch (job.getString("action")) {
            case "CheckNet":
                jobsaction = new CheckNetAction();
                break;
            case "Mail":
                jobsaction = new MailAction();
                break;
            case "CommandExecution":
                jobsaction = new CommandExecutionAction();
                break;
            case "ReadData":
                jobsaction = new ReadDataAction();
                break;
            case "WriteData":
                jobsaction = new WriteDataAction();
                break;
            case "SendNotification":
                jobsaction = new SendNotificationAction();
                break;
            case "CheckEventTriggers":
                jobsaction = new CheckEventTriggers();
                break;
            case "CreateFlagFile":
                jobsaction = new CreateFlagFileAction();
                break;
            case "DeleteData":
                jobsaction = new DeleteDataAction();
                break;
            default:
                jobsaction = null;
        }
        return jobsaction;
    }

    private JsonObject loadJobParams(JsonArray jobparams, JsonObjectBuilder returnJson) {
        // Transform to object
        JsonObjectBuilder jobparamobjb = Json.createObjectBuilder();
        for (int i = 0; i < jobparams.size(); i++) {
            JsonObject jobparam = jobparams.getJsonObject(i);
            String key = jobparam.getString("key");
            String valstr = jobparam.getString("value");
            String type = jobparam.getString("type", null);
            switch (type) {
                case "string":
                case "email":
                case "url":
                    jobparamobjb.add(key, valstr);
                    break;
                case "int":
                    jobparamobjb.add(key, Integer.parseInt(valstr));
                    break;
                case "float":
                    jobparamobjb.add(key, Float.parseFloat(valstr));
                    break;
                default:
                    returnJson.add("paramError", "Parameter type >" + type + "< for configuration entry >" + key + "< with value >" + valstr + "< is not known. Please use string, email, url, int or float.");
            }
        }
        return jobparamobjb.build();
    }

    private void runJobAsync(Action jobsaction, String smartdataurl, String collection, String storage, JsonObject job, long jobid, LocalDateTime stop, JsonObject params) {
        Logger.startProcess(jobid + " (" + job.getString("name") + "):" + Instant.now().toString());
        Logger.log("Now running runnableTask for job >" + jobid + "<");

        try {
            if (stopped) {
                JsonObjectBuilder result = Json.createObjectBuilder();
                result.add("state", "stopped");
                result.add("stateMsg", "Job execution stopped, because SmartDataJobs was stopped.");
                saveResultLog(smartdataurl, collection, storage, jobid, result.build(), false, false);
                return;
            }

            // Stop time check
            if (stop != null && LocalDateTime.now().isAfter(stop)) {
                Logger.log("Job >" + jobid + "< stopped because stop-time reached.");
                this.stopJob(smartdataurl, collection, storage, jobid);
                return;
            }

            JsonObject result = jobsaction.call();
            JsonObjectBuilder innerJobinfo = Json.createObjectBuilder();
            job.forEach(innerJobinfo::add);
            innerJobinfo.add("params", params);
            innerJobinfo.add("result", result);
            saveResultLog(smartdataurl, collection, storage, jobid, result, false, false);
            CompletableFuture.runAsync(() -> {
                try {
                    this.startJobChain(smartdataurl, collection, storage, innerJobinfo.build());
                } catch (Exception e) {
                    Logger.log("Error while starting jobChain for job >" + jobid + "<: " + e.getLocalizedMessage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            JsonObjectBuilder result = Json.createObjectBuilder();
            result.add("state", "error");
            result.add("error", e.getClass().getSimpleName() + " occured: " + JSONFormatter.escapeForJson(e.getLocalizedMessage()));
            try {
                saveResultLog(smartdataurl, collection, storage, jobid, result.build(), true, false);
            } catch (JobRunnerException ex) {
                result.add("error", "Execution was done. But result could not be saved: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
            }
            Logger.log("Error occured while executing job >" + job.getInt("id") + "<: " + e.getLocalizedMessage());
            e.printStackTrace();
        } finally {
            Logger.endProcess();
        }
    }
}
