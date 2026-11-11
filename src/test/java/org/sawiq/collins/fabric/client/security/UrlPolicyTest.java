package org.sawiq.collins.fabric.client.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of collins-paper's UrlPolicyTest, so client and server agree on
 * every address, plus the client-only "restriction off" and display cases.
 */
class UrlPolicyTest {

    private static final List<String> DEFAULTS = UrlPolicy.DEFAULT_HOSTS;

    @Test
    void defaultListMatchesTheServerPlugin() {
        assertEquals(List.of(
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
                "youtube-nocookie.com", "twitch.tv", "www.twitch.tv", "m.twitch.tv"), DEFAULTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://youtube.com/watch?v=dQw4w9WgXcQ",          // exact
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",      // listed subdomain
            "https://gaming.youtube.com/x",                     // unlisted subdomain of a listed domain
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/x",
            "https://www.twitch.tv/somechannel",
            "https://twitch.tv/somechannel",
            "http://m.twitch.tv/somechannel",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=x",                // upper case
            "https://YouTube.Com/watch?v=x",
            "https://www.youtube.com./watch?v=x",               // trailing dot
            "https://www.youtube.com:443/watch?v=x",            // port
            "  https://youtu.be/x  ",                           // surrounding spaces
    })
    void allowed(String url) {
        assertTrue(UrlPolicy.hostAllowed(url, DEFAULTS), url);
        assertTrue(UrlPolicy.mayLoad(url, true, DEFAULTS), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/video.mp4",                    // direct file
            "https://rutube.ru/video/abc/",
            "https://vk.com/video-1_2",
            "https://vkvideo.ru/video-1_2",
            "https://youtube.com.evil.example/x",               // look-alike suffix
            "https://evil.example/?youtube.com",                // name in the query
            "https://evil.example/youtube.com/watch",           // name in the path
            "https://evil.example/#youtube.com",
            "https://notyoutube.com/x",                         // "contains", no dot boundary
            "https://eviltwitch.tv/x",
            "https://youtube.com@evil.example/",                // @ trick
            "https://youtube.com:pw@evil.example/",
            "https://evil.example\\@youtube.com/",              // backslash trick (not a valid URI)
            "https://xn--yutube-wqf.com/x",                     // punycode look-alike
            "https://\u0443outube.com/x",                       // Cyrillic "у" (IDN homograph)
            "https://youtube.co/x",
            "https://tv/x",
            "ftp://youtube.com/x",                              // not http(s)
            "file:///C:/youtube.com/x.mp4",
            "javascript:alert('youtube.com')",
            "youtube.com/watch?v=x",                            // no scheme
            "//youtube.com/x",
            "https:///youtube.com",                             // empty host
            "",
    })
    void refused(String url) {
        assertFalse(UrlPolicy.hostAllowed(url, DEFAULTS), url);
        if (!url.isBlank()) {
            assertFalse(UrlPolicy.mayLoad(url, true, DEFAULTS), url);
        }
    }

    @Test
    void blankAddressIsNotAHostAndIsNeverRefused() {
        // A 1.7.0+ server sends "" for a screen it blocked: that is "no
        // address" (idle screen), not something to warn the player about.
        assertFalse(UrlPolicy.hostAllowed("", DEFAULTS));
        assertTrue(UrlPolicy.mayLoad("", true, DEFAULTS));
        assertTrue(UrlPolicy.mayLoad("   ", true, DEFAULTS));
        assertTrue(UrlPolicy.mayLoad(null, true, DEFAULTS));
    }

    @Test
    void restrictionOffBehavesAsBefore() {
        assertTrue(UrlPolicy.mayLoad("https://example.com/video.mp4", false, DEFAULTS));
        assertTrue(UrlPolicy.mayLoad("https://rutube.ru/video/abc/", false, DEFAULTS));
        assertTrue(UrlPolicy.mayLoad("https://youtube.com.evil.example/x", false, DEFAULTS));
    }

    @Test
    void listEntriesAreNormalisedAndAnEmptyListAllowsNothing() {
        List<String> sloppy = List.of(" YouTube.COM. ", "");
        assertTrue(UrlPolicy.hostAllowed("https://www.youtube.com/x", sloppy));
        assertFalse(UrlPolicy.hostAllowed("https://twitch.tv/x", sloppy));

        assertFalse(UrlPolicy.mayLoad("https://www.youtube.com/x", true, List.of()));
        assertFalse(UrlPolicy.mayLoad("https://www.youtube.com/x", true, null));
    }

    @Test
    void editedListAddsAHostAndItsSubdomainsOnly() {
        List<String> withExample = List.of("youtube.com", "example.com");
        assertTrue(UrlPolicy.mayLoad("https://cdn.example.com/video.mp4", true, withExample));
        assertFalse(UrlPolicy.mayLoad("https://example.com.evil.example/video.mp4", true, withExample));
    }

    @Test
    void normalizeHostsDropsEmptiesAndDuplicates() {
        assertEquals(List.of("youtube.com", "twitch.tv"),
                UrlPolicy.normalizeHosts(List.of("YouTube.com.", " youtube.com", "", "  ", "twitch.tv")));
        assertEquals(List.of(), UrlPolicy.normalizeHosts(null));
    }

    @Test
    void hostOfAndDisplayHost() {
        assertEquals("evil.example", UrlPolicy.hostOf("https://youtube.com@evil.example/"));
        assertEquals("www.youtube.com", UrlPolicy.hostOf("https://WWW.YouTube.com.:443/x"));
        assertNull(UrlPolicy.hostOf("ftp://youtube.com/x"));
        assertNull(UrlPolicy.hostOf(null));

        assertEquals("cdn.example.com", UrlPolicy.displayHost("https://cdn.example.com/a.mp4"));
        assertEquals("youtube.com/watch", UrlPolicy.displayHost("youtube.com/watch"));
        assertEquals("ab", UrlPolicy.displayHost("a\u0007b"));
        assertEquals("?", UrlPolicy.displayHost(null));
        assertTrue(UrlPolicy.displayHost("x".repeat(500)).length() <= 67);
    }
}
