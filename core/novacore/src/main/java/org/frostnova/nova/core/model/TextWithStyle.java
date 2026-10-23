package org.frostnova.nova.core.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.awt.*;

/**
 * 含格式文本
 */
@Getter
@Setter
@NoArgsConstructor
public class TextWithStyle {
    /**
     * 文本内容
     */
    private String text;

    /**
     * 字体
     */
    private Font font;

    /**
     * 大小
     */
    private int size;

    /**
     * 颜色
     */
    private Color color;

    // 两参构造保留可读取的默认色，绘制器仍可区分是否指定过颜色。
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean implicitColor;

    /**
     * 风格
     */
    private int style;

    public TextWithStyle(String text, int size) {
        this.text = text;
        this.size = size;
        this.color = Color.BLACK;
        this.implicitColor = true;
        this.style = Font.PLAIN;
    }

    public TextWithStyle(String text, int size, Color color) {
        this.text = text;
        this.size = size;
        this.color = color;
        this.style = Font.PLAIN;
    }

    public TextWithStyle(String text, int size, Color color, int style) {
        this.text = text;
        this.size = size;
        this.color = color;
        this.style = style;
    }

    public TextWithStyle(String text, Font font, int size, Color color, int style) {
        this.text = text;
        this.font = font;
        this.size = size;
        this.color = color;
        this.style = style;
    }

    /**
     * 是否显式指定了非空颜色；未指定时绘制器可使用画布的默认字色。
     */
    public boolean hasExplicitColor() {
        return color != null && !implicitColor;
    }

    public void setColor(Color color) {
        this.color = color;
        this.implicitColor = false;
    }
}
