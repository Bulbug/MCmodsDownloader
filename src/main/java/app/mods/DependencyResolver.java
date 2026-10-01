package app.mods;

import app.api.ContentProvider;
import app.api.ProjectVersion;
import app.api.ProjectVersion.Dependency;
import app.api.ProjectVersion.DependencyType;
import app.api.ProjectVersion.VersionFile;
import app.api.ProviderException;
import app.downloads.FileNames;
import app.instance.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out everything that has to be installed together with a mod, without changing anything.
 *
 * Rules:
 * - REQUIRED dependencies are installed too (and their own requirements, any depth)
 * - OPTIONAL ones are only listed, EMBEDDED ones are ignored (already inside the jar)
 * - INCOMPATIBLE ones block the plan if present (in the instance or in the plan)
 * - mods must match the instance's exact Minecraft version and loader
 * - problems are collected and explained; network trouble throws {@link ProviderException}
 */
public final class DependencyResolver {

    static final int MAX_MODS = 40;
    private static final Logger log = LoggerFactory.getLogger("Mods");

    private final ContentProvider provider;

    public DependencyResolver(ContentProvider provider) {
        this.provider = provider;
    }

    public InstallPlan resolve(Instance instance, Path modsDir, List<InstalledMod> installed,
                               String rootTitle, ProjectVersion root, boolean allowPreRelease) {
        Run run = new Run(instance, modsDir, installed, allowPreRelease);
        run.go(rootTitle, root);
        return new InstallPlan(instance, List.copyOf(run.planned.values()), run.alreadyInstalled,
                run.optional, run.warnings, run.problems);
    }

    /** State of one resolve call. */
    private final class Run {
        final Instance instance;
        final Path modsDir;
        final boolean allowPreRelease;
        final String loader;
        final Map<String, InstalledMod> installedByProject = new HashMap<>();
        final List<InstalledMod> installed;
        final Map<String, PlannedMod> planned = new LinkedHashMap<>();
        final Set<String> usedFileNames = new HashSet<>();
        final Map<String, String> titles = new HashMap<>();
        final List<String> alreadyInstalled = new ArrayList<>();
        final List<String> optional = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> problems = new ArrayList<>();

        Run(Instance instance, Path modsDir, List<InstalledMod> installed, boolean allowPreRelease) {
            this.instance = instance;
            this.modsDir = modsDir;
            this.installed = installed;
            this.allowPreRelease = allowPreRelease;
            this.loader = instance.loader().toLowerCase(Locale.ROOT);
            for (InstalledMod m : installed) {
                installedByProject.put(m.projectId(), m);
                titles.put(m.projectId(), m.title());
            }
        }

        void go(String rootTitle, ProjectVersion root) {
            if (loader.equals("vanilla")) {
                problems.add("This instance uses Vanilla Minecraft, which cannot load mods. "
                        + "Create an instance with Fabric, Quilt, Forge or NeoForge.");
                return;
            }
            if (root.projectId() == null) {
                problems.add("This version has no project identifier, so it cannot be installed.");
                return;
            }
            titles.put(root.projectId(), rootTitle);

            InstalledMod already = installedByProject.get(root.projectId());
            if (already != null) {
                problems.add(rootTitle + " is already installed (version " + already.versionNumber() + ").");
                return;
            }
            String mismatch = compatibilityProblem(rootTitle, root);
            if (mismatch != null) {
                problems.add(mismatch);
                return;
            }
            if (!addPlanned(rootTitle, root, true, null)) return;

            Deque<PlannedMod> queue = new ArrayDeque<>();
            queue.add(planned.get(root.projectId()));
            while (!queue.isEmpty()) {
                PlannedMod current = queue.poll();
                for (Dependency dep : current.version().dependencies()) {
                    handle(dep, current, queue);
                    if (planned.size() > MAX_MODS) {
                        problems.add("This would install more than " + MAX_MODS + " mods. Something looks wrong; "
                                + "please install the required mods one at a time.");
                        return;
                    }
                }
            }
        }

        private void handle(Dependency dep, PlannedMod from, Deque<PlannedMod> queue) {
            if (dep.type() == DependencyType.EMBEDDED) return;

            ProjectVersion pinned = null;
            String projectId = dep.projectId();
            if (projectId == null && dep.versionId() != null) {
                pinned = provider.version(dep.versionId());
                projectId = pinned.projectId();
            }
            if (projectId == null) {
                warnings.add(from.title() + " lists a dependency that could not be identified"
                        + (dep.fileName() == null ? "" : " (" + dep.fileName() + ")") + ". It was skipped.");
                return;
            }

            switch (dep.type()) {
                case OPTIONAL -> {
                    if (!planned.containsKey(projectId) && !installedByProject.containsKey(projectId)) {
                        optional.add(titleOf(projectId));
                    }
                }
                case INCOMPATIBLE -> {
                    if (planned.containsKey(projectId) || installedByProject.containsKey(projectId)) {
                        problems.add(from.title() + " is incompatible with " + titleOf(projectId)
                                + ", which is installed or about to be installed.");
                    }
                }
                case REQUIRED -> required(dep, projectId, pinned, from, queue);
                default -> { }
            }
        }

