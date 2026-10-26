package JobRunnerInitializer;

import de.ngi.logging.Logger;
import de.smartdata.jobs.config.Configuration;
import de.smartdata.jobs.threads.JobRunner;
import de.smartdata.jobs.threads.JobRunnerException;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Initializes the JobRunner when the application starts
 * 
 * @author Florian Fehring
 */
@WebListener
public class JobRunnerInitializer implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        Logger.log("Starting JobRunner initialization...");
        try {
            JobRunner jobRunner = JobRunner.getInstance();
            
            // Initialize executor services
            initializeExecutors(jobRunner);
            
            // Load and start jobs from configuration
            loadAndStartJobs(jobRunner);
            
            Logger.log("JobRunner successfully initialized");
            Logger.endProcess();
        } catch (Exception e) {
            Logger.log("Error initializing JobRunner: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        Logger.log("Shutting down JobRunner...");
        try {
            JobRunner instance = JobRunner.getInstance();
            instance.shutdownSmartDataJobs();
            Logger.log("JobRunner successfully shut down");
        } catch (Exception e) {
            Logger.log("Error shutting down JobRunner: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Initialize the executor services for the JobRunner
     */
    private void initializeExecutors(JobRunner jobRunner) {
        System.out.println("Initializing executors");
        
        int cores = Runtime.getRuntime().availableProcessors();
        
        // Create standard executor service
        jobRunner.setExecutorService(Executors.newScheduledThreadPool(cores));
        
        // Create priority executor with high priority threads
        ThreadFactory highPriorityFactory = r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        };
        jobRunner.setPriorityExecutor(Executors.newScheduledThreadPool(cores, highPriorityFactory));
    }
    
    /**
     * Load jobs from configuration and start them
     */
    private void loadAndStartJobs(JobRunner jobRunner) throws JobRunnerException {
        System.out.println("Loading jobs from configuration");
        
        Configuration conf = new Configuration();
        Map<String, Map<String, String>> groupedEntries = new HashMap<>();
        Pattern pattern = Pattern.compile("startup\\.(smartdataurl|collection|storage)_(\\d+)");
        System.out.println("Pattern: " + pattern);

        // Group configuration entries by their numeric suffix
        for (Map.Entry<Object, Object> entry : conf.getAllProperties()) {
            String key = entry.getKey().toString();
            String value = entry.getValue().toString();

            Matcher matcher = pattern.matcher(key);
            if (matcher.matches()) {
                System.out.println("Matcher: " + matcher);
                String paramType = matcher.group(1); // smartdataurl, collection, or storage
                String groupId = matcher.group(2);   // The group number

                groupedEntries.putIfAbsent(groupId, new HashMap<>());
                groupedEntries.get(groupId).put(paramType, value);
            }
        }

        // Process each group and start jobs
        for (Map.Entry<String, Map<String, String>> groupEntry : groupedEntries.entrySet()) {
            Map<String, String> params = groupEntry.getValue();
            if (params.containsKey("smartdataurl") && params.containsKey("collection") && params.containsKey("storage")) {
                String smartdataurl = params.get("smartdataurl");
                String collection = params.get("collection");
                String storage = params.get("storage");

                // Auto correction of short urls
                if (smartdataurl == null) {
                    smartdataurl = "http://localhost:8080/SmartData";
                }
                if (smartdataurl.startsWith("/")) {
                    smartdataurl = "http://localhost:8080" + smartdataurl;
                }
                // Default values
                if (collection == null) {
                    collection = "datajobs";
                }
                if (storage == null) {
                    storage = "public";
                }

                try {
                    jobRunner.startAll(smartdataurl, collection, storage);
                } catch (JobRunnerException ex) {
                    Logger.log("Error: " + ex.getLocalizedMessage());
                }
            } else {
                Logger.log("Missing parameters for group " + groupEntry.getKey());
            }
        }
    }
}