package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成图字体的默认表
 * <p>
 * 报告图上每个字先问哪个字体，由这张表的顺序决定。排错了不报错，只在图发出去之后才看得见：
 * Java 画不出彩色表情字体，而它照样报告自己「显示得出」，排在前面就把表情画成空白；
 * 列着一个没装的字体，则每次启动多一句找不到字体的提示。
 */
@DisplayName("成图字体默认表")
class PaintDefaultFontsTest {
    /** 画不出来的彩色表情字体，以及已由随程序发布的字体顶替的两款 */
    private static final List<String> RETIRED = List.of("Noto Color Emoji", "WenQuanYi Zen Hei", "FreeSans");

    /** 三种系统各取一个 {@code os.name} 的真实写法 */
    private static final List<String> OS_NAMES = List.of("Linux", "Windows 11", "Mac OS X");

    @Test
    @DisplayName("Linux：内置中文、内置表情、系统中文、内置符号、SansSerif")
    void linuxOrder() {
        assertEquals(List.of("内置", "内置表情", "Noto Sans CJK SC", "内置符号", "SansSerif"),
                NovaCoreProperties.defaultFonts("Linux"),
                "表情字体要排在所有系统字体之前, 西文符号字体要排在 SansSerif 之前");
    }

    @Test
    @DisplayName("三种系统的默认表都不再列那三款字体")
    void retiredFontsAreGoneEverywhere() {
        List<String> found = new ArrayList<>();
        for (String os : OS_NAMES) {
            List<String> fonts = NovaCoreProperties.defaultFonts(os);
            assertTrue(fonts.contains("内置") && fonts.contains("SansSerif"),
                    "锚: " + os + " 的默认表该有首尾两项, 取到的是 " + fonts + ", 说明这条检查没取对表");
            for (String font : fonts) {
                if (RETIRED.stream().anyMatch(retired -> retired.equalsIgnoreCase(font))) {
                    found.add(os + ": " + font);
                }
            }
        }

        assertTrue(found.isEmpty(), "默认表里仍列着: " + found);
    }

    @Test
    @DisplayName("启动时把本机系统的默认表接在使用者的表之后")
    void initAppendsDefaultsAfterUserFonts() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("使用者写的字体");

        properties.init();

        List<String> expected = new ArrayList<>();
        expected.add("使用者写的字体");
        expected.addAll(NovaCoreProperties.defaultFonts(System.getProperty("os.name")));
        assertEquals(expected, properties.getPaint().getFonts(), "使用者写的排最前, 默认表整张接在后面");
    }
}
