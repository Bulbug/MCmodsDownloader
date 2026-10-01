package app.api;

import app.api.ProjectVersion.DependencyType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the provider against a local fake server that answers like the Modrinth v2 API documents. */
class ModrinthProviderTest {

    private static final String SEARCH_JSON = """
            {"hits":[
              {"project_id":"AAAA1111","project_type":"mod","slug":"example-mod","author":"alice",
               "title":"Example Mod","description":"Does example things",
               "categories":["fabric","optimization"],"display_categories":["optimization"],
               "versions":["1.21.7","1.21.8"],"downloads":1234567,"follows":890,
               "icon_url":"https://cdn.modrinth.com/data/AAAA1111/icon.png",
               "date_modified":"2026-05-01T10:00:00Z","license":"MIT"},
              {"project_id":"BBBB2222","project_type":"mod","title":"Bare Minimum","description":null,
               "author":"bob","categories":[],"versions":[],"downloads":0,"follows":0,
               "icon_url":null,"slug":null},
              {"title":"Broken entry without id"}
            ],"offset":0,"limit":20,"total_hits":2}
            """;

    private static final String VERSIONS_JSON = """
            [
              {"id":"ver-old","project_id":"AAAA1111","name":"Old","version_number":"1.0.0","version_type":"beta",
               "game_versions":["1.21.7"],"loaders":["fabric"],"date_published":"2026-01-01T00:00:00Z",
               "downloads":5,"dependencies":[],"files":[]},
              {"id":"ver-new","project_id":"AAAA1111","name":"New","version_number":"2.0.0","version_type":"release",
               "game_versions":["1.21.8"],"loaders":["fabric"],"date_published":"2026-05-01T10:00:00Z","downloads":100,
               "dependencies":[
                 {"version_id":null,"project_id":"LIB00001","file_name":null,"dependency_type":"required"},
                 {"project_id":"OPT00001","dependency_type":"optional"},
                 {"project_id":"BAD00001","dependency_type":"incompatible"},
                 {"project_id":"WEIRD001","dependency_type":"something-new"}],
               "files":[
                 {"hashes":{"sha1":"%s","sha512":"%s"},"url":"https://cdn.modrinth.com/data/AAAA1111/versions/ver-new/extra.jar",
                  "filename":"extra.jar","primary":false,"size":10},
                 {"hashes":{"sha1":"%s","sha512":"%s"},"url":"https://cdn.modrinth.com/data/AAAA1111/versions/ver-new/main.jar",
                  "filename":"main.jar","primary":true,"size":1234},
                 {"hashes":{},"url":"http://evil.example.com/not-secure.jar","filename":"x.jar","primary":false,"size":1}
               ]}
            ]
            """.formatted("a".repeat(40), "b".repeat(128), "C".repeat(40), "D".repeat(128));

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastUserAgent;
    private volatile int forcedStatus = 200;
    private volatile String forcedBody;
    private volatile String forcedResetHeader;
    private final AtomicLong clock = new AtomicLong(0);
    private ModrinthProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v2", this::handle);
        server.start();

        provider = new ModrinthProvider(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v2",
                () -> "tester@example.com", () -> Duration.ofMinutes(10),
                new ResponseCache(50, clock::get));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        lastPath = ex.getRequestURI().getPath();
        lastQuery = ex.getRequestURI().getRawQuery();
        lastUserAgent = ex.getRequestHeaders().getFirst("User-Agent");

