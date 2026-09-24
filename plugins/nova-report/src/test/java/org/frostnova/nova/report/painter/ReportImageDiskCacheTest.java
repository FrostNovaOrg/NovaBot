package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveGiftTotal;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.service.BilibiliLiveReportRedrawer;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 礼物图标与大航海标志落在本机之后，重启、换图、取失败、重画、放久了，各会怎样
 */
@DisplayName("礼物图标与大航海标志的本机缓存")
class ReportImageDiskCacheTest {
    private static final String GIFT_URL =
            "https://i0.hdslb.com/bfs/live/aabbccddeeff00112233445566778899aabbccdd.png";

    private static final String GIFT_URL_NEW =
            "https://i0.hdslb.com/bfs/live/00112233445566778899aabbccddeeffaabbccdd.png";

    private static final String GUARD_URL =
            "https://i0.hdslb.com/bfs/live/ffeeddccbbaa99887766554433221100ffeeddcc.png";

    private static final String GUARD_URL_NEW =
            "https://i0.hdslb.com/bfs/live/99887766554433221100ffeeddccbbaa99887766.png";

    private static final String AVATAR_URL =
            "https://i0.hdslb.com/bfs/face/11223344556677889900aabbccddeeff11223344.jpg";

    private static final Color GIFT_COLOR = new Color(255, 0, 17);

    private static final Color GIFT_COLOR_NEW = new Color(0, 40, 255);

    private static final Color GUARD_COLOR = new Color(0, 180, 40);

    private static final Color GUARD_COLOR_NEW = new Color(180, 0, 180);

    private static final Color OTHER = new Color(10, 10, 10);

    @TempDir
    Path temp;

    private NovaCommonPainterFactory factory;

    private FontUtil fontUtil;

    private ReportImageDiskCache cache;

    private LiveRoomInfoHistory roomInfoHistory;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties core = new NovaCoreProperties();
        core.getPaint().getFonts().add("内置");
        core.getLive().setSaveLiveData(false);
        core.getLive().setLiveDataPath(temp.resolve("data.json").toString());

