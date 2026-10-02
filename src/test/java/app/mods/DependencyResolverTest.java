package app.mods;

import app.api.ProjectVersion;
import app.api.ProjectVersion.Dependency;
import app.api.ProjectVersion.DependencyType;
import app.instance.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static app.mods.FakeProvider.jar;
import static app.mods.FakeProvider.of;
import static app.mods.FakeProvider.required;
import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    private static final List<String> MC = List.of("1.21.8");
    private static final List<String> FABRIC = List.of("fabric");

    @TempDir
    Path tempDir;

    private FakeProvider provider;
    private DependencyResolver resolver;
    private Path modsDir;
    private Instance fabric;

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();
        resolver = new DependencyResolver(provider);
        modsDir = tempDir.resolve("mods");
        fabric = instance("Fabric", "1.21.8");
    }

    private static Instance instance(String loader, String mc) {
        return new Instance("test", "Test", mc, loader, null, 0, 0, null, 4096);
    }

    private InstallPlan resolve(ProjectVersion root, List<InstalledMod> installed, boolean allowPre) {
        return resolver.resolve(fabric, modsDir, installed, provider.titles.get(root.projectId()), root, allowPre);
    }

    private InstallPlan resolve(ProjectVersion root) {
        return resolve(root, List.of(), false);
    }

    private static InstalledMod installedMod(String projectId, String title, String versionId, List<String> incompatible) {
        return new InstalledMod(projectId, title, versionId, versionId + "-num", title + ".jar", null,
                true, 0, List.of(), incompatible);
    }

    @Test
    void simpleModWithoutDependencies() {
        ProjectVersion sodium = provider.add("SODIUM", "Sodium", "s1", "release", MC, FABRIC, List.of(), jar("sodium.jar"));

        InstallPlan plan = resolve(sodium);

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(1, plan.toInstall().size());
        assertTrue(plan.toInstall().get(0).chosen());
        assertEquals("sodium.jar", plan.toInstall().get(0).targetFileName());
    }

    @Test
    void requiredDependencyIsAddedAndAttributed() {
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(List.of("Mod A", "Fabric API"), plan.toInstall().stream().map(PlannedMod::title).toList());
        assertEquals("Mod A", plan.toInstall().get(1).neededBy());
        assertFalse(plan.toInstall().get(1).chosen());
    }

    @Test
    void transitiveDependenciesAndSharedOnesAreNotDuplicated() {
        provider.add("LIBD", "Library D", "d1", "release", MC, FABRIC, List.of(), jar("d.jar"));
        provider.add("APIC", "API C", "c1", "release", MC, FABRIC, List.of(required("LIBD")), jar("c.jar"));
        provider.add("LIBB", "Library B", "b1", "release", MC, FABRIC,
                List.of(required("APIC"), required("LIBD")), jar("b.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("LIBB"), required("LIBD")), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(4, plan.toInstall().size());
        assertEquals(1, plan.toInstall().stream().filter(m -> m.projectId().equals("LIBD")).count());
    }

    @Test
    void dependencyCyclesDoNotLoopForever() {
        provider.add("MODB", "Mod B", "b1", "release", MC, FABRIC, List.of(required("MODA")), jar("b.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("MODB")), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(2, plan.toInstall().size());
    }

    @Test
    void alreadyInstalledDependencyIsNotInstalledAgain() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a.jar"));
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api.jar"));

        InstallPlan plan = resolve(root, List.of(installedMod("FAPI", "Fabric API", "f1", List.of())), false);

        assertTrue(plan.canInstall());
        assertEquals(1, plan.toInstall().size());
        assertEquals(1, plan.alreadyInstalled().size());
        assertTrue(plan.alreadyInstalled().get(0).contains("Fabric API"));
    }

    @Test
    void missingCompatibleDependencyVersionExplainsTheProblem() {
        provider.add("FAPI", "Fabric API", "f1", "release", List.of("1.20.1"), FABRIC, List.of(), jar("fabric-api.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertFalse(plan.canInstall());
        String problem = plan.problems().get(0);
        assertTrue(problem.contains("Mod A") && problem.contains("Fabric API") && problem.contains("1.21.8"), problem);
    }

    @Test
    void pinnedDependencyVersionIsUsed() {
        provider.add("FAPI", "Fabric API", "f2", "release", MC, FABRIC, List.of(), jar("fabric-api-new.jar"));
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api-old.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(new Dependency("FAPI", "f1", null, DependencyType.REQUIRED)), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals("fabric-api-old.jar", plan.toInstall().get(1).targetFileName());
    }

    @Test
    void pinnedVersionConflictingWithInstalledOneIsAProblem() {
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api-old.jar"));
        provider.add("FAPI", "Fabric API", "f2", "release", MC, FABRIC, List.of(), jar("fabric-api-new.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(new Dependency("FAPI", "f1", null, DependencyType.REQUIRED)), jar("a.jar"));

        InstallPlan plan = resolve(root, List.of(installedMod("FAPI", "Fabric API", "f2", List.of())), false);

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("specific version"));
    }

    @Test
    void incompatibleModAlreadyInstalledBlocksTheInstall() {
        provider.add("OTHER", "Other Mod", "o1", "release", MC, FABRIC, List.of(), jar("other.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(of("OTHER", DependencyType.INCOMPATIBLE)), jar("a.jar"));

        InstallPlan plan = resolve(root, List.of(installedMod("OTHER", "Other Mod", "o1", List.of())), false);

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("incompatible with Other Mod"));
    }

    @Test
    void installedModThatDeclaresIncompatibilityBlocksTheInstall() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));

        InstallPlan plan = resolve(root, List.of(installedMod("OLD", "Old Mod", "o1", List.of("MODA"))), false);

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("Old Mod is incompatible with Mod A"));
    }

    @Test
    void incompatibleModThatIsNotPresentIsHarmless() {
        provider.add("OTHER", "Other Mod", "o1", "release", MC, FABRIC, List.of(), jar("other.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(of("OTHER", DependencyType.INCOMPATIBLE)), jar("a.jar"));
        assertTrue(resolve(root).canInstall());
    }

    @Test
    void optionalDependenciesAreListedNotInstalledAndEmbeddedOnesIgnored() {
        provider.add("OPT", "Nice Extra", "p1", "release", MC, FABRIC, List.of(), jar("extra.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(of("OPT", DependencyType.OPTIONAL), of("INNER", DependencyType.EMBEDDED)), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall());
        assertEquals(1, plan.toInstall().size());
        assertEquals(List.of("Nice Extra"), plan.optional());
    }

    @Test
    void vanillaInstancesCannotTakeMods() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));
        InstallPlan plan = resolver.resolve(instance("Vanilla", "1.21.8"), modsDir, List.of(), "Mod A", root, false);
        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("Vanilla"));
    }

    @Test
    void wrongMinecraftVersionOrLoaderIsExplained() {
        ProjectVersion old = provider.add("MODA", "Mod A", "a1", "release", List.of("1.20.1"), FABRIC, List.of(), jar("a.jar"));
        InstallPlan plan = resolve(old);
        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("1.20.1") && plan.problems().get(0).contains("1.21.8"));

        ProjectVersion forge = provider.add("MODB", "Mod B", "b1", "release", MC, List.of("forge"), List.of(), jar("b.jar"));
        plan = resolve(forge);
        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("forge") && plan.problems().get(0).contains("Fabric"));
    }

    @Test
    void quiltIsStrictAndOnlyAcceptsQuiltMods() {
        ProjectVersion fabricOnly = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));
        InstallPlan plan = resolver.resolve(instance("Quilt", "1.21.8"), modsDir, List.of(), "Mod A", fabricOnly, false);
        assertFalse(plan.canInstall());

        ProjectVersion quilt = provider.add("MODB", "Mod B", "b1", "release", MC, List.of("quilt"), List.of(), jar("b.jar"));
        assertTrue(resolver.resolve(instance("Quilt", "1.21.8"), modsDir, List.of(), "Mod B", quilt, false).canInstall());
    }

    @Test
    void preReleaseDependenciesNeedPermission() {
        provider.add("LIB", "Library", "l1", "beta", MC, FABRIC, List.of(), jar("lib.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("LIB")), jar("a.jar"));

        InstallPlan denied = resolve(root, List.of(), false);
        assertFalse(denied.canInstall());
        assertTrue(denied.problems().get(0).contains("beta"));

        InstallPlan allowed = resolve(root, List.of(), true);
        assertTrue(allowed.canInstall());
        assertTrue(allowed.warnings().stream().anyMatch(w -> w.contains("beta")));
    }

    @Test
    void releaseIsPreferredOverNewerBetaWhenNotAllowed() {
        provider.add("LIB", "Library", "l2", "beta", MC, FABRIC, List.of(), jar("lib-beta.jar"));
        provider.add("LIB", "Library", "l1", "release", MC, FABRIC, List.of(), jar("lib.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("LIB")), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall());
        assertEquals("lib.jar", plan.toInstall().get(1).targetFileName());
    }

    @Test
    void alreadyInstalledRootIsNotInstalledTwice() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));
        InstallPlan plan = resolve(root, List.of(installedMod("MODA", "Mod A", "a0", List.of())), false);
        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("already installed"));
    }

    @Test
    void existingFileWithTheSameNameIsNeverOverwritten() throws IOException {
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("a.jar"), "someone else's file");
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));

        InstallPlan plan = resolve(root);

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("already exists"));
    }

    @Test
    void nonJarFilesAndMissingFilesAreRefused() {
        ProjectVersion zip = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.zip"));
        assertFalse(resolve(zip).canInstall());
        ProjectVersion none = provider.add("MODB", "Mod B", "b1", "release", MC, FABRIC, List.of());
        assertFalse(resolve(none).canInstall());
    }

    @Test
    void hostileFileNamesFromTheApiCannotEscapeTheModsFolder() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(),
                jar("../../../Windows/evil.jar"));

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall(), plan.problems().toString());
        String name = plan.toInstall().get(0).targetFileName();
        assertFalse(name.contains("/") || name.contains("\\"), name);
        assertTrue(modsDir.resolve(name).normalize().startsWith(modsDir.normalize()));
    }

    @Test
    void missingChecksumProducesAWarning() {
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(),
                new ProjectVersion.VersionFile("https://cdn.modrinth.com/a.jar", "a.jar", true, 5, null, null));
        InstallPlan plan = resolve(root);
        assertTrue(plan.canInstall());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("checksum")));
    }

    @Test
    void dependencyNameLookupFailureIsNotFatal() {
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api.jar"));
        ProjectVersion root = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a.jar"));
        provider.failProjectLookups = true;

        InstallPlan plan = resolve(root);

        assertTrue(plan.canInstall());
        assertEquals("FAPI", plan.toInstall().get(1).title()); // falls back to the id
    }

    @Test
    void absurdDependencyChainsAreStopped() {
        for (int i = 0; i < 60; i++) {
            provider.add("P" + i, "Mod " + i, "v" + i, "release", MC, FABRIC,
                    List.of(required("P" + (i + 1))), jar("m" + i + ".jar"));
        }
        provider.add("P60", "Mod 60", "v60", "release", MC, FABRIC, List.of(), jar("m60.jar"));

        InstallPlan plan = resolve(provider.versionsById.get("v0"));

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("more than"));
    }

    // ---- update mode ------------------------------------------------------------------

    private InstallPlan resolveUpdate(InstalledMod current, ProjectVersion target, List<InstalledMod> installed) {
        return resolver.resolveUpdate(fabric, modsDir, installed, current, "Mod A", target, false);
    }

    @Test
    void updatePlanReplacesTheModAndMayReuseItsFileName() throws IOException {
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("a.jar"), "old version");
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a.jar", null, true, 0, List.of(), List.of());
        ProjectVersion next = provider.add("MODA", "Mod A", "a2", "release", MC, FABRIC, List.of(), jar("a.jar"));

        InstallPlan plan = resolveUpdate(current, next, List.of(current));

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(1, plan.toInstall().size());
        assertEquals("a.jar", plan.toInstall().get(0).targetFileName());
    }

    @Test
    void updatePlanPullsInNewRequiredDependencies() {
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api.jar"));
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a-old.jar", null, true, 0, List.of(), List.of());
        ProjectVersion next = provider.add("MODA", "Mod A", "a2", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a-new.jar"));

        InstallPlan plan = resolveUpdate(current, next, List.of(current));

        assertTrue(plan.canInstall(), plan.problems().toString());
        assertEquals(List.of("Mod A", "Fabric API"), plan.toInstall().stream().map(PlannedMod::title).toList());
    }

    @Test
    void updatePlanKnowsADependencyIsAlreadyInstalled() {
        provider.add("FAPI", "Fabric API", "f1", "release", MC, FABRIC, List.of(), jar("fabric-api.jar"));
        InstalledMod fapi = new InstalledMod("FAPI", "Fabric API", "f1", "1", "fabric-api.jar", null, false, 0, List.of(), List.of());
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a-old.jar", null, true, 0, List.of("FAPI"), List.of());
        ProjectVersion next = provider.add("MODA", "Mod A", "a2", "release", MC, FABRIC,
                List.of(required("FAPI")), jar("a-new.jar"));

        InstallPlan plan = resolveUpdate(current, next, List.of(current, fapi));

        assertTrue(plan.canInstall());
        assertEquals(1, plan.toInstall().size());
        assertEquals(1, plan.alreadyInstalled().size());
    }

    @Test
    void updatePlanRefusesTheSameVersionAndOtherProjects() {
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a.jar", null, true, 0, List.of(), List.of());
        ProjectVersion same = provider.add("MODA", "Mod A", "a1", "release", MC, FABRIC, List.of(), jar("a.jar"));
        assertFalse(resolveUpdate(current, same, List.of(current)).canInstall());

        ProjectVersion other = provider.add("MODB", "Mod B", "b1", "release", MC, FABRIC, List.of(), jar("b.jar"));
        assertFalse(resolveUpdate(current, other, List.of(current)).canInstall());
    }

    @Test
    void updatePlanStillChecksCompatibilityAndIncompatibleMods() {
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a.jar", null, true, 0, List.of(), List.of());
        ProjectVersion wrongMc = provider.add("MODA", "Mod A", "a2", "release", List.of("1.20.1"), FABRIC, List.of(), jar("a2.jar"));
        assertFalse(resolveUpdate(current, wrongMc, List.of(current)).canInstall());

        InstalledMod rival = new InstalledMod("RIVAL", "Rival", "r1", "1", "r.jar", null, true, 0, List.of(), List.of("MODA"));
        ProjectVersion ok = provider.add("MODA", "Mod A", "a3", "release", MC, FABRIC, List.of(), jar("a3.jar"));
        InstallPlan plan = resolveUpdate(current, ok, List.of(current, rival));
        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("Rival is incompatible"));
    }

    @Test
    void updateStillRefusesToOverwriteSomeoneElsesFile() throws IOException {
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("taken.jar"), "user file");
        InstalledMod current = new InstalledMod("MODA", "Mod A", "a1", "1.0", "a.jar", null, true, 0, List.of(), List.of());
        ProjectVersion next = provider.add("MODA", "Mod A", "a2", "release", MC, FABRIC, List.of(), jar("taken.jar"));

        InstallPlan plan = resolveUpdate(current, next, List.of(current));

        assertFalse(plan.canInstall());
        assertTrue(plan.problems().get(0).contains("already exists"));
    }
}