        String body = forcedBody != null ? forcedBody
                : lastPath.endsWith("/search") ? SEARCH_JSON : VERSIONS_JSON;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (forcedResetHeader != null) ex.getResponseHeaders().add("X-Ratelimit-Reset", forcedResetHeader);
        ex.sendResponseHeaders(forcedStatus, forcedStatus == 200 ? bytes.length : -1);
        if (forcedStatus == 200) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private String param(String name) {
        for (String pair : lastQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ---- search ----------------------------------------------------------------------

    @Test
    void searchSendsDocumentedParametersAndUserAgent() {
        provider.search(new SearchQuery("sodium", ProjectType.MOD, "1.21.8", "Fabric", SearchSort.DOWNLOADS, 40, 20));

        assertEquals("/v2/search", lastPath);
        assertEquals("sodium", param("query"));
        assertEquals("[[\"project_type:mod\"],[\"versions:1.21.8\"],[\"categories:fabric\"]]", param("facets"));
        assertEquals("downloads", param("index"));
        assertEquals("40", param("offset"));
        assertEquals("20", param("limit"));
        assertEquals("MinecraftManager/0.1.0 (tester@example.com)", lastUserAgent);
    }

    @Test
    void loaderFilterIsIgnoredForContentWithoutLoaders() {
        provider.search(new SearchQuery("", ProjectType.SHADER, null, "fabric", SearchSort.RELEVANCE, 0, 10));
        assertEquals("[[\"project_type:shader\"]]", param("facets"));
    }

    @Test
    void searchParsesResultsAndToleratesMissingFields() {
        SearchPage page = provider.search(new SearchQuery("", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20));

        assertEquals(2, page.totalHits());
        assertEquals(2, page.hits().size()); // entry without an id is skipped

        ProjectSummary full = page.hits().get(0);
        assertEquals("AAAA1111", full.id());
        assertEquals("example-mod", full.slug());
        assertEquals("Example Mod", full.title());
        assertEquals(1234567L, full.downloads());
        assertEquals(List.of("optimization"), full.categories());
        assertEquals(List.of("1.21.7", "1.21.8"), full.minecraftVersions());
        assertEquals("MIT", full.license());

        ProjectSummary bare = page.hits().get(1);
        assertNull(bare.slug());
        assertNull(bare.iconUrl());
        assertEquals("", bare.description());
    }

    @Test
    void unsafeFilterValuesAreRejectedBeforeAnyRequest() {
        assertThrows(ProviderException.class, () -> provider.search(
                new SearchQuery("x", ProjectType.MOD, "1.21\"],[\"project_type:mod", null, SearchSort.RELEVANCE, 0, 20)));
        assertThrows(ProviderException.class, () -> provider.search(
                new SearchQuery("x", ProjectType.MOD, null, "bukkit", SearchSort.RELEVANCE, 0, 20)));
        assertEquals(0, hits.get());
    }

    @Test
    void queryTextIsEncodedSafely() {
        provider.search(new SearchQuery("a&b=c \"quote\" ü", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20));
        assertEquals("a&b=c \"quote\" ü", param("query"));
    }

    // ---- versions --------------------------------------------------------------------

    @Test
    void versionsAreSortedNewestFirstAndParsed() {
        List<ProjectVersion> versions = provider.versions("AAAA1111", "1.21.8", "fabric");

        assertEquals("/v2/project/AAAA1111/version", lastPath);
        assertEquals("false", param("include_changelog"));
        assertEquals("[\"1.21.8\"]", param("game_versions"));
        assertEquals("[\"fabric\"]", param("loaders"));

        assertEquals(List.of("ver-new", "ver-old"), versions.stream().map(ProjectVersion::id).toList());
        ProjectVersion v = versions.get(0);
        assertEquals("release", v.versionType());
        assertEquals(List.of("fabric"), v.loaders());
    }

    @Test
    void dependenciesAreParsedAndUnknownKindsIgnored() {
        ProjectVersion v = provider.versions("AAAA1111", null, null).get(0);

        assertEquals(3, v.dependencies().size());
        assertEquals(1, v.count(DependencyType.REQUIRED));
        assertEquals(1, v.count(DependencyType.OPTIONAL));
        assertEquals(1, v.count(DependencyType.INCOMPATIBLE));
        assertEquals("LIB00001", v.dependencies().get(0).projectId());
        assertNull(v.dependencies().get(0).versionId());
    }

    @Test
    void filesNeedHttpsAndPrimaryIsChosen() {
        ProjectVersion v = provider.versions("AAAA1111", null, null).get(0);

        assertEquals(2, v.files().size()); // the http:// file was dropped
        ProjectVersion.VersionFile primary = v.primaryFile();
        assertEquals("main.jar", primary.fileName());
        assertEquals(1234L, primary.size());
        assertEquals("c".repeat(40), primary.sha1()); // normalised to lowercase
        assertEquals("d".repeat(128), primary.sha512());
    }

    @Test
    void versionWithoutFilesHasNoPrimaryFile() {
        ProjectVersion old = provider.versions("AAAA1111", null, null).get(1);
        assertNull(old.primaryFile());
    }

    @Test
    void badProjectIdsAreRejected() {
        assertThrows(ProviderException.class, () -> provider.versions("../../etc", null, null));
        assertThrows(ProviderException.class, () -> provider.versions("", null, null));
        assertThrows(ProviderException.class, () -> provider.versions(null, null, null));
        assertEquals(0, hits.get());
    }

    // ---- errors ----------------------------------------------------------------------

    @Test
    void rateLimitAnswerIsExplainedWithResetTime() {
        forcedStatus = 429;
        forcedResetHeader = "42";
        ProviderException e = assertThrows(ProviderException.class, () -> provider.search(
                new SearchQuery("x", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20)));
        assertTrue(e.getMessage().contains("limiting"));
        assertTrue(e.getMessage().contains("42"));
    }

    @Test
    void otherHttpErrorsGetFriendlyMessages() {
        forcedStatus = 404;
        assertTrue(assertThrows(ProviderException.class, () -> provider.versions("AAAA1111", null, null))
                .getMessage().contains("could not find"));
        forcedStatus = 503;
        assertTrue(assertThrows(ProviderException.class, () -> provider.versions("AAAA1111", null, null))
                .getMessage().contains("problems"));
        forcedStatus = 410;
        assertTrue(assertThrows(ProviderException.class, () -> provider.versions("AAAA1111", null, null))
                .getMessage().contains("update"));
    }

    @Test
    void malformedAnswerGivesAFriendlyError() {
        forcedBody = "<html>not json</html>";
        assertTrue(assertThrows(ProviderException.class, () -> provider.search(
                new SearchQuery("x", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20)))
                .getMessage().contains("does not understand"));

        forcedBody = "[1,2,3]"; // valid JSON but not the shape search returns
        assertThrows(ProviderException.class, () -> provider.search(
                new SearchQuery("x", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20)));
    }

    @Test
    void unreachableServerGivesAFriendlyError() {
        server.stop(0);
        assertTrue(assertThrows(ProviderException.class, () -> provider.versions("AAAA1111", null, null))
                .getMessage().contains("internet"));
    }

    // ---- cache -----------------------------------------------------------------------

    @Test
    void repeatedIdenticalRequestsAreServedFromTheCacheUntilItExpires() {
        SearchQuery q = new SearchQuery("sodium", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20);

        provider.search(q);
        provider.search(q);
        assertEquals(1, hits.get());

        provider.search(new SearchQuery("other", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20));
        assertEquals(2, hits.get());

        clock.addAndGet(Duration.ofMinutes(11).toNanos());
        provider.search(q);
        assertEquals(3, hits.get());
    }

    @Test
    void failuresAreNotCached() {
        forcedStatus = 503;
        SearchQuery q = new SearchQuery("x", ProjectType.MOD, null, null, SearchSort.RELEVANCE, 0, 20);
        assertThrows(ProviderException.class, () -> provider.search(q));
        forcedStatus = 200;
        assertEquals(2, provider.search(q).totalHits());
    }

    // ---- small helpers ---------------------------------------------------------------

    @Test
    void userAgentIsCleanedAndOptional() {
        assertEquals("MinecraftManager/0.1.0", ModrinthProvider.userAgent(null));
        assertEquals("MinecraftManager/0.1.0", ModrinthProvider.userAgent("   "));
        assertEquals("MinecraftManager/0.1.0 (me@example.com)", ModrinthProvider.userAgent("me@example.com"));
        // line breaks and brackets cannot be smuggled into the header
        assertEquals("MinecraftManager/0.1.0 (evilX-Header: 1)", ModrinthProvider.userAgent("evil\r\nX-Header: 1()"));
    }
}
