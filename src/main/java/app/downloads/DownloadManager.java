package app.downloads;

import app.downloads.DownloadItem.StopRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Queue of downloads. At most N run at once; the rest wait as QUEUED.
 * Every method is safe to call from the UI thread: nothing here blocks on the network.
 */
public final class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger("Downloads");

    private final Supplier<List<Path>> allowedRoots;
    private final boolean allowLoopbackHttp;
    private final ThreadPoolExecutor executor;
    private final Downloader downloader;
    private final List<DownloadItem> items = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /**
     * @param allowedRoots folders files may be written into (evaluated on every enqueue, so
     *                     changing the instances folder in Settings takes effect immediately)
     */
    public DownloadManager(int maxConcurrent, Supplier<List<Path>> allowedRoots) {
        this(maxConcurrent, allowedRoots, false, 1000L);
    }

    /** Test hook: allows plain http to 127.0.0.1 and shortens retry delays. */
    DownloadManager(int maxConcurrent, Supplier<List<Path>> allowedRoots,
                    boolean allowLoopbackHttp, long retryBaseMillis) {
        this.allowedRoots = allowedRoots;
        this.allowLoopbackHttp = allowLoopbackHttp;
        int n = clamp(maxConcurrent);
        this.executor = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread t = new Thread(runnable, "download-worker");
            t.setDaemon(true);
            return t;
        });
        this.executor.allowCoreThreadTimeOut(true);

        // HTTPS -> HTTP redirects are never followed by Redirect.NORMAL.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.downloader = new Downloader(client, retryBaseMillis);
    }

    /** Validates the request and adds it to the queue. Throws {@link DownloadException} if it is not allowed. */
    public DownloadItem enqueue(DownloadRequest request) {
        DownloadRequest safe = validate(request);
        DownloadItem item = new DownloadItem(nextId.getAndIncrement(), safe);
        items.add(item);
        submit(item);
        return item;
    }

    public List<DownloadSnapshot> snapshots() {
        return items.stream().map(DownloadItem::snapshot).toList();
    }

    public void pause(long id) {
        DownloadItem item = find(id);
        if (item == null) return;
        DownloadState state = item.state();
        if (state == DownloadState.QUEUED) {
            item.finishStopped(DownloadState.PAUSED);
        } else if (state == DownloadState.DOWNLOADING) {
            item.requestStop(StopRequest.PAUSE);
        }
    }

    public void resume(long id) {
        DownloadItem item = find(id);
        if (item != null && item.moveToQueued(DownloadState.PAUSED)) submit(item);
    }

    /** Cancels and removes the partial file. */
    public void cancel(long id) {
        DownloadItem item = find(id);
        if (item == null) return;
        DownloadState state = item.state();
        if (state == DownloadState.DOWNLOADING) {
            item.requestStop(StopRequest.CANCEL);
        } else if (state == DownloadState.QUEUED || state == DownloadState.PAUSED || state == DownloadState.FAILED) {
            deletePartQuietly(item);
            item.finishStopped(DownloadState.CANCELLED);
        }
    }

    /** Starts a failed or cancelled download again. A kept partial file is resumed. */
    public void retry(long id) {
        DownloadItem item = find(id);
        if (item != null && item.moveToQueued(DownloadState.FAILED, DownloadState.CANCELLED)) submit(item);
    }

    /** Removes completed, failed and cancelled entries from the list (files are not touched). */
    public void clearFinished() {
        items.removeIf(i -> i.state().isFinished());
    }

    public void setMaxConcurrent(int max) {
        int n = clamp(max);
        if (n > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(n);
            executor.setCorePoolSize(n);
        } else {
            executor.setCorePoolSize(n);
            executor.setMaximumPoolSize(n);
        }
    }

    /** Stops workers. Running downloads become PAUSED and keep their .part files (resumed if requested again). */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ---- internals ---------------------------------------------------------------

    private void submit(DownloadItem item) {
        try {
            executor.execute(() -> downloader.run(item));
        } catch (RuntimeException e) { // executor already shut down
            item.fail("The download manager is closing.");
        }
    }

    private DownloadItem find(long id) {
        for (DownloadItem i : items) if (i.id() == id) return i;
        return null;
    }

    private void deletePartQuietly(DownloadItem item) {
        try {
            Files.deleteIfExists(Downloader.partFile(item.request().destination()));
        } catch (IOException e) {
            log.warn("Could not remove partial file for {}", item.request().displayName(), e);
        }
    }

    private DownloadRequest validate(DownloadRequest request) {
        URI url = request.url();
        String host = url.getHost();
        if (host == null || url.getScheme() == null) throw new DownloadException("The web address is not valid.", false);

        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean loopbackHttp = allowLoopbackHttp && "http".equalsIgnoreCase(url.getScheme()) && isLoopback(host);
        if (!https && !loopbackHttp) {
            throw new DownloadException("Only secure (https) downloads are allowed.", false);
        }

        Path destination = request.destination().toAbsolutePath().normalize();
        Path fileName = destination.getFileName();
        if (fileName == null) throw new DownloadException("The destination is not a file.", false);

        boolean inside = false;
        for (Path root : allowedRoots.get()) {
            Path r = root.toAbsolutePath().normalize();
            if (destination.startsWith(r) && !destination.equals(r)) {
                inside = true;
                break;
            }
        }
        if (!inside) {
            throw new DownloadException("Files can only be saved inside the downloads or instances folders.", false);
        }
        if (FileNames.hasBlockedExtension(fileName.toString())) {
            throw new DownloadException("Files of this type are not allowed: " + fileName, false);
        }
        if (fileName.toString().toLowerCase(Locale.ROOT).endsWith(".part")) {
            throw new DownloadException("File names ending in .part are reserved.", false);
        }
        return request.withDestination(destination);
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")
                || host.equals("[::1]") || host.equals("::1");
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(10, n));
    }
}
