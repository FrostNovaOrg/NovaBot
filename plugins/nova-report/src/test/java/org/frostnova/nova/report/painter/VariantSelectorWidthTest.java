package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告图里的变体选择符不占宽度、不出墨
 * <p>
 * 使用者故障：❤️ 里跟在心形后面的变体选择符 U+FE0F 只有表情字体认，认得它的那款字体把它画成一个满宽的空字形——
 * 榜单行里心与 👍 之间空出一个表情宽（39px，别处 6px），看着像漏了字；词云里同样。
 * 配的字体表里没有认得这个码位的字体时，它落回表里第一个，画成一个豆腐块。
 * <p>
 * 变体选择符（U+FE00–U+FE0F，补充平面 U+E0100–U+E01EF）只指定前一个字用哪种字形，
 * 自己不占位置：量宽与落笔都该跳过它。
 * <p>
 * 只装「内置」一份字体：它不认变体选择符，那个码位会落回表里第一个画成豆腐块，正是出故障的那张表。
 * 系统字体装没装取决于跑测试的机器。
 */
@DisplayName("变体选择符不占宽也不出墨")
class VariantSelectorWidthTest {

    /** 心形 U+2764 后跟变体选择符 U+FE0F，再一个翘拇指 U+1F44D，写作「❤️👍」 */
    private static final String WITH_SELECTOR = "\u2764\uFE0F\uD83D\uDC4D";

    /** 同一行字，只去掉那个变体选择符，写作「❤👍」 */
    private static final String WITHOUT_SELECTOR = "\u2764\uD83D\uDC4D";

    private static final int SIZE = 30;

    private static final int WIDTH = 480;

    private static final int HEIGHT = 200;

    private static final int LEFT = 20;

    private static final int TOP = 40;

    /** 不透明度到这个值才算笔画 */
    private static final int INK_ALPHA = 128;

    private NovaCoreProperties properties;

    private FontUtil fontUtil;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();
    }

    @Test
    @DisplayName("量宽：「❤️👍」与「❤👍」一样宽")
    void widthIgnoresVariantSelector() {
        int with = width(WITH_SELECTOR);
        int without = width(WITHOUT_SELECTOR);

        assertTrue(without > 0, "锚: 「❤👍」量到 0px, 这把尺没在量真宽度");
        assertEquals(without, with,
                "带不带变体选择符该一样宽: 「❤️👍」" + with + "px, 「❤👍」" + without + "px, 多出来的就是它占的位");
    }

    @Test
    @DisplayName("画一行文字：变体选择符不出墨，也不把后面的字推开")
    void textLineLeavesNoInkOnVariantSelector() {
        assertSamePixels(paintLine(WITHOUT_SELECTOR), paintLine(WITH_SELECTOR), "画一行文字");
    }

    @Test
    @DisplayName("逐字画字符：变体选择符不出墨，也不把后面的字推开")
    void elementLeavesNoInkOnVariantSelector() {
        assertSamePixels(paintElements(WITHOUT_SELECTOR), paintElements(WITH_SELECTOR), "逐字画字符");
    }

    @Test
    @DisplayName("词云：摆开的宽度不给变体选择符留位")
    void wordCloudWidthIgnoresVariantSelector() {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);
        Dimension with = renderer.measure(WITH_SELECTOR, SIZE);
        Dimension without = renderer.measure(WITHOUT_SELECTOR, SIZE);

        assertTrue(without.width > 0, "锚: 「❤👍」在词云里摆开量到 0px 宽, 这把尺没在量真宽度");
        assertEquals(without.width, with.width,
                "词云摆开时带不带变体选择符该一样宽: 「❤️👍」" + with.width + "px, 「❤👍」" + without.width + "px");
    }

    private int width(String text) {
        BufferedImage canvas = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = canvas.createGraphics();
        try {
            return fontUtil.getStringWidthAndHeight(draw, new TextWithStyle(text, SIZE, Color.BLACK, Font.PLAIN)).getFirst();
        } finally {
            draw.dispose();
        }
    }

    private BufferedImage paintLine(String text) {
        CommonPainter painter = painter();
        painter.drawTextWithStyle(List.of(new TextWithStyle(text, SIZE, Color.BLACK, Font.PLAIN)), new Point(LEFT, TOP), false, 0);
        return painter.getImage();
    }

    private BufferedImage paintElements(String text) {
        CommonPainter painter = painter();
        painter.setPos(LEFT, TOP);
        for (int codePoint : text.codePoints().toArray()) {
            painter.drawElement(codePoint, Integer.valueOf(SIZE));
        }
        return painter.getImage();
    }

    private CommonPainter painter() {
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "0.0.0");
        return new CommonPainter(new BuildProperties(buildInfo), properties, fontUtil, WIDTH, HEIGHT, false);
    }

    /**
     * 两张画布逐像素相同：变体选择符不占位也不出墨时，带它与不带它画出来的该是同一张图。
     * 它一出墨就多一块笔画，一占位就把后面的字推走，两种都看得见。
     * 先看不带它的那张有没有笔画——两张都空白时「一样」什么也说明不了
     */
    private static void assertSamePixels(BufferedImage expected, BufferedImage actual, String how) {
        assertTrue(hasInk(expected), "锚: " + how + "后「❤👍」整张画布没笔画, 比不出差别");

        List<String> differences = new ArrayList<>();
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y) && differences.size() < 5) {
                    differences.add("(" + x + "," + y + ")");
                }
            }
        }
        assertTrue(differences.isEmpty(), how + "时变体选择符那处出了墨或把字推开了, 头几处不同的像素: "
                + String.join("、", differences));
    }

    private static boolean hasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) >= INK_ALPHA) {
                    return true;
                }
            }
        }
        return false;
    }
}
