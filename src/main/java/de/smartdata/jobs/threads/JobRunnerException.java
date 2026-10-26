package de.smartdata.jobs.threads;

/**
 * Reports errors in job running
 * 
 * @author Florian Fehring
 */
public class JobRunnerException extends Exception {

    /**
     * Creates a new instance of <code>JobRunnerException</code> without detail
     * message.
     */
    public JobRunnerException() {
    }

    /**
     * Constructs an instance of <code>JobRunnerException</code> with the
     * specified detail message.
     *
     * @param msg the detail message.
     */
    public JobRunnerException(String msg) {
        super(msg);
    }
}
