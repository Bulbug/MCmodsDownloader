package app.downloads;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class DownloadManagerTest {

    @TempDir
    Path tempDir;

    private TestServer server;
    private DownloadManager manager;
    private Path downloads;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestServer();
        downloads = tempDir.resolve("downloads");
        manager = new DownloadManager(2, () -> List.of(downloads), true, 10L);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        server.close();
    }

    private static DownloadSnapshot await(DownloadItem item, Predicate<DownloadSnapshot> condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            DownloadSnapshot s = item.snapshot();
            if (condition.test(s)) return s;
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail("Timed out. Last state: " + item.snapshot());
        return null;
    }

    private static Predicate<DownloadSnapshot> state(DownloadState expected) {
        return s -> s.state() == expected;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void downloadsAFileAndLeavesNoPartFile() throws IOException {
        Path dest = downloads.resolve("mod.jar");
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/file"), dest));

        DownloadSnapshot done = await(item, state(DownloadState.COMPLETED));

        assertArrayEquals(TestServer.DATA, Files.readAllBytes(dest));
        assertFalse(Files.exists(downloads.resolve("mod.jar.part")));
        assertEquals(1.0, done.fraction());
        assertEquals(TestServer.DATA.length, done.totalBytes());
    }

    @Test
    void correctChecksumPasses() throws Exception {
        Checksum sum = new Checksum(Checksum.Algorithm.SHA256, sha256(TestServer.DATA));
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("ok.jar"), sum));
        await(item, state(DownloadState.COMPLETED));
        assertTrue(Files.exists(downloads.resolve("ok.jar")));
    }

    @Test
    void wrongChecksumFailsAndDiscardsTheFile() {
        Checksum wrong = new Checksum(Checksum.Algorithm.SHA256, "0".repeat(64));
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("bad.jar"), wrong));

        DownloadSnapshot failed = await(item, state(DownloadState.FAILED));

        assertTrue(failed.message().toLowerCase().contains("checksum"));
        assertFalse(Files.exists(downloads.resolve("bad.jar")));
        assertFalse(Files.exists(downloads.resolve("bad.jar.part")));
    }

    @Test
    void notFoundFailsWithoutRetrying() {
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/missing"), downloads.resolve("x.jar")));

        DownloadSnapshot failed = await(item, state(DownloadState.FAILED));

        assertTrue(failed.message().contains("not found"));
        assertEquals(1, server.hits("/missing"));
    }

    @Test
    void temporaryServerErrorsAreRetried() throws IOException {
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/flaky"), downloads.resolve("f.jar")));

        await(item, state(DownloadState.COMPLETED));

        assertEquals(3, server.hits("/flaky"));
        assertArrayEquals(TestServer.DATA, Files.readAllBytes(downloads.resolve("f.jar")));
    }

    @Test
    void pauseKeepsPartialFileAndResumeUsesRangeRequest() throws IOException {
        Path dest = downloads.resolve("slow.jar");
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/slow"), dest));

        await(item, s -> s.state() == DownloadState.DOWNLOADING && s.downloadedBytes() > 0);
        manager.pause(item.id());
        await(item, state(DownloadState.PAUSED));

        Path part = downloads.resolve("slow.jar.part");
        assertTrue(Files.exists(part));
        long partial = Files.size(part);
        assertTrue(partial > 0 && partial < TestServer.DATA.length);

        manager.resume(item.id());
        await(item, state(DownloadState.COMPLETED));

        assertEquals("bytes=" + partial + "-", server.lastRangeHeader);
        assertArrayEquals(TestServer.DATA, Files.readAllBytes(dest));
    }

    @Test
    void cancelRemovesPartialFile() {
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/slow"), downloads.resolve("c.jar")));
        await(item, s -> s.state() == DownloadState.DOWNLOADING && s.downloadedBytes() > 0);

        manager.cancel(item.id());
        await(item, state(DownloadState.CANCELLED));

        assertFalse(Files.exists(downloads.resolve("c.jar.part")));
        assertFalse(Files.exists(downloads.resolve("c.jar")));
    }

    @Test
    void serverThatIgnoresRangeStillGivesACorrectFile() throws IOException {
        Files.createDirectories(downloads);
        // Leftover partial file with WRONG content; server will ignore Range and send everything.
        Files.write(downloads.resolve("n.jar.part"), new byte[1000]);

        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/norange"), downloads.resolve("n.jar")));
        await(item, state(DownloadState.COMPLETED));

        assertArrayEquals(TestServer.DATA, Files.readAllBytes(downloads.resolve("n.jar")));
    }

    @Test
    void retryAfterFailureWorks() throws IOException {
        Path dest = downloads.resolve("r.jar");
        DownloadItem item = manager.enqueue(DownloadRequest.of(server.url("/missing"), dest));
        await(item, state(DownloadState.FAILED));

        manager.retry(item.id());
        // Still missing, so it fails again; the point is that it ran a second time.
        await(item, s -> s.state() == DownloadState.FAILED && server.hits("/missing") == 2);
    }

    @Test
    void secondDownloadWaitsWhenLimitIsOne() {
        manager.setMaxConcurrent(1);
        DownloadItem first = manager.enqueue(DownloadRequest.of(server.url("/slow"), downloads.resolve("1.jar")));
        await(first, state(DownloadState.DOWNLOADING));
        DownloadItem second = manager.enqueue(DownloadRequest.of(server.url("/slow"), downloads.resolve("2.jar")));

        assertEquals(DownloadState.QUEUED, second.snapshot().state());

        await(first, state(DownloadState.COMPLETED));
        await(second, state(DownloadState.COMPLETED));
    }

    @Test
    void pausingAQueuedDownloadPreventsItFromStarting() {
        manager.setMaxConcurrent(1);
        DownloadItem first = manager.enqueue(DownloadRequest.of(server.url("/slow"), downloads.resolve("1.jar")));
        await(first, state(DownloadState.DOWNLOADING));
        DownloadItem second = manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("2.jar")));

        manager.pause(second.id());
        await(first, state(DownloadState.COMPLETED));
        // Give the worker a chance to (wrongly) pick it up.
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        assertEquals(DownloadState.PAUSED, second.snapshot().state());
        assertFalse(Files.exists(downloads.resolve("2.jar")));

        manager.resume(second.id());
        await(second, state(DownloadState.COMPLETED));
    }

    @Test
    void existingFileIsNotOverwrittenUnlessAllowed() throws IOException {
        Path dest = downloads.resolve("keep.jar");
        Files.createDirectories(downloads);
        Files.writeString(dest, "original");

        DownloadItem refused = manager.enqueue(DownloadRequest.of(server.url("/file"), dest));
        DownloadSnapshot failed = await(refused, state(DownloadState.FAILED));
        assertTrue(failed.message().contains("already exists"));
        assertEquals("original", Files.readString(dest));

        DownloadItem allowed = manager.enqueue(new DownloadRequest(server.url("/file"), dest, null, null, 0, true));
        await(allowed, state(DownloadState.COMPLETED));
        assertArrayEquals(TestServer.DATA, Files.readAllBytes(dest));
    }

    @Test
    void clearFinishedRemovesOnlyFinishedEntries() {
        DownloadItem done = manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("a.jar")));
        await(done, state(DownloadState.COMPLETED));
        DownloadItem running = manager.enqueue(DownloadRequest.of(server.url("/slow"), downloads.resolve("b.jar")));

        manager.clearFinished();

        assertEquals(1, manager.snapshots().size());
        assertEquals(running.id(), manager.snapshots().get(0).id());
    }

    // ---- security rules ---------------------------------------------------------------

    @Test
    void refusesToWriteOutsideAllowedFolders() {
        Path outside = tempDir.resolve("elsewhere/evil.jar");
        assertThrows(DownloadException.class,
                () -> manager.enqueue(DownloadRequest.of(server.url("/file"), outside)));
        assertThrows(DownloadException.class,
                () -> manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("../escape.jar"))));
    }

    @Test
    void refusesExecutableFileTypes() {
        for (String name : new String[]{"setup.exe", "run.BAT", "x.cmd", "s.ps1", "a.sh", "b.msi"}) {
            assertThrows(DownloadException.class,
                    () -> manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve(name))), name);
        }
        assertDoesNotThrow(() -> manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("mod.jar"))));
    }

    @Test
    void refusesPartFileNamesAndPlainHttpToRemoteHosts() {
        assertThrows(DownloadException.class,
                () -> manager.enqueue(DownloadRequest.of(server.url("/file"), downloads.resolve("a.jar.part"))));

        DownloadManager strict = new DownloadManager(1, () -> List.of(downloads));
        try {
            assertThrows(DownloadException.class, () -> strict.enqueue(
                    DownloadRequest.of(URI.create("http://example.com/a.jar"), downloads.resolve("a.jar"))));
            assertThrows(DownloadException.class, () -> strict.enqueue(
                    DownloadRequest.of(server.url("/file"), downloads.resolve("a.jar")))); // loopback http not allowed in production mode
            assertThrows(DownloadException.class, () -> strict.enqueue(
                    DownloadRequest.of(URI.create("ftp://example.com/a.jar"), downloads.resolve("a.jar"))));
        } finally {
            strict.shutdown();
        }
    }
}
