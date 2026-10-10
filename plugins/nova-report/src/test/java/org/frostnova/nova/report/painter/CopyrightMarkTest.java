package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
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
        CommonPainter.CopyrightLine line = painter.copyrightLineLayout();
        int left = NARROWEST_WIDTH - MARGIN - line.width();
        System.out.println("版本 " + CURRENT_VERSION + "：整行宽 " + line.width() + "，" + line
                + "，左沿 x=" + left + "，左边距 " + MARGIN);
        assertTrue(left >= MARGIN, "版权行越过左边距：左沿 x=" + left);
    }

    @Test
    @DisplayName("🔴 成图上：字的左边有图标，左边距以内没有一个可见像素")
    void markIsPaintedLeftOfTheTextAndInsideTheMargin() {
        Reading reading = read(CURRENT_VERSION);
        System.out.println("版本 " + CURRENT_VERSION + "：" + reading);

        assertEquals(0, reading.strayLeft, "左边距以内画上了东西");
        assertTrue(reading.colored > 0 && reading.markRight < reading.nLeft, "字的左边没有图标：" + reading);
    }

    /**
     * 抓的故障：页脚图标比字大一截、偏上。
     * <p>
     * 气泡（不含尾巴）上沿对 N 顶、下沿对基线；尾巴从气泡下沿伸到 viewBox 的 55.9，
     * 所以有色外框高是 N 高 × (55.9 − 4) / (46 − 4)。两边都从成图的像素量，不读排版算出来的数
     */
    @Test
    @DisplayName("🔴 成图上：图标上沿对齐 N 顶，外框高是 N 高 × 51.9/42")
    void markIsSizedAndAlignedToTheCapitalN() {
        Reading reading = read(CURRENT_VERSION);
        System.out.println("版本 " + CURRENT_VERSION + "：" + reading);

        int markHeight = reading.maxY - reading.minY + 1;
        double expected = reading.nHeight() * 51.9 / 42;
        StringBuilder reds = new StringBuilder();
        if (Math.abs(reading.minY - reading.nTop) > 1) {
            reds.append("图标上沿 y=").append(reading.minY).append("，N 上沿 y=").append(reading.nTop).append("；");
        }
        if (Math.abs(markHeight - expected) > 1) {
            reds.append("图标外框高 ").append(markHeight).append("，应为 N 高 ").append(reading.nHeight())
                    .append(" × 51.9/42 = ").append(String.format("%.1f", expected)).append("；");
        }
        assertTrue(reds.isEmpty(), reds.toString());
    }

    @Test
    @DisplayName("🔴 版本号长到放不下图标时只画字，不越过左边距")
    void markIsDroppedWhenItWouldCrossTheMargin() {
        // 这一串比眼下的版本号多四个字，量过放不下图标（多三个字的 10.10.10 还放得下）；
        // 先证它真放不下，下面的「没画」才有意义
        String longVersion = "10.10.100";
        CommonPainter.CopyrightLine line = painter(longVersion, NARROWEST_WIDTH).copyrightLineLayout();
        assertTrue(NARROWEST_WIDTH - MARGIN - line.width() < MARGIN, "这串版本号放得下图标，量不到退让那一支");

        Reading reading = read(longVersion);
        System.out.println("版本 " + longVersion + "：" + reading);
        assertEquals(0, reading.strayLeft, "左边距以内画上了东西");
        assertEquals(0, reading.colored, "放不下还是画了图标");
    }

    /** 版权行上沿 */
    private static final int TOP = 10;

    /**
     * 一张图的读数：图标（有色像素）的外框，N 字迹（字的最左一笔）的上沿与下沿，左边距内的可见像素
     * <p>
     * N 是这一行的头一个字，它左边那一竖从顶到基线：取字迹最左的三列量上下沿，
     * 不会碰上后面 o 的圆肚子越过基线的那一点
     */
    private record Reading(int colored, int markRight, int minY, int maxY, int nLeft, int nTop, int nBottom, int strayLeft) {
        int nHeight() {
            return nBottom - nTop + 1;
        }
    }

    private static Reading read(String version) {
        CommonPainter painter = painter(version, NARROWEST_WIDTH);
        painter.setPos(MARGIN, TOP);
        BufferedImage image = painter.drawCopyright(MARGIN).getImage();

        int strayLeft = 0;
        int colored = 0;
        int markRight = -1;
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
                if (isColored(argb)) {
                    colored++;
                    markRight = Math.max(markRight, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        // 字是浅灰，三通道相等；星是白色但落在图标外框里，从图标右沿往右找就碰不到
        int nLeft = -1;
        for (int x = markRight + 1; x < image.getWidth() && nLeft < 0; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isGray(image.getRGB(x, y))) {
                    nLeft = x;
                    break;
                }
            }
        }
        int nTop = Integer.MAX_VALUE;
        int nBottom = -1;
        for (int x = Math.max(nLeft, 0); x < nLeft + 3; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isGray(image.getRGB(x, y))) {
                    nTop = Math.min(nTop, y);
                    nBottom = Math.max(nBottom, y);
                }
            }
        }
        return new Reading(colored, markRight, minY, maxY, nLeft, nTop, nBottom, strayLeft);
    }

    private static boolean isColored(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return ((argb >>> 24) & 0xFF) != 0 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40;
    }

    private static boolean isGray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return ((argb >>> 24) & 0xFF) != 0 && r == g && g == b;
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
