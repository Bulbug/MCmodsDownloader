package app.instance;

/** Error whose message is written for the user (no stack trace jargon). */
public class InstanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstanceException(String userMessage) {
        super(userMessage);
    }

    public InstanceException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
