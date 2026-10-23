package org.frostnova.nova.report.demo;

import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("报告图配色预设与样张")
class ReportThemeSamplesTest {
    private static FontUtil fonts;

    @BeforeAll
    static void headlessAndFonts() {
        System.setProperty("java.awt.headless", "true");
        fonts = DemoAssets.bundledFonts();
    }

    @Test
    @DisplayName("四套预设按经典、粉蓝、暖阳、夜幕排列，经典就是不带配色的入口用的那一套")
    void presetsAreNamedAndClassicIsDefault() {
        assertSame(ReportPalette.DEFAULT, ReportPalette.CLASSIC);
        var presets = ReportPalette.PRESETS;
        assertEquals(List.of("classic", "pink-blue", "warm", "night"), presets.stream().map(ReportPalette.Preset::id).toList());
        assertEquals(List.of("经典", "粉蓝", "暖阳", "夜幕"), presets.stream().map(ReportPalette.Preset::name).toList());
        assertSame(ReportPalette.CLASSIC, presets.get(0).palette());
        assertEquals(presets.size(), new HashSet<>(presets.stream().map(ReportPalette.Preset::palette).toList()).size());
    }

    @Test
    @DisplayName("新增三套的每组文字与底色都达到对比度下限")
    void newPresetsMeetContrastMinimums() {
        var failures = new ArrayList<String>();
        for (var preset : ReportPalette.PRESETS) {
            // 经典除外：它就是升级前的样子，一色不改，对比度不另作要求
            if (preset.palette() == ReportPalette.CLASSIC) continue;
            for (var check : ReportThemeSamples.CHECKS) {
                double ratio = check.ratio(preset.palette());
                if (ratio < check.min()) failures.add(preset.name() + " " + check.label() + " " + ratio);
            }
        }
        assertTrue(failures.isEmpty(), () -> failures.size() + " below minimum: " + failures);
    }

    @Test
    @DisplayName("对比度判法认得出不达标的颜色")
    void contrastRejectsLowContrastPair() {
        // 经典的提示字压在白底上，约 2.6:1，正好是判法该报出的那种
        var check = ReportThemeSamples.CHECKS.stream().filter(c -> c.label().equals("提示字 / 画布底色")).findFirst().orElseThrow();
        assertTrue(check.ratio(ReportPalette.CLASSIC) < check.min());
        assertEquals(21.0, ReportThemeSamples.contrast(java.awt.Color.BLACK, java.awt.Color.WHITE), 1e-9);
    }

    @Test
    @DisplayName("经典出的三种图与不带配色的旧入口逐像素相同")
    void classicMatchesDefaultEntryPixelForPixel(@TempDir Path dir) throws Exception {
        var fixtures = new ReportSampleFixtures(dir.resolve("demo"), fonts);
        for (var kind : ReportSampleFixtures.Kind.values()) {
            BufferedImage expected = fixtures.render(kind, null);
            BufferedImage actual = fixtures.render(kind, ReportPalette.CLASSIC);
            assertEquals(expected.getWidth(), actual.getWidth(), kind.label);
            assertEquals(expected.getHeight(), actual.getHeight(), kind.label);
            long differing = 0;
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    if (expected.getRGB(x, y) != actual.getRGB(x, y)) differing++;
                }
            }
            System.out.println("CLASSIC_DIFF " + kind + " " + expected.getWidth() + "x" + expected.getHeight()
                    + " differing=" + differing);
            assertEquals(0, differing, kind.label);
        }
    }

    // 样张页不进仓，默认不出：要看时跑 tools/demo-assets/render-report-themes.sh
    @Test
    @EnabledIfSystemProperty(named = "report.themes.publish", matches = "true")
    @DisplayName("样张页内嵌 12 张图、不引用外部资源、不超过 6 MB")
    void samplePageIsSelfContained(@TempDir Path dir) throws Exception {
        Path page = DemoAssets.repoRoot().resolve("samples").resolve("report-themes").resolve("index.html");
        long bytes = ReportThemeSamples.write(page, dir.resolve("demo"), fonts);
        System.out.println("SAMPLE_PAGE " + page.toAbsolutePath().normalize() + " bytes=" + bytes);
        String html = Files.readString(page, StandardCharsets.UTF_8);
        assertTrue(bytes <= ReportThemeSamples.PAGE_MAX_BYTES, "bytes=" + bytes);
        assertEquals(12, Pattern.compile("src=\"data:image/jpeg;base64,").matcher(html).results().count());
        assertEquals(12, Pattern.compile("src=\"").matcher(html).results().count());
        assertFalse(html.contains("http:") || html.contains("https:") || html.contains("url(") || html.contains("<link"),
                "page must not reference external resources");
        for (var preset : ReportPalette.PRESETS) assertTrue(html.contains(preset.name()), preset.name());
        for (var kind : ReportSampleFixtures.Kind.values()) assertTrue(html.contains(kind.label), kind.label);
        assertTrue(html.contains("对比度"));
    }
}
