package org.sawiq.collins.fabric.client.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheLimiterTest {

    @TempDir
    Path cache;

    /** Writes {@code size} bytes and sets the modification time to {@code ageMinutes} ago. */
    private static Path file(Path dir, String name, int size, int ageMinutes) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, new byte[size]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageMinutes * 60_000L));
        return p;
    }

    @Test
    void overLimitDeletesOldestFirst() throws Exception {
        Path oldest = file(cache, "a.mp4", 100, 30);
        Path middle = file(cache, "b.mp4", 100, 20);
        Path newest = file(cache, "c.mp4", 100, 10);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 200, Set.of());

        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(middle));
        assertTrue(Files.exists(newest));
        assertEquals(300, r.bytesBefore());
        assertEquals(200, r.bytesAfter());
        assertEquals(1, r.filesDeleted());
    }

    @Test
    void underLimitDeletesNothing() throws Exception {
        Path a = file(cache, "a.mp4", 100, 30);
        Path video = file(cache.resolve("youtube"), "abc.720p.mkv", 100, 20);
        Path sidecar = file(cache.resolve("youtube"), "abc.duration.txt", 5, 20);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 1_000, Set.of());

        assertTrue(Files.exists(a));
        assertTrue(Files.exists(video));
        assertTrue(Files.exists(sidecar));
        assertEquals(0, r.filesDeleted());
        assertEquals(200, r.bytesBefore());
        assertEquals(200, r.bytesAfter());
    }

    @Test
    void nestedPlatformDirIsCounted() throws Exception {
        Path top = file(cache, "hash.mp4", 100, 10);
        Path platformVideo = file(cache.resolve("youtube"), "abc.720p.mkv", 300, 60);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 150, Set.of());

        assertEquals(400, r.bytesBefore());
        assertFalse(Files.exists(platformVideo), "the older platform download is deleted");
        assertTrue(Files.exists(top));
        assertEquals(100, r.bytesAfter());
    }

    @Test
    void separatePlatformRootIsCounted() throws Exception {
        // getCacheDir() can live elsewhere on Windows (short path, C:\collins-cache).
        Path main = cache.resolve("main");
        Path platform = cache.resolve("game").resolve("collins-cache").resolve("vk");
        Path a = file(main, "hash.mp4", 100, 10);
        Path b = file(platform, "1_2.720p.mp4", 100, 50);

        CacheLimiter.enforce(List.of(main, platform), 100, Set.of());

        assertTrue(Files.exists(a));
        assertFalse(Files.exists(b));
    }

    @Test
    void inProgressFilesAreNeverDeletedOrCounted() throws Exception {
        Path part = file(cache, "hash.part", 500, 5);
        Path ytPart = file(cache.resolve("youtube"), "abc.720p.f137.mp4.part", 500, 5);
        Path frag = file(cache.resolve("youtube"), "abc.720p.f137.mp4.part-Frag3", 500, 5);
        Path ytdl = file(cache.resolve("youtube"), "abc.720p.f137.mp4.ytdl", 10, 5);
        Path merging = file(cache.resolve("youtube"), "abc.720p.temp.mkv", 500, 5);
        Path finished = file(cache, "done.mp4", 100, 10);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 50, Set.of());

        assertTrue(Files.exists(part));
        assertTrue(Files.exists(ytPart));
        assertTrue(Files.exists(frag));
        assertTrue(Files.exists(ytdl));
        assertTrue(Files.exists(merging));
        assertFalse(Files.exists(finished));
        assertEquals(100, r.bytesBefore());
        assertEquals(0, r.bytesAfter());
    }

    @Test
    void stalePartialFilesAreDeletedUnlessTheirDownloadIsRunning() throws Exception {
        Path stale = file(cache, "hash.part", 500, 120);
        Path staleYt = file(cache.resolve("youtube"), "old.1080p.f137.mp4.part", 500, 120);
        Path running = file(cache.resolve("youtube"), "abc.720p.f137.mp4.part", 500, 120);
        Path recent = file(cache, "other.part", 500, 5);
        Path finished = file(cache, "done.mp4", 100, 10);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 1000, Set.of(),
                p -> p.getFileName().toString().startsWith("abc.720p."));

        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(staleYt));
        assertTrue(Files.exists(running));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(finished));
        assertEquals(0, r.filesDeleted());
    }

    @Test
    void protectedFilesAreNeverDeleted() throws Exception {
        Path playing = file(cache, "playing.mp4", 100, 90);
        Path old = file(cache, "old.mp4", 100, 60);
        Path newer = file(cache, "newer.mp4", 100, 10);

        // The protected path is given in a different but equal form.
        Path sameFile = cache.resolve(".").resolve("playing.mp4");
        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 100, Set.of(sameFile));

        assertTrue(Files.exists(playing));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(newer));
        assertEquals(100, r.bytesAfter());
    }

    @Test
    void extraProtectionTestIsHonoured() throws Exception {
        Path downloading = file(cache.resolve("youtube"), "abc.720p.f137.mp4", 100, 90);
        Path other = file(cache, "other.mp4", 100, 10);

        CacheLimiter.enforce(List.of(cache), 100, Set.of(),
                p -> p.getFileName().toString().startsWith("abc.720p."));

        assertTrue(Files.exists(downloading));
        assertFalse(Files.exists(other));
    }

    @Test
    void stopsWhenOnlyProtectedFilesAreLeft() throws Exception {
        Path playing = file(cache, "playing.mp4", 500, 90);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 100, Set.of(playing));

        assertTrue(Files.exists(playing));
        assertEquals(0, r.filesDeleted());
        assertEquals(500, r.bytesAfter());
    }

    @Test
    void sidecarIsDeletedWithItsVideo() throws Exception {
        Path yt = cache.resolve("youtube");
        Path video = file(yt, "abc.720p.mkv", 100, 60);
        Path sidecar = file(yt, "abc.duration.txt", 5, 60);
        Path keepVideo = file(yt, "xyz.720p.mkv", 100, 10);
        Path keepSidecar = file(yt, "xyz.duration.txt", 5, 10);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 150, Set.of());

        assertFalse(Files.exists(video));
        assertFalse(Files.exists(sidecar));
        assertTrue(Files.exists(keepVideo));
        assertTrue(Files.exists(keepSidecar));
        // Sidecars are not videos: not counted in the total.
        assertEquals(200, r.bytesBefore());
        assertEquals(1, r.filesDeleted());
    }

    @Test
    void sidecarKeptWhileAnotherQualityOfTheVideoRemains() throws Exception {
        Path yt = cache.resolve("youtube");
        Path old720 = file(yt, "abc.720p.mkv", 100, 60);
        Path new1080 = file(yt, "abc.1080p.mkv", 100, 10);
        Path sidecar = file(yt, "abc.duration.txt", 5, 60);

        CacheLimiter.enforce(List.of(cache), 150, Set.of());

        assertFalse(Files.exists(old720));
        assertTrue(Files.exists(new1080));
        assertTrue(Files.exists(sidecar));
    }

    @Test
    void orphanSidecarIsNotKeptAsAVideo() throws Exception {
        Path yt = cache.resolve("youtube");
        Path orphan = file(yt, "gone.duration.txt", 5, 60);
        Path video = file(yt, "abc.720p.mkv", 100, 10);

        CacheLimiter.Result r = CacheLimiter.enforce(List.of(cache), 1_000, Set.of());

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(video));
        assertEquals(100, r.bytesBefore());
        assertEquals(0, r.filesDeleted());
    }

    @Test
    void deleteSidecarIfOrphanedAfterManualDelete() throws Exception {
        Path yt = cache.resolve("rutube");
        Path video = file(yt, "abc.720p.mp4", 100, 60);
        Path sidecar = file(yt, "abc.duration.txt", 5, 60);
        Files.delete(video);

        CacheLimiter.deleteSidecarIfOrphaned(video);

        assertFalse(Files.exists(sidecar));
    }

    @Test
    void missingRootIsFine() throws Exception {
        Path a = file(cache, "a.mp4", 100, 30);

        CacheLimiter.Result r = CacheLimiter.enforce(
                List.of(cache.resolve("does-not-exist"), cache), 1_000, Set.of());

        assertTrue(Files.exists(a));
        assertEquals(100, r.bytesBefore());

        CacheLimiter.Result none = CacheLimiter.enforce(List.of(cache.resolve("nope")), 0, Set.of());
        assertEquals(0, none.bytesBefore());
        assertEquals(0, none.filesDeleted());
    }

    @Test
    void duplicateAndNestedRootsAreNotDoubleCounted() throws Exception {
        file(cache, "a.mp4", 100, 30);
        file(cache.resolve("youtube"), "abc.720p.mkv", 100, 20);

        List<Path> roots = List.of(
                cache,
                cache.resolve("youtube"),
                cache.resolve(".").resolve("youtube"),
                cache.resolve("youtube").resolve("..")
        );
        CacheLimiter.Result r = CacheLimiter.enforce(roots, 1_000, Set.of());
        assertEquals(200, r.bytesBefore());

        CacheLimiter.Usage u = CacheLimiter.measure(roots);
        assertEquals(200, u.bytes());
        assertEquals(2, u.files());
    }

    @Test
    void nothingOutsideTheRootsIsTouched() throws Exception {
        Path outside = file(cache.resolve("elsewhere"), "keep.mp4", 1_000, 999);
        Path root = cache.resolve("collins-cache");
        Path inside = file(root, "a.mp4", 100, 10);

        CacheLimiter.enforce(List.of(root), 0, Set.of());

        assertTrue(Files.exists(outside));
        assertFalse(Files.exists(inside));
    }
}
