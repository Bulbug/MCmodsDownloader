package app.api;

/** Problem talking to a content platform. The message is written for the user. */
public class ProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProviderException(String userMessage) {
        super(userMessage);
    }

    public ProviderException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
