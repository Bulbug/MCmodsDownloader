package app.downloads;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What to download and where to put it.
 *
 * @param checksum     expected hash, or null when the source does not provide one
 * @param expectedSize size in bytes if known, otherwise 0
 * @param overwrite    replace an existing file at the destination (default is to refuse)
 */
public record DownloadRequest(URI url, Path destination, String displayName, Checksum checksum,
                              long expectedSize, boolean overwrite) {

    public DownloadRequest {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(destination, "destination");
        if (displayName == null || displayName.isBlank()) {
            displayName = destination.getFileName() == null ? url.toString() : destination.getFileName().toString();
        }
    }

    public static DownloadRequest of(URI url, Path destination) {
        return new DownloadRequest(url, destination, null, null, 0L, false);
    }

    public static DownloadRequest of(URI url, Path destination, Checksum checksum) {
        return new DownloadRequest(url, destination, null, checksum, 0L, false);
    }

    DownloadRequest withDestination(Path newDestination) {
        return new DownloadRequest(url, newDestination, displayName, checksum, expectedSize, overwrite);
    }
}
