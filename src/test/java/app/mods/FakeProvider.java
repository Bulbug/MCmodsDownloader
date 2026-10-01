package app.mods;

import app.api.ContentProvider;
import app.api.ProjectSummary;
import app.api.ProjectType;
import app.api.ProjectVersion;
import app.api.ProjectVersion.Dependency;
import app.api.ProjectVersion.DependencyType;
import app.api.ProjectVersion.VersionFile;
import app.api.ProviderException;
import app.api.SearchPage;
import app.api.SearchQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory stand-in for Modrinth, so resolver tests need no network. */
final class FakeProvider implements ContentProvider {

    final Map<String, List<ProjectVersion>> versionsByProject = new HashMap<>();
    final Map<String, ProjectVersion> versionsById = new HashMap<>();
    final Map<String, String> titles = new HashMap<>();
    boolean failProjectLookups;

    /** Adds a version (newest should be added first). */
    ProjectVersion add(String projectId, String title, String versionId, String type,
                       List<String> gameVersions, List<String> loaders, List<Dependency> deps, VersionFile... files) {
        ProjectVersion v = new ProjectVersion(versionId, projectId, title, versionId + "-num", type,
                gameVersions, loaders, "2026-01-01T00:00:00Z", 0, deps, List.of(files));
        versionsByProject.computeIfAbsent(projectId, k -> new ArrayList<>()).add(v);
        versionsById.put(versionId, v);
        titles.put(projectId, title);
        return v;
    }

    static VersionFile jar(String name) {
        return new VersionFile("https://cdn.modrinth.com/data/x/" + name, name, true, 10, "a".repeat(40), "b".repeat(128));
    }

    static Dependency required(String projectId) {
        return new Dependency(projectId, null, null, DependencyType.REQUIRED);
    }

    static Dependency of(String projectId, DependencyType type) {
        return new Dependency(projectId, null, null, type);
    }

    @Override
    public String name() {
        return "Fake";
    }

    @Override
    public SearchPage search(SearchQuery query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ProjectVersion> versions(String projectId, String mc, String loader) {
        List<ProjectVersion> all = versionsByProject.getOrDefault(projectId, List.of());
        // Behave like the real API: filter by game version and loader when given.
        return all.stream()
                .filter(v -> mc == null || v.gameVersions().contains(mc))
                .filter(v -> loader == null || v.loaders().stream().anyMatch(l -> l.equalsIgnoreCase(loader)))
                .toList();
    }

    @Override
    public ProjectVersion version(String versionId) {
        ProjectVersion v = versionsById.get(versionId);
        if (v == null) throw new ProviderException("Modrinth could not find that.");
        return v;
    }

    @Override
    public ProjectSummary project(String projectId) {
        if (failProjectLookups || !titles.containsKey(projectId)) throw new ProviderException("Modrinth could not find that.");
        return new ProjectSummary(projectId, null, ProjectType.MOD, titles.get(projectId), "", "", null, 0, 0,
                List.of(), List.of(), null, null);
    }
}
