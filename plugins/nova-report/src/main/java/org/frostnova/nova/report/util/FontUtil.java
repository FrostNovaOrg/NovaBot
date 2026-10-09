package org.frostnova.nova.report.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.lang.StringUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字体工具类
 * <p>
 * 每一张图上的每一个字都从这里过两趟：先按字挑一个显示得出它的字体，再拿这个字体量宽。
 * 字体表按配置项 {@code novabot.core.paint.fonts} 的顺序排，<b>顺序即优先级</b>——
 * 排在前面的字体先被问到，所以正文字体该排在只补表情、只补符号的那几个之前。
 */
@Slf4j
@Component
public class FontUtil {
    /**
     * 配置里代表随程序发布的那几份字体的写法，及各自在类路径上的位置
     * <p>
     * 「内置」是中文正文字体：有它兜底，一台什么字体都没装的服务器也画得出中文。
     * 「内置表情」是单色表情字体，「内置符号」补西文字母与各类符号。
     * 这几个词是使用者写在 yml 里的值，改它们等于改配置格式。
     * <p>
     * 三份都是许可允许随软件再分发的字体，许可原文就放在同目录，与字体一起进 jar
     */
    private static final Map<String, String> BUNDLED_FONTS = Map.of(
            "内置", "classpath:fonts/NotoSansSC-Regular.ttf",
            "内置表情", "classpath:fonts/NotoEmoji-Regular.ttf",
            "内置符号", "classpath:fonts/DejaVuSans.ttf");

    /**
     * 装进表里时统一用的字号
     * <p>
     * 真正画的时候一律 {@code deriveFont} 到当时要的字号，所以这个数只是个占位；
     * 表里存的字体不带「当前字号」这个状态，两处画不同大小的字才不会互相干扰
     */
    private static final int DEFAULT_FONT_SIZE = 30;

    /**
     * 不论表里怎么排，都先交给「内置符号」画的字
     * <p>
     * 中文字体把间隔号「·」（U+00B7）画成一个汉字宽：Noto Sans SC 里它与「中」同为 1 em，
     * 字母 a 只有 0.56 em。报告里「弹幕 · 128 人参与」这类写法两侧本就各有空格，
     * 分隔处于是空出一大截。「内置符号」（DejaVu Sans）里它约 0.32 em。
     */
    private static final Set<Integer> WESTERN_WIDTH_CHARACTERS = Set.of(0x00B7);

    private static final String BUNDLED_SYMBOL_FONT = "内置符号";

    /**
     * 内置字体在临时目录里的解出位置：{@code java.io.tmpdir} 下固定的一个子目录
     * <p>
     * 按 {@code Font.createFont(int, InputStream)} 读类路径上的字体，JDK 会先把整份字体
     * 另存成临时目录里的一份复制（{@code +~JF} 开头的文件），字体对象活着就一直留着：
     * 每初始化一次就多两三份、十多 MB，进程被强杀就留在那里。先解到这个子目录里、
     * 之后按文件读——{@code createFont(int, File)} 直接用原文件，不再复制——就只有第一份。
     */
    private static final String EXTRACTED_FONT_DIRECTORY = "novabot-fonts";

    private final ResourceLoader resourceLoader;

    private final NovaCoreProperties properties;

    private Set<String> systemFonts = new HashSet<>();

    private final List<Font> fonts = new ArrayList<>();

    /**
     * 画 {@link #WESTERN_WIDTH_CHARACTERS} 用的字体；Windows、macOS 的默认表里没有「内置符号」，照样单独装上
     */
    private Font westernWidthFont;

    @Autowired
    public FontUtil(ResourceLoader resourceLoader, NovaCoreProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 系统字体名一律按小写存：使用者在 yml 里写「pingfang sc」还是「PingFang SC」都该认得出
        systemFonts = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<String> configured = properties.getPaint().fontChain();
        log.info("已指定使用字体列表: {}, 可使用配置项 novabot.core.paint.fonts 自定义字体列表", configured);

        // 装不上的那一项直接跳过，不占位置：留个空位在表里，挑字体时会挑到一个 null
        for (String fontDefinition : configured) {
            Optional<Font> font = parseFont(fontDefinition);
            font.ifPresent(fonts::add);
            if (BUNDLED_SYMBOL_FONT.equals(fontDefinition)) {
                westernWidthFont = font.orElse(null);
            }
        }
        if (westernWidthFont == null && !configured.contains(BUNDLED_SYMBOL_FONT)) {
            westernWidthFont = parseFont(BUNDLED_SYMBOL_FONT).orElse(null);
        }
    }

