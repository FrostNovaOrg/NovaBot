package org.frostnova.nova.report.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Font;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随程序发布的字体
 * <p>
 * 报告图不指望服务器装了什么字体：中文、表情、西文与符号各带一份，放在插件 jar 的 {@code fonts/} 下。
 * 这几份字体的许可允许随软件再分发，条件是每一份拷贝都附上版权声明与许可全文——
 * 所以这里钉的不只是「装得上」，还有「许可原文就在字体旁边」「发布声明里写了出处」。
 * <p>
 * 字体文件是按目录现列的，不抄名单：往 {@code fonts/} 里多放一份字体而忘了带许可，这里就红。
 */
@DisplayName("随程序发布的字体")
class BundledFontsTest {
    /** 配置里的写法 → 装上之后应当是哪一款字体 */
    private static final Map<String, String> FAMILY_BY_WORD = new LinkedHashMap<>();

    static {
        FAMILY_BY_WORD.put("内置", "Noto Sans SC");
        FAMILY_BY_WORD.put("内置表情", "Noto Emoji");
        FAMILY_BY_WORD.put("内置符号", "DejaVu Sans");
    }

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("三个写法各装得上，装上的是该是的那一款")
    void eachWordLoadsItsTypeface() {
        FontUtil util = new FontUtil(new DefaultResourceLoader(), new NovaCoreProperties());
        util.init();

        List<String> failures = new ArrayList<>();
        FAMILY_BY_WORD.forEach((word, family) -> {
            Optional<Font> font = util.parseFont(word);
            if (font.isEmpty()) {
                failures.add("「" + word + "」装不上");
            } else if (!family.equals(font.get().getFamily(Locale.ROOT))) {
                failures.add("「" + word + "」装上的是 " + font.get().getFamily(Locale.ROOT) + ", 应为 " + family);
            }
        });

        assertTrue(failures.isEmpty(), "随程序发布的字体不对, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    @Test
    @DisplayName("每份字体旁边都放着它的许可原文")
    void everyFontShipsWithItsLicense() throws IOException, URISyntaxException {
        Path directory = fontDirectory();
        List<Path> fonts = fontFiles(directory);
        assertTrue(!fonts.isEmpty(), "锚: " + directory + " 下一份字体都没列出, 这条检查没对准目录");

        List<String> failures = new ArrayList<>();
        for (Path font : fonts) {
            Path license = directory.resolve(licenseNameOf(font));
            if (!Files.isRegularFile(license)) {
                failures.add(font.getFileName() + " 旁边没有 " + license.getFileName());
                continue;
            }
            String text = Files.readString(license, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!text.contains("copyright") || !text.contains("permission")) {
                failures.add(license.getFileName() + " 里没有版权声明与授权条款, 不像许可原文");
            }
        }

        assertTrue(failures.isEmpty(), "字体没带许可, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 发布声明（仓库根目录的 NOTICE）逐份写明字体出处与许可文件名，
     * 且不再列已经不随包的字体——一条对不上实物的署名，与漏写一条同样误导人。
     */
    @Test
    @DisplayName("发布声明逐份写明字体与许可文件，不列已不随包的字体")
    void noticeNamesEveryBundledFont() throws IOException, URISyntaxException {
        Path directory = fontDirectory();
        List<Path> fonts = fontFiles(directory);
        assertTrue(!fonts.isEmpty(), "锚: " + directory + " 下一份字体都没列出, 这条检查没对准目录");
        String notice = Files.readString(repoRoot().resolve("NOTICE"), StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        for (Path font : fonts) {
            String fontName = font.getFileName().toString();
            if (!notice.contains("fonts/" + fontName)) {
                failures.add("NOTICE 没写 fonts/" + fontName);
            }
            if (!notice.contains(licenseNameOf(font))) {
                failures.add("NOTICE 没写 " + fontName + " 的许可文件 " + licenseNameOf(font));
            }
        }
        Matcher mentioned = Pattern.compile("fonts/([A-Za-z0-9_.-]+\\.(?:ttf|otf))").matcher(notice);
        while (mentioned.find()) {
            if (!Files.isRegularFile(directory.resolve(mentioned.group(1)))) {
                failures.add("NOTICE 仍列着不随包的 fonts/" + mentioned.group(1));
            }
        }

        assertTrue(failures.isEmpty(), "发布声明与随包字体对不上, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * {@code NotoSansSC-Regular.ttf} → {@code LICENSE-NotoSansSC.txt}：取第一个「-」或「.」之前的那段
     */
    private static String licenseNameOf(Path font) {
        return "LICENSE-" + font.getFileName().toString().split("[-.]", 2)[0] + ".txt";
    }

    private static Path fontDirectory() throws URISyntaxException {
        URL marker = BundledFontsTest.class.getResource("/fonts/");
        assertNotNull(marker, "类路径上没有 fonts/ 目录");
        assertEquals("file", marker.getProtocol(), "fonts/ 不在目录形态的类路径上, 列不出里面的文件: " + marker);
        return Path.of(marker.toURI());
    }

    private static List<Path> fontFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(ttf|otf)"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }
}
