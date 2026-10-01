package app.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ApiModelsTest {

    @Test
    void searchQueryClampsAndCleansValues() {
        SearchQuery q = new SearchQuery("  hi  ", null, "  ", "", null, -5, 5000);
        assertEquals("hi", q.text());
        assertEquals(ProjectType.MOD, q.type());
        assertNull(q.minecraftVersion());
        assertNull(q.loader());
        assertEquals(SearchSort.RELEVANCE, q.sort());
        assertEquals(0, q.offset());
        assertEquals(100, q.limit());
        assertEquals(100, new SearchQuery("x".repeat(500), ProjectType.MOD, null, null, null, 0, 1).text().length());
    }

    @Test
    void projectTypeMapping() {
        assertEquals("resourcepack", ProjectType.RESOURCE_PACK.apiValue());
        assertEquals(ProjectType.SHADER, ProjectType.fromApi("SHADER", ProjectType.MOD));
        assertEquals(ProjectType.MOD, ProjectType.fromApi("plugin", ProjectType.MOD));
        assertEquals(ProjectType.MOD, ProjectType.fromApi(null, ProjectType.MOD));
        assertTrue(ProjectType.MOD.usesLoader());
        assertFalse(ProjectType.SHADER.usesLoader());
    }

    @Test
    void onlyModrinthHttpsImagesAreTrusted() {
        assertTrue(TrustedUrls.isTrustedImage("https://cdn.modrinth.com/data/AAAA1111/icon.png"));
        assertTrue(TrustedUrls.isTrustedImage("https://modrinth.com/a.png"));
        assertFalse(TrustedUrls.isTrustedImage("http://cdn.modrinth.com/a.png"));
        assertFalse(TrustedUrls.isTrustedImage("file:///C:/Windows/win.ini"));
        assertFalse(TrustedUrls.isTrustedImage("https://evilmodrinth.com/a.png"));
        assertFalse(TrustedUrls.isTrustedImage("https://modrinth.com.evil.example/a.png"));
        assertFalse(TrustedUrls.isTrustedImage("not a url"));
        assertFalse(TrustedUrls.isTrustedImage(null));
    }

    @Test
    void projectPageLinks() {
        assertEquals(URI.create("https://modrinth.com/mod/sodium"), TrustedUrls.modrinthPage(ProjectType.MOD, "sodium"));
        assertEquals("modrinth.com", TrustedUrls.modrinthPage(ProjectType.SHADER, "a b/c").getHost());
        assertNull(TrustedUrls.modrinthPage(ProjectType.MOD, " "));
        assertNull(TrustedUrls.modrinthPage(ProjectType.MOD, null));
    }

    @Test
    void cacheExpiresAndEvictsOldestEntries() {
        AtomicLong now = new AtomicLong(0);
        ResponseCache cache = new ResponseCache(2, now::get);

        cache.put("a", "1", 100);
        cache.put("b", "2", 100);
        cache.put("c", "3", 100); // evicts "a"
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));

        now.set(100);
        assertNull(cache.get("b"));

        cache.put("zero", "x", 0); // a TTL of zero means do not cache
        assertNull(cache.get("zero"));
    }
}
