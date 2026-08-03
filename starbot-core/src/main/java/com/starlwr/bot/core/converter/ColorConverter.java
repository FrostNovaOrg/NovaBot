package com.starlwr.bot.core.converter;

import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.awt.*;

/**
 * 颜色转换器
 */
@Component
@ConfigurationPropertiesBinding
public class ColorConverter implements Converter<String, Color> {
    @Override
    public Color convert(@NonNull String source) {
        if (StringUtil.isBlank(source)) {
            return null;
        }

        String value = source.trim();

        try {
            try {
                return (Color) Color.class.getField(value.toUpperCase()).get(null);
            } catch (NoSuchFieldException ignored) {
            }

            if (value.matches("\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}")) {
                String[] parts = value.split("\\s*,\\s*");
                int r = Integer.parseInt(parts[0]);
                int g = Integer.parseInt(parts[1]);
                int b = Integer.parseInt(parts[2]);
                return new Color(r, g, b);
            }

            if (value.startsWith("#")) {
                return Color.decode(value);
            } else if (value.matches("[0-9a-fA-F]{6}")) {
                return Color.decode("#" + value);
            }

        } catch (Exception ignored) {
        }

        throw new IllegalArgumentException("无法解析配置文件中的颜色值: " + source);
    }
}
