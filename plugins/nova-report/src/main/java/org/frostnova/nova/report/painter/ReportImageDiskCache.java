package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.plugin.NovaComponent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 下播报告用过的礼物图标和大航海标志，按请求地址留在本机
 * <p>
 * 图片地址的文件名就是这张图的内容摘要，同一地址永远是同一张图；图标换了就是新地址。
 * 所以按地址留，不探测图有没有被换掉，也不定期重下。
 * <p>
 * 目录与每场明细同一个取法：直播数据文件所在目录下的 {@code image-cache/}。
 * 文件名是地址的摘要，不用地址原文拼路径——地址里有斜杠和缩放后缀，直接拼会写出目录。
 * 先把整份内容写入临时文件并刷盘，再改成正式名字；读的人只打开正式名字，写到一半的文件读不到。
 * <p>
 * 取失败的不放进来。超过 {@link #RETENTION} 没再被读到的删掉：读到时刷新修改时间，
 * 刷新失败也不影响这次命中。程序起来时清一次，之后每天清一次。
 * 目录本身若是指向别处的链接，不清理，免得删到别的地方。头像不走这里。
 */
@Slf4j
@NovaComponent
public class ReportImageDiskCache {
    static final Duration RETENTION = Duration.ofDays(30);

    private static final String DIRECTORY_NAME = "image-cache";

    /**
     * 一天的毫秒数。注解要编译期常量，不能写 {@code Duration.ofDays(1)}
     */
    private static final long SWEEP_INTERVAL_MILLIS = 86_400_000L;

    private final Path directory;

    /**
     * 按直播数据文件的位置决定缓存目录
     * @param properties 核心配置，只用其中的直播数据文件路径
     */
    @Autowired
    public ReportImageDiskCache(NovaCoreProperties properties) {
        this(directoryFor(properties.getLive().getLiveDataPath()));
    }

    /**
     * @param directory 缓存目录；{@code null} 表示不落盘（预览、以及不带这份缓存构造的画手）
     */
    ReportImageDiskCache(Path directory) {
        this.directory = directory;
    }

    /**
     * 不读写磁盘的一份，给不该落盘的画手
     */
    static ReportImageDiskCache none() {
        return new ReportImageDiskCache((Path) null);
    }

    /**
     * 与明细目录同一个取法：数据文件的父目录下叫 {@code image-cache}，
     * 数据文件本身没有父目录时（例如默认的 {@code data.json}）就落在当前目录下的这个名字
     * @param liveDataPath 直播数据文件路径
     * @return 缓存目录
     */
    static Path directoryFor(String liveDataPath) {
        Path liveData = Path.of(liveDataPath);
        Path parent = liveData.getParent();
        return parent == null ? Path.of(DIRECTORY_NAME) : parent.resolve(DIRECTORY_NAME);
    }

    Path directory() {
        return directory;
    }

    /**
     * 这个地址对应的正式文件。文件可以还不存在
     * @param requestUrl 实际请求的地址，含缩放后缀
     * @return 正式文件路径
     */
    Path file(String requestUrl) {
        return directory.resolve(fileName(requestUrl));
    }

    /**
     * 读一张已经落盘的图。读到就用；刷新修改时间失败也不影响这次命中。
     * 内容不是一张完整的图、或读的过程出错，都当没有
     * @param requestUrl 实际请求的地址
     * @return 读到的图；没有、或读不出来时为空
     */
    Optional<BufferedImage> read(String requestUrl) {
        if (directory == null || requestUrl == null || requestUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = file(requestUrl);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                return Optional.empty();
            }
            rememberRead(path);
            return Optional.of(image);
        } catch (Exception e) {
            log.debug("读不到已留下的图标: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 记下这张图刚被读过。改不了修改时间也不影响这次命中
     */
    private void rememberRead(Path path) {
        try {
            touchLastRead(path);
        } catch (Exception e) {
            log.debug("没能记下这张图标刚被读过，这次仍用读到的图: {}", e.getMessage());
        }
    }

    /**
     * 把这张图的修改时间改成现在
     */
    void touchLastRead(Path path) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
    }

    /**
     * 把一张取到的图写入本机。写临时文件、刷盘、再改成正式名字
     * @param requestUrl 实际请求的地址
     * @param image 已经按绘制需要缩放过的图
     */
    void store(String requestUrl, BufferedImage image) {
        if (directory == null || requestUrl == null || requestUrl.isBlank() || image == null) {
            return;
        }
        Path target = file(requestUrl);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(directory);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", buffer)) {
                throw new IOException("写不出 png");
            }
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer.toByteArray());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("礼物图标或大航海标志没能存到本机: {}", e.getMessage());
            deleteQuietly(temp);
        }
    }

    /**
     * 删掉超过 {@link #RETENTION} 没再被读到的缓存文件，以及同样放久了的半成品
     */
    @PostConstruct
    public void sweepOnStartup() {
        sweep();
    }

    /**
     * 每天再清一次。第一次延后一天：起来的那一次已经由 {@link #sweepOnStartup} 做过
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS, initialDelay = SWEEP_INTERVAL_MILLIS)
    public void sweepDaily() {
        sweep();
    }

    /**
     * 按修改时间清理。目录本身若是指向别处的链接，这一次不清理，免得删到别的地方的文件
     */
    public void sweep() {
        if (directory == null) {
            return;
        }
        try {
            if (Files.isSymbolicLink(directory)) {
                log.warn("图标缓存目录是指向别处的链接，这次不清理");
                return;
            }
            if (!Files.isDirectory(directory)) {
                return;
            }
            Instant cutoff = Instant.now().minus(RETENTION);
            try (Stream<Path> listed = Files.list(directory)) {
                for (Path path : listed.toList()) {
                    String name = path.getFileName().toString();
                    if (!Files.isRegularFile(path) || !isCacheFile(name)) {
                        continue;
                    }
                    try {
                        if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(path);
                        }
                    } catch (Exception e) {
                        log.warn("清理本机图标时没能处理 {}: {}", name, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理本机图标缓存失败: {}", e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件没能删掉: {}", e.getMessage());
        }
    }

    /**
     * 正式文件是 64 个十六进制字符加 {@code .png}；写到一半的是同一前缀加 {@code .tmp-}
     */
    private static boolean isCacheFile(String name) {
        int dot = name.indexOf('.');
        if (dot != 64) {
            return false;
        }
        for (int i = 0; i < dot; i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        String rest = name.substring(dot);
        return ".png".equals(rest) || rest.startsWith(".png.tmp-");
    }

    private static String fileName(String requestUrl) {
        return hash(requestUrl) + ".png";
    }

    private static String hash(String requestUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte piece : digest) {
                hex.append(Character.forDigit((piece >> 4) & 0xf, 16));
                hex.append(Character.forDigit(piece & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
