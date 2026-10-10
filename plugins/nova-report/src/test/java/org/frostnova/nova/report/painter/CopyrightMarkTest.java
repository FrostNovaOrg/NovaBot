package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.data.util.Pair;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.image.BufferedImage;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版权行左边的 NovaBot 图标
 * <p>
 * 两把尺：一把量排版算出来的整行宽度，一把数成图上的像素。前者答「算的时候放不放得下」，
 * 后者答「画出来有没有越过左边距、图标在不在」——算的和画的走的是两条路，各量各的。
 */
@DisplayName("版权行左边的 NovaBot 图标")
class CopyrightMarkTest {

    /** 出图里最窄的一种：数据命令与排行榜那张，760 宽、左右边距 35 */
    private static final int NARROWEST_WIDTH = 760;

    private static final int MARGIN = 35;

    /** 眼下发布的版本号 */
    private static final String CURRENT_VERSION = "5.8.1";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("🔴 最窄那种图上，眼下这一版的图标加字整行不越过左边距")
    void lineFitsInsideTheNarrowestImage() {
        CommonPainter painter = painter(CURRENT_VERSION, NARROWEST_WIDTH);
        Pair<Integer, Integer> line = painter.copyrightLineWidthAndMarkSize();
        int left = NARROWEST_WIDTH - MARGIN - line.getFirst();
        System.out.println("版本 " + CURRENT_VERSION + "：整行宽 " + line.getFirst() + "，图标边长 " + line.getSecond()
                + "，左沿 x=" + left + "，左边距 " + MARGIN);
        assertTrue(left >= MARGIN, "版权行越过左边距：左沿 x=" + left);
    }

    @Test
    @DisplayName("🔴 成图上：字的左边有图标，高不超过字高，左边距以内没有一个可见像素")
    void markIsPaintedLeftOfTheTextAndInsideTheMargin() {
        Reading reading = read(CURRENT_VERSION);
        System.out.println("版本 " + CURRENT_VERSION + "：" + reading);

        assertEquals(0, reading.strayLeft, "左边距以内画上了东西");
        assertTrue(reading.colored > reading.markSize * reading.markSize / 4, "图标那一格几乎没有有色像素：" + reading);
        assertTrue(reading.minY >= TOP && reading.maxY < TOP + reading.markSize, "图标高出了这一行：" + reading);
    }

    @Test
    @DisplayName("🔴 版本号长到放不下图标时只画字，不越过左边距")
    void markIsDroppedWhenItWouldCrossTheMargin() {
        // 这一串比眼下的版本号多三个字，量过放不下图标；先证它真放不下，下面的「没画」才有意义
        String longVersion = "10.10.10";
        Pair<Integer, Integer> line = painter(longVersion, NARROWEST_WIDTH).copyrightLineWidthAndMarkSize();
        assertTrue(NARROWEST_WIDTH - MARGIN - line.getFirst() < MARGIN, "这串版本号放得下图标，量不到退让那一支");

        Reading reading = read(longVersion);
        System.out.println("版本 " + longVersion + "：" + reading);
        assertEquals(0, reading.strayLeft, "左边距以内画上了东西");
        assertEquals(0, reading.colored, "放不下还是画了图标");
    }

    /** 版权行上沿 */
    private static final int TOP = 10;

    /**
     * 一张图的读数：图标格里的有色像素与其纵向范围，左边距内的可见像素
     */
    private record Reading(int markLeft, int markSize, int colored, int minY, int maxY, int strayLeft) {
    }

    private static Reading read(String version) {
        CommonPainter painter = painter(version, NARROWEST_WIDTH);
        painter.setPos(MARGIN, TOP);
        Pair<Integer, Integer> line = painter.copyrightLineWidthAndMarkSize();
        int markSize = line.getSecond();
        // 放不下时图标格挪到边距以内去数：那一支不该在任何地方画出有色像素
        int markLeft = Math.max(0, NARROWEST_WIDTH - MARGIN - line.getFirst());
        BufferedImage image = painter.drawCopyright(MARGIN).getImage();

        int strayLeft = 0;
        int colored = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                if (x < MARGIN) {
                    strayLeft++;
                }
                // 图标那一块：落在图标的格子里，且是有色的（字是浅灰，三通道相等）
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (x >= markLeft && x < markLeft + markSize
                        && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40) {
                    colored++;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new Reading(markLeft, markSize, colored, minY, maxY, strayLeft);
    }

    private static CommonPainter painter(String version, int width) {
        NovaCoreProperties properties = new NovaCoreProperties();
        // 用内置字体，免得结论取决于跑测试这台机器装了什么字体
        properties.getPaint().getFonts().add("内置");
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", version);
        buildInfo.setProperty("name", "NovaBot");
        return new CommonPainter(new BuildProperties(buildInfo), properties, fontUtil, width, 200, false);
    }
}
