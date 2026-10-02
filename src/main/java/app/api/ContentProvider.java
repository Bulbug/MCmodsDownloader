package app.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A place content can be searched and downloaded from (Modrinth today; others can be added later).
 * Methods do network work, so call them from a background thread.
 */
public interface ContentProvider {

    String name();

    SearchPage search(SearchQuery query) throws ProviderException;

    /**
     * Versions of one project, newest first.
     *
     * @param minecraftVersion filter or null
     * @param loader           filter (fabric, forge, ...) or null
     */
    List<ProjectVersion> versions(String projectId, String minecraftVersion, String loader) throws ProviderException;

    /** One specific version by its id (used when a dependency names an exact version). */
    ProjectVersion version(String versionId) throws ProviderException;

    /** Basic information about a project, mainly to show its name. Takes a project id. */
    ProjectSummary project(String projectId) throws ProviderException;

    /**
     * For files already on disk: the newest version of each file's project that fits the given
     * Minecraft version and loader. Files the platform does not know are simply missing from the result.
     *
     * @param sha512Hashes lowercase hex SHA-512 hashes of the files
     * @param releaseOnly  true to ignore beta and alpha versions
     * @return map from each hash you sent to the newest matching version
     */
    Map<String, ProjectVersion> latestVersionsForHashes(Collection<String> sha512Hashes, String minecraftVersion,
                                                         String loader, boolean releaseOnly) throws ProviderException;
}
