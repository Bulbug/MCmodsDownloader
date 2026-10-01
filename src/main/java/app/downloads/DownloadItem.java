package app.downloads;

import java.io.IOException;
import java.io.InputStream;

/** One download in the queue. All state changes are synchronized; the UI reads {@link #snapshot()}. */
public final class DownloadItem {

    enum StopRequest { NONE, PAUSE, CANCEL }

    private final long id;
    private final DownloadRequest request;

    private DownloadState state = DownloadState.QUEUED;
    private long downloaded;
    private long total = -1;
    private double speed;
    private String message = "";
    private StopRequest stop = StopRequest.NONE;
    private volatile InputStream activeStream;

    DownloadItem(long id, DownloadRequest request) {
        this.id = id;
        this.request = request;
        this.total = request.expectedSize() > 0 ? request.expectedSize() : -1;
    }

    public long id() {
        return id;
    }

    public DownloadRequest request() {
        return request;
    }

    public synchronized DownloadSnapshot snapshot() {
        return new DownloadSnapshot(id, request.displayName(), request.destination(), state,
                downloaded, total, speed, message);
    }

    // ---- used by DownloadManager / Downloader (same package) ------------------------

    /** QUEUED -> DOWNLOADING. Returns false if the item was paused/cancelled while waiting. */
    synchronized boolean tryStart() {
        if (state != DownloadState.QUEUED) return false;
        state = DownloadState.DOWNLOADING;
        stop = StopRequest.NONE;
        message = "";
        speed = 0;
        return true;
    }

    synchronized boolean moveToQueued(DownloadState... allowedFrom) {
        for (DownloadState s : allowedFrom) {
            if (state == s) {
                state = DownloadState.QUEUED;
                message = "";
                speed = 0;
                stop = StopRequest.NONE;
                return true;
            }
        }
        return false;
    }

    synchronized DownloadState state() {
        return state;
    }

    synchronized void progress(long downloadedBytes, long totalBytes, double bytesPerSecond) {
        this.downloaded = downloadedBytes;
        this.total = totalBytes;
        this.speed = bytesPerSecond;
    }

    synchronized void note(String text) {
        this.message = text == null ? "" : text;
    }

    synchronized void setVerifying() {
        state = DownloadState.VERIFYING;
        speed = 0;
    }

    synchronized void complete(long sizeBytes) {
        state = DownloadState.COMPLETED;
        downloaded = sizeBytes;
        total = sizeBytes;
        speed = 0;
        message = "";
    }

    synchronized void fail(String userMessage) {
        state = DownloadState.FAILED;
        speed = 0;
        message = userMessage;
    }

    synchronized void finishStopped(DownloadState newState) {
        state = newState;
        speed = 0;
        message = "";
    }

    synchronized StopRequest stopRequest() {
        return stop;
    }

    /** Ask a running download to stop. Closes the connection so a blocked read ends immediately. */
    void requestStop(StopRequest request) {
        synchronized (this) {
            if (state != DownloadState.DOWNLOADING) return;
            stop = request;
        }
        closeStream();
    }

    synchronized void forceStop(StopRequest request) {
        if (stop == StopRequest.NONE) stop = request;
    }

    void attach(InputStream stream) {
        this.activeStream = stream;
    }

    private void closeStream() {
        InputStream s = activeStream;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing only speeds up stopping; nothing to do if it fails.
            }
        }
    }
}
