package org.frostnova.nova.core.converter;

import org.frostnova.nova.core.lang.StringUtil;
import lombok.NonNull;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.util.regex.Pattern;

/**
 * 颜色转换器
 * <p>
 * 接的是使用者手写在 yml 里的颜色值。认三种写法：英文颜色名（{@code RED}）、
 * 逗号分隔的三个分量（{@code 255,0,0}）、十六进制（{@code #FF0000} 或 {@code FF0000}）——
 * 三种都是有人已经写在自己配置里的，少认一种就是一次启动失败。
 * <p>
 * 认不出来时<b>当场抛</b>而不是退回某个默认色：一个悄悄变黑的颜色，
 * 使用者只会以为是程序画错了，不会想到是自己那一行写错了。
 */
@Component
@ConfigurationPropertiesBinding
public class ColorConverter implements Converter<String, Color> {
    /** 三个 0–255 的分量，逗号两边允许有空格 */
    private static final Pattern RGB = Pattern.compile("\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}");

    private static final Pattern RGB_SEPARATOR = Pattern.compile("\\s*,\\s*");

    /** 不带井号的六位十六进制 */
    private static final Pattern BARE_HEX = Pattern.compile("[0-9a-fA-F]{6}");

    @Override
    public Color convert(@NonNull String source) {
        // 留空表示这一项不设置，交回 null 让上层用它自己的默认值
        if (StringUtil.isBlank(source)) {
            return null;
        }

        String value = source.trim();

        try {
            Color color = byName(value);
            if (color == null) {
                color = byComponents(value);
            }
            if (color == null) {
                color = byHex(value);
            }

            if (color != null) {
                return color;
            }
        } catch (Exception ignored) {
            // 认出了是哪种写法、但解到一半失败（分量越界、十六进制里有别的字符……），
            // 一律落到下面那句：报错里要出现的是使用者写的那一串，
            // 而不是某个解析器的内部消息——后者说不清是哪一行配置写错了
        }

        throw new IllegalArgumentException("无法解析配置文件中的颜色值: " + source);
    }

    /**
     * 按英文颜色名取 {@link Color} 上的同名常量
     * <p>
     * 整串转大写去找，所以写成 {@code red}、{@code RED}、{@code Red} 都认，
     * 但驼峰的 {@code lightGray} 取不到——常量名叫 {@code LIGHT_GRAY}
     *
     * @return 没有这个名字时返回 null，交给后面的写法去认
     */
    private Color byName(String value) throws ReflectiveOperationException {
        try {
            // Color 还从 Transparency 继承了几个 int 常量，按名字也找得到；
            // 这里的强制转换就是拦它们的那一道，转不过去会落到调用处那句报错
            return (Color) Color.class.getField(value.toUpperCase()).get(null);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    /**
     * 按逗号分隔的三个分量取色
     *
     * @return 不是这种写法时返回 null；分量越界时抛
     */
    private Color byComponents(String value) {
        if (!RGB.matcher(value).matches()) {
            return null;
        }

        String[] parts = RGB_SEPARATOR.split(value);
        return new Color(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /**
     * 按十六进制取色
     * <p>
     * ⚠️ 带井号的那一路走 {@link Color#decode}，它认的是「一个整数」而不是 CSS 里的颜色写法：
     * {@code #F00} 不会被当成 {@code #FF0000}，而是按 0xF00 解成一个近乎全黑的绿
     *
     * @return 不是这种写法时返回 null；十六进制里有别的字符时抛
     */
    private Color byHex(String value) {
        if (value.startsWith("#")) {
            return Color.decode(value);
        }

        if (BARE_HEX.matcher(value).matches()) {
            return Color.decode("#" + value);
        }

        return null;
    }
}
