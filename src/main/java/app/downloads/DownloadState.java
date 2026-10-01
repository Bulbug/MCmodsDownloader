package app.downloads;

public enum DownloadState {
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    VERIFYING("Verifying"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    private final String label;

    DownloadState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** No further work will happen unless the user retries. */
    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
