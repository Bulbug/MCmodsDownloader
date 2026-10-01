package app.api;

import java.util.List;

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
}
