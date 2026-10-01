package app.mods;

/** Installation problem with a message written for the user. */
public class InstallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstallException(String userMessage) {
        super(userMessage);
    }

    public InstallException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
