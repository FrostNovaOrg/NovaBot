package com.starlwr.bot.report.util;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.lang.StringUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字体工具类
 * <p>
 * 每一张图上的每一个字都从这里过两趟：先按字挑一个显示得出它的字体，再拿这个字体量宽。
 * 字体表按配置项 {@code starbot.core.paint.fonts} 的顺序排，<b>顺序即优先级</b>——
 * 排在前面的字体先被问到，所以正文字体该排在只补表情、只补符号的那几个之前。
 */
@Slf4j
@Component
public class FontUtil {
    /**
     * 配置里代表随程序发布的那份字体的写法
     * <p>
     * 有它兜底，一台什么字体都没装的服务器也画得出中文；
     * 这个词是使用者写在 yml 里的值，改它等于改配置格式
     */
    private static final String BUNDLED_FONT = "内置";

    private static final String BUNDLED_FONT_LOCATION = "classpath:fonts/font.ttf";

    /**
     * 装进表里时统一用的字号
     * <p>
     * 真正画的时候一律 {@code deriveFont} 到当时要的字号，所以这个数只是个占位；
     * 表里存的字体不带「当前字号」这个状态，两处画不同大小的字才不会互相干扰
     */
    private static final int DEFAULT_FONT_SIZE = 30;

    private final ResourceLoader resourceLoader;

    private final StarBotCoreProperties properties;

    private Set<String> systemFonts = new HashSet<>();

    private final List<Font> fonts = new ArrayList<>();

    @Autowired
    public FontUtil(ResourceLoader resourceLoader, StarBotCoreProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 系统字体名一律按小写存：使用者在 yml 里写「pingfang sc」还是「PingFang SC」都该认得出
        systemFonts = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<String> configured = properties.getPaint().getFonts();
        log.info("已指定使用字体列表: {}, 可使用配置项 starbot.core.paint.fonts 自定义字体列表", configured);

        // 装不上的那一项直接跳过，不占位置：留个空位在表里，挑字体时会挑到一个 null
        for (String fontDefinition : configured) {
            parseFont(fontDefinition).ifPresent(fonts::add);
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

            if (BUNDLED_FONT.equals(font)) {
                return Optional.of(loadBundledFont());
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
     * 查找可以显示指定字符的字体
     *
     * @param charCodePoint 字符编码
     * @return 可以显示该字符的字体
     */
    public Font findFontForCharacter(int charCodePoint) {
        return fonts.stream()
                .filter(font -> font.canDisplay(charCodePoint))
                .findFirst()
                // 谁都显示不出时用表里第一个：画出来是个豆腐块，但整张图还在。
                // 一个字体都没装上时这里会抛——那是配置问题，越早响越好
                .orElseGet(() -> fonts.get(0));
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

    private Font loadBundledFont() throws IOException, FontFormatException {
        try (InputStream fontStream = resourceLoader.getResource(BUNDLED_FONT_LOCATION).getInputStream()) {
            return atDefaultSize(Font.createFont(Font.TRUETYPE_FONT, fontStream));
        }
    }

    /**
     * 从文件读出来的字体默认是 1 号字，统一 derive 到表里的字号
     */
    private Font atDefaultSize(Font font) {
        return font.deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE);
    }
}
