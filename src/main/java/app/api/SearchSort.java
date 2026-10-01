package app.api;

/** Sort orders supported by Modrinth's search ("index" parameter). */
public enum SearchSort {
    RELEVANCE("Relevance", "relevance"),
    DOWNLOADS("Downloads", "downloads"),
    FOLLOWS("Followers", "follows"),
    NEWEST("Newest", "newest"),
    UPDATED("Recently updated", "updated");

    private final String label;
    private final String apiValue;

    SearchSort(String label, String apiValue) {
        this.label = label;
        this.apiValue = apiValue;
    }

    public String label() {
        return label;
    }

    public String apiValue() {
        return apiValue;
    }
}
