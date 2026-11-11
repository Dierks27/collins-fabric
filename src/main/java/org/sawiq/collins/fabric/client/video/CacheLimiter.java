package org.sawiq.collins.fabric.client.video;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Keeps the downloaded-video cache under a size limit.
 *
 * <p>Walks every cache directory (the top-level {@code collins-cache/} and
 * the per-platform folders yt-dlp writes into), adds up the finished video
 * files and deletes the least recently used ones (oldest modification time
 * first) until the total is at or under the limit.</p>
 *
 * <p>Never deleted: files still being written (yt-dlp and the mod's own
 * {@code .part}/{@code .ytdl}/{@code .temp}/{@code .tmp} and fragment
 * files), the protected files the caller passes (what is playing or was
 * just downloaded), symbolic links, and anything outside the given
 * directories. A partly downloaded file that has not changed for
 * {@link #STALE_PARTIAL_MS} is left over from a crash or a forced quit and
 * is deleted, unless it belongs to a download that is running.</p>
 *
 * <p>Plain java.nio, no Minecraft classes, so it can be unit tested.</p>
 */
public final class CacheLimiter {

    /** Duration sidecar yt-dlp downloads leave next to a video ({@code <id>.duration.txt}). */
    static final String SIDECAR_SUFFIX = ".duration.txt";

    /** A partly downloaded file untouched for this long is left over and deleted. */
    static final long STALE_PARTIAL_MS = 60L * 60L * 1000L;

    private static final Object LOCK = new Object();

    private CacheLimiter() {
    }

    /**
     * @param bytesBefore  finished video bytes found before trimming
     * @param bytesAfter   finished video bytes left after trimming
     * @param filesDeleted video files deleted (sidecars not counted)
     */
    public record Result(long bytesBefore, long bytesAfter, int filesDeleted) {
    }

    /** @param bytes finished video bytes; @param files finished video files */
    public record Usage(long bytes, int files) {
    }

    public static Result enforce(Collection<Path> roots, long maxBytes, Set<Path> protectedFiles) {
        return enforce(roots, maxBytes, protectedFiles, null);
    }

    /**
     * Deletes the oldest finished videos under {@code roots} until they use
     * at most {@code maxBytes}.
     *
     * @param alsoProtect optional extra test for files that must be kept
     *                    (for example, files of a download that is running)
     */
    public static Result enforce(Collection<Path> roots, long maxBytes, Set<Path> protectedFiles,
                                 Predicate<Path> alsoProtect) {
        synchronized (LOCK) {
            Scan scan = scan(roots);

            Set<Path> keep = new HashSet<>();
            if (protectedFiles != null) {
                for (Path p : protectedFiles) {
                    if (p != null) keep.add(canonical(p));
                }
            }

            // Leftovers of a download that was cut off (crash, forced quit).
            long staleBefore = System.currentTimeMillis() - STALE_PARTIAL_MS;
            for (Entry e : scan.partials) {
                if (e.modified >= staleBefore) continue;
                if (keep.contains(e.path)) continue;
                if (alsoProtect != null && safeTest(alsoProtect, e.path)) continue;
                try {
                    if (Files.deleteIfExists(e.path)) scan.others.remove(e.path);
                } catch (Exception ignored) {
                }
            }

            long before = 0L;
            for (Entry e : scan.videos) before += e.size;
            long total = before;
            int deleted = 0;

            if (total > maxBytes) {
                List<Entry> candidates = new ArrayList<>();
                for (Entry e : scan.videos) {
                    if (keep.contains(e.path)) continue;
                    if (alsoProtect != null && safeTest(alsoProtect, e.path)) continue;
                    candidates.add(e);
                }
                candidates.sort(Comparator.comparingLong((Entry e) -> e.modified)
                        .thenComparing(e -> e.path.toString()));

                for (Entry e : candidates) {
                    if (total <= maxBytes) break;
                    try {
                        if (Files.deleteIfExists(e.path)) {
                            deleted++;
                        }
                        total -= e.size;
                        scan.others.remove(e.path);
                    } catch (Exception ignored) {
                        // In use by another program (Windows) or no permission: keep counting it.
                    }
                }
            }

            deleteOrphanSidecars(scan);
            return new Result(before, total, deleted);
        }
    }

    /** Finished video bytes and file count under {@code roots}, without deleting anything. */
    public static Usage measure(Collection<Path> roots) {
        Scan scan = scan(roots);
        long bytes = 0L;
        for (Entry e : scan.videos) bytes += e.size;
        return new Usage(bytes, scan.videos.size());
    }

    /**
     * Deletes the duration sidecar of {@code videoFile} if no other file of
     * the same video is left in its folder. Call after deleting a video.
     */
    public static void deleteSidecarIfOrphaned(Path videoFile) {
        if (videoFile == null) return;
        Path dir = videoFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) return;
        String name = videoFile.getFileName().toString();
        Set<String> sidecars = new HashSet<>();
        int firstDot = name.indexOf('.');
        if (firstDot > 0) sidecars.add(name.substring(0, firstDot) + SIDECAR_SUFFIX);
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) sidecars.add(name.substring(0, lastDot) + SIDECAR_SUFFIX);
        for (String sidecar : sidecars) {
            String id = sidecar.substring(0, sidecar.length() - SIDECAR_SUFFIX.length());
            if (!hasFileWithPrefix(dir, id + ".", sidecar)) {
                try {
                    Files.deleteIfExists(dir.resolve(sidecar));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * True for names of files a download is still writing: the mod's own
     * {@code <hash>.part}, and yt-dlp's {@code .part}, {@code .part-FragN},
     * {@code .ytdl}, {@code .temp}/{@code .tmp} and merger {@code .temp.mkv}.
     */
    static boolean isInProgressName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.contains(".part")
                || n.contains("-frag")
                || n.endsWith(".ytdl")
                || n.endsWith(".temp")
                || n.endsWith(".tmp")
                || n.contains(".temp.")
                || n.contains(".tmp.");
    }

    static boolean isSidecarName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(SIDECAR_SUFFIX);
    }

    // ------------------------------------------------------------------

    private record Entry(Path path, long size, long modified) {
    }

    private static final class Scan {
        final List<Entry> videos = new ArrayList<>();
        /** Files a download is (or was) still writing. */
        final List<Entry> partials = new ArrayList<>();
        final List<Path> sidecars = new ArrayList<>();
        /** Every non-sidecar file seen (finished, in progress or protected). */
        final Set<Path> others = new HashSet<>();
    }

    private static Scan scan(Collection<Path> roots) {
        Scan scan = new Scan();
        for (Path root : distinctRoots(roots)) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // walkFileTree does not follow links: a link reports
                        // isSymbolicLink() and is left alone.
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String name = file.getFileName().toString();
                        if (isSidecarName(name)) {
                            scan.sidecars.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                        scan.others.add(file);
                        Entry entry = new Entry(file, attrs.size(), attrs.lastModifiedTime().toMillis());
                        if (isInProgressName(name)) {
                            scan.partials.add(entry);
                            return FileVisitResult.CONTINUE;
                        }
                        scan.videos.add(entry);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {
            }
        }
        return scan;
    }

    /**
     * Existing roots, made canonical, with any root that is the same as or
     * inside another one dropped so no file is counted twice.
     */
    private static List<Path> distinctRoots(Collection<Path> roots) {
        List<Path> existing = new ArrayList<>();
        if (roots == null) return existing;
        for (Path r : roots) {
            if (r == null) continue;
            try {
                if (!Files.isDirectory(r)) continue;
                Path c = canonical(r);
                // Never treat a whole drive or filesystem root as the cache.
                if (c.getNameCount() == 0) continue;
                existing.add(c);
            } catch (Exception ignored) {
            }
        }
        existing.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> out = new ArrayList<>();
        for (Path c : existing) {
            boolean covered = false;
            for (Path kept : out) {
                if (c.startsWith(kept)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(c);
        }
        return out;
    }

    private static void deleteOrphanSidecars(Scan scan) {
        for (Path sidecar : scan.sidecars) {
            Path dir = sidecar.getParent();
            if (dir == null) continue;
            String name = sidecar.getFileName().toString();
            String id = name.substring(0, name.length() - SIDECAR_SUFFIX.length());
            boolean hasVideo = false;
            for (Path other : scan.others) {
                if (dir.equals(other.getParent()) && other.getFileName().toString().startsWith(id + ".")) {
                    hasVideo = true;
                    break;
                }
            }
            if (!hasVideo) {
                try {
                    Files.deleteIfExists(sidecar);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean hasFileWithPrefix(Path dir, String prefix, String exceptName) {
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && !n.equals(exceptName) && !isSidecarName(n);
            });
        } catch (Exception e) {
            // Could not look: keep the sidecar.
            return true;
        }
    }

    private static boolean safeTest(Predicate<Path> test, Path p) {
        try {
            return test.test(p);
        } catch (Exception e) {
            // When unsure, keep the file.
            return true;
        }
    }

    /**
     * Real path when the file exists (resolves Windows 8.3 short names and
     * links), otherwise the absolute, normalised path.
     */
    static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
