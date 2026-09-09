package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折线原语：描边、不填充
 */
@DisplayName("折线原语")
class CommonPainterPolylineTest {
    private CommonPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        painter = new NovaCommonPainterFactory(new BuildProperties(buildInfo), properties, fontUtil)
                .create(200, 120, false);
    }

    @Test
    @DisplayName("水平折线只占约笔宽，不向下填满")
    void horizontalLineIsAStrokeNotAFill() {
        Color ink = new Color(20, 140, 80);
        painter.drawPolyline(List.of(new Point(10, 40), new Point(190, 40)), ink, 3);

        BufferedImage image = painter.getImage();
        int run = 0;
        int longest = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if (isColor(image, 100, y, ink)) {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }

        assertTrue(longest >= 1 && longest <= 6, "笔宽约 3 像素, 实测连续 " + longest);
        assertFalse(isColor(image, 100, 80, ink), "折线下方不应被填充");
    }

    @Test
    @DisplayName("折线经过顶点但不填充顶点围成的区域")
    void strokesTheLineAndDoesNotFillTheInterior() {
        Color red = new Color(220, 40, 40);
        painter.drawPolyline(List.of(new Point(20, 80), new Point(100, 20), new Point(180, 80)), red, 3);

        BufferedImage image = painter.getImage();
        assertTrue(near(image, 100, 20, red), "折线应经过峰值顶点");
        assertFalse(isColor(image, 100, 55, red), "折线围成的内部不应被填充");
    }

    @Test
    @DisplayName("少于两个点时不画、也不抛")
    void ignoresShortPointLists() {
        Color ink = new Color(10, 10, 10);
        assertSame(painter, painter.drawPolyline(List.of(), ink, 3));
        assertSame(painter, painter.drawPolyline(List.of(new Point(10, 10)), ink, 3));
        assertFalse(isColor(painter.getImage(), 10, 10, ink));
    }

    private static boolean isColor(BufferedImage image, int x, int y, Color color) {
        return (image.getRGB(x, y) & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF);
    }

    private static boolean near(BufferedImage image, int x, int y, Color color) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < image.getWidth() && ny < image.getHeight()
                        && isColor(image, nx, ny, color)) {
                    return true;
                }
            }
        }
        return false;
    }
}
