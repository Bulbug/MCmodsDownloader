package app.api;

/**
 * What the user is looking for.
 *
 * @param text             search words (may be empty to browse)
 * @param minecraftVersion filter, or null for any version
 * @param loader           fabric / quilt / forge / neoforge, or null for any
 */
public record SearchQuery(String text, ProjectType type, String minecraftVersion, String loader,
                          SearchSort sort, int offset, int limit) {

    public static final int MAX_LIMIT = 100;

    public SearchQuery {
        text = text == null ? "" : text.trim();
        if (text.length() > 100) text = text.substring(0, 100);
        minecraftVersion = blankToNull(minecraftVersion);
        loader = blankToNull(loader);
        if (type == null) type = ProjectType.MOD;
        if (sort == null) sort = SearchSort.RELEVANCE;
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
