package app.downloads;

/** Download problem with a message written for the user. */
public class DownloadException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    public DownloadException(String userMessage, boolean retryable) {
        super(userMessage);
        this.retryable = retryable;
    }

    public DownloadException(String userMessage, boolean retryable, Throwable cause) {
        super(userMessage, cause);
        this.retryable = retryable;
    }

    /** True if trying again automatically might succeed (network trouble, server overload). */
    public boolean isRetryable() {
        return retryable;
    }
}
