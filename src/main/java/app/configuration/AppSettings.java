package app.configuration;

/** Persisted user settings. Plain mutable class so Gson can (de)serialize it. */
public final class AppSettings {

    public String theme = "dark";
    public String language = "en";
    public int maxConcurrentDownloads = 3;
    /** Null means "use the default instances folder". */
    public String instanceDirectory = null;
    /** Null means "auto-detect the default .minecraft folder". */
    public String minecraftDirectory = null;
    /** How long search results are kept before asking Modrinth again. */
    public int cacheMinutes = 10;
    /** Optional email or GitHub name added to the User-Agent so Modrinth can contact the app's author. */
    public String apiContact = null;

    /** Clamps values that could have been edited by hand into a sane range. */
    void normalize() {
        if (maxConcurrentDownloads < 1) maxConcurrentDownloads = 1;
        if (maxConcurrentDownloads > 10) maxConcurrentDownloads = 10;
        if (theme == null || theme.isBlank()) theme = "dark";
        if (language == null || language.isBlank()) language = "en";
        if (instanceDirectory != null && instanceDirectory.isBlank()) instanceDirectory = null;
        if (minecraftDirectory != null && minecraftDirectory.isBlank()) minecraftDirectory = null;
        if (cacheMinutes < 1) cacheMinutes = 1;
        if (cacheMinutes > 1440) cacheMinutes = 1440;
        if (apiContact != null && apiContact.isBlank()) apiContact = null;
    }
}
