package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GuardIconRenderingTest {
    private static final String MEDAL_URL = "https://img.example/medal.png";
    @TempDir Path temp;
    private ReportImageDiskCache disk;
    private BilibiliApiUtil api;
    private BilibiliLiveReportPainter live;

    @BeforeEach
    void setUp() {
        disk = new ReportImageDiskCache(temp);
        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(solid(Color.GREEN)));
        live = painter();
    }

    @ParameterizedTest(name = "礼物格 {0} 级请求新的原图 PNG")
    @CsvSource({
            "1, 1d16bf0fcc3b1b768d1179d60f1fdbabe6ab4489",
            "2, 98a201c14a64e860a758f089144dcf3f42e7038c",
            "3, 143f5ec3003b4080d1b5f817a9efdca46d631945"
    })
    void giftCellFetchesOriginalBadge(int level, String hash) throws Exception {
        Class<?> cell = Class.forName(BilibiliLiveReportPainter.class.getName() + "$GuardTierCell");
        var constructor = cell.getDeclaredConstructor(int.class, String.class, long.class);
        constructor.setAccessible(true);
        var row = BilibiliLiveReportPainter.class.getDeclaredMethod("drawGuardTierRow", CommonPainter.class, List.class, int.class);
        row.setAccessible(true);
        CommonPainter canvas = mock(CommonPainter.class, RETURNS_DEEP_STUBS);
        row.invoke(live, canvas, List.of(constructor.newInstance(level, "徽章", 1L)), 0);
        verify(api).getBilibiliImage("https://i0.hdslb.com/bfs/live/" + hash + ".png");
        verifyNoMoreInteractions(api);
    }

    @Test
    @DisplayName("透明四角的徽章放大后保留贴边尖角")
    void transparentBadgeKeepsPoint() {
        BufferedImage source = new BufferedImage(66, 66, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.RED);
        g.fillPolygon(new int[]{0, 30, 30}, new int[]{8, 18, 40}, 3);
        g.dispose();
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(source));
        BufferedImage result = live.guardIcon(MEDAL_URL, 96);
        assertTrue((result.getRGB(9, 18) >>> 24) > 200, "贴边尖角不能被圆形裁掉");
        assertEquals(0, result.getRGB(95, 95) >>> 24, "透明背景应保留");
    }

    @ParameterizedTest(name = "{0}px 方图圆边有半透明过渡")
    @ValueSource(ints = {32, 96})
    void opaqueSquareHasSoftCircleEdge(int size) {
        BufferedImage result = live.guardIcon(MEDAL_URL, size);
        assertEquals(0, result.getRGB(0, 0) >>> 24);
        assertEquals(Color.GREEN.getRGB(), result.getRGB(size / 2, size / 2));
        boolean partial = false;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int alpha = result.getRGB(x, y) >>> 24;
                partial |= alpha > 0 && alpha < 255;
            }
        }
        assertTrue(partial, "圆边应有半透明像素");
    }

    @Test
    @DisplayName("名单徽章缩小后细纹不会变成明暗条带")
    void downsamplingAveragesFineStripes() {
        BufferedImage source = solid(Color.BLACK);
        for (int x = 1; x < 66; x += 2) {
            for (int y = 0; y < 66; y++) {
                source.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(source));
        BufferedImage result = live.guardIcon(MEDAL_URL, 32);
        for (int x = 8; x < 24; x++) {
            int gray = result.getRGB(x, 16) & 255;
            assertTrue(gray >= 120 && gray <= 136, "等宽黑白细纹缩小应接近中灰，实际 " + gray);
        }
    }

    @ParameterizedTest(name = "{0}px 下播报告丢弃旧画法成品")
    @ValueSource(ints = {32, 96})
    void liveIgnoresOldFinishedImage(int size) {
        disk.store(MEDAL_URL + "@" + size + "w.webp", solid(Color.RED));
        BufferedImage result = live.guardIcon(MEDAL_URL, size);
        assertEquals(Color.GREEN.getRGB(), result.getRGB(size / 2, size / 2), "下播报告必须重新取图重画");
        verify(api).getBilibiliImage(MEDAL_URL);
        assertEquals(size, painter().guardIcon(MEDAL_URL, size).getWidth(), "重启后使用新成品");
        verifyNoMoreInteractions(api);
    }

    @Test
    @DisplayName("32 与 96 像素成品分别缓存，重启后尺寸不串")
    void finishedSizesStaySeparate() {
        live.guardIcon(MEDAL_URL, 32);
        live.guardIcon(MEDAL_URL, 96);
        var restarted = painter();
        assertEquals(32, restarted.guardIcon(MEDAL_URL, 32).getWidth());
        assertEquals(96, restarted.guardIcon(MEDAL_URL, 96).getWidth());
        verify(api, times(2)).getBilibiliImage(MEDAL_URL);
    }

    @ParameterizedTest(name = "单场报告兼容 {0} 的旧成品")
    @CsvSource({
            "1d16bf0fcc3b1b768d1179d60f1fdbabe6ab4489, 0d2b29717af2e7b1bbdc21a4fba8619636f82517",
            "98a201c14a64e860a758f089144dcf3f42e7038c, 405bffdfd78bb562e0394dd828f8bf69ea01f400",
            "143f5ec3003b4080d1b5f817a9efdca46d631945, 00749d246e2b49b2328cb981de02142fb6aeceba",
            "medal, medal"
    })
    void replayFallsBackToOldBadge(String current, String previous) {
        String currentUrl = current.equals("medal") ? MEDAL_URL : "https://i0.hdslb.com/bfs/live/" + current + ".png";
        String oldUrl = previous.equals("medal") ? MEDAL_URL : "https://i0.hdslb.com/bfs/live/" + previous + ".png";
        for (int size : new int[]{32, 96}) {
            disk.store(oldUrl + "@" + size + "w.webp", solid(Color.RED));
            BufferedImage result = size == 32 ? replay().guardIcon(currentUrl) : replay().guardIcon(currentUrl, size);
            assertEquals(Color.RED.getRGB(), result.getRGB(8, 8), "单场报告应读取换地址前的旧成品");
        }
        verifyNoInteractions(api);
    }

    @ParameterizedTest(name = "{0}px 单场报告优先新画法成品")
    @ValueSource(ints = {32, 96})
    void replayPrefersNewFinishedImage(int size) {
        disk.store(MEDAL_URL + "@" + size + "w.webp", solid(Color.RED));
        live.guardIcon(MEDAL_URL, size);
        clearInvocations(api);
        BufferedImage result = size == 32 ? replay().guardIcon(MEDAL_URL) : replay().guardIcon(MEDAL_URL, size);
        assertEquals(Color.GREEN.getRGB(), result.getRGB(size / 2, size / 2), "单场报告应优先新成品");
        verifyNoInteractions(api);
    }

    private BilibiliLiveReportPainter painter() {
        return new BilibiliLiveReportPainter(mock(NovaCommonPainterFactory.class), api, mock(LiveDataService.class),
                mock(FontUtil.class), new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class), disk);
    }

    private BilibiliLiveReportReplayPainter replay() {
        LiveDetail detail = mock(LiveDetail.class);
        when(detail.platform()).thenReturn("bilibili");
        when(detail.uid()).thenReturn(10001L);
        return new BilibiliLiveReportReplayPainter(mock(NovaCommonPainterFactory.class), api, mock(FontUtil.class),
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class), detail, disk);
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(66, 66, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 66, 66);
        g.dispose();
        return image;
    }
}
