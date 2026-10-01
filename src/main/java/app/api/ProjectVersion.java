package app.api;

import java.util.List;

/** One downloadable release of a project. */
public record ProjectVersion(String id, String projectId, String name, String versionNumber,
                             String versionType, List<String> gameVersions, List<String> loaders,
                             String datePublished, long downloads,
                             List<Dependency> dependencies, List<VersionFile> files) {

    public enum DependencyType {
        REQUIRED, OPTIONAL, INCOMPATIBLE, EMBEDDED;

        static DependencyType fromApi(String value) {
            if (value == null) return null;
            for (DependencyType t : values()) {
                if (t.name().equalsIgnoreCase(value)) return t;
            }
            return null;
        }
    }

    /** projectId and versionId may each be null (a dependency can point at either, or at a file name). */
    public record Dependency(String projectId, String versionId, String fileName, DependencyType type) { }

    /** sha1/sha512 are lowercase hex or null. */
    public record VersionFile(String url, String fileName, boolean primary, long size,
                              String sha1, String sha512) { }

    /** The file to install: the one marked primary, otherwise the first. Null if the version has no files. */
    public VersionFile primaryFile() {
        for (VersionFile f : files) if (f.primary()) return f;
        return files.isEmpty() ? null : files.get(0);
    }

    public long count(DependencyType type) {
        return dependencies.stream().filter(d -> d.type() == type).count();
    }
}