    /**
     * 解析字体
     * @param font 字体名称或路径
     * @return 字体
     */
    public Optional<Font> parseFont(String font) {
        try {
            if (isSystemFont(font)) {
                return Optional.of(new Font(font, Font.PLAIN, DEFAULT_FONT_SIZE));
            }

            if (isFontFilePath(font)) {
                return Optional.of(loadFontFile(font));
            }

            String bundledLocation = BUNDLED_FONTS.get(font);
            if (bundledLocation != null) {
                return Optional.of(loadBundledFont(bundledLocation));
            }

            log.warn("{} 不在系统字体库中, 且不是一个有效的字体文件", font);
        } catch (Exception e) {
            // 装不上一个字体不该让整个程序起不来：少一个字体只是少一层兜底，
            // 剩下的字体照样画得出大部分字
            log.error("加载 {} 字体失败", font, e);
        }

        return Optional.empty();
    }

    /**
     * 获取当前已加载的字体名称列表
     *
     * @return 当前已加载的字体名称列表
     */
    public List<String> getFontNames() {
        return fonts.stream()
                .map(Font::getName)
                .collect(Collectors.toList());
    }

    /**
     * 表里第一个字体：一行字的基线按它定
     */
    public Font primaryFont() {
        return fonts.get(0);
    }

    /**
     * 查找可以显示指定字符的字体
     *
     * @param charCodePoint 字符编码
     * @return 可以显示该字符的字体
     */
    public Font findFontForCharacter(int charCodePoint) {
        if (westernWidthFont != null && WESTERN_WIDTH_CHARACTERS.contains(charCodePoint)
                && westernWidthFont.canDisplay(charCodePoint)) {
            return westernWidthFont;
        }
        return fonts.stream()
                .filter(font -> font.canDisplay(charCodePoint))
                .findFirst()
                // 谁都显示不出时用表里第一个：画出来是个豆腐块，但整张图还在。
                // 一个字体都没装上时这里会抛——那是配置问题，越早响越好
                .orElseGet(() -> fonts.get(0));
    }

    /**
     * 变体选择符：U+FE00–U+FE0F 与补充平面的 U+E0100–U+E01EF
     * <p>
     * 它只指定紧跟在前面那个字用哪种字形，自己不占位置、也不出墨。
     * 逐码位量宽、落笔的地方都要跳过它：跳不过去时，认得它的字体把它画成一个满宽的空字形
     * （心形表情后面空出一个表情宽），谁都不认时它落回表里第一个字体，画成一个豆腐块。
     *
     * @param charCodePoint 字符编码
     * @return 是否是变体选择符
     */
    public static boolean isVariantSelector(int charCodePoint) {
        return (charCodePoint >= 0xFE00 && charCodePoint <= 0xFE0F)
                || (charCodePoint >= 0xE0100 && charCodePoint <= 0xE01EF);
    }

    /**
     * 计算指定字符串在 Graphics2D 中绘制时的像素宽度和高度
     *
     * @param draw 用于绘制文本的 Graphics2D 对象
     * @param text 要计算宽度和高度的含格式文本
     * @return 宽度，高度
     */
    public Pair<Integer, Integer> getStringWidthAndHeight(Graphics2D draw, TextWithStyle text) {
        if (StringUtil.isEmpty(text.getText())) {
            return Pair.of(0, 0);
        }

        Font originalFont = draw.getFont();

        // 指定了字体就整串用它；没指定则逐字挑——挑字体只能按字来，
        // 一串中英日夹杂的昵称往往没有任何一个字体全显示得出
        boolean perCharacter = text.getFont() == null;
        if (!perCharacter) {
            draw.setFont(sized(text.getFont(), text));
        }

        int width = 0;
        int maxHeight = 0;

        // 按码位而不是按 char 走：一个增补平面的字占两个 char，
        // 拆开量得到的是两个孤立代理项的宽度，表情符号那一路的版面就全错了
        for (int codePoint : text.getText().codePoints().toArray()) {
            // 变体选择符不占宽度也不出墨：宽窄和高矮都按没有它来量
            if (isVariantSelector(codePoint)) {
                continue;
            }

            if (perCharacter) {
                draw.setFont(sized(findFontForCharacter(codePoint), text));
            }

            FontMetrics metrics = draw.getFontMetrics();
            width += metrics.stringWidth(new String(Character.toChars(codePoint)));
            maxHeight = Math.max(maxHeight, metrics.getHeight());
        }

        // 量宽这一路会在画笔上反复换字体，量完必须放回去：
        // 漏了这一步，下一笔画上去的字会用最后量到的那个字的字体和字号
        draw.setFont(originalFont);

        return Pair.of(width, maxHeight);
    }

