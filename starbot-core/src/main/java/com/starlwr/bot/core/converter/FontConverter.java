package com.starlwr.bot.core.converter;

import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字体转换器
 */
@Slf4j
@Component
@ConfigurationPropertiesBinding
public class FontConverter implements Converter<String, Font> {
    private static final Set<String> systemFonts;

    private static final int DEFAULT_FONT_SIZE = 30;

    static {
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        systemFonts = Arrays.stream(graphicsEnvironment.getAvailableFontFamilyNames())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    @Override
    public Font convert(@NonNull String source) {
        if (StringUtil.isBlank(source)) {
            return null;
        }

        return parseFont(source.trim())
                .orElseThrow(() -> new IllegalArgumentException("无法解析配置文件中的字体: " + source));
    }

    /**
     * 解析字体
     * @param font 字体名称或路径
     * @return 字体
     */
    private Optional<Font> parseFont(String font) {
        try {
            if (systemFonts.contains(font.toLowerCase())) {
                return Optional.of(new Font(font, Font.PLAIN, DEFAULT_FONT_SIZE));
            } else if (font.toLowerCase().endsWith(".ttf")) {
                return Optional.of(Font.createFont(Font.TRUETYPE_FONT, Paths.get(font).toFile()).deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE));
            } else {
                log.warn("{} 不在系统字体库中, 且不是一个有效的字体文件", font);
            }
        } catch (Exception e) {
            log.error("加载 {} 字体失败", font, e);
        }

        return Optional.empty();
    }
}
