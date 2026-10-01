package app.downloads;

import java.nio.file.Path;

/** Immutable picture of a download at one moment. Safe to hand to the UI. */
public record DownloadSnapshot(long id, String name, Path destination, DownloadState state,
                               long downloadedBytes, long totalBytes, double bytesPerSecond,
                               String message) {

    /** 0.0 to 1.0, or -1 when the total size is unknown. */
    public double fraction() {
        if (state == DownloadState.COMPLETED) return 1.0;
        if (totalBytes <= 0) return -1;
        return Math.min(1.0, (double) downloadedBytes / totalBytes);
    }

    /** Estimated seconds left, or -1 if unknown. */
    public long etaSeconds() {
        if (state != DownloadState.DOWNLOADING || totalBytes <= 0 || bytesPerSecond < 1) return -1;
        return (long) Math.ceil((totalBytes - downloadedBytes) / bytesPerSecond);
    }
}
