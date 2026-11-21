package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.assistant.AssistantChartRenderer.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliAssistantChartPainterTest {
    private static BilibiliAssistantChartPainter painter;

    @BeforeAll
    static void setUp() {
        System.setProperty("java.awt.headless", "true");
        var properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("Dialog");
        var fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        var factory = new NovaCommonPainterFactory(new BuildProperties(new Properties()), properties, fonts);
        painter = new BilibiliAssistantChartPainter(factory, fonts);
    }

    @Test
    @DisplayName("即使系统绘图字体配置不同，图表的每个中文字仍由内置中文字体显示")
    void bundledFontDisplaysEveryChineseTitleCharacter() throws Exception {
        String title = "近十场直播流水与观众互动";
        var font = painter.chineseFont();
        assertNotNull(font);
        assertTrue(font.getFamily(java.util.Locale.ROOT).contains("Noto Sans SC"), font::getFamily);
        title.codePoints().forEach(cp -> assertTrue(font.canDisplay(cp), () -> Character.toString(cp)));
        var image = render(new Chart(Type.LINE, title, "时间", "人数", List.of("观众"),
                List.of(row("昨晚", 7), row("今晚", 30)), new ReferenceLine(BigDecimal.valueOf(20), "目标"), "合成归档", 2));
        assertTrue(darkPixels(image, 35, 500, 39, 85) > 200, "实际标题区域中应有可见文字");
        assertTrue(darkPixels(image, 590, 727, 522, 550) > 100, "出图标注应在图片内部");
    }

    @Test
    @DisplayName("柱形像素高度必须按系统收到的数值成比例，表格导出固定幅面")
    void pngBarsUseRecordedValuesAndTableKeepsFixedCanvas() throws Exception {
        var rows = List.of(row("首场", 10), row("次场", 30));
        var bar = render(new Chart(Type.BAR, "近两场流水", "场次", "金额（元）", List.of("流水"), rows, null, "合成归档", 2));
        int first = band(bar, 114, 400, ReportPalette.DEFAULT.curveOnline());
        int second = band(bar, 410, 705, ReportPalette.DEFAULT.curveOnline());
        assertTrue(first > 0);
        assertEquals(3.0, (double) second / first, 0.05);
        assertEquals(760, bar.getWidth());
        var table = render(new Chart(Type.TABLE, "近两场流水", "场次", "金额（元）", List.of("流水"), rows, null, "合成归档", 2));
        assertEquals(bar.getWidth(), table.getWidth());
        assertEquals(816, table.getHeight());
        assertTrue(darkPixels(table, 590, 727, 778, 806) > 100, "表格同样保留生成标注");
    }

    private static BufferedImage render(Chart chart) throws Exception {
        var bytes = Base64.getDecoder().decode(painter.render(chart).orElseThrow());
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static Row row(String label, long value) {
        return new Row(label, List.of(new Value(Long.toString(value), BigDecimal.valueOf(value))));
    }

    private static int band(BufferedImage image, int left, int right, Color color) {
        return IntStream.range(left, right).map(x -> (int) IntStream.range(155, 443)
                .filter(y -> image.getRGB(x, y) == color.getRGB()).count()).max().orElse(0);
    }

    private static long darkPixels(BufferedImage image, int left, int right, int top, int bottom) {
        return IntStream.range(left, right).mapToLong(x -> IntStream.range(top, bottom)
                .filter(y -> new Color(image.getRGB(x, y)).getRed() < 150).count()).sum();
    }
}
