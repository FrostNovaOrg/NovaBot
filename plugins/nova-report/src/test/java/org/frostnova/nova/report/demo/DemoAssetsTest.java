package org.frostnova.nova.report.demo;

import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 示意图素材：合成图可复现，报告图走正式绘制方法。
 */
@DisplayName("示意图素材生成与报告渲染")
class DemoAssetsTest {
    private static FontUtil fonts;

    @BeforeAll
    static void headlessAndFonts() {
        System.setProperty("java.awt.headless", "true");
        fonts = DemoAssets.bundledFonts();
    }

    @Test
    @DisplayName("同一种子两次生成头像与封面，逐件 sha256 相同")
    void generateTwiceSameDigest(@TempDir Path first, @TempDir Path second) throws Exception {
        DemoAssets.generate(first, fonts);
        DemoAssets.generate(second, fonts);
        List<String> red = new ArrayList<>();
        for (String name : List.of(DemoAssets.AVATAR_FILES[0], DemoAssets.AVATAR_FILES[1],
                DemoAssets.AVATAR_FILES[2], DemoAssets.COVER_FILE, DemoAssets.ROSTER_FILE)) {
            try {
                assertEquals(DemoAssets.sha256(first.resolve(name)), DemoAssets.sha256(second.resolve(name)), name);
            } catch (Throwable t) {
                red.add(name + " " + t.getMessage());
            }
        }
        try {
            BufferedImage avatar = ImageIO.read(first.resolve(DemoAssets.AVATAR_FILES[0]).toFile());
            assertEquals(DemoAssets.AVATAR_PX, avatar.getWidth());
            assertEquals(DemoAssets.AVATAR_PX, avatar.getHeight());
        } catch (Throwable t) {
            red.add("avatar size " + t.getMessage());
        }
        try {
            BufferedImage cover = ImageIO.read(first.resolve(DemoAssets.COVER_FILE).toFile());
            assertEquals(DemoAssets.COVER_W, cover.getWidth());
            assertEquals(DemoAssets.COVER_H, cover.getHeight());
        } catch (Throwable t) {
            red.add("cover size " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("合成场次经 BilibiliLiveReportPainter.paint 出图，宽高与体积落在范围内，两次 sha256 相同")
    void renderTwiceFromPainter(@TempDir Path dir) throws Exception {
        Path demo = dir.resolve("demo");
        DemoAssets.generate(demo, fonts);
        Path firstPng = dir.resolve("first.png");
        Path secondPng = dir.resolve("second.png");
        DemoAssets.Rendered first = DemoAssets.render(demo, firstPng, fonts);
        DemoAssets.Rendered second = DemoAssets.render(demo, secondPng, fonts);
        List<String> red = new ArrayList<>();
        try {
            assertEquals(DemoAssets.RENDER_VIA, first.via());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(first.width() >= 1100 && first.width() <= 1300, "width " + first.width());
            assertTrue(first.bytes() > 0 && first.bytes() <= DemoAssets.REPORT_MAX_BYTES,
                    "bytes " + first.bytes());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(DemoAssets.sha256(firstPng), DemoAssets.sha256(secondPng));
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("把示意图写进 docs/assets，名册只含虚构名与假号")
    void writesPublishedAssets() throws Exception {
        Path root = DemoAssets.repoRoot();
        Path demo = root.resolve("docs").resolve("assets").resolve("demo");
        Path report = root.resolve("docs").resolve("assets").resolve("report-demo.png");
        DemoAssets.generate(demo, fonts);
        DemoAssets.Rendered rendered = DemoAssets.render(demo, report, fonts);
        List<String> red = new ArrayList<>();
        try {
            assertTrue(Files.isRegularFile(demo.resolve(DemoAssets.COVER_FILE)));
            assertTrue(Files.isRegularFile(report));
            assertEquals(DemoAssets.RENDER_VIA, rendered.via());
            assertTrue(rendered.width() >= 1100 && rendered.width() <= 1300, "width " + rendered.width());
            assertTrue(rendered.bytes() <= DemoAssets.REPORT_MAX_BYTES, "bytes " + rendered.bytes());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            String roster = Files.readString(demo.resolve(DemoAssets.ROSTER_FILE));
            for (String name : DemoAssets.fictionalNames()) {
                assertTrue(roster.contains(name), name);
            }
            for (Long uid : DemoAssets.fictionalUids()) {
                assertTrue(roster.contains(Long.toString(uid)), Long.toString(uid));
            }
            for (String line : roster.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                boolean known = DemoAssets.fictionalNames().stream().anyMatch(line::contains)
                        || line.startsWith("房间号")
                        || line.startsWith("标题")
                        || line.startsWith("改题");
                assertTrue(known, line);
            }
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }
}
