package org.frostnova.nova.report.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置字体在临时目录里的解出
 * <p>
 * 按 {@code Font.createFont(int, InputStream)} 读 jar 里的字体，JDK 会先把整份字体另存成
 * 临时目录里的一份复制（{@code +~JF} 开头的文件），字体对象活着就一直留着：每初始化一次
 * 就多两三份、十多 MB，进程被强杀一次就多留一批。所以内置字体该先解到临时目录下一个
 * 固定的子目录里、之后按文件读——{@code createFont(int, File)} 直接用原文件，不再复制。
 * <p>
 * 这组测试只盯着「解出」这件事：初始化多少次都只解一份、解出的那份内容不对会换成对的、
 * 落不下这份时退回按流读。「装得上、装上的是哪一款」由 {@link BundledFontsTest} 管。
 */
@DisplayName("内置字体在临时目录里的解出")
class BundledFontExtractionTest {
    /** 内置字体解到 java.io.tmpdir 下的这个固定子目录里，与 FontUtil 里同一个名字 */
    private static final String EXTRACTED_DIRECTORY = "novabot-fonts";

    /** 配置里的写法 → 类路径上那份字体文件的名字，也是解出文件名的开头一段 */
    private static final Map<String, String> RESOURCE_NAME_BY_WORD = new LinkedHashMap<>();

    static {
        RESOURCE_NAME_BY_WORD.put("内置", "NotoSansSC-Regular");
        RESOURCE_NAME_BY_WORD.put("内置表情", "NotoEmoji-Regular");
        RESOURCE_NAME_BY_WORD.put("内置符号", "DejaVuSans");
    }

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 按给定的字体表造一个已初始化的字体工具
     * <p>
     * 返回的实例得留在调用方手里：它攥着的字体对象一旦被回收，JDK 给流读那份建的临时文件
     * 就跟着没了，下面的判据数不到它。
     */
    private static FontUtil fontUtil(String... fontDefinitions) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(List.of(fontDefinitions));

        FontUtil util = new FontUtil(new DefaultResourceLoader(), properties);
        util.init();
        return util;
    }

    /**
     * JDK 按流读字体时落在临时目录里的复制：{@code +~JF} 开头的文件
     */
    private static Set<Path> jdkFontCopies() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("+~JF"))
                    .collect(Collectors.toSet());
        }
    }

    private static Path extractedDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir")).resolve(EXTRACTED_DIRECTORY);
    }

    /**
     * 固定子目录里这一份字体的全部解出文件：正式名（带内容摘要）与写一半的临时名都在内，
     * 这样「该正好一份」数得出多出来的任何东西
     */
    private static List<Path> extractedFamily(String resourceName) throws IOException {
        Path directory = extractedDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith(resourceName + "-"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 清掉三份内置字体已解出的文件，让下面的判据从零开始数
     */
    private static void clearExtractedFonts() throws IOException {
        for (String resourceName : RESOURCE_NAME_BY_WORD.values()) {
            for (Path file : extractedFamily(resourceName)) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * 连固定子目录一起清掉，给「目录建不成」的那条判据腾出位置
     */
    private static void removeExtractedDirectory() throws IOException {
        clearExtractedFonts();
        Path directory = extractedDirectory();
        if (Files.isDirectory(directory)) {
            List<Path> left = new ArrayList<>();
            try (Stream<Path> files = Files.list(directory)) {
                files.toList().forEach(left::add);
            }
            for (Path file : left) {
                Files.deleteIfExists(file);
            }
            Files.delete(directory);
        }
    }

    private static List<String> logsOf(Level level, Supplier<?> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(FontUtil.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            action.get();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 表里不写「内置符号」：画间隔号用的那一份是初始化末尾另装的（字体表里没配时的补装），
     * 一样得走解出这一路，这条判据把它一起量进来。
     */
    @Test
    @DisplayName("先后两次初始化，每种内置字体只解一份，不再往临时目录复制")
    void eachBundledFontIsExtractedOnce() throws IOException {
        clearExtractedFonts();
        Set<Path> copiesBefore = jdkFontCopies();

        FontUtil first = fontUtil("内置", "内置表情");
        FontUtil second = fontUtil("内置", "内置表情");

        Set<Path> newCopies = jdkFontCopies();
        newCopies.removeAll(copiesBefore);
        assertTrue(newCopies.isEmpty(),
                "又往临时目录复制了 JDK 的字体临时文件: " + newCopies + "; 每初始化一次都该多两三份、十多 MB");

        for (String resourceName : RESOURCE_NAME_BY_WORD.values()) {
            List<Path> family = extractedFamily(resourceName);
            assertEquals(1, family.size(), resourceName + " 在固定子目录里该正好一份: " + family);
        }
    }

    /**
     * 固定子目录里放着一份同名、内容不对的文件时——比如上一次写到一半、或者文件被截断了——
     * 得换成对的那份再用，不能把错的那份当字体读。这条判据认的就是「换成对的那份」：
     * 落点在初始化之后解出文件的内容与类路径上的一致。
     */
    @Test
    @DisplayName("同名但内容不对的解出文件，会被换成对的那份")
    void wrongExtractedFileIsReplacedByTheRightOne() throws IOException {
        clearExtractedFonts();
        fontUtil("内置");

        List<Path> family = extractedFamily("NotoSansSC-Regular");
        assertEquals(1, family.size(), "锚: 先解出一份才谈得上换: " + family);
        Path extracted = family.get(0);
        Files.write(extracted, "这不是一份字体, 只是占了同一个名字。".repeat(2048).getBytes(StandardCharsets.UTF_8));

        FontUtil util = fontUtil("内置");
        Font font = util.parseFont("内置").orElse(null);
        assertNotNull(font, "解出的文件不对之后, 字体装不上了");

        assertEquals("Noto Sans SC", font.getFamily(Locale.ROOT), "装上的得是内置中文正文字体那一份");
        assertTrue(font.canDisplay('中'), "中文字得显示得出");

        try (InputStream resource = BundledFontExtractionTest.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            assertNotNull(resource, "内置字体不在类路径上");
            assertArrayEquals(resource.readAllBytes(), Files.readAllBytes(extracted),
                    "解出文件的内容要与类路径上的一致, 而不是留着错的那份");
        }
    }

    /**
     * 固定子目录落不下（这里用同名文件占住它的位置）时，要说一句再退回按流读，
     * 字体照样装得上——报告插件不能因为临时目录写不进就起不来。
     */
    @Test
    @DisplayName("解不出时说一句并退回按流读，字体照样装得上")
    void fallsBackToTheStreamWhenExtractionFails() throws IOException {
        removeExtractedDirectory();
        Path directory = extractedDirectory();
        Files.createFile(directory);

        try {
            Font[] loaded = new Font[1];
            List<String> warnings = logsOf(Level.WARN, () -> {
                FontUtil util = fontUtil("内置");
                loaded[0] = util.parseFont("内置").orElse(null);
                return loaded[0];
            });

            assertNotNull(loaded[0], "退回了按流读, 字体还装不上");
            assertEquals("Noto Sans SC", loaded[0].getFamily(Locale.ROOT));
            assertTrue(warnings.stream().anyMatch(message -> message.contains("NotoSansSC-Regular")),
                    "解不出要说一句, 提示里得认得出是哪一份字体: " + warnings);
        } finally {
            Files.deleteIfExists(directory);
        }
    }
}
