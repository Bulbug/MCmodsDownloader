package app.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Small in-memory cache for API answers. Entries expire; the oldest are dropped when it is full. */
final class ResponseCache {

    private record Entry(String body, long expiresAtNanos) { }

    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    ResponseCache(int maxEntries, LongSupplier nanoClock) {
        this.maxEntries = maxEntries;
        this.nanoClock = nanoClock;
    }

    ResponseCache() {
        this(200, System::nanoTime);
    }

    synchronized String get(String key) {
        Entry e = entries.get(key);
        if (e == null) return null;
        if (nanoClock.getAsLong() - e.expiresAtNanos() >= 0) {
            entries.remove(key);
            return null;
        }
        return e.body();
    }

    synchronized void put(String key, String body, long ttlNanos) {
        if (ttlNanos <= 0) return;
        entries.remove(key);
        while (entries.size() >= maxEntries) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
        entries.put(key, new Entry(body, nanoClock.getAsLong() + ttlNanos));
    }

    synchronized void clear() {
        entries.clear();
    }
}
