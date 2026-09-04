package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 下播报告图片留档
 * <p>
 * 报告图此前只存在于发出去的那条消息里：想再看一眼，只能翻聊天记录，
 * 而聊天记录会被清理，图片也有有效期。场次归档记着「那一场发生过」，
 * 却记不下「那一场的报告长什么样」——留一份到本地，场次才真的点得开。
 * <p>
 * <b>保留期与场次归档一致</b>：归档不设过期，本留档也不设。删图会让场次列表里
 * 那个「看报告」点开是 404，而使用者分不出「这一场没出过报告」与「图被清掉了」。
 * ⚠️ 代价是磁盘占用随场次线性增长（一张报告图数量级在几百 KB，
 * 5 位主播每天 3 场跑一年约合一两 GB）——真要设上限，得连同「过期的那一场
 * 在界面上怎么表示」一起做，不能只删文件。
 */
@Slf4j
@Service
public class LiveReportArchive {
    /**
     * 留档目录名，与场次归档同目录下的一个子目录
     */
    private static final String DIRECTORY_NAME = "reports";

    /**
     * 允许的平台名形状
     * <p>
     * 平台名会成为文件名的一部分，而查看报告那一支是从<b>请求路径</b>里取它的。
     * 写成白名单而不是「过滤掉 {@code ../}」：黑名单迟早会漏，白名单漏不了——
     * 这里根本容不下一个点号，路径穿越连拼都拼不出来。
     */
    private static final Pattern PLATFORM = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final StarBotCoreProperties properties;

    /**
     * 写锁。多个直播间可能同时下播，各自写各自的文件，但建目录这一步会撞
     */
    private final Object writeLock = new Object();

    @Autowired
    public LiveReportArchive(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 留一份报告图
     * <p>
     * <b>失败只记日志，绝不向上抛。</b>调用点在下播推送里，留档写不进去
     * 不该连累推送本身——那条消息里的图已经发出去了。
     * <p>
     * 同一场会被调用多次（推给几个通道就画几张），此处只留一份，规则是
     * <b>「金额可见的那份优先」</b>：留档是给这台机器的主人看的，他本就看得到金额，
     * 留一份抹掉了金额的副本等于让他看一份比自己权限更少的报告。
     * 因此没有时写，有了之后只有金额可见的那份才覆盖。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒），与场次归档里的 {@code startTime} 同值——
     *                  两边对不上的话，场次列表点开的会是另一场的报告
     * @param image PNG 字节
     * @param revenueVisible 这一份画的时候金额是否可见
     */
    public void store(@NonNull String platform, @NonNull Long uid, long startTime,
                      byte[] image, boolean revenueVisible) {
        if (image == null || image.length == 0) {
            return;
        }

        Optional<Path> target = path(platform, uid, startTime);
        if (target.isEmpty()) {
            log.warn("平台名 {} 不能作为文件名, 本场报告不留档", platform);
            return;
        }

        Path path = target.get();
        synchronized (writeLock) {
            if (Files.exists(path) && !revenueVisible) {
                return;
            }

            try {
                Files.createDirectories(path.getParent());
                // 先写临时文件再原子改名：直接往目标文件上写的话，写到一半被读到的是半张图，
                // 而半张 PNG 在界面上与「图坏了」一模一样
                Path temp = Files.createTempFile(path.getParent(), "report-", ".part");
                Files.write(temp, image);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.debug("已留档 {} 的一场报告: {} 字节", uid, image.length);
            } catch (IOException e) {
                log.error("留档下播报告失败, 该场的报告将无法在控制台查看", e);
            }
        }
    }

    /**
     * 读一份报告图
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒）
     * @return PNG 字节，没留下过时为空
     */
    public Optional<byte[]> read(@NonNull String platform, @NonNull Long uid, long startTime) {
        Optional<Path> path = path(platform, uid, startTime);
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readAllBytes(path.get()));
        } catch (IOException e) {
            log.error("读取下播报告留档失败", e);
            return Optional.empty();
        }
    }

    /**
     * 有没有留下这一场的报告
     * <p>
     * 场次列表据此决定哪一行点得开。<b>不靠「这一场配没配下播报告」推断</b>：
     * 配了也可能画失败改发了文字版，那一场就是没有图。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒）
     * @return 是否有留档
     */
    public boolean has(@NonNull String platform, @NonNull Long uid, long startTime) {
        return path(platform, uid, startTime).filter(Files::isRegularFile).isPresent();
    }

    /**
     * 一场报告的留档路径，平台名不合形状时为空
     */
    private Optional<Path> path(String platform, Long uid, long startTime) {
        if (!PLATFORM.matcher(platform).matches()) {
            return Optional.empty();
        }
        return Optional.of(directory().resolve(platform + "-" + uid + "-" + startTime + ".png"));
    }

    /**
     * 留档目录，与场次归档同目录下的 {@code reports/}
     */
    private Path directory() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(DIRECTORY_NAME) : parent.resolve(DIRECTORY_NAME);
    }
}
