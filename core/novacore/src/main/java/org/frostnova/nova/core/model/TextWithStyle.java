package org.frostnova.nova.core.model;

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

    /**
     * 风格
     */
    private int style;

    public TextWithStyle(String text, int size) {
        this.text = text;
        this.size = size;
        // 未指定颜色时由绘制器按当前配色取默认文字色。
        this.color = null;
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
}
