package app.mods;

import app.api.ProjectVersion;
import app.api.ProviderException;
import app.downloads.Checksum;
import app.instance.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static app.mods.FakeProvider.jar;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {

    private static final List<String> MC = List.of("1.21.8");
    private static final List<String> FABRIC = List.of("fabric");

    @TempDir
    Path tempDir;

    private FakeProvider provider;
    private UpdateChecker checker;
    private Path instanceDir;
    private Path mods;
    private Instance instance;

    @BeforeEach
    void setUp() throws IOException {
        provider = new FakeProvider();
        checker = new UpdateChecker(provider);
        instanceDir = tempDir.resolve("Test");
        mods = instanceDir.resolve("game").resolve("mods");
        Files.createDirectories(mods);
        instance = new Instance("Test", "Test", "1.21.8", "Fabric", null, 0, 0, null, 4096);
    }

    /** Writes a jar with the given content, records it as installed, and returns its real SHA-512. */
    private String install(String projectId, String versionId, String publishedAt, String content) throws IOException {
        Files.writeString(mods.resolve(projectId + ".jar"), content);
        List<InstalledMod> all = new InstalledContentStore(instanceDir).load();
        all.add(new InstalledMod(projectId, "Mod " + projectId, versionId, versionId + "-num", projectId + ".jar",
                null, true, 0, List.of(), List.of(), publishedAt));
        new InstalledContentStore(instanceDir).save(all);
        return Checksum.compute(Checksum.Algorithm.SHA512, mods.resolve(projectId + ".jar"));
    }

    private ProjectVersion version(String projectId, String versionId, String published) {
        return provider.add(projectId, "Mod " + projectId, versionId, "release", MC, FABRIC, List.of(), jar(projectId + ".jar"));
    }

    private ProjectVersion versionPublished(String projectId, String versionId, String published) {
        return new ProjectVersion(versionId, projectId, "Mod " + projectId, versionId + "-num", "release", MC, FABRIC,
                published, 0, List.of(), List.of(jar(projectId + ".jar")));
    }

    @Test
    void reportsANewerVersionAsAnUpdate() throws IOException {
        String hash = install("A", "a1", "2026-01-01T00:00:00Z", "old bytes");
        provider.latestByHash.put(hash, versionPublished("A", "a2", "2026-03-01T00:00:00Z"));

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertEquals(1, report.updates().size());
        assertEquals("a2", report.updates().get(0).latest().id());
        assertEquals("A", report.updates().get(0).installed().projectId());
        assertTrue(report.upToDate().isEmpty() && report.notChecked().isEmpty());
    }

    @Test
    void sameVersionIdMeansUpToDate() throws IOException {
        String hash = install("A", "a1", "2026-01-01T00:00:00Z", "bytes");
        provider.latestByHash.put(hash, versionPublished("A", "a1", "2026-01-01T00:00:00Z"));

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertTrue(report.updates().isEmpty());
        assertEquals(1, report.upToDate().size());
    }

    @Test
    void aFileThatMatchesTheLatestVersionIsUpToDateEvenIfTheRecordIsStale() throws IOException {
        // The user replaced the jar by hand with the newest one; the record still names the old version.
        String hash = install("A", "a1", "2026-01-01T00:00:00Z", "newest bytes");
        ProjectVersion latest = new ProjectVersion("a2", "A", "Mod A", "a2-num", "release", MC, FABRIC,
                "2026-03-01T00:00:00Z", 0, List.of(), List.of(
                new ProjectVersion.VersionFile("https://cdn.modrinth.com/a.jar", "a.jar", true, 1, null, hash)));
        provider.latestByHash.put(hash, latest);

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertTrue(report.updates().isEmpty());
        assertEquals(1, report.upToDate().size());
    }

    @Test
    void neverOffersADowngrade() throws IOException {
        // Running a newer beta; "latest release" from the server is older.
        String hash = install("A", "a-beta", "2026-05-01T00:00:00Z", "beta bytes");
        provider.latestByHash.put(hash, versionPublished("A", "a-release", "2026-02-01T00:00:00Z"));

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertTrue(report.updates().isEmpty());
        assertEquals(1, report.upToDate().size());
    }

    @Test
    void looksUpThePublishDateWhenTheRecordHasNone() throws IOException {
        String hash = install("A", "a1", null, "bytes");
        provider.versionsById.put("a1", versionPublished("A", "a1", "2026-06-01T00:00:00Z")); // installed is newer
        provider.latestByHash.put(hash, versionPublished("A", "a0", "2026-01-01T00:00:00Z"));

        assertTrue(checker.check(instanceDir, instance, false).updates().isEmpty());
    }

    @Test
    void anUnknownPublishDateIsTreatedAsAnUpdate() throws IOException {
        String hash = install("A", "a1", null, "bytes"); // provider cannot look a1 up
        provider.latestByHash.put(hash, versionPublished("A", "a2", "2026-03-01T00:00:00Z"));

        assertEquals(1, checker.check(instanceDir, instance, false).updates().size());
    }

    @Test
    void unknownFilesMissingFilesAndMismatchedProjectsAreNotChecked() throws IOException {
        install("UNKNOWN", "u1", "2026-01-01T00:00:00Z", "not on the platform");
        install("GONE", "g1", "2026-01-01T00:00:00Z", "will be deleted");
        Files.delete(mods.resolve("GONE.jar"));
        String swapped = install("SWAP", "s1", "2026-01-01T00:00:00Z", "someone else's jar");
        provider.latestByHash.put(swapped, versionPublished("OTHER", "o1", "2026-03-01T00:00:00Z"));

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertTrue(report.updates().isEmpty() && report.upToDate().isEmpty());
        assertEquals(3, report.notChecked().size());
    }

    @Test
    void identicalCopiesAreNotCheckedTwice() throws IOException {
        String hash = install("A", "a1", "2026-01-01T00:00:00Z", "same bytes");
        install("B", "b1", "2026-01-01T00:00:00Z", "same bytes");
        provider.latestByHash.put(hash, versionPublished("A", "a2", "2026-03-01T00:00:00Z"));

        UpdateChecker.Report report = checker.check(instanceDir, instance, false);

        assertEquals(1, report.updates().size());          // A is checked
        assertEquals(1, report.notChecked().size());       // B is an identical copy, so it is skipped
        assertEquals("B", report.notChecked().get(0).projectId());
        assertEquals(1, provider.lastHashes.size());       // only one hash was sent
    }

    @Test
    void vanillaInstancesAndEmptyInstancesMakeNoRequests() throws IOException {
        install("A", "a1", "2026-01-01T00:00:00Z", "bytes");
        Instance vanilla = new Instance("Test", "Test", "1.21.8", "Vanilla", null, 0, 0, null, 4096);
        assertTrue(checker.check(instanceDir, vanilla, false).updates().isEmpty());
        assertEquals(0, provider.hashLookups);

        UpdateChecker.Report empty = new UpdateChecker(provider).check(tempDir.resolve("EmptyInstance"), instance, false);
        assertTrue(empty.updates().isEmpty() && empty.notChecked().isEmpty());
    }

    @Test
    void resultsAreSortedByName() throws IOException {
        String h1 = install("ZED", "z1", "2026-01-01T00:00:00Z", "z");
        String h2 = install("ALPHA", "a1", "2026-01-01T00:00:00Z", "a");
        provider.latestByHash.put(h1, versionPublished("ZED", "z2", "2026-03-01T00:00:00Z"));
        provider.latestByHash.put(h2, versionPublished("ALPHA", "a2", "2026-03-01T00:00:00Z"));

        List<String> names = checker.check(instanceDir, instance, false).updates().stream()
                .map(c -> c.installed().title()).toList();

        assertEquals(List.of("Mod ALPHA", "Mod ZED"), names);
    }

    @Test
    void networkProblemsSurfaceAsProviderExceptions() throws IOException {
        install("A", "a1", "2026-01-01T00:00:00Z", "bytes");
        UpdateChecker failing = new UpdateChecker(new FakeProvider() {
            @Override
            public java.util.Map<String, ProjectVersion> latestVersionsForHashes(java.util.Collection<String> h,
                    String mc, String loader, boolean releaseOnly) {
                throw new ProviderException("Could not reach Modrinth.");
            }
        });
        assertThrows(ProviderException.class, () -> failing.check(instanceDir, instance, false));
    }
}
