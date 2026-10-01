package app.api;

import java.util.List;

/** One search result. Optional fields can be null or empty; the API does not always provide them. */
public record ProjectSummary(String id, String slug, ProjectType type, String title, String description,
                             String author, String iconUrl, long downloads, long follows,
                             List<String> categories, List<String> minecraftVersions,
                             String license, String dateModified) { }