        private void required(Dependency dep, String projectId, ProjectVersion pinned,
                              PlannedMod from, Deque<PlannedMod> queue) {
            PlannedMod inPlan = planned.get(projectId);
            if (inPlan != null) {
                if (dep.versionId() != null && !dep.versionId().equals(inPlan.version().id())) {
                    problems.add(from.title() + " needs a specific version of " + inPlan.title()
                            + ", but a different version is already part of this plan.");
                }
                return; // also stops dependency cycles
            }
            InstalledMod have = installedByProject.get(projectId);
            if (have != null) {
                if (dep.versionId() != null && !dep.versionId().equals(have.versionId())) {
                    problems.add(from.title() + " needs a specific version of " + have.title()
                            + ", but version " + have.versionNumber() + " is installed. Update or remove it first.");
                } else {
                    alreadyInstalled.add(have.title() + " (version " + have.versionNumber() + ")");
                }
                return;
            }

            String depTitle = titleOf(projectId);
            ProjectVersion chosen;
            if (dep.versionId() != null) {
                chosen = pinned != null ? pinned : provider.version(dep.versionId());
                String mismatch = compatibilityProblem(depTitle, chosen);
                if (mismatch != null) {
                    problems.add(from.title() + " needs " + mismatch);
                    return;
                }
            } else {
                chosen = pickNewest(depTitle, projectId, from);
                if (chosen == null) return;
            }
            if (addPlanned(depTitle, chosen, false, from.title())) {
                queue.add(planned.get(projectId));
            }
        }

        /** Newest suitable version of a dependency, or null (after recording a problem). */
        private ProjectVersion pickNewest(String depTitle, String projectId, PlannedMod from) {
            List<ProjectVersion> candidates = provider.versions(projectId, instance.minecraftVersion(), loader);
            ProjectVersion firstPreRelease = null;
            for (ProjectVersion v : candidates) {
                if (compatibilityProblem(depTitle, v) != null) continue;
                if (isRelease(v) || allowPreRelease) return v;
                if (firstPreRelease == null) firstPreRelease = v;
            }
            String where = "Minecraft " + instance.minecraftVersion() + " with " + instance.loader();
            if (firstPreRelease != null) {
                problems.add(from.title() + " requires " + depTitle + ", but for " + where
                        + " there is only a " + firstPreRelease.versionType() + " version ("
                        + firstPreRelease.versionNumber() + "). Allow beta/alpha versions to install it.");
            } else {
                problems.add(from.title() + " requires " + depTitle + ", but there is no version of it for "
                        + where + ".");
            }
            return null;
        }

        private boolean addPlanned(String title, ProjectVersion version, boolean chosen, String neededBy) {
            VersionFile file = version.primaryFile();
            if (file == null) {
                problems.add(title + " " + version.versionNumber() + " has no file to download.");
                return false;
            }
            String fileName = FileNames.sanitize(file.fileName());
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                problems.add(title + " " + version.versionNumber() + " is not a .jar file (" + fileName
                        + "), so it is not installed as a mod.");
                return false;
            }
            if (Files.exists(modsDir.resolve(fileName))) {
                problems.add("A file named \"" + fileName + "\" already exists in this instance's mods folder "
                        + "and was not installed by this app. Move or rename it first.");
                return false;
            }
            if (!usedFileNames.add(fileName.toLowerCase(Locale.ROOT))) {
                problems.add("Two mods in this plan would use the same file name (" + fileName + ").");
                return false;
            }
            if (file.sha512() == null && file.sha1() == null) {
                warnings.add(title + " has no checksum, so the download cannot be verified.");
            }
            if (!isRelease(version)) {
                warnings.add(title + " " + version.versionNumber() + " is a " + version.versionType()
                        + " version and may be unstable.");
            }
            for (InstalledMod other : installed) {
                if (other.incompatibleWith().contains(version.projectId())) {
                    problems.add(other.title() + " is incompatible with " + title + ".");
                }
            }

            String projectId = version.projectId();
            titles.put(projectId, title);
            planned.put(projectId, new PlannedMod(projectId, title, version, file, fileName, chosen, neededBy));
            return true;
        }

        /** Null if the version fits this instance, otherwise a sentence that starts with the mod name. */
        private String compatibilityProblem(String title, ProjectVersion v) {
            String label = title + " " + v.versionNumber();
            if (!v.gameVersions().contains(instance.minecraftVersion())) {
                return label + " is for Minecraft " + String.join(", ", v.gameVersions())
                        + ", but this instance uses Minecraft " + instance.minecraftVersion() + ".";
            }
            boolean loaderOk = v.loaders().stream().anyMatch(l -> l.equalsIgnoreCase(loader));
            if (!loaderOk) {
                return label + " supports " + String.join(", ", v.loaders())
                        + ", but this instance uses " + instance.loader() + ".";
            }
            return null;
        }

        private String titleOf(String projectId) {
            String known = titles.get(projectId);
            if (known != null) return known;
            String title;
            try {
                title = provider.project(projectId).title();
            } catch (ProviderException e) {
                log.warn("Could not look up the name of project {}: {}", projectId, e.getMessage());
                title = projectId; // showing the id is better than failing
            }
            titles.put(projectId, title);
            return title;
        }
    }

    private static boolean isRelease(ProjectVersion v) {
        return "release".equalsIgnoreCase(v.versionType());
    }
}
