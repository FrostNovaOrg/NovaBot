package org.frostnova.nova.report.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linux 默认字体表上的挑字
 * <p>
 * 挑字只问字体「显示得出这个字吗」。Java 画不出彩色表情字体，可它照样答「显示得出」，
 * 于是它排在谁前面，谁就轮不到——服务器上的报告图里，表情就是这样变成一片空白的。
 * 默认表因此把随程序发布的单色表情字体排在所有系统字体之前，这组测试按那张表的顺序装字体，
 * 看中文、西文、表情各落到哪一份，表情画出来有没有笔画。
 * <p>
 * 只装表里随程序发布的那几项：系统字体装没装取决于跑测试的机器，拿它们作参照物，结论换台机器就不成立。
 */
@DisplayName("Linux 默认字体表上的挑字")
class DefaultFontChainTest {
    /** 配置里的写法 → 装上之后应当是哪一款字体 */
    private static final Map<String, String> FAMILY_BY_WORD = new LinkedHashMap<>();

    static {
        FAMILY_BY_WORD.put("内置", "Noto Sans SC");
        FAMILY_BY_WORD.put("内置表情", "Noto Emoji");
        FAMILY_BY_WORD.put("内置符号", "DejaVu Sans");
    }

    /** 画表情用的字号，与报告图正文同一量级 */
    private static final float SIZE = 48f;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 按 Linux 默认表的顺序，只装随程序发布的那几项
     */
    private static FontUtil bundledPartOfLinuxDefaults() {
        List<String> chain = NovaCoreProperties.defaultFonts("Linux").stream()
                .filter(FAMILY_BY_WORD::containsKey)
                .toList();
        assertEquals(FAMILY_BY_WORD.keySet(), Set.copyOf(chain),
                "锚: Linux 默认表里该有随程序发布的三项, 取到的是 " + chain + ", 下面挑出来的结果说明不了默认表");

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(chain);
        FontUtil util = new FontUtil(new DefaultResourceLoader(), properties);
        util.init();
        return util;
    }

    private record Expectation(int codePoint, String word, String why) {
    }

    @Test
    @DisplayName("中文、西文字母、昵称装饰字、表情各落到该落的那一份")
    void eachCharacterLandsOnTheExpectedFont() {
        FontUtil util = bundledPartOfLinuxDefaults();
        List<Expectation> expectations = List.of(
                new Expectation('字', "内置", "中文由正文字体画"),
                new Expectation('A', "内置", "基本拉丁字母正文字体就有, 不该让给后面的字体"),
                new Expectation(0x010D, "内置符号", "正文字体缺的西文字母由符号字体补"),
                new Expectation(0x10E6, "内置符号", "昵称里常见的装饰字由符号字体补"),
                new Expectation(0x1F970, "内置表情", "表情由表情字体画"),
                new Expectation(0x2764, "内置表情", "符号字体也有这个字, 表情字体排在它前面才轮得到"));

        List<String> failures = new ArrayList<>();
        for (Expectation expected : expectations) {
            String picked = util.findFontForCharacter(expected.codePoint()).getFamily(Locale.ROOT);
            String wanted = FAMILY_BY_WORD.get(expected.word());
            if (!wanted.equals(picked)) {
                failures.add(describe(expected.codePoint()) + " 落到了 " + picked + ", 应为 " + wanted
                        + "（" + expected.why() + "）");
            }
        }

        assertTrue(failures.isEmpty(), "挑字结果不对, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 量的是<b>画出来的像素</b>，不是字宽。
     * <p>
     * 使用者看到的毛病是「这里什么都没有」，像素直接量的就是这件事；字宽只是间接的迹象——
     * 字体可以给一个正常的字宽而画不出笔画（字形数据是这条画字路径不认的格式时就是这样），
     * 量字宽的检查在那种字体上是绿的，图上照样是空白。
     * <p>
     * 另要求挑中的字体自己显示得出这个字：谁都显示不出时会回落到表里第一个字体，
     * 画出来的豆腐块也有像素，不排除它，「表情字体没装上」会被当成「画出来了」。
     */
    @Test
    @DisplayName("表情画出来有笔画，不是空白，也不是豆腐块")
    void emojiIsDrawnWithInk() {
        FontUtil util = bundledPartOfLinuxDefaults();
        assertEquals(0, inkedPixels(util.findFontForCharacter(' '), ' '), "锚: 空格该量出 0, 否则这把尺把底色也算成了笔画");
        assertTrue(inkedPixels(util.findFontForCharacter('字'), '字') > 0, "锚: 中文字该量出笔画, 否则这把尺量不出任何东西");

        List<String> failures = new ArrayList<>();
        for (int emoji : new int[]{0x1F970, 0x1F44D, 0x2764, 0x2B50}) {
            Font font = util.findFontForCharacter(emoji);
            if (!font.canDisplay(emoji)) {
                failures.add(describe(emoji) + " 没有字体显示得出, 画出来的是 " + font.getFamily(Locale.ROOT) + " 的豆腐块");
            } else if (inkedPixels(font, emoji) == 0) {
                failures.add(describe(emoji) + " 交给了 " + font.getFamily(Locale.ROOT) + ", 画出来一个像素都没有");
            }
        }

        assertTrue(failures.isEmpty(), "表情没画出来, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 白底黑字画一个字，数比中灰更深的像素
     */
    private static int inkedPixels(Font font, int codePoint) {
        int side = (int) (SIZE * 2);
        BufferedImage canvas = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D draw = canvas.createGraphics();
        try {
            draw.setColor(Color.WHITE);
            draw.fillRect(0, 0, side, side);
            draw.setColor(Color.BLACK);
            draw.setFont(font.deriveFont(Font.PLAIN, SIZE));
            draw.drawString(new String(Character.toChars(codePoint)), side / 4, side * 3 / 4);
        } finally {
            draw.dispose();
        }

        int inked = 0;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                if (canvas.getRaster().getSample(x, y, 0) < 128) {
                    inked++;
                }
            }
        }
        return inked;
    }

    private static String describe(int codePoint) {
        return new String(Character.toChars(codePoint)) + "（U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + "）";
    }
}
