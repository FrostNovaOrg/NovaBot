package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级之后用哪张字体表
 * <p>
 * 程序第一次写出配置文件时，曾把运行中的整张字体表——也就是当时的默认表——一并写进
 * {@code novabot.core.paint.fonts}。此后每次升级，新的默认表都只能接在这张旧表后面：
 * 2026-09-17 以前装在 Linux 上的实例，旧表里排着 Java 画不出的彩色表情字体，
 * 新加的「内置表情」轮不到，报告图里的表情一直是空白。
 * <p>
 * 下面各格里的历代默认表是<b>照 git 史抄的字面量</b>，不取程序里那份历代表：
 * 拿被测自己的表当参照，程序里少抄一代，这里照样全绿。
 */
@DisplayName("升级后的成图字体表")
class PaintFontChainTest {
    /** 2026-09-17 以前各系统的默认表 */
    private static final Map<String, List<String>> FIRST_GENERATION = Map.of(
            "Windows 11", List.of("内置", "微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif"),
            "Mac OS X", List.of("内置", "PingFang SC", "Apple Color Emoji", "SansSerif"),
            "Linux", List.of("内置", "Noto Sans CJK SC", "WenQuanYi Zen Hei", "Noto Color Emoji", "DejaVu Sans", "FreeSans", "SansSerif"));

    /** 2026-09-17 起各系统的默认表 */
    private static final Map<String, List<String>> SECOND_GENERATION = Map.of(
            "Windows 11", List.of("内置", "微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif"),
            "Mac OS X", List.of("内置", "PingFang SC", "Apple Color Emoji", "SansSerif"),
            "Linux", List.of("内置", "内置表情", "Noto Sans CJK SC", "内置符号", "SansSerif"));

    /**
     * 用户故障：Linux 上 09-17 以前装的实例，配置里存着旧默认表，升级后报告里的表情画成空白
     */
    @Test
    @DisplayName("Linux 旧版落下的默认表当作没设，表情字体排回彩色表情字体之前")
    void legacyLinuxTableIsTreatedAsUnset() {
        List<String> chain = NovaCoreProperties.fontChain(FIRST_GENERATION.get("Linux"), "Linux");

        assertEquals(NovaCoreProperties.defaultFonts("Linux"), chain,
                "配置里那张是旧版自动写下的默认表, 应当只用现在的默认表");
        assertTrue(!chain.contains("Noto Color Emoji") || chain.indexOf("内置表情") < chain.indexOf("Noto Color Emoji"),
                "彩色表情字体排在「内置表情」前面, 表情会画成空白: " + chain);
    }

    @Test
    @DisplayName("三种系统、每一代默认表原样存着的，都只用现在的默认表")
    void everyPastDefaultTableIsTreatedAsUnset() {
        List<String> failures = new ArrayList<>();
        for (Map<String, List<String>> generation : List.of(FIRST_GENERATION, SECOND_GENERATION)) {
            generation.forEach((os, stored) -> {
                List<String> chain = NovaCoreProperties.fontChain(stored, os);
                if (!chain.equals(NovaCoreProperties.defaultFonts(os))) {
                    failures.add(os + " 存着 " + stored + " 时用的是 " + chain);
                }
            });
        }

        assertTrue(failures.isEmpty(), "旧默认表没当作未设, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 用户故障：使用者自己配的表被当成旧默认表丢掉，或被挪到默认表后面
     * <p>
     * 只差一项也是使用者的表：旧 Linux 表末尾多加一款字体，就不是程序写下的那张了。
     */
    @Test
    @DisplayName("使用者自己配的表照旧排前，默认表接后，重复的只留第一次出现的")
    void userTableStaysFirstWithoutDuplicates() {
        assertEquals(List.of("我的字体", "内置", "内置表情", "Noto Sans CJK SC", "内置符号", "SansSerif"),
                NovaCoreProperties.fontChain(List.of("我的字体", "内置"), "Linux"));

        List<String> edited = new ArrayList<>(FIRST_GENERATION.get("Linux"));
        edited.add("我的字体");
        List<String> expected = new ArrayList<>(edited);
        expected.addAll(List.of("内置表情", "内置符号"));
        assertEquals(expected, NovaCoreProperties.fontChain(edited, "Linux"),
                "改过的旧表是使用者的表, 原样排前");
    }

    @Test
    @DisplayName("启动后挑字用的是整理过的表，使用者那一项原样不动")
    void initResolvesChainAndLeavesConfiguredFontsAlone() {
        String os = System.getProperty("os.name");
        List<String> stored = NovaCoreProperties.defaultFonts(os);
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(stored);

        properties.init();

        assertEquals(NovaCoreProperties.defaultFonts(os), properties.getPaint().fontChain(),
                "存着本系统默认表时, 挑字的表不该再把默认表接一遍");
        assertEquals(stored, properties.getPaint().getFonts(), "配置项的值是使用者写的, 启动时不该往里加东西");
    }
}
