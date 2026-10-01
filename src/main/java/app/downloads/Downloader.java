package app.downloads;

import app.downloads.DownloadItem.StopRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does the actual transfer of one item, on a worker thread.
 * Downloads go to "name.part" first and are moved into place only after the size and checksum check out.
 */
final class Downloader {

    static final int MAX_ATTEMPTS = 4;
    private static final Logger log = LoggerFactory.getLogger("Downloads");
    private static final String USER_AGENT = "MinecraftManager/0.1 (desktop app)";
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final HttpClient client;
    private final long retryBaseMillis;

    Downloader(HttpClient client, long retryBaseMillis) {
        this.client = client;
        this.retryBaseMillis = retryBaseMillis;
    }

    static Path partFile(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".part");
    }

    void run(DownloadItem item) {
        if (!item.tryStart()) return; // paused or cancelled while it was waiting in the queue

        DownloadRequest request = item.request();
        Path destination = request.destination();
        Path part = partFile(destination);
        log.info("Download started: {} from {}", request.displayName(), describe(request.url()));

        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination) && !request.overwrite()) {
                throw new DownloadException("A file named \"" + destination.getFileName() + "\" already exists.", false);
            }

            boolean completed = false;
            for (int attempt = 1; ; attempt++) {
                try {
                    completed = transfer(item, part);
                    break;
                } catch (DownloadException e) {
                    if (item.stopRequest() != StopRequest.NONE) break;
                    if (!e.isRetryable() || attempt >= MAX_ATTEMPTS) throw e;
                    log.warn("Attempt {} failed for {}: {}", attempt, request.displayName(), e.getMessage());
                    item.note(e.getMessage() + " Retrying (" + attempt + "/" + (MAX_ATTEMPTS - 1) + ")...");
                    if (!sleepUnlessStopped(item, retryBaseMillis << (attempt - 1))) break;
                }
            }

            if (!completed) {
                handleStop(item, part);
                return;
            }
            verifyAndMove(item, part);
            log.info("Download completed: {}", request.displayName());

        } catch (DownloadException e) {
            log.warn("Download failed: {} - {}", request.displayName(), e.getMessage());
            item.fail(e.getMessage());
        } catch (IOException e) {
            log.error("Download failed (disk): {}", request.displayName(), e);
            item.fail("Could not write the file. Check free disk space and folder permissions.");
        } catch (RuntimeException e) {
            log.error("Download failed (unexpected): {}", request.displayName(), e);
            item.fail("Unexpected error. Details were written to the log file.");
        }
    }

    /** @return true when all bytes arrived, false if the download was paused/cancelled. */
    private boolean transfer(DownloadItem item, Path part) throws IOException {
        DownloadRequest request = item.request();
        long existing = Files.isRegularFile(part) ? Files.size(part) : 0;

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.url())
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET();
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-");

        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            item.forceStop(StopRequest.PAUSE);
            return false;
        } catch (IOException e) {
            throw new DownloadException("Could not connect to the server. Check your internet connection.", true, e);
        }

        int status = response.statusCode();
        try (InputStream in = response.body()) {
            item.attach(in);

            boolean append;
            long start;
            long total;
            if (status == 206) {
                Matcher m = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
                if (!m.matches() || Long.parseLong(m.group(1)) != existing) {
                    Files.deleteIfExists(part);
                    throw new DownloadException("The server sent an unexpected response. Starting over.", true);
                }
                append = true;
                start = existing;
                total = m.group(3).equals("*") ? -1 : Long.parseLong(m.group(3));
            } else if (status == 200) {
                append = false; // fresh download, or the server ignored our Range request
                start = 0;
                total = response.headers().firstValueAsLong("Content-Length").orElse(request.expectedSize() > 0
                        ? request.expectedSize() : -1);
            } else if (status == 416) {
                Files.deleteIfExists(part);
                throw new DownloadException("The server rejected resuming. Starting over.", true);
            } else {
                throw httpError(status);
            }

            item.note("");
            return copy(item, in, part, append, start, total);
        } finally {
            item.attach(null);
        }
    }

    private boolean copy(DownloadItem item, InputStream in, Path part, boolean append,
                         long start, long total) throws IOException {
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

        long downloaded = start;
        double speed = 0;
        long sampleTime = System.nanoTime();
        long sampleBytes = downloaded;
        item.progress(downloaded, total, 0);

        try (OutputStream out = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[65536];
            while (true) {
                if (item.stopRequest() != StopRequest.NONE) return false;
                if (Thread.currentThread().isInterrupted()) {
                    item.forceStop(StopRequest.PAUSE);
                    return false;
                }

                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException e) {
                    // Closing the stream to pause/cancel also lands here; that is not an error.
                    if (item.stopRequest() != StopRequest.NONE) return false;
                    if (Thread.currentThread().isInterrupted()) {
                        item.forceStop(StopRequest.PAUSE);
                        return false;
                    }
                    throw new DownloadException("The connection was lost during the download.", true, e);
                }
                if (n < 0) break;

                out.write(buffer, 0, n);
                downloaded += n;

                long now = System.nanoTime();
                long elapsed = now - sampleTime;
                if (elapsed >= 400_000_000L) {
                    double instant = (downloaded - sampleBytes) / (elapsed / 1_000_000_000.0);
                    speed = speed == 0 ? instant : (0.7 * speed + 0.3 * instant);
                    sampleTime = now;
                    sampleBytes = downloaded;
                }
                item.progress(downloaded, total, speed);
            }
        }

        if (total >= 0 && downloaded != total) {
            if (downloaded > total) Files.deleteIfExists(part);
            throw new DownloadException("The connection closed before the download finished.", true);
        }
        item.progress(downloaded, total >= 0 ? total : downloaded, 0);
        return true;
    }

    private void verifyAndMove(DownloadItem item, Path part) throws IOException {
        DownloadRequest request = item.request();
        Path destination = request.destination();
        item.setVerifying();

        Checksum expected = request.checksum();
        if (expected != null) {
            String actual = Checksum.compute(expected.algorithm(), part);
            if (!actual.equals(expected.hex())) {
                Files.deleteIfExists(part);
                throw new DownloadException("The downloaded file does not match its checksum, so it was discarded. "
                        + "It may be corrupted or tampered with.", false);
            }
        }
        if (Files.exists(destination) && !request.overwrite()) {
            throw new DownloadException("A file named \"" + destination.getFileName() + "\" already exists.", false);
        }

        long size = Files.size(part);
        try {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        item.complete(size);
    }

    private void handleStop(DownloadItem item, Path part) {
        if (item.stopRequest() == StopRequest.CANCEL) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                log.warn("Could not remove partial file {}", part, e);
            }
            item.finishStopped(DownloadState.CANCELLED);
            log.info("Download cancelled: {}", item.request().displayName());
        } else {
            item.finishStopped(DownloadState.PAUSED);
            log.info("Download paused: {}", item.request().displayName());
        }
    }

    private boolean sleepUnlessStopped(DownloadItem item, long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        try {
            while (System.nanoTime() < end) {
                if (item.stopRequest() != StopRequest.NONE) return false;
                Thread.sleep(Math.min(50, Math.max(1, millis)));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            item.forceStop(StopRequest.PAUSE);
            return false;
        }
    }

    private static DownloadException httpError(int status) {
        return switch (status) {
            case 404, 410 -> new DownloadException("The file was not found on the server (HTTP " + status + ").", false);
            case 401, 403 -> new DownloadException("The server refused access to the file (HTTP " + status + ").", false);
            case 408, 429 -> new DownloadException("The server is limiting requests (HTTP " + status + "). Try again later.", true);
            default -> status >= 500
                    ? new DownloadException("The server has a problem right now (HTTP " + status + ").", true)
                    : new DownloadException("The server gave an unexpected answer (HTTP " + status + ").", false);
        };
    }

    /** Host and path only. Query strings can contain access tokens, so they never reach the log. */
    private static String describe(URI url) {
        return url.getScheme() + "://" + url.getHost() + (url.getPath() == null ? "" : url.getPath());
    }
}
