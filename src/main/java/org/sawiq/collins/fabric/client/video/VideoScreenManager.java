package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.security.UrlPolicy;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoScreenManager {

    private static final boolean DEBUG = false;

    private VideoScreenManager() {}

    private static final Map<String, VideoScreen> SCREENS = new ConcurrentHashMap<>();
    private static final Set<String> SHOWN_DELETE_PROMPT = ConcurrentHashMap.newKeySet();
    private static volatile String pendingDeletePath = null;

    // Screens whose address "Only play YouTube and Twitch" refused, by screen
    // key, holding the state exactly as the server sent it. The VideoScreen
    // itself only ever sees an empty, stopped copy (see gate()).
    private static final Map<String, ScreenState> BLOCKED = new ConcurrentHashMap<>();
    // Screen key -> refused address the player has already been told about,
    // so the notice is shown once per screen and address, not once per sync.
    private static final Map<String, String> BLOCKED_NOTIFIED = new ConcurrentHashMap<>();

    private static final int GREEN = 0x00FF00;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final  Component PREFIX = Component.translatable("text.collins.prefix").setStyle(Style.EMPTY.withColor(GREEN));

    private static volatile long lastActionbarUpdateMs = 0;
    private static volatile String lastClientWorldKey = "";

    static String currentWorldKey(Minecraft client) {
        if (client == null) return "";
        try {
            if (client.level != null && client.level.dimension() != null) {
                String k = client.level.dimension().identifier().toString();
                return (k == null) ? "" : k;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String defaultBukkitWorldNameForDim(String dimKey) {
        if (dimKey == null || dimKey.isBlank()) return null;
        String k = dimKey.toLowerCase(Locale.ROOT);
        if (k.equals("minecraft:overworld")) return "world";
        if (k.equals("minecraft:the_nether")) return "world_nether";
        if (k.equals("minecraft:the_end")) return "world_the_end";
        return null;
    }

    private static boolean isDefaultBukkitWorldName(String w) {
        if (w == null) return false;
        String s = w.toLowerCase(Locale.ROOT);
        return s.equals("world") || s.equals("world_nether") || s.equals("world_the_end");
    }

    static boolean isCompatibleWithCurrentWorld(ScreenState st, Minecraft client) {
        if (st == null || client == null) return true;
        String sw = st.world();
        if (sw == null || sw.isBlank()) return true;

        String dimKey = currentWorldKey(client);
        if (sw.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            return sw.equalsIgnoreCase(dimKey);
        }

        if (isDefaultBukkitWorldName(sw)) {
            String expected = defaultBukkitWorldNameForDim(dimKey);
            return expected != null && sw.equalsIgnoreCase(expected);
        }

        return true;
    }

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static VideoScreen getByName(String name) {
        if (name == null) return null;
        return SCREENS.get(name.toLowerCase(Locale.ROOT));
    }

    public static VideoScreen findNearestPlaying(Vec3 playerPos) {
        return findNearestPlayingInternal(playerPos, false);
    }

    public static VideoScreen findNearestPlayingOrEnded(Vec3 playerPos) {
        return findNearestPlayingInternal(playerPos, true);
    }

    private static VideoScreen findNearestPlayingInternal(Vec3 playerPos, boolean includeEnded) {
        if (playerPos == null) return null;

        Minecraft client = Minecraft.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            boolean shouldInclude = st.playing() || (includeEnded && s.hasEnded());
            if (!shouldInclude) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    private static VideoScreen findNearestPlayingInRadius(Vec3 playerPos, int radiusBlocks) {
        if (playerPos == null) return null;
        if (radiusBlocks <= 0) return findNearestPlaying(playerPos);

        Minecraft client = Minecraft.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        double r = (double) radiusBlocks;
        double r2 = r * r;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!st.playing() && !s.isEnded()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;

            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    public static void applySync(Map<String, ScreenState> incoming) {
        Set<String> keep = new HashSet<>(incoming.keySet());

        for (String key : new ArrayList<>(SCREENS.keySet())) {
            if (!keep.contains(key)) {
                VideoScreen vs = SCREENS.remove(key);
                if (vs != null) {
                    if (DEBUG) System.out.println("[Collins] STOP by remove: key=" + key);
                    vs.stop();
                    // Drop any "delete cached file?" prompts that were
                    // associated with this screen, otherwise SHOWN_DELETE_PROMPT
                    // accumulates {name + "_" + url} forever - one entry per
                    // distinct URL ever played on the screen.
                    String namePrefix = vs.state() != null ? vs.state().name() + "_" : null;
                    if (namePrefix != null) {
                        SHOWN_DELETE_PROMPT.removeIf(k -> k.startsWith(namePrefix));
                    }
                }
                BLOCKED.remove(key);
                BLOCKED_NOTIFIED.remove(key);
            }
        }

        for (var e : incoming.entrySet()) {
            String key = e.getKey();
            ScreenState st = gate(key, e.getValue());

            VideoScreen vs = SCREENS.get(key);
            if (vs == null) {
                vs = new VideoScreen(st);
                SCREENS.put(key, vs);
                if (DEBUG) System.out.println("[Collins] screen created: key=" + key + " name=" + st.name());
            } else {
                vs.updateState(st);
            }

            if (!st.playing() || st.url() == null || st.url().isEmpty()) {
                if (DEBUG) {
                    System.out.println("[Collins] STOP by sync: name=" + st.name() + " playing=" + st.playing() + " url=" + st.url());
                }
                vs.stop();
            }
        }
    }

    /**
     * Re-runs the last sync from the server through the site check, so a
     * change to "Only play YouTube and Twitch" or the host list takes effect
     * at once instead of at the next sync. Call on the client thread.
     */
    public static void reapplySync() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) return;
        // Empty after a disconnect/server switch: CollinsNet.SCREENS may still
        // hold the previous server's screens, which must not come back.
        if (SCREENS.isEmpty()) return;
        applySync(new HashMap<>(CollinsNet.SCREENS));
    }

    /**
     * The state a VideoScreen gets. An address the player's settings refuse
     * is replaced by an empty, stopped one - exactly what a 1.7.0+ server
     * sends for an address it will not play - so nothing is resolved,
     * downloaded or shown. The server's state is kept in BLOCKED for the
     * notice; the incoming map itself is never changed.
     */
    private static ScreenState gate(String key, ScreenState st) {
        if (st == null || st.url() == null || st.url().isBlank() || CollinsClientConfig.mayLoad(st.url())) {
            BLOCKED.remove(key);
            return st;
        }
        BLOCKED.put(key, st);
        return new ScreenState(
                st.name(), st.world(),
                st.x1(), st.y1(), st.z1(),
                st.x2(), st.y2(), st.z2(),
                st.axis(),
                "",
                false,
                st.loop(),
                st.volume(),
                st.youtubeQuality(),
                st.startEpochMs(),
                st.basePosMs()
        );
    }

    // Tells the player, once per screen and address, that a screen near them
    // would have started a video from a host their settings refuse. Only when
    // the mod would otherwise have started downloading: the server says the
    // screen is playing, it is in this world and within the hear radius.
    private static void notifyBlocked(Minecraft client, Player player, Vec3 pos, int radius) {
        if (BLOCKED.isEmpty()) return;
        if (!CollinsClientConfig.get().renderVideo) return;
        for (var e : BLOCKED.entrySet()) {
            ScreenState st = e.getValue();
            if (!st.playing()) continue;
            if (st.url().equals(BLOCKED_NOTIFIED.get(e.getKey()))) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!isInRadius(st, pos, radius)) continue;

            BLOCKED_NOTIFIED.put(e.getKey(), st.url());
            player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.sites.blocked", UrlPolicy.displayHost(st.url()))
                    .setStyle(Style.EMPTY.withColor(YELLOW))));
        }
    }

    // Addresses already reported as over the cache limit, with the limit then.
    private static final Map<String, Long> TOO_LARGE_NOTIFIED = new ConcurrentHashMap<>();

    /**
     * Tells the player, once per address and limit, that a video was not
     * downloaded because it is bigger than their video cache limit. Safe to
     * call from any thread.
     */
    public static void notifyOverCacheLimit(String url, long limitBytes) {
        if (url == null) return;
        Long previous = TOO_LARGE_NOTIFIED.put(url, limitBytes);
        if (previous != null && previous == limitBytes) return;
        if (TOO_LARGE_NOTIFIED.size() > 256) TOO_LARGE_NOTIFIED.clear();
        long gb = limitBytes / (1024L * 1024L * 1024L);
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            Player player = client.player;
            if (player == null) return;
            player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.cache.too_large", gb)
                    .setStyle(Style.EMPTY.withColor(YELLOW))));
        });
    }

    // Same centre and radius maths as VideoScreen.isInHearRadius.
    private static boolean isInRadius(ScreenState st, Vec3 pos, int radiusBlocks) {
        if (pos == null) return false;
        if (radiusBlocks <= 0) return true;

        double cx = (st.minX() + st.maxX() + 1) * 0.5;
        double cy = (st.minY() + st.maxY() + 1) * 0.5;
        double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

        double dx = pos.x - cx;
        double dy = pos.y - cy;
        double dz = pos.z - cz;

        double r = (double) radiusBlocks;
        return (dx * dx + dy * dy + dz * dz) <= (r * r);
    }

    private static MutableComponent buildActionBarText(VideoScreen nearest, long serverNowMs) {
        int pct = Math.max(0, nearest.getDownloadPercent());
        long dlMb = Math.max(0L, nearest.getDownloadedMb());
        long totalMb = Math.max(0L, nearest.getDownloadTotalMb());

        if (nearest.isDownloadingYtdlp()) {
            return Component.translatable("text.collins.youtube.installing_progress", pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        String platform = nearest.getPlatformLabel();
        boolean platformHasRealProgress = totalMb > 0 || pct > 0 || dlMb > 0;
        if (nearest.isDownloadingPlatformVideo() && totalMb > 0) {
            return Component.translatable("text.collins.platform.download.progress_size", platform, pct, dlMb, totalMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && pct > 0) {
            return Component.translatable("text.collins.platform.download.progress", platform, pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && dlMb > 0) {
            return Component.translatable("text.collins.platform.download.size", platform, dlMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && !platformHasRealProgress) {
            if (nearest.hasDownloadProgressReceived()) {
                // Progress events flowing but values still 0 - show 0%
                return Component.translatable("text.collins.platform.download.progress", platform, 0).setStyle(Style.EMPTY.withColor(YELLOW));
            }
            return Component.translatable("text.collins.platform.preparing", platform).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isResolvingPlatformVideo()) {
            return Component.translatable("text.collins.platform.preparing", platform).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && totalMb > 0) {
            return Component.translatable("text.collins.video.download.progress_size", pct, dlMb, totalMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && pct > 0) {
            return Component.translatable("text.collins.video.download.progress", pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && dlMb > 0) {
            return Component.translatable("text.collins.video.download.size", dlMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading()) {
            return Component.translatable("text.collins.video.preparing").setStyle(Style.EMPTY.withColor(YELLOW));
        }

        if (nearest.isLiveStream()) {
            return Component.translatable("text.collins.video.live", nearest.state().name()).setStyle(Style.EMPTY.withColor(RED));
        }

        long posMs = nearest.currentPosMsForDisplay(serverNowMs);
        long durMs = nearest.durationMs();
        if (durMs > 0) {
            return Component.translatable("text.collins.timeline.full", nearest.state().name(), TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
                .setStyle(Style.EMPTY.withColor(GREEN));
        }
        return Component.translatable("text.collins.timeline.single", nearest.state().name(), TimeFormatUtil.formatMs(posMs))
            .setStyle(Style.EMPTY.withColor(GREEN));
    }

    private static void sendEndedPrompt(Player player, VideoScreen nearest) {
        String screenKey = nearest.state().name() + "_" + nearest.state().url();
        if (!nearest.hasCachedFile() || SHOWN_DELETE_PROMPT.contains(screenKey)) return;

        SHOWN_DELETE_PROMPT.add(screenKey);
        pendingDeletePath = nearest.getCachedFilePath();
        long sizeMb = nearest.getCachedFileSizeMb();

        player.sendSystemMessage(PREFIX.copy()
            .append(Component.translatable("text.collins.video.session_finished_cache", sizeMb).setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("\n"))
            .append(Component.literal("  /collins-cache delete").setStyle(Style.EMPTY.withColor(RED)))
            .append(Component.translatable("text.collins.video.delete_command_desc").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("\n"))
            .append(Component.literal("  /collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Component.translatable("text.collins.video.open_command_desc").setStyle(Style.EMPTY.withColor(GRAY))));
    }

    public static void tick(Minecraft client) {
        Player p = client.player;

        // Independent watchdog. We cannot rely solely on
        // ClientPlayConnectionEvents.DISCONNECT because some mods
        // (notably Replay Mod, which records server packets and intercepts
        // network teardown) delay or swallow that event entirely - the user
        // reported audio bleeding through for the full duration of the
        // recording finalization. Whenever the client is no longer in any
        // world (title screen, disconnect, server-switch transition,
        // singleplayer save-and-quit), there is no legitimate reason for
        // any Collins screen to keep playing, so we drop everything.
        if (p == null || client.level == null) {
            if (!SCREENS.isEmpty()) {
                stopAll();
                lastClientWorldKey = "";
            } else {
                // Even with no screens we may still have a stale audio
                // line from a player that was just removed from SCREENS
                // by a previous tick or disconnect hook.
                VideoAudioPlayer.shutdownAll();
            }
            return;
        }

        String worldKey = currentWorldKey(client);
        if (!worldKey.equals(lastClientWorldKey)) {
            lastClientWorldKey = worldKey;
            stopAllPlayback();
        }

        Vec3 pos = p.position();
        int radius = CollinsNet.HEAR_RADIUS;
        float globalVolume = CollinsNet.GLOBAL_VOLUME;
        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st != null && !isCompatibleWithCurrentWorld(st, client)) {
                if (st.playing()) s.stop();
                continue;
            }
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }

        notifyBlocked(client, p, pos, radius);

        CollinsClientConfig cfg = CollinsClientConfig.get();
        if (cfg.renderVideo && cfg.actionbarTimeline && !(client.gui.screen() instanceof ChatScreen)) {
            long now = System.currentTimeMillis();
            if (now - lastActionbarUpdateMs >= 500L) {
                lastActionbarUpdateMs = now;

                VideoScreen nearest = findNearestPlayingInRadius(pos, radius);
                if (nearest != null) {
                    if (nearest.isEnded()) {
                        sendEndedPrompt(p, nearest);
                        p.sendOverlayMessage(Component.literal(""));
                    } else if (nearest.hasEnded()) {
                        p.sendOverlayMessage(Component.literal(""));
                    } else {
                        p.sendOverlayMessage(PREFIX.copy().append(buildActionBarText(nearest, serverNowMs)));
                    }
                }
            }
        }
    }

    public static long estimateServerNowMs() {
        long sn = CollinsNet.SERVER_NOW_MS;
        long cr = CollinsNet.CLIENT_RECV_MS;
        if (sn <= 0 || cr <= 0) return 0;
        return sn + (System.currentTimeMillis() - cr);
    }

    public static void stopAll() {
        if (DEBUG) System.out.println("[Collins] stopAll()");
        // Belt-and-suspenders: globally close every audio line that any
        // VideoPlayer ever opened BEFORE we walk the SCREENS map. Per-screen
        // stop() also tries to silence audio, but it routes through that
        // screen's currentAudio reference, which has a narrow race window
        // around playOnce()'s try-with-resources where currentAudio can be
        // either null or already-discarded. Closing all lines up-front
        // makes the user-perceived silence instant and unconditional.
        VideoAudioPlayer.shutdownAll();
        for (VideoScreen s : SCREENS.values()) s.stop();
        SCREENS.clear();
        BLOCKED.clear();
        BLOCKED_NOTIFIED.clear();
    }

    public static void stopAllPlayback() {
        if (DEBUG) System.out.println("[Collins] stopAllPlayback()");
        VideoAudioPlayer.shutdownAll();
        for (VideoScreen s : SCREENS.values()) s.stop();
    }

    public static String getPendingDeletePath() {
        return pendingDeletePath;
    }

    public static boolean deletePendingFile() {
        String path = pendingDeletePath;
        if (path != null && !path.isEmpty()) {
            boolean deleted = VideoPlayer.deleteCachedFile(path);
            if (deleted) {
                pendingDeletePath = null;
            }
            return deleted;
        }
        return false;
    }

    public static void clearDeletePromptHistory() {
        SHOWN_DELETE_PROMPT.clear();
    }

    public static void clearPendingDeletePath() {
        pendingDeletePath = null;
    }
}

