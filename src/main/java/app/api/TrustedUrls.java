package app.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** URL rules for things that come from web content. */
public final class TrustedUrls {

    private TrustedUrls() { }

    /** Icons are only loaded from Modrinth's own servers over https (no file:, http:, or other hosts). */
    public static boolean isTrustedImage(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("modrinth.com") || host.toLowerCase(Locale.ROOT).endsWith(".modrinth.com"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Link to the project's page on modrinth.com, or null if it cannot be built. */
    public static URI modrinthPage(ProjectType type, String slugOrId) {
        if (slugOrId == null || slugOrId.isBlank()) return null;
        try {
            // The multi-argument constructor percent-encodes anything unsafe in the path.
            return new URI("https", "modrinth.com", "/" + type.apiValue() + "/" + slugOrId.trim(), null);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
