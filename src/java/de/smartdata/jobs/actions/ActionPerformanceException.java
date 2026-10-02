package de.smartdata.jobs.actions;

/**
 * Reports errors while performing an action
 * 
 * @author Florian Fehring
 */
public class ActionPerformanceException extends Exception {

    /**
     * Creates a new instance of <code>ActionConfigurationException</code>
     * without detail message.
     */
    public ActionPerformanceException() {
    }

    /**
     * Constructs an instance of <code>ActionConfigurationException</code> with
     * the specified detail message.
     *
     * @param msg the detail message.
     */
    public ActionPerformanceException(String msg) {
        super(msg);
    }
}
