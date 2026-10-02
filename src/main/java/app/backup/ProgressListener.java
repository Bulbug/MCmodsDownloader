package app.backup;

/** Receives progress while a backup or restore runs (bytes done of bytes total; total may be 0 if unknown). */
@FunctionalInterface
public interface ProgressListener {
    void update(long doneBytes, long totalBytes);
}
