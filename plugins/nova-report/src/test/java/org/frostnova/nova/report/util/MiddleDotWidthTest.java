package org.frostnova.nova.report.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告里的间隔号「·」按西文宽度画
 * <p>
 * 用户故障：「弹幕 · 128 人参与」这类写法，中文正文字体把「·」画成一个汉字宽，
 * 两侧再各一个空格，分隔处空出一大截。
 * <p>
 * 只装随程序发布的字体：系统字体装没装取决于跑测试的机器。
 * 只有「内置」的那张表也要量——Windows、macOS 的默认表里没有「内置符号」。
 */
@DisplayName("间隔号按西文宽度画")
class MiddleDotWidthTest {
    private static final int SIZE = 30;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("间隔号不到汉字宽度的一半，不比西文字母宽")
    void middleDotIsNarrow() {
        List<String> failures = new ArrayList<>();
        for (List<String> chain : List.of(List.of("内置"), List.of("内置", "内置表情", "内置符号"))) {
            FontUtil util = load(chain);
            int dot = width(util, "·");
            int han = width(util, "中");
            int latin = width(util, "a");
            assertTrue(han > latin && latin > 0, "锚: 汉字该比西文字母宽, 量到 " + han + " 与 " + latin + ", 这把尺没在量真宽度");
            if (dot * 2 >= han || dot > latin) {
                failures.add(chain + ": 「·」" + dot + "px, 汉字 " + han + "px, 字母 a " + latin + "px");
            }
        }

        assertTrue(failures.isEmpty(), "间隔号仍按汉字宽度画:\n" + String.join("\n", failures));
    }

    private static FontUtil load(List<String> chain) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(chain);
        FontUtil util = new FontUtil(new DefaultResourceLoader(), properties);
        util.init();
        return util;
    }

    private static int width(FontUtil util, String text) {
        BufferedImage canvas = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = canvas.createGraphics();
        try {
            return util.getStringWidthAndHeight(draw, new TextWithStyle(text, SIZE, Color.BLACK, Font.PLAIN)).getFirst();
        } finally {
            draw.dispose();
        }
    }
}
