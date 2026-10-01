package app.mods;

import app.api.ProjectVersion;
import app.api.ProjectVersion.VersionFile;
import app.downloads.DownloadManager;
import app.downloads.TestManagers;
import app.instance.Instance;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Installs real files from a local web server into a temporary instance folder. */
class ModInstallerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private DownloadManager downloads;
    private Path instanceDir;
    private Instance instance;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/f/", ex -> {
            byte[] data = files.get(ex.getRequestURI().getPath().substring(3));
            if (data == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            boolean slow = ex.getRequestURI().getQuery() != null;
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < data.length; i += 1024) {
                    out.write(data, i, Math.min(1024, data.length - i));
                    out.flush();
                    if (slow) Thread.sleep(25);
                }
            } catch (IOException | InterruptedException ignored) {
                // client cancelled
            }
        });
        server.start();

        instanceDir = tempDir.resolve("instances").resolve("Test");
        Files.createDirectories(instanceDir);
        instance = new Instance("Test", "Test", "1.21.8", "Fabric", null, 0, 0, null, 4096);
        downloads = TestManagers.loopback(3, tempDir);
    }

    @AfterEach
    void tearDown() {
        downloads.shutdown();
        server.stop(0);
    }

    private PlannedMod planned(String projectId, String title, String fileName, byte[] content, boolean chosen,
                               boolean slow, String sha512Override) throws Exception {
        files.put(fileName, content);
        String sha = sha512Override != null ? sha512Override
                : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/f/" + fileName + (slow ? "?slow" : "");
        VersionFile file = new VersionFile(url, fileName, true, content.length, null, sha);
        ProjectVersion version = new ProjectVersion("ver-" + projectId, projectId, title, "1.0", "release",
                List.of("1.21.8"), List.of("fabric"), "2026-01-01T00:00:00Z", 0, List.of(), List.of(file));
        return new PlannedMod(projectId, title, version, file, fileName, chosen, chosen ? null : "Root");
    }

    private InstallPlan plan(PlannedMod... mods) {
        return new InstallPlan(instance, List.of(mods), List.of(), List.of(), List.of(), List.of());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path mods() {
        return instanceDir.resolve("game").resolve("mods");
    }

    @Test
    void installsAllFilesAndRecordsThem() throws Exception {
        InstallPlan plan = plan(planned("P1", "Mod A", "a.jar", bytes("AAA"), true, false, null),
                planned("P2", "Lib B", "b.jar", bytes("BBB"), false, false, null));
        List<String> messages = new ArrayList<>();

        List<InstalledMod> added = new ModInstaller(downloads).install(plan, instanceDir, () -> false, messages::add);

        assertEquals(2, added.size());
        assertEquals("AAA", Files.readString(mods().resolve("a.jar")));
        assertEquals("BBB", Files.readString(mods().resolve("b.jar")));
        assertFalse(Files.exists(instanceDir.resolve(".staging")));

        List<InstalledMod> saved = new InstalledContentStore(instanceDir).load();
        assertEquals(2, saved.size());
        assertTrue(saved.stream().filter(m -> m.projectId().equals("P1")).findFirst().orElseThrow().explicit());
        assertFalse(saved.stream().filter(m -> m.projectId().equals("P2")).findFirst().orElseThrow().explicit());
        assertEquals("Done.", messages.get(messages.size() - 1));
    }

    @Test
    void newModsAreAddedToTheExistingRecord() throws Exception {
        new InstalledContentStore(instanceDir).save(List.of(
                new InstalledMod("OLD", "Old", "o1", "1", "old.jar", null, true, 0, List.of(), List.of())));

        new ModInstaller(downloads).install(plan(planned("P1", "Mod A", "a.jar", bytes("A"), true, false, null)),
                instanceDir, () -> false, m -> { });

        assertEquals(2, new InstalledContentStore(instanceDir).load().size());
    }

    @Test
    void oneFailedDownloadLeavesTheInstanceUntouched() throws Exception {
        PlannedMod good = planned("P1", "Mod A", "a.jar", bytes("AAA"), true, false, null);
        PlannedMod broken = planned("P2", "Lib B", "b.jar", bytes("BBB"), false, false, null);
        files.remove("b.jar"); // the server now answers 404 for it

        InstallException e = assertThrows(InstallException.class, () ->
                new ModInstaller(downloads).install(plan(good, broken), instanceDir, () -> false, m -> { }));

        assertTrue(e.getMessage().contains("Lib B"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was changed"), e.getMessage());
        assertFalse(Files.exists(mods().resolve("a.jar")));
        assertFalse(Files.exists(mods().resolve("b.jar")));
        assertFalse(Files.exists(instanceDir.resolve(InstalledContentStore.FILE_NAME)));
        assertFalse(Files.exists(instanceDir.resolve(".staging")));
    }

    @Test
    void checksumMismatchAbortsEverything() throws Exception {
        PlannedMod good = planned("P1", "Mod A", "a.jar", bytes("AAA"), true, false, null);
        PlannedMod tampered = planned("P2", "Lib B", "b.jar", bytes("BBB"), false, false, "0".repeat(128));

        InstallException e = assertThrows(InstallException.class, () ->
                new ModInstaller(downloads).install(plan(good, tampered), instanceDir, () -> false, m -> { }));

        assertTrue(e.getMessage().toLowerCase().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(mods().resolve("a.jar")));
        assertFalse(Files.exists(mods().resolve("b.jar")));
    }

    @Test
    void cancellingDuringDownloadUndoesEverything() throws Exception {
        byte[] big = new byte[200_000];
        PlannedMod slow = planned("P1", "Big Mod", "big.jar", big, true, true, null);
        AtomicBoolean cancel = new AtomicBoolean(false);

        InstallException e = assertThrows(InstallException.class, () ->
                new ModInstaller(downloads).install(plan(slow), instanceDir, cancel::get, message -> {
                    if (message.startsWith("Downloading")) cancel.set(true);
                }));

        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertFalse(Files.exists(mods().resolve("big.jar")));
        assertFalse(Files.exists(instanceDir.resolve(".staging")));
        assertFalse(Files.exists(instanceDir.resolve(InstalledContentStore.FILE_NAME)));
    }

    @Test
    void aFileThatAppearsBeforeTheMoveIsNeverOverwritten() throws Exception {
        PlannedMod mod = planned("P1", "Mod A", "a.jar", bytes("NEW"), true, false, null);
        Files.createDirectories(mods());
        Files.writeString(mods().resolve("a.jar"), "USER FILE");

        InstallException e = assertThrows(InstallException.class, () ->
                new ModInstaller(downloads).install(plan(mod), instanceDir, () -> false, m -> { }));

        assertTrue(e.getMessage().contains("Nothing was changed"));
        assertEquals("USER FILE", Files.readString(mods().resolve("a.jar")));
        assertFalse(Files.exists(instanceDir.resolve(InstalledContentStore.FILE_NAME)));
    }

    @Test
    void aPlanWithProblemsIsRefused() {
        InstallPlan bad = new InstallPlan(instance, List.of(), List.of(), List.of(), List.of(), List.of("nope"));
        assertThrows(InstallException.class, () ->
                new ModInstaller(downloads).install(bad, instanceDir, () -> false, m -> { }));
    }
}
