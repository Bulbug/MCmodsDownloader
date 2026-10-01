package app.api;

import app.api.ProjectVersion.Dependency;
import app.api.ProjectVersion.DependencyType;
import app.api.ProjectVersion.VersionFile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Modrinth API v2 (https://docs.modrinth.com). Only public, read-only endpoints are used,
 * so no login or token is needed.
 *
 * Rules from the Modrinth docs that this class follows:
 * - every request carries a User-Agent that identifies this app (plus optional contact info)
 * - the limit is 300 requests per minute per IP; answers are cached and the app only searches
 *   when the user asks, never on every keystroke
 */
public final class ModrinthProvider implements ContentProvider {

    public static final String BASE_URL = "https://api.modrinth.com/v2";
    static final String APP_NAME = "MinecraftManager/0.1.0";
    private static final Set<String> LOADERS = Set.of("fabric", "quilt", "forge", "neoforge");

    private static final Logger log = LoggerFactory.getLogger("Modrinth");

    private final HttpClient client;
    private final String baseUrl;
    private final Supplier<String> contact;
    private final Supplier<Duration> cacheTtl;
    private final ResponseCache cache;

    /** @param contact optional email or GitHub name, added to the User-Agent (recommended by Modrinth) */
    public ModrinthProvider(Supplier<String> contact, Supplier<Duration> cacheTtl) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)).build(),
                BASE_URL, contact, cacheTtl, new ResponseCache());
    }

    /** Test hook: other base URL (a local test server) and cache. */
    ModrinthProvider(HttpClient client, String baseUrl, Supplier<String> contact,
                     Supplier<Duration> cacheTtl, ResponseCache cache) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.contact = contact;
        this.cacheTtl = cacheTtl;
        this.cache = cache;
    }

    @Override
    public String name() {
        return "Modrinth";
    }

    // ---- search --------------------------------------------------------------------

    @Override
    public SearchPage search(SearchQuery q) {
        JsonArray facets = new JsonArray();
        facets.add(group("project_type:" + q.type().apiValue()));
        if (q.minecraftVersion() != null) facets.add(group("versions:" + validateGameVersion(q.minecraftVersion())));
        if (q.loader() != null && q.type().usesLoader()) facets.add(group("categories:" + validateLoader(q.loader())));

        String url = baseUrl + "/search"
                + "?query=" + enc(q.text())
                + "&facets=" + enc(facets.toString())
                + "&index=" + q.sort().apiValue()
                + "&offset=" + q.offset()
                + "&limit=" + q.limit();

        JsonObject root = parseObject(get(url));
        List<ProjectSummary> hits = new ArrayList<>();
        JsonElement hitsElement = root.get("hits");
        if (hitsElement != null && hitsElement.isJsonArray()) {
            for (JsonElement e : hitsElement.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    ProjectSummary summary = toSummary(e.getAsJsonObject(), q.type());
                    if (summary != null) hits.add(summary);
                }
            }
        }
        return new SearchPage(hits, (int) number(root, "offset"), (int) number(root, "limit"),
                (int) number(root, "total_hits"));
    }

    private static ProjectSummary toSummary(JsonObject o, ProjectType fallbackType) {
        String id = string(o, "project_id");
        String title = string(o, "title");
        if (id == null || title == null) return null; // unusable entry
        return new ProjectSummary(id, string(o, "slug"),
                ProjectType.fromApi(string(o, "project_type"), fallbackType),
                title, nullToEmpty(string(o, "description")), nullToEmpty(string(o, "author")),
                string(o, "icon_url"), number(o, "downloads"), number(o, "follows"),
                strings(o, "display_categories").isEmpty() ? strings(o, "categories") : strings(o, "display_categories"),
                strings(o, "versions"), string(o, "license"), string(o, "date_modified"));
    }

    // ---- versions ------------------------------------------------------------------

    @Override
    public List<ProjectVersion> versions(String projectId, String minecraftVersion, String loader) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9]{1,32}")) {
            throw new ProviderException("That project has an invalid identifier.");
        }
        StringBuilder url = new StringBuilder(baseUrl).append("/project/").append(projectId)
                .append("/version?include_changelog=false");
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            JsonArray arr = new JsonArray();
            arr.add(validateGameVersion(minecraftVersion.trim()));
            url.append("&game_versions=").append(enc(arr.toString()));
        }
        if (loader != null && !loader.isBlank()) {
            JsonArray arr = new JsonArray();
            arr.add(validateLoader(loader));
            url.append("&loaders=").append(enc(arr.toString()));
        }

        JsonElement root = parse(get(url.toString()));
        if (!root.isJsonArray()) throw unexpected(null);

        List<ProjectVersion> result = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (e.isJsonObject()) {
                ProjectVersion v = toVersion(e.getAsJsonObject());
                if (v != null) result.add(v);
            }
        }
        // ISO-8601 timestamps sort correctly as text.
        result.sort(Comparator.comparing((ProjectVersion v) -> nullToEmpty(v.datePublished())).reversed());
        return result;
    }

    @Override
    public ProjectVersion version(String versionId) {
        if (versionId == null || !versionId.matches("[A-Za-z0-9]{1,32}")) {
            throw new ProviderException("That version has an invalid identifier.");
        }
        ProjectVersion v = toVersion(parseObject(get(baseUrl + "/version/" + versionId)));
        if (v == null) throw unexpected(null);
        return v;
    }

    @Override
    public ProjectSummary project(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9]{1,32}")) {
            throw new ProviderException("That project has an invalid identifier.");
        }
        JsonObject o = parseObject(get(baseUrl + "/project/" + projectId));
        // A single project uses "id"; search results use "project_id". Accept either.
        String id = string(o, "id") != null ? string(o, "id") : string(o, "project_id");
        String title = string(o, "title");
        if (id == null || title == null) throw unexpected(null);
        return new ProjectSummary(id, string(o, "slug"),
                ProjectType.fromApi(string(o, "project_type"), ProjectType.MOD),
                title, nullToEmpty(string(o, "description")), "", string(o, "icon_url"),
                number(o, "downloads"), number(o, "followers"), strings(o, "categories"),
                List.of(), null, string(o, "updated"));
    }

    private static ProjectVersion toVersion(JsonObject o) {
        String id = string(o, "id");
        if (id == null) return null;

        List<Dependency> deps = new ArrayList<>();
        JsonElement depsElement = o.get("dependencies");
        if (depsElement != null && depsElement.isJsonArray()) {
            for (JsonElement d : depsElement.getAsJsonArray()) {
                if (!d.isJsonObject()) continue;
                JsonObject dep = d.getAsJsonObject();
                DependencyType type = DependencyType.fromApi(string(dep, "dependency_type"));
                if (type == null) continue; // unknown kind of dependency: ignore rather than guess
                deps.add(new Dependency(string(dep, "project_id"), string(dep, "version_id"),
                        string(dep, "file_name"), type));
            }
        }

        List<VersionFile> files = new ArrayList<>();
        JsonElement filesElement = o.get("files");
        if (filesElement != null && filesElement.isJsonArray()) {
            for (JsonElement f : filesElement.getAsJsonArray()) {
                if (!f.isJsonObject()) continue;
                JsonObject file = f.getAsJsonObject();
                String url = string(file, "url");
                String fileName = string(file, "filename");
                // Downloads must be https; anything else is dropped.
                if (url == null || fileName == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) continue;
                JsonObject hashes = file.get("hashes") != null && file.get("hashes").isJsonObject()
                        ? file.getAsJsonObject("hashes") : new JsonObject();
                files.add(new VersionFile(url, fileName,
                        file.has("primary") && !file.get("primary").isJsonNull() && file.get("primary").getAsBoolean(),
                        number(file, "size"), lower(string(hashes, "sha1")), lower(string(hashes, "sha512"))));
            }
        }

        return new ProjectVersion(id, string(o, "project_id"), nullToEmpty(string(o, "name")),
                nullToEmpty(string(o, "version_number")), nullToEmpty(string(o, "version_type")),
                strings(o, "game_versions"), strings(o, "loaders"), string(o, "date_published"),
                number(o, "downloads"), deps, files);
    }

    // ---- HTTP ----------------------------------------------------------------------

    private String get(String url) {
        String cached = cache.get(url);
        if (cached != null) return cached;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent(contact.get()))
                .header("Accept", "application/json")
                .GET().build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderException("Could not reach Modrinth. Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("The request was cancelled.", e);
        }

        int status = response.statusCode();
        log.info("GET {} -> {}", URI.create(url).getPath(), status);
        if (status == 200) {
            long ttl = cacheTtl.get().toNanos();
            cache.put(url, response.body(), ttl);
            return response.body();
        }
        throw switch (status) {
            case 429 -> new ProviderException("Modrinth is limiting requests right now"
                    + response.headers().firstValue("X-Ratelimit-Reset")
                    .map(s -> ". Try again in about " + s.trim() + " seconds.").orElse(". Try again in a minute."));
            case 404 -> new ProviderException("Modrinth could not find that.");
            case 410 -> new ProviderException("This version of the app uses a Modrinth API that was shut down. "
                    + "Please update the app.");
            default -> status >= 500
                    ? new ProviderException("Modrinth is having problems right now (HTTP " + status + "). Try again later.")
                    : new ProviderException("Modrinth refused the request (HTTP " + status + ").");
        };
    }

    /** "MinecraftManager/0.1.0 (contact)". The contact is reduced to plain printable characters. */
    static String userAgent(String contactInfo) {
        String clean = contactInfo == null ? "" : contactInfo.chars()
                .filter(c -> c >= 0x20 && c <= 0x7E && c != '(' && c != ')')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().trim();
        if (clean.length() > 100) clean = clean.substring(0, 100);
        return clean.isEmpty() ? APP_NAME : APP_NAME + " (" + clean + ")";
    }

    // ---- validation and JSON helpers -----------------------------------------------

    private static JsonArray group(String facet) {
        JsonArray g = new JsonArray();
        g.add(facet);
        return g;
    }

    static String validateGameVersion(String v) {
        if (!v.matches("[A-Za-z0-9._+\\-]{1,48}")) {
            throw new ProviderException("The Minecraft version \"" + v + "\" contains unsupported characters.");
        }
        return v;
    }

    static String validateLoader(String loader) {
        String l = loader.trim().toLowerCase(Locale.ROOT);
        if (!LOADERS.contains(l)) throw new ProviderException("Unknown mod loader: " + loader);
        return l;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonElement parse(String body) {
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw unexpected(e);
        }
    }

    private static JsonObject parseObject(String body) {
        JsonElement e = parse(body);
        if (!e.isJsonObject()) throw unexpected(null);
        return e.getAsJsonObject();
    }

    private static ProviderException unexpected(Throwable cause) {
        return new ProviderException("Modrinth sent an answer this app does not understand.", cause);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? null : e.getAsString();
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> result = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                if (item.isJsonPrimitive()) result.add(item.getAsString());
            }
        }
        return result;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