        fontUtil = new FontUtil(new DefaultResourceLoader(), core);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "5.6.0");
        buildInfo.setProperty("group", "org.frostnova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), core, fontUtil);

        cache = new ReportImageDiskCache(core);
        roomInfoHistory = mock(LiveRoomInfoHistory.class);
    }

    @Test
    @DisplayName("重启后同一地址的礼物图标和大航海标志从本机读出，不再向外取")
    void restartReadsGiftAndGuardFromDisk() throws IOException {
        assertEquals(temp.resolve("image-cache"), cache.directory());
        assertEquals(Path.of("image-cache"), ReportImageDiskCache.directoryFor("data.json"));

        BilibiliApiUtil first = mock(BilibiliApiUtil.class);
        BilibiliLiveReportPainter live = painter(first);
        String giftKey = live.giftFetchUrl(GIFT_URL);
        String guardKey = live.guardFetchUrl(GUARD_URL);
        when(first.getBilibiliImage(giftKey)).thenReturn(Optional.of(solid(GIFT_COLOR)));
        when(first.getBilibiliImage(guardKey)).thenReturn(Optional.of(solid(GUARD_COLOR)));

        BufferedImage gift = live.giftIcon(GIFT_URL);
        BufferedImage guard = live.guardIcon(GUARD_URL);
        assertEquals(GIFT_COLOR.getRGB(), center(gift));
        assertEquals(GUARD_COLOR.getRGB(), center(guard));
        assertTrue(Files.isRegularFile(cache.file(giftKey)), "应按实际请求的地址留下礼物图");
        assertTrue(Files.isRegularFile(cache.file(guardKey)), "应按实际请求的地址留下大航海标志");
        assertFalse(Files.exists(cache.file(GIFT_URL)), "不带缩放后缀的原地址不该单独成一个文件");
        verify(first, times(1)).getBilibiliImage(giftKey);
        verify(first, times(1)).getBilibiliImage(guardKey);

        BilibiliApiUtil second = mock(BilibiliApiUtil.class);
        when(second.getBilibiliImage(anyString())).thenReturn(Optional.of(solid(OTHER)));
        BilibiliLiveReportPainter restarted = painter(second);

        assertEquals(center(gift), center(restarted.giftIcon(GIFT_URL)), "重启后应仍是原先那张礼物图");
        assertEquals(center(guard), center(restarted.guardIcon(GUARD_URL)), "重启后应仍是原先那张大航海标志");
        verifyNoInteractions(second);
    }

    @Test
    @DisplayName("图标换成新地址时去取新图，不继续显示旧图")
    void newAddressFetchesADifferentImage() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BilibiliLiveReportPainter live = painter(api);
        when(api.getBilibiliImage(live.giftFetchUrl(GIFT_URL))).thenReturn(Optional.of(solid(GIFT_COLOR)));
        when(api.getBilibiliImage(live.giftFetchUrl(GIFT_URL_NEW))).thenReturn(Optional.of(solid(GIFT_COLOR_NEW)));
        when(api.getBilibiliImage(live.guardFetchUrl(GUARD_URL))).thenReturn(Optional.of(solid(GUARD_COLOR)));
        when(api.getBilibiliImage(live.guardFetchUrl(GUARD_URL_NEW))).thenReturn(Optional.of(solid(GUARD_COLOR_NEW)));

        assertEquals(GIFT_COLOR.getRGB(), center(live.giftIcon(GIFT_URL)));
        assertEquals(GIFT_COLOR_NEW.getRGB(), center(live.giftIcon(GIFT_URL_NEW)));
        assertEquals(GUARD_COLOR.getRGB(), center(live.guardIcon(GUARD_URL)));
        assertEquals(GUARD_COLOR_NEW.getRGB(), center(live.guardIcon(GUARD_URL_NEW)));
        verify(api, times(1)).getBilibiliImage(live.giftFetchUrl(GIFT_URL_NEW));
        verify(api, times(1)).getBilibiliImage(live.guardFetchUrl(GUARD_URL_NEW));
    }

    @Test
    @DisplayName("取失败后本机不留下文件，之后也不会拿一份坏图来画")
    void failedFetchWritesNothing() throws IOException {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        BilibiliLiveReportPainter live = painter(api);

        assertNull(live.giftIcon(GIFT_URL));
        assertNull(live.guardIcon(GUARD_URL));
        assertNull(live.giftIcon(GIFT_URL));
        assertNull(live.guardIcon(GUARD_URL));

        assertEquals(List.of(), cacheFiles(), "取失败不该在本机留下文件");
        verify(api, times(1)).getBilibiliImage(live.giftFetchUrl(GIFT_URL));
        verify(api, times(1)).getBilibiliImage(live.guardFetchUrl(GUARD_URL));
    }

    @Test
    @DisplayName("重画命中本机已有的图就用，没有就画占位，全程不向外取")
    void replayReadsDiskAndNeverCallsOut() throws IOException {
        BilibiliApiUtil seeding = mock(BilibiliApiUtil.class);
        BilibiliLiveReportPainter live = painter(seeding);
        when(seeding.getBilibiliImage(live.giftFetchUrl(GIFT_URL))).thenReturn(Optional.of(solid(GIFT_COLOR)));
        when(seeding.getBilibiliImage(live.guardFetchUrl(GUARD_URL))).thenReturn(Optional.of(solid(GUARD_COLOR)));
        assertNotNull(live.giftIcon(GIFT_URL));
        assertNotNull(live.guardIcon(GUARD_URL));

        BilibiliApiUtil quiet = mock(BilibiliApiUtil.class);
        when(quiet.getBilibiliImage(anyString())).thenReturn(Optional.of(solid(OTHER)));
        LiveDetail detail = detailWithGift(GIFT_URL);
        BilibiliLiveReportReplayPainter replay = new BilibiliLiveReportReplayPainter(
                factory, quiet, fontUtil, new NovaBilibiliProperties(), roomInfoHistory, detail, cache);

        assertEquals(GIFT_COLOR.getRGB(), center(replay.giftIcon(GIFT_URL)));
        assertNull(replay.giftIcon(GIFT_URL_NEW), "本机没有的礼物图应画占位");
        assertEquals(GUARD_COLOR.getRGB(), center(replay.guardIcon(GUARD_URL)));
        BufferedImage guardMiss = replay.guardIcon(GUARD_URL_NEW);
        assertNotNull(guardMiss, "本机没有的大航海标志应画占位");
        assertNotEquals(GUARD_COLOR.getRGB(), center(guardMiss));
        verifyNoInteractions(quiet);

        BilibiliLiveReportRedrawer redrawer = new BilibiliLiveReportRedrawer(
                factory, quiet, fontUtil, new NovaBilibiliProperties(), roomInfoHistory, cache);
        Optional<byte[]> png = redrawer.redraw(detail);
        assertTrue(png.isPresent(), "重画应画出报告");
        BufferedImage report = ImageIO.read(new ByteArrayInputStream(png.get()));
        assertNotNull(report);
        assertTrue(contains(report, GIFT_COLOR.getRGB()), "重画应画出本机已有的礼物图标");
        verifyNoInteractions(quiet);
    }

    @Test
    @DisplayName("超过 30 天没再读到的缓存会被删掉，刚读过的和未满 30 天的留着")
    void dropsFilesUnreadForOverThirtyDays() throws IOException {
        String oldUrl = "https://img.example/old.png";
        String recentUrl = "https://img.example/recent.png";
        String rereadUrl = "https://img.example/reread.png";
        cache.store(oldUrl, solid(GIFT_COLOR));
        cache.store(recentUrl, solid(GIFT_COLOR_NEW));
        cache.store(rereadUrl, solid(GUARD_COLOR));

        Instant now = Instant.now();
        Files.setLastModifiedTime(cache.file(oldUrl), FileTime.from(now.minus(Duration.ofDays(31))));
        Files.setLastModifiedTime(cache.file(recentUrl), FileTime.from(now.minus(Duration.ofDays(29))));
        Files.setLastModifiedTime(cache.file(rereadUrl), FileTime.from(now.minus(Duration.ofDays(31))));

        Path staleTemp = cache.file(oldUrl).resolveSibling(cache.file(oldUrl).getFileName() + ".tmp-stale");
        Files.write(staleTemp, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(staleTemp, FileTime.from(now.minus(Duration.ofDays(31))));
        Path freshTemp = cache.file(recentUrl).resolveSibling(cache.file(recentUrl).getFileName() + ".tmp-fresh");
        Files.write(freshTemp, new byte[] {4, 5, 6});

        assertEquals(GUARD_COLOR.getRGB(), center(cache.read(rereadUrl).orElseThrow()));

        cache.sweep();

        assertFalse(Files.exists(cache.file(oldUrl)), "超过 30 天没再读到的应删掉");
        assertFalse(Files.exists(staleTemp), "写到一半、放超过 30 天的临时文件应删掉");
        assertTrue(Files.isRegularFile(cache.file(recentUrl)), "未满 30 天的应留着");
        assertTrue(Files.isRegularFile(cache.file(rereadUrl)), "刚读过的应留着");
        assertTrue(Files.isRegularFile(freshTemp), "刚写下的临时文件不应被清掉");
        assertEquals(GUARD_COLOR.getRGB(), center(cache.read(rereadUrl).orElseThrow()));
    }

    @Test
    @DisplayName("头像只留在内存里，不写入本机缓存")
    void avatarIsNotStoredOnDisk() throws IOException {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(solid(GIFT_COLOR)));
        BilibiliLiveReportPainter live = painter(api);

        assertNotNull(live.avatar(AVATAR_URL));
        assertEquals(List.of(), cacheFiles());
    }

    @Test
    @DisplayName("写到一半的文件不会被当成已经缓存的图")
    void halfWrittenFileIsNotRead() throws IOException {
        Files.createDirectories(cache.directory());
        Path target = cache.file(GIFT_URL);
        Path partial = target.resolveSibling(target.getFileName() + ".tmp-half");
        Files.write(partial, new byte[] {0, 1, 2, 3, 4});
        assertTrue(cache.read(GIFT_URL).isEmpty(), "只有临时文件时不应读出一张图");

        Files.write(target, new byte[] {9, 8, 7});
        assertTrue(cache.read(GIFT_URL).isEmpty(), "没写完的正式文件不应被当成图");
    }

    private BilibiliLiveReportPainter painter(BilibiliApiUtil api) {
        return new BilibiliLiveReportPainter(factory, api, mock(LiveDataService.class), fontUtil,
                new NovaBilibiliProperties(), roomInfoHistory, cache);
    }

    private static LiveDetail detailWithGift(String url) {
        return new LiveDetail(LiveDetail.VERSION, "bilibili", 19604318752096L, "主播甲", 47615208934771L,
                1_700_000_000_000L, 1_700_003_600_000L, 3600,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), Map.of(),
                List.of(new LiveGiftTotal(1L, "小电视", 1245, 1, url)));
    }

    private List<Path> cacheFiles() throws IOException {
        if (!Files.isDirectory(cache.directory())) {
            return List.of();
        }
        try (var listed = Files.list(cache.directory())) {
            return listed.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 16, 16);
        graphics.dispose();
        return image;
    }

    private static int center(BufferedImage image) {
        assertNotNull(image);
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    private static boolean contains(BufferedImage image, int rgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }
}
