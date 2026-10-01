package app.downloads;

import java.nio.file.Path;
import java.util.List;

/** Test-only access to the loopback-http constructor (which is package-private on purpose). */
public final class TestManagers {

    private TestManagers() { }

    public static DownloadManager loopback(int maxConcurrent, Path allowedRoot) {
        return new DownloadManager(maxConcurrent, () -> List.of(allowedRoot), true, 10L);
    }
}