    /**
     * 按这段文本要的字号与风格取一份字体
     */
    private Font sized(Font font, TextWithStyle text) {
        return font.deriveFont(text.getStyle(), text.getSize());
    }

    private boolean isSystemFont(String font) {
        return systemFonts.contains(font.toLowerCase());
    }

    private boolean isFontFilePath(String font) {
        return font.toLowerCase().endsWith(".ttf");
    }

    private Font loadFontFile(String path) throws IOException, FontFormatException {
        return atDefaultSize(Font.createFont(Font.TRUETYPE_FONT, Paths.get(path).toFile()));
    }

    private Font loadBundledFont(String location) throws IOException, FontFormatException {
        try {
            return loadFontFile(extractBundledFont(location).toString());
        } catch (Exception e) {
            // 解不出（目录建不了、写不进、解出的那份读不进）不该连字体一起丢：
            // 说一句再退回按流读，字体照样装得上，代价是 JDK 又往临时目录复制一份
            log.warn("在临时目录解出内置字体 {} 失败, 退回按流加载", location, e);
        }
        try (InputStream fontStream = resourceLoader.getResource(location).getInputStream()) {
            return atDefaultSize(Font.createFont(Font.TRUETYPE_FONT, fontStream));
        }
    }

    /**
     * 把类路径上的一份内置字体解到 {@link #EXTRACTED_FONT_DIRECTORY} 里，给出那份文件
     * <p>
     * 文件名带内容的摘要：内容不同不会撞名，重启、并发各解各的都落在同一份上；
     * 已在且内容对得上就直接用，不重写。写的时候先落到同目录的临时名、写完再原子改名，
     * 两个进程同时解同一份时，谁也不会把半截文件当成品读。
     */
    private Path extractBundledFont(String location) throws IOException {
        byte[] content;
        try (InputStream fontStream = resourceLoader.getResource(location).getInputStream()) {
            content = fontStream.readAllBytes();
        }
        String fileName = extractedFontName(location, content);
        Path directory = Paths.get(System.getProperty("java.io.tmpdir"), EXTRACTED_FONT_DIRECTORY);
        Files.createDirectories(directory);
        Path extracted = directory.resolve(fileName);
        if (contentAlreadyAt(extracted, content)) {
            return extracted;
        }
        Path staging = Files.createTempFile(directory, fileName, ".part");
        Files.write(staging, content);
        try {
            Files.move(staging, extracted, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException noAtomicRename) {
            Files.move(staging, extracted, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException raced) {
            // 改名时正式名已在：对面那份内容对得上就算成，用对面的；对不上另抛出去走退路
            if (!contentAlreadyAt(extracted, content)) {
                throw raced;
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        return extracted;
    }

    /**
     * {@code classpath:fonts/NotoSansSC-Regular.ttf} → {@code NotoSansSC-Regular-<内容摘要>.ttf}
     */
    private static String extractedFontName(String location, byte[] content) {
        String resourceName = location.substring(location.lastIndexOf('/') + 1);
        int extensionAt = resourceName.lastIndexOf('.');
        return resourceName.substring(0, extensionAt) + "-" + contentDigest(content)
                + resourceName.substring(extensionAt);
    }

    /**
     * 内容摘要取十六进制的前十六位：只在给解出文件起名时用，两份不同内容撞上它的机会小到不算
     */
    private static String contentDigest(byte[] content) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            // 每个Java实现都得有SHA-256，走到这里只是把检查异常收拢掉
            throw new IllegalStateException("这个Java实现没有SHA-256摘要算法", e);
        }
        return HexFormat.of().formatHex(Arrays.copyOf(digest, 8));
    }

    private static boolean contentAlreadyAt(Path file, byte[] content) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == content.length
                && Arrays.equals(content, Files.readAllBytes(file));
    }

    /**
     * 从文件读出来的字体默认是 1 号字，统一 derive 到表里的字号
     */
    private Font atDefaultSize(Font font) {
        return font.deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE);
    }
}
