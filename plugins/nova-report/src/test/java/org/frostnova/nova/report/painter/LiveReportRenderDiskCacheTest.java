package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.StandbyPhases;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveReportRenderDiskCacheTest {
    @TempDir Path temp;
    private LiveReportRenderDiskCache disk;
    private String image;
    private LiveReportRenderCache.Key key;

    @BeforeEach void setup() throws Exception {
        disk = new LiveReportRenderDiskCache(temp.resolve("report-render-cache"));
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", buffer);
        image = Base64.getEncoder().encodeToString(buffer.toByteArray());
        key = key("bilibili", 1L, 100, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT);
    }

    // 抓住写盘异常让正常下播报告也发不出去的故障。
    @Test void failedWriteDoesNotThrowOrLeavePartialImage() throws Exception {
        Files.createFile(temp.resolve("report-render-cache"));
        assertDoesNotThrow(() -> disk.store(key, image));
        assertTrue(disk.read(key).isEmpty());
    }

    // 抓住磁盘坏图直接发到群里、没有退回重画的故障。
    @Test void corruptImageIsAMissAndTemporaryFileIsNeverRead() throws Exception {
        disk.store(key, image);
        Path path = disk.file(key);
        Files.writeString(path, "broken png");
        assertTrue(disk.read(key).isEmpty());
        Files.move(path, path.resolveSibling(path.getFileName() + ".tmp-1234"));
        assertTrue(disk.read(key).isEmpty());
    }

    // 抓住金额、配色或主播不同却串用同一份原图的故障。
    @Test void diskKeyIncludesPlatformStreamerSessionRevenueAndActualPalette() {
        disk.store(key, image);
        var hidden = BilibiliLiveReportOptions.of(new JSONObject(), false);
        for (var other : List.of(
                key("other", 1L, 100, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT),
                key("bilibili", 2L, 100, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT),
                key("bilibili", 1L, 101, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT),
                key("bilibili", 1L, 100, hidden, ReportPalette.DEFAULT),
                key("bilibili", 1L, 100, new BilibiliLiveReportOptions(),
                        ReportPalette.DEFAULT.toBuilder().background(new Color(255, 255, 255, 128)).build()))) {
            assertNotEquals(disk.file(key), disk.file(other));
            assertTrue(disk.read(other).isEmpty());
        }
        assertEquals(image, disk.read(key).orElseThrow());
        assertEquals(Path.of("report-render-cache"), LiveReportRenderDiskCache.directoryFor("data.json"));
        assertEquals(temp.resolve("report-render-cache"), LiveReportRenderDiskCache.directoryFor(temp.resolve("data.json").toString()));
    }

    // 抓住读取后仍按旧时间删图，以及长期不用的原图和半成品越攒越多的故障。
    @Test void readRefreshesRetentionAndSweepOnlyDeletesOldCacheFiles() throws Exception {
        disk.store(key, image);
        Path old = disk.file(key);
        Path unfinished = old.resolveSibling(old.getFileName() + ".tmp-1234");
        Files.writeString(unfinished, "partial");
        Path unrelated = old.resolveSibling("keep.txt"); Files.writeString(unrelated, "keep");
        FileTime stale = FileTime.from(Instant.now().minus(Duration.ofDays(31)));
        for (Path path : List.of(old, unfinished, unrelated)) Files.setLastModifiedTime(path, stale);
        assertEquals(image, disk.read(key).orElseThrow());
        disk.sweep();
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(unfinished));
        assertTrue(Files.exists(unrelated));
        Files.setLastModifiedTime(old, stale);
        disk.sweep();
        assertFalse(Files.exists(old));
    }

    // 抓住程序重启后没有清理旧图，或每天的清理没有生效的故障。
    @Test void startupAndDailySweepRemoveExpiredImagesAfterGate() throws Exception {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(temp.resolve("data.json").toString());
        var cache = new LiveReportRenderCache(properties);
        assertEquals(StandbyPhases.AFTER_GATE, cache.getPhase());
        assertTrue(cache.isAutoStartup());
        disk.store(key, image);
        Files.setLastModifiedTime(disk.file(key), FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        cache.start();
        assertTrue(cache.isRunning());
        assertFalse(Files.exists(disk.file(key)));
        disk.store(key, image);
        Files.setLastModifiedTime(disk.file(key), FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        cache.sweepDaily();
        assertFalse(Files.exists(disk.file(key)));
        cache.stop();
        assertFalse(cache.isRunning());
    }

    // 抓住成品目录为链接时，把链接目标里的旧文件也删掉的故障。
    @Test void linkedDirectoryIsNotCleanedForRetentionOrNewSession() throws Exception {
        Path target = temp.resolve("linked-target"); Files.createDirectory(target);
        try { Files.createSymbolicLink(temp.resolve("report-render-cache"), target); }
        catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("当前文件系统不支持创建符号链接: " + e.getMessage());
        }
        disk.store(key, image);
        Files.setLastModifiedTime(disk.file(key), FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        disk.store(key("bilibili", 1L, 200, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT), image);
        disk.sweep();
        assertTrue(Files.exists(disk.file(key)));
    }

    // 抓住延迟完成的旧场次把新场次原图删掉的故障；不同主播各自保留。
    @Test void lateOldSessionCannotReplaceNewestAndOtherStreamerIsKept() {
        var other = key("bilibili", 2L, 100, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT);
        var newer = key("bilibili", 1L, 200, new BilibiliLiveReportOptions(), ReportPalette.DEFAULT);
        disk.store(other, image);
        disk.store(newer, image);
        disk.store(key, image);
        assertTrue(disk.read(key).isEmpty());
        assertEquals(image, disk.read(newer).orElseThrow());
        assertEquals(image, disk.read(other).orElseThrow());
    }

    // 抓住补发重画也落盘，之后被当作原图复用的故障。
    @Test void memoryOnlyReplayNeverWritesDiskAndMemoryWins() throws Exception {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(temp.resolve("data.json").toString());
        var cache = new LiveReportRenderCache(properties);
        var options = new BilibiliLiveReportOptions();
        cache.put("bilibili", 1L, 100, options, ReportPalette.DEFAULT, "QUJD");
        assertFalse(Files.exists(disk.file(key)));
        disk.store(key, image);
        Files.writeString(disk.file(key), "broken png");
        assertEquals("QUJD", cache.get("bilibili", 1L, 100, options, ReportPalette.DEFAULT).orElseThrow());
    }

    private LiveReportRenderCache.Key key(String platform, Long uid, long start,
            BilibiliLiveReportOptions options, ReportPalette palette) {
        return new LiveReportRenderCache.Key(platform, uid, start, JSON.toJSONString(options), palette);
    }
}
