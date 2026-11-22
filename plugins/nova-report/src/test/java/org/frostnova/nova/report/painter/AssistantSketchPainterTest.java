package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.assistant.AssistantChartRenderer.Sketch;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.SketchRejected;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AssistantSketchPainterTest {
    private static final FontUtil FONTS = new FontUtil(new DefaultResourceLoader(), new NovaCoreProperties());
    @TempDir Path directory;

    @Test
    @DisplayName("手绘输出固定幅面的 PNG，且底部实际画出了两行生成标注")
    void pngContainsBothNotices() throws Exception {
        var image = render("<rect x='80' y='50' width='180' height='120' fill='#4c74be'/>");
        assertEquals(760, image.getWidth());
        assertEquals(560, image.getHeight());
        assertEquals(0xff4c74be, image.getRGB(150, 150));

        var expected = new BufferedImage(760, 560, BufferedImage.TYPE_INT_RGB);
        var g = expected.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 760, 560);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(ReportPalette.DEFAULT.tip().darker());
            g.setFont(Font.createFont(Font.TRUETYPE_FONT, FONTS.bundledChineseFontFile().toFile()).deriveFont(14f));
            g.drawString("助手手绘，仅示意", 35, 525);
            g.drawString("由人工智能生成", 35, 546);
        } finally {
            g.dispose();
        }
        assertArrayEquals(ink(expected, 35, 509, 110, 19), ink(image, 35, 509, 110, 19));
        assertArrayEquals(ink(expected, 35, 532, 108, 17), ink(image, 35, 532, 108, 17));
    }

    @Test
    @DisplayName("机器没有模型指定的字体时，常用汉字仍按内置字体绘制且不会变成同样的方块")
    void missingModelFontStillDrawsDistinctChineseGlyphs() throws Exception {
        String labels = "<text x='50' y='100' font-size='42' font-family='%s'>礼</text>"
                + "<text x='120' y='100' font-size='42' font-family='%s'>物</text>";
        var missing = render(labels.formatted("Nonexistent Example Font", "Nonexistent Example Font"));
        var generic = render(labels.formatted("Monospaced", "Monospaced"));
        assertArrayEquals(ink(generic, 80, 135, 125, 49), ink(missing, 80, 135, 125, 49));
        byte[] first = ink(missing, 85, 135, 44, 49);
        byte[] second = ink(missing, 155, 135, 44, 49);
        assertFalse(java.util.Arrays.equals(first, second), "礼和物应有不同的字形");
        assertTrue(java.util.stream.IntStream.range(0, first.length).filter(i -> first[i] != 0).count() > 150);
        assertTrue(java.util.stream.IntStream.range(0, second.length).filter(i -> second[i] != 0).count() > 150);
    }

    @Test
    @DisplayName("正文完全透明、与背景同色或在画布外时，标题和标注不能把空图伪装成成功")
    void blankBodyDoesNotPassBecauseItHasATitle() {
        for (String body : new String[]{
                "<rect x='30' y='30' width='400' height='70' fill='#ffffff'/>",
                "<rect x='30' y='30' width='400' height='70' fill-opacity='0'/>",
                "<circle cx='4000' cy='4000' r='20'/>"}) {
            assertTrue(assertThrows(SketchRejected.class, () -> render(body)).getMessage().contains("没有可见内容"));
        }
    }

    @Test
    @DisplayName("即便绕过图元入口过滤，JSVG 的资源设置也不加载本机或内嵌图片")
    void jsvgDoesNotLoadLocalOrEmbeddedImages() throws Exception {
        var icon = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Path png = directory.resolve("fixture.png");
        ImageIO.write(icon, "png", png.toFile());
        String embedded = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(png));
        for (String uri : new String[]{png.toUri().toASCIIString(), embedded}) {
            String body = "<image x='100' y='20' width='80' height='80' href='" + uri + "'/>";
            assertTrue(assertThrows(SketchRejected.class, () -> render(body)).getMessage().contains("没有可见内容"));
        }
    }

    private static BufferedImage render(String body) throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 690 416'>" + body + "</svg>";
        byte[] png = Base64.getDecoder().decode(AssistantSketchPainter.render(new Sketch("直播示意", svg), FONTS).orElseThrow());
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static byte[] ink(BufferedImage image, int left, int top, int width, int height) {
        var ink = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ink[y * width + x] = (byte) (((image.getRGB(left + x, top + y) >> 16 & 255) < 220) ? 1 : 0);
            }
        }
        return ink;
    }
}
