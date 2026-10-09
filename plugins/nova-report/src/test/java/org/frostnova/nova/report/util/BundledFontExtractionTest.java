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
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 内置字体在临时目录里的解出
 * <p>
 * 按 {@code Font.createFont(int, InputStream)} 读 jar 里的字体，JDK 会先把整份字体另存成
 * 临时目录里的一份复制（{@code +~JF} 开头的文件），字体对象活着就一直留着：每初始化一次
 * 就多两三份、十多 MB，进程被强杀一次就多留一批。所以内置字体该先解到临时目录下一个
 * 固定的子目录里、之后按文件读——{@code createFont(int, File)} 直接用原文件，不再复制。
 * <p>
 * 这个子目录得按用户分开、只给本人进出：手动前台跑的时候 {@code java.io.tmpdir} 是全机
 * 共用的 {@code /tmp}，固定名字谁都能抢建，谁都进得去的目录不能拿来放要解析的字体文件。
 * <p>
 * 这组测试只盯着「解出」这件事：初始化多少次都只解一份、第二次初始化连类路径资源都不再
 * 读、解出的那份内容不对会换成对的、目录不合用或落不下时退回按流读、建出的目录与文件
 * 只有本人可用。「装得上、装上的是哪一款」由 {@link BundledFontsTest} 管。
 */
@DisplayName("内置字体在临时目录里的解出")
class BundledFontExtractionTest {
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
     * 内置字体解到 java.io.tmpdir 下的这个子目录里：名字带上当前用户（用户名里字母、数字
     * 和 {@code - _ .} 以外的字符换成 {@code _}），与 FontUtil 里同一个算法——共享的
     * {@code /tmp} 里不带用户名的固定名字，别的本机用户可以抢先建一个来等着
     */
    private static String extractedDirectoryName() {
        String userName = System.getProperty("user.name", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return userName.isEmpty() ? "novabot-fonts" : "novabot-fonts-" + userName;
    }

    /**
     * 按给定的字体表造一份配置
     */
    private static NovaCoreProperties properties(String... fontDefinitions) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(List.of(fontDefinitions));
        return properties;
    }

    /**
     * 按给定的字体表造一个已初始化的字体工具
     * <p>
     * 返回的实例得留在调用方手里：它攥着的字体对象一旦被回收，JDK 给流读那份建的临时文件
     * 就跟着没了，下面的判据数不到它。
     */
    private static FontUtil fontUtil(String... fontDefinitions) {
        FontUtil util = new FontUtil(new DefaultResourceLoader(), properties(fontDefinitions));
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
        return Path.of(System.getProperty("java.io.tmpdir")).resolve(extractedDirectoryName());
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

    /**
     * 临时目录所在的文件系统认不认 POSIX 权限：认才有「只给本人」可量；
     * 不认（如 Windows，它的临时目录本来就按用户分开）时相关判据跳过
     */
    private static boolean posixPermissions() {
        try {
            return Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                    .supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (Exception unknown) {
            return false;
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

    /**
     * 解出子目录已在、但组或其他人有权限时——比如别的本机用户抢建了这个名字，或者有人
     * 按老习惯建成了 0775——谁都进得去的目录不能拿来放要解析的字体文件：说一句、退回
     * 按流读，目录里一个文件都不多。
     */
    @Test
    @DisplayName("解出子目录组或其他人有权限时退回按流读，不往里写")
    void groupOrOthersAccessibleDirectoryIsNotUsed() throws IOException {
        assumeTrue(posixPermissions(), "临时目录的文件系统不支持 POSIX 权限（如 Windows）, 本条跳过");

        removeExtractedDirectory();
        Path directory = extractedDirectory();
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));

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
                    "不用这个目录得说一句: " + warnings);
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> written = files.toList();
                assertTrue(written.isEmpty(), "不该往组或其他人进得去的目录里写任何东西: " + written);
            }
        } finally {
            removeExtractedDirectory();
        }
    }

    /**
     * 新建的解出子目录只给本人（0700），解出的文件只给本人读写（0600），而且权限是建的
     * 时候就带上的——先建成大家可进再收紧，中间有一段谁都进得来。文件系统不认 POSIX
     * 权限时没法量，跳过。
     */
    @Test
    @DisplayName("新建的解出子目录是 0700，解出的文件是 0600")
    void newDirectoryAndFilesAreOwnerOnly() throws IOException {
        assumeTrue(posixPermissions(), "临时目录的文件系统不支持 POSIX 权限（如 Windows）, 本条跳过");

        removeExtractedDirectory();

        fontUtil("内置", "内置表情", "内置符号");

        Path directory = extractedDirectory();
        assertTrue(Files.isDirectory(directory), "锚: 先建出子目录才谈得上权限");
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(directory), "子目录只该有本人的 rwx");
        for (String resourceName : RESOURCE_NAME_BY_WORD.values()) {
            for (Path file : extractedFamily(resourceName)) {
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(file), resourceName + " 解出的文件只该有本人的 rw");
            }
        }
    }

    /**
     * 同一进程里第二次初始化不再整份读类路径资源。量法：给第二个实例一个会记下每次
     * {@code getResource} 的加载器——第一次初始化解出并核对过的那份被记住之后，第二次
     * 初始化连向类路径要都不该再要这份资源，要都没要，自然谈不上读。
     */
    @Test
    @DisplayName("同一进程里第二次初始化不再读那份类路径资源")
    void secondInitSkipsTheBundledResource() throws IOException {
        clearExtractedFonts();

        FontUtil first = fontUtil("内置");

        CountingResourceLoader counting = new CountingResourceLoader();
        FontUtil second = new FontUtil(counting, properties("内置"));
        second.init();
        Font font = second.parseFont("内置").orElse(null);

        assertNotNull(font, "第二次初始化得照样装得上内置中文正文字体");
        assertEquals("Noto Sans SC", font.getFamily(Locale.ROOT));
        assertEquals(Map.of(), counting.askedLocations,
                "第二次初始化不该再向类路径要内置字体的资源: " + counting.askedLocations);
        assertEquals(1, extractedFamily("NotoSansSC-Regular").size(), "解出的还是那一份, 没多出新文件");
    }

    /**
     * 记下 FontUtil 通过它向类路径要过的每一次资源，「第二次初始化不再读类路径资源」
     * 那条判据量的是它
     */
    private static final class CountingResourceLoader implements ResourceLoader {
        private final ResourceLoader delegate = new DefaultResourceLoader();
        private final Map<String, Integer> askedLocations = new LinkedHashMap<>();

        @Override
        public Resource getResource(String location) {
            askedLocations.merge(location, 1, Integer::sum);
            return delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }
    }
}
