package app.backup;

/** Backup problem with a message written for the user. */
public class BackupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackupException(String userMessage) {
        super(userMessage);
    }

    public BackupException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
