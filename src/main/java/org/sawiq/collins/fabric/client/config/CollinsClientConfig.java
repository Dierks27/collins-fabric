package org.sawiq.collins.fabric.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.sawiq.collins.fabric.client.security.UrlPolicy;
import org.sawiq.collins.fabric.client.video.YouTubeQuality;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CollinsClientConfig {

    public int localVolumePercent = 100;
    public boolean renderVideo = true;
    public boolean actionbarTimeline = true;
    public int youtubeMaxQuality = YouTubeQuality.DEFAULT;
    // Off by default: this fork is private and never published on Modrinth.
    public boolean checkModrinthUpdates = false;

    /**
     * "Only play YouTube and Twitch". Off by default, so videos an admin put
     * on a screen (any site) play for everyone. When a player turns it on,
     * the mod refuses to load a video from any host not in
     * {@link #allowedHosts}, whatever the server sends.
     */
    public boolean onlyAllowedSites = false;
    /** Hosts (and their subdomains) that may be loaded while {@link #onlyAllowedSites} is on. */
    public List<String> allowedHosts = new ArrayList<>(UrlPolicy.DEFAULT_HOSTS);

    /**
     * Most disk space, in GB, that downloaded videos may use in
     * {@code collins-cache/} (all platforms together). Oldest files are
     * deleted first when a new download would go over it; a single video
     * larger than this is not downloaded at all.
     */
    public int cacheLimitGb = DEFAULT_CACHE_LIMIT_GB;

    public static final int DEFAULT_CACHE_LIMIT_GB = 16;
    public static final int MIN_CACHE_LIMIT_GB = 1;
    public static final int MAX_CACHE_LIMIT_GB = 256;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "collins.json";

    private static volatile CollinsClientConfig INSTANCE;

    private CollinsClientConfig() {
    }

    public static CollinsClientConfig get() {
        CollinsClientConfig cfg = INSTANCE;
        if (cfg == null) {
            cfg = load();
            INSTANCE = cfg;
        }
        return cfg;
    }

    public static CollinsClientConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.exists(path)) {
                try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    CollinsClientConfig cfg = GSON.fromJson(r, CollinsClientConfig.class);
                    if (cfg != null) {
                        sanitize(cfg);
                        return cfg;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        CollinsClientConfig cfg = new CollinsClientConfig();
        sanitize(cfg);
        save(cfg);
        return cfg;
    }

    public static void save(CollinsClientConfig cfg) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        CollinsClientConfig cfg = get();
        sanitize(cfg);
        save(cfg);
    }

    public static void reload() {
        INSTANCE = load();
    }

    public float localVolumeMultiplier() {
        return Math.max(0f, Math.min(1f, localVolumePercent / 100f));
    }

    public long cacheLimitBytes() {
        int gb = Math.max(MIN_CACHE_LIMIT_GB, Math.min(MAX_CACHE_LIMIT_GB, cacheLimitGb));
        return gb * 1024L * 1024L * 1024L;
    }

    /**
     * The one check every address from the server goes through before the
     * client fetches, resolves or prefetches anything for it.
     */
    public static boolean mayLoad(String url) {
        CollinsClientConfig cfg = get();
        return UrlPolicy.mayLoad(url, cfg.onlyAllowedSites, cfg.allowedHosts);
    }

    private static void sanitize(CollinsClientConfig cfg) {
        if (cfg.localVolumePercent < 0) cfg.localVolumePercent = 0;
        if (cfg.localVolumePercent > 100) cfg.localVolumePercent = 100;
        cfg.youtubeMaxQuality = YouTubeQuality.sanitize(cfg.youtubeMaxQuality, YouTubeQuality.DEFAULT);
        // A config written by an older mod has no list: start from the defaults.
        cfg.allowedHosts = cfg.allowedHosts == null
                ? new ArrayList<>(UrlPolicy.DEFAULT_HOSTS)
                : new ArrayList<>(UrlPolicy.normalizeHosts(cfg.allowedHosts));
        if (cfg.cacheLimitGb < MIN_CACHE_LIMIT_GB) cfg.cacheLimitGb = MIN_CACHE_LIMIT_GB;
        if (cfg.cacheLimitGb > MAX_CACHE_LIMIT_GB) cfg.cacheLimitGb = MAX_CACHE_LIMIT_GB;
    }
}
