package com.starlwr.bot.core.util;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字体工具类
 */
@Slf4j
@Component
public class FontUtil {
    private final ResourceLoader resourceLoader;

    private final StarBotCoreProperties properties;

    private Set<String> systemFonts = new HashSet<>();

    private final List<Font> fonts = new ArrayList<>();

    private final int DEFAULT_FONT_SIZE = 30;

    @Autowired
    public FontUtil(ResourceLoader resourceLoader, StarBotCoreProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        systemFonts = Arrays.stream(graphicsEnvironment.getAvailableFontFamilyNames())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        log.info("已指定使用字体列表: {}, 可使用配置项 starbot.core.paint.fonts 自定义字体列表", properties.getPaint().getFonts());
        for (String fontDefinition: properties.getPaint().getFonts()) {
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
            if (systemFonts.contains(font.toLowerCase())) {
                return Optional.of(new Font(font, Font.PLAIN, DEFAULT_FONT_SIZE));
            } else if (font.toLowerCase().endsWith(".ttf")) {
                return Optional.of(Font.createFont(Font.TRUETYPE_FONT, Paths.get(font).toFile()).deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE));
            } else if ("内置".equals(font)) {
                try (InputStream fontStream = resourceLoader.getResource("classpath:fonts/font.ttf").getInputStream()) {
                    return Optional.of(Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE));
                }
            } else {
                log.warn("{} 不在系统字体库中, 且不是一个有效的字体文件", font);
            }
        } catch (Exception e) {
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
                .orElse(fonts.get(0));
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

        int width = 0;
        int maxHeight = 0;

        Font originalFont = draw.getFont();

        if (text.getFont() != null) {
            draw.setFont(text.getFont().deriveFont(text.getStyle(), text.getSize()));
        }

        int[] codePoints = text.getText().codePoints().toArray();
        for (int codePoint : codePoints) {
            if (text.getFont() == null) {
                draw.setFont(findFontForCharacter(codePoint).deriveFont(text.getStyle(), text.getSize()));
            }

            String charAsString = new String(Character.toChars(codePoint));
            width += draw.getFontMetrics().stringWidth(charAsString);
            maxHeight = Math.max(maxHeight, draw.getFontMetrics().getHeight());
        }

        draw.setFont(originalFont);

        return Pair.of(width, maxHeight);
    }
}
