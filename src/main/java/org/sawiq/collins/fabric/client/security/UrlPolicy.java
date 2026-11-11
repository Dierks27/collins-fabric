package org.sawiq.collins.fabric.client.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Which hosts the client agrees to fetch a video from.
 *
 * <p>The player's own game downloads a screen's video, straight from the
 * host in the address the server sent, as soon as they come near a
 * playing screen. With "Only play YouTube and Twitch" on, an address is
 * loaded only when its host is a listed host or a subdomain of one.</p>
 *
 * <p>The matching rules are the same as the server plugin's
 * ({@code collins-paper}, {@code org.sawiq.collins.paper.util.UrlPolicy}),
 * so client and server never disagree about an address. No Minecraft
 * types here, so the rules can be unit-tested on their own.</p>
 */
public final class UrlPolicy {

    /** The list shipped as the default, identical to the server plugin's. */
    public static final List<String> DEFAULT_HOSTS = List.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
            "youtube-nocookie.com", "twitch.tv", "www.twitch.tv", "m.twitch.tv");

    private UrlPolicy() {
    }

    /**
     * True when the address's host is a listed host or a subdomain of one.
     * Only the host part counts: a listed name elsewhere in the address
     * (path, query, fragment, the part before an {@code @}) matches nothing.
     */
    public static boolean hostAllowed(String url, Collection<String> allowedHosts) {
        String host = hostOf(url);
        if (host == null || allowedHosts == null) return false;
        for (String listed : allowedHosts) {
            String allowed = normalizeHost(listed);
            if (allowed == null) continue;
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /**
     * May the client load this address? Always true with the restriction
     * off. A blank address is never loaded (there is nothing to fetch), so
     * it is not "refused" either: callers treat it as "no address".
     */
    public static boolean mayLoad(String url, boolean restricted, Collection<String> allowedHosts) {
        if (url == null || url.isBlank()) return true;
        if (!restricted) return true;
        return hostAllowed(url, allowedHosts);
    }

    /** The lower-cased host of an http(s) address without a trailing dot, or null. */
    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A host as the player would recognise it, for the "blocked" notice:
     * the parsed host when there is one, otherwise a short, printable
     * excerpt of the address.
     */
    public static String displayHost(String url) {
        String host = hostOf(url);
        if (host != null) return host;
        if (url == null) return "?";
        String s = url.trim().replaceAll("\\p{Cntrl}", "");
        return s.length() > 64 ? s.substring(0, 64) + "..." : s;
    }

    /** Lower-case, trimmed, trailing dots removed; null for an empty entry. */
    public static String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    /**
     * A list entry the player typed, as the bare host name the matcher
     * needs, or null when it cannot be one. An address ("https://vk.com/",
     * "vk.com/video") becomes its host, and a leading "*." or "." is dropped
     * (an entry always allows its subdomains). Only cleans up the list: the
     * matching rules in {@link #hostAllowed} stay the server's.
     */
    public static String hostEntry(String entry) {
        String h = normalizeHost(entry);
        if (h == null || h.indexOf('@') >= 0) return null;
        if (h.startsWith("*.")) h = h.substring(2);
        while (h.startsWith(".")) h = h.substring(1);
        if (!h.contains("://")) h = "https://" + h;
        h = hostOf(h);
        if (h == null || !h.matches("[a-z0-9._-]+")) return null;
        return h;
    }

    /**
     * Cleaned, de-duplicated copy of a host list, dropping entries that are
     * empty or cannot be read as a host name (see {@link #hostEntry}).
     */
    public static List<String> normalizeHosts(Collection<String> hosts) {
        List<String> out = new ArrayList<>();
        if (hosts == null) return out;
        for (String h : hosts) {
            String n = hostEntry(h);
            if (n != null && !out.contains(n)) out.add(n);
        }
        return out;
    }
}
