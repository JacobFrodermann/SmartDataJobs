package de.smartdata.jobs.actions;

/**
 * Reports missconfiguration in action
 * 
 * @author Florian Fehring
 */
public class ActionConfigurationException extends Exception {

    /**
     * Creates a new instance of <code>ActionConfigurationException</code>
     * without detail message.
     */
    public ActionConfigurationException() {
    }

    /**
     * Constructs an instance of <code>ActionConfigurationException</code> with
     * the specified detail message.
     *
     * @param msg the detail message.
     */
    public ActionConfigurationException(String msg) {
        super(msg);
    }
}
