package app.downloads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Tiny local web server so download tests need no internet. */
final class TestServer implements AutoCloseable {

    static final byte[] DATA = new byte[200_000];

    static {
        for (int i = 0; i < DATA.length; i++) DATA[i] = (byte) (i * 31 + 7);
    }

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    volatile String lastRangeHeader;

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/file", ex -> serve(ex, true, 0));
        server.createContext("/slow", ex -> serve(ex, true, 20));
        server.createContext("/norange", ex -> serve(ex, false, 0));
        server.createContext("/missing", ex -> {
            count(ex);
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/flaky", ex -> {
            int n = count(ex);
            if (n <= 2) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
            } else {
                serve(ex, true, 0);
            }
        });
        server.start();
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    private int count(HttpExchange ex) {
        return hits.computeIfAbsent(ex.getRequestURI().getPath(), k -> new AtomicInteger()).incrementAndGet();
    }

    private void serve(HttpExchange ex, boolean honorRange, int chunkDelayMillis) throws IOException {
        // The /flaky handler already counted this request itself.
        if (!"/flaky".equals(ex.getRequestURI().getPath())) count(ex);
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null) lastRangeHeader = range;

        int start = 0;
        int status = 200;
        if (honorRange && range != null && range.startsWith("bytes=")) {
            start = Integer.parseInt(range.substring(6, range.indexOf('-')));
            if (start >= DATA.length) {
                ex.sendResponseHeaders(416, -1);
                ex.close();
                return;
            }
            status = 206;
            ex.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + (DATA.length - 1) + "/" + DATA.length);
        }
        ex.sendResponseHeaders(status, DATA.length - start);
        try (OutputStream out = ex.getResponseBody()) {
            int pos = start;
            while (pos < DATA.length) {
                int n = Math.min(4096, DATA.length - pos);
                out.write(DATA, pos, n);
                out.flush();
                pos += n;
                if (chunkDelayMillis > 0) Thread.sleep(chunkDelayMillis);
            }
        } catch (IOException | InterruptedException ignored) {
            // Client went away (pause/cancel). Expected in some tests.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
