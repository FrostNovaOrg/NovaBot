package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 推送时的成品图原始字节。每位主播只留最近一场的各套外观，三十天未读即清理。 */
@Slf4j
final class LiveReportRenderDiskCache {
    static final Duration RETENTION = Duration.ofDays(30);
    private static final Pattern CACHE_FILE = Pattern.compile("[0-9a-f]{64}-([0-9]+)-[0-9a-f]{64}\\.png(?:\\.tmp-[0-9a-f-]+)?");
    private final Path directory;

    LiveReportRenderDiskCache(Path directory) { this.directory = directory; }

    static Path directoryFor(String liveDataPath) {
        Path parent = Path.of(liveDataPath).getParent();
        return parent == null ? Path.of("report-render-cache") : parent.resolve("report-render-cache");
    }

    Path file(LiveReportRenderCache.Key key) {
        String appearance = hash(JSON.toJSONString(new Object[]{key.options(), key.palette()}));
        return directory.resolve(streamerPrefix(key) + key.start() + "-" + appearance + ".png");
    }

    Optional<String> read(LiveReportRenderCache.Key key) {
        if (directory == null || key.start() <= 0) return Optional.empty();
        try {
            Path path = file(key);
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
                throw new IOException("不是普通图片文件");
            byte[] bytes = Files.readAllBytes(path);
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IOException("图片内容损坏");
            try {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } catch (Exception e) {
                log.debug("没能记下下播报告原图刚被读过: {}", e.getMessage());
            }
            // 校验时解码，发送时直接用原始字节，不重新编码图片。
            return Optional.of(Base64.getEncoder().encodeToString(bytes));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("读不到下播报告原图，将从明细重画: {}", e.getMessage());
            return Optional.empty();
        }
    }

    synchronized void store(LiveReportRenderCache.Key key, String image) {
        if (directory == null) return;
        Path temp = null;
        try {
            Path target = file(key);
            Files.createDirectories(directory);
            // 较旧场次延迟完成时，不把已经存好的新场次换回去。
            if (!Files.isSymbolicLink(directory) && hasNewerSession(key)) return;
            temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            byte[] bytes = Base64.getDecoder().decode(image);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // 新图完整写好后才删除旧场次；目录本身是链接时不清理。
            deleteOlderSessions(key);
        } catch (Exception e) {
            log.warn("下播报告原图没能存到本机，本次仍照常推送: {}", e.getMessage());
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (Exception e) { log.debug("下播报告临时文件没能删掉: {}", e.getMessage()); }
            }
        }
    }

    private boolean hasNewerSession(LiveReportRenderCache.Key key) throws IOException {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> sessionStart(path, key) > key.start()
                    && path.getFileName().toString().endsWith(".png")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private void deleteOlderSessions(LiveReportRenderCache.Key key) throws IOException {
        if (Files.isSymbolicLink(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                long start = sessionStart(path, key);
                if (start <= 0 || start >= key.start() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try { Files.deleteIfExists(path); }
                catch (Exception e) { log.warn("删不掉旧场次的下播报告原图 {}: {}", path.getFileName(), e.getMessage()); }
            }
        }
    }

    private static long sessionStart(Path path, LiveReportRenderCache.Key key) {
        String name = path.getFileName().toString();
        var match = CACHE_FILE.matcher(name);
        if (!name.startsWith(streamerPrefix(key)) || !match.matches()) return -1;
        try { return Long.parseLong(match.group(1)); }
        catch (NumberFormatException e) { return -1; }
    }

    synchronized void sweep() {
        if (directory == null) return;
        try {
            if (Files.isSymbolicLink(directory)) {
                log.warn("下播报告原图目录是指向别处的链接，这次不清理");
                return;
            }
            if (!Files.isDirectory(directory)) return;
            Instant cutoff = Instant.now().minus(RETENTION);
            try (var files = Files.list(directory)) {
                for (Path path : files.toList()) {
                    if (!CACHE_FILE.matcher(path.getFileName().toString()).matches()
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
                    } catch (Exception e) {
                        log.warn("清理下播报告原图失败 {}: {}", path.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理下播报告原图目录失败: {}", e.getMessage());
        }
    }

    private static String streamerPrefix(LiveReportRenderCache.Key key) {
        return hash(JSON.toJSONString(new Object[]{key.platform(), key.uid()})) + "-";
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
