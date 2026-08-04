package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.util.FontUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.util.Pair;
import org.springframework.util.CollectionUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 绘图器
 */
@Slf4j
public class CommonPainter {
    private final BuildProperties buildProperties;

    private final StarBotCoreProperties properties;

    private final FontUtil fontUtil;

    @Getter
    private Integer rowSpace = 10;

    @Getter
    private final Integer width;

    @Getter
    private Integer height;

    private final boolean autoExpand;

    private BufferedImage canvas;

    private Graphics2D draw;

    private Point xy;

    public static final int CHAPTER_FONT_SIZE = 50;

    public static final int SECTION_FONT_SIZE = 40;

    public static final int TEXT_FONT_SIZE = 30;

    public static final int TIP_FONT_SIZE = 25;

    public static final Color COLOR_LINK = new Color(23, 139, 207);

    /**
     * 初始化绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @param autoExpand 是否自动扩展画布高度
     */
    public CommonPainter(BuildProperties buildProperties, StarBotCoreProperties properties, FontUtil fontUtil, int width, int height, boolean autoExpand) {
        this.buildProperties = buildProperties;
        this.properties = properties;
        this.fontUtil = fontUtil;

        this.width = width;
        this.height = height;
        this.autoExpand = autoExpand;

        this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.draw = canvas.createGraphics();

        this.draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        this.xy = new Point(0, 0);
    }

    /**
     * 自动按需扩展画布高度
     * @param height 所需绘制高度
     */
    public void expandHeightIfNeeded(int height) {
        expandHeightIfNeeded(height, properties.getPaint().getAutoExpandHeight());
    }

    /**
     * 自动按需扩展画布高度
     * @param height 所需绘制高度
     * @param autoExpandHeight 每次扩展的高度
     */
    public void expandHeightIfNeeded(int height, int autoExpandHeight) {
        if (!this.autoExpand || this.height > height) {
            return;
        }

        int increment  = 0;
        while (this.height + increment <= height) {
            increment += autoExpandHeight;
        }

        int newHeight = this.height + increment;
        BufferedImage newCanvas = new BufferedImage(this.width, newHeight, this.canvas.getType());
        Graphics2D newDraw = newCanvas.createGraphics();
        newDraw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        newDraw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        newDraw.drawImage(this.canvas, 0, 0, null);

        newDraw.setColor(this.draw.getColor());
        newDraw.setFont(this.draw.getFont());

        this.draw.dispose();

        this.canvas = newCanvas;
        this.draw = newDraw;
        this.height = newHeight;
    }

    /**
     * 获取当前 X 坐标
     * @return 当前 X 坐标
     */
    public int getX() {
        return this.xy.x;
    }

    /**
     * 获取当前 Y 坐标
     * @return 当前 Y 坐标
     */
    public int getY() {
        return this.xy.y;
    }

    /**
     * 获取图片
     * @return 图片
     */
    public BufferedImage getImage() {
        return this.canvas;
    }

    /**
     * 设置默认行距
     * @param rowSpace 行距
     * @return 当前绘图器实例
     */
    public CommonPainter setRowSpace(Integer rowSpace) {
        this.rowSpace = rowSpace;
        return this;
    }

    /**
     * 设置绘图坐标
     * @param x X 坐标
     * @param y Y 坐标
     * @return 当前绘图器实例
     */
    public CommonPainter setPos(Integer x, Integer y) {
        int newX = x != null ? x : this.xy.x;
        int newY = y != null ? y : this.xy.y;
        this.xy = new Point(newX, newY);

        expandHeightIfNeeded(newY + this.rowSpace);

        return this;
    }

    /**
     * 移动绘图坐标
     * @param x X 偏移量
     * @param y Y 偏移量
     * @return 当前绘图器实例
     */
    public CommonPainter movePos(int x, int y) {
        this.xy = new Point(this.xy.x + x, this.xy.y + y);

        expandHeightIfNeeded(this.xy.y + this.rowSpace);

        return this;
    }

    /**
     * 绘制一个矩形，此方法不会移动绘图坐标
     * @param x 矩形左上角的 x 坐标
     * @param y 矩形左上角的 y 坐标
     * @param width 矩形的宽度
     * @param height 矩形的高度
     * @param color 矩形的背景颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawRectangle(int x, int y, int width, int height, @NonNull Color color) {
        expandHeightIfNeeded(y + height);

        Color originalColor = this.draw.getColor();

        try {
            this.draw.setColor(color);
            this.draw.fillRect(x, y, width, height);
        } finally {
            this.draw.setColor(originalColor);
        }

        return this;
    }

    /**
     * 绘制一个圆角矩形，此方法不会移动绘图坐标
     * @param x 圆角矩形左上角的 x 坐标
     * @param y 圆角矩形左上角的 y 坐标
     * @param width 圆角矩形的宽度
     * @param height 圆角矩形的高度
     * @param radius 圆角矩形的圆角半径
     * @param color 圆角矩形的背景颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawRoundedRectangle(int x, int y, int width, int height, int radius, @NonNull Color color) {
        expandHeightIfNeeded(y + height);

        Color originalColor = this.draw.getColor();

        try {
            this.draw.setColor(color);
            this.draw.fill(new RoundRectangle2D.Float(x, y, width, height, radius, radius));
        } finally {
            this.draw.setColor(originalColor);
        }

        return this;
    }

    /**
     * 绘制一个多边形，此方法不会移动绘图坐标
     *
     * @param points   多边形的顶点坐标列表
     * @param color    多边形的背景颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawPolygon(@NonNull List<Point> points, @NonNull Color color) {
        if (points.isEmpty()) {
            return this;
        }

        int[] xPoints = new int[points.size()];
        int[] yPoints = new int[points.size()];
        int maxY = 0;

        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            xPoints[i] = point.x;
            yPoints[i] = point.y;
            maxY = Math.max(maxY, yPoints[i]);
        }

        expandHeightIfNeeded(maxY);

        Color originalColor = this.draw.getColor();

        try {
            this.draw.setColor(color);
            this.draw.fillPolygon(xPoints, yPoints, points.size());
        } finally {
            this.draw.setColor(originalColor);
        }

        return this;
    }


    /**
     * 在当前绘图坐标绘制一张图片，并自动移动绘图坐标至下次绘图适合位置
     * @param image 图片
     * @return 当前绘图器实例
     */
    public CommonPainter drawImage(@NonNull BufferedImage image) {
        return drawImage(image, null);
    }

    /**
     * 在指定坐标绘制一张图片，不会自动移动绘图坐标
     * @param image 图片
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawImage(@NonNull BufferedImage image, Point drawLocation) {
        if (drawLocation == null) {
            expandHeightIfNeeded(this.xy.y + image.getHeight() + this.rowSpace);

            this.draw.drawImage(image, this.xy.x, this.xy.y, null);
            this.movePos(0, image.getHeight() + this.rowSpace);
        } else {
            expandHeightIfNeeded(drawLocation.y + image.getHeight() + this.rowSpace);

            this.draw.drawImage(image, drawLocation.x, drawLocation.y, null);
        }

        return this;
    }

    /**
     * 在当前绘图坐标绘制一张附带圆角矩形边框图片，并自动移动绘图坐标至下次绘图适合位置
     * @param image 图片
     * @return 当前绘图器实例
     */
    public CommonPainter drawImageWithBorder(@NonNull BufferedImage image) {
        return drawImageWithBorder(image, null, null, 10, 1);
    }

    /**
     * 在当前绘图坐标绘制一张附带圆角矩形边框图片，并自动移动绘图坐标至下次绘图适合位置
     * @param image 图片
     * @param color 边框颜色
     * @param radius 边框圆角半径
     * @param width 边框粗细
     * @return 当前绘图器实例
     */
    public CommonPainter drawImageWithBorder(@NonNull BufferedImage image, Color color, int radius, int width) {
        return drawImageWithBorder(image, null, color, radius, width);
    }

    /**
     * 在指定坐标绘制一张附带圆角矩形边框的图片，不会自动移动绘图坐标
     * @param image 图片
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawImageWithBorder(@NonNull BufferedImage image, Point drawLocation) {
        return drawImageWithBorder(image, drawLocation, null, 10, 1);
    }

    /**
     * 在指定坐标绘制一张附带圆角矩形边框的图片，不会自动移动绘图坐标
     * @param image 图片
     * @param drawLocation 绘图坐标
     * @param color 边框颜色
     * @param radius 边框圆角半径
     * @param width 边框粗细
     * @return 当前绘图器实例
     */
    public CommonPainter drawImageWithBorder(@NonNull BufferedImage image, Point drawLocation, Color color, int radius, int width) {
        if (drawLocation == null) {
            expandHeightIfNeeded(this.xy.y + image.getHeight() + this.rowSpace + width * 2);
            drawImage(image);
        } else {
            expandHeightIfNeeded(drawLocation.y + image.getHeight() + this.rowSpace + width * 2);
            drawImage(image, drawLocation);
        }

        BufferedImage border = new BufferedImage(image.getWidth() + (width * 2), image.getHeight() + (width * 2), BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = border.createGraphics();
        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setColor(color != null ? color : Color.BLACK);
        draw.setStroke(new BasicStroke(width));
        draw.draw(new RoundRectangle2D.Float(0, 0, image.getWidth(), image.getHeight(), radius, radius));
        draw.dispose();

        if (drawLocation == null) {
            this.draw.drawImage(border, this.xy.x - width, this.xy.y - width, null);
        } else {
            this.draw.drawImage(border, drawLocation.x - width, drawLocation.y - width, null);
        }

        return this;
    }

    /**
     * 在当前绘图坐标绘制章节标题，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param chapter 章节名称
     * @return 当前绘图器实例
     */
    public CommonPainter drawChapter(@NonNull String chapter) {
        return drawTextWithStyle(List.of(new TextWithStyle(chapter, CHAPTER_FONT_SIZE, Color.BLACK, Font.BOLD)));
    }

    /**
     * 在当前绘图坐标绘制章节标题，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param chapter 章节名称
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawChapter(@NonNull String chapter, Color color) {
        return drawTextWithStyle(List.of(new TextWithStyle(chapter, CHAPTER_FONT_SIZE, color, Font.BOLD)));
    }

    /**
     * 在指定坐标绘制章节标题，不会自动移动绘图坐标
     *
     * @param chapter 章节名称
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawChapter(@NonNull String chapter, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(chapter, CHAPTER_FONT_SIZE, Color.BLACK, Font.BOLD)), drawLocation);
    }

    /**
     * 在指定坐标绘制章节标题，不会自动移动绘图坐标
     *
     * @param chapter 章节名称
     * @param color 字体颜色
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawChapter(@NonNull String chapter, Color color, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(chapter, CHAPTER_FONT_SIZE, color, Font.BOLD)), drawLocation);
    }

    /**
     * 在当前绘图坐标绘制小节标题，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param section 小节名称
     * @return 当前绘图器实例
     */
    public CommonPainter drawSection(@NonNull String section) {
        return drawTextWithStyle(List.of(new TextWithStyle(section, SECTION_FONT_SIZE, Color.BLACK, Font.BOLD)));
    }

    /**
     * 在当前绘图坐标绘制小节标题，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param section 小节名称
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawSection(@NonNull String section, Color color) {
        return drawTextWithStyle(List.of(new TextWithStyle(section, SECTION_FONT_SIZE, color, Font.BOLD)));
    }

    /**
     * 在指定坐标绘制小节标题，不会自动移动绘图坐标
     *
     * @param section 小节名称
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawSection(@NonNull String section, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(section, SECTION_FONT_SIZE, Color.BLACK, Font.BOLD)), drawLocation);
    }

    /**
     * 在指定坐标绘制小节标题，不会自动移动绘图坐标
     *
     * @param section 小节名称
     * @param color 字体颜色
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawSection(@NonNull String section, Color color, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(section, SECTION_FONT_SIZE, color, Font.BOLD)), drawLocation);
    }

    /**
     * 在当前绘图坐标绘制正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 正文文本
     * @return 当前绘图器实例
     */
    public CommonPainter drawText(@NonNull String text) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, Color.BLACK, Font.PLAIN)));
    }

    /**
     * 在当前绘图坐标绘制正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 正文文本
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawText(@NonNull String text, Color color) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)));
    }

    /**
     * 在指定坐标绘制正文文本，不会自动移动绘图坐标
     *
     * @param text 正文文本
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawText(@NonNull String text, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, Color.BLACK, Font.PLAIN)), drawLocation);
    }

    /**
     * 在指定坐标绘制正文文本，不会自动移动绘图坐标
     *
     * @param text 正文文本
     * @param color 字体颜色
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawText(@NonNull String text, Color color, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)), drawLocation);
    }

    /**
     * 在当前绘图坐标绘制提示信息，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param tip 提示信息
     * @return 当前绘图器实例
     */
    public CommonPainter drawTip(@NonNull String tip) {
        return drawTextWithStyle(List.of(new TextWithStyle(tip, TIP_FONT_SIZE, Color.LIGHT_GRAY, Font.PLAIN)));
    }

    /**
     * 在当前绘图坐标绘制提示信息，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param tip 提示信息
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawTip(@NonNull String tip, Color color) {
        return drawTextWithStyle(List.of(new TextWithStyle(tip, TIP_FONT_SIZE, color, Font.PLAIN)));
    }

    /**
     * 在指定坐标绘制提示信息，不会自动移动绘图坐标
     *
     * @param tip 提示信息
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTip(@NonNull String tip, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(tip, TIP_FONT_SIZE, Color.LIGHT_GRAY, Font.PLAIN)), drawLocation);
    }

    /**
     * 在指定坐标绘制提示信息，不会自动移动绘图坐标
     *
     * @param tip 提示信息
     * @param color 字体颜色
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTip(@NonNull String tip, Color color, Point drawLocation) {
        return drawTextWithStyle(List.of(new TextWithStyle(tip, TIP_FONT_SIZE, color, Font.PLAIN)), drawLocation);
    }

    /**
     * 在当前绘图坐标绘制多行正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 多行正文文本
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextMultiLine(@NonNull String text, int marginRight) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, Color.BLACK, Font.PLAIN)), null, true, marginRight);
    }

    /**
     * 在当前绘图坐标绘制多行正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 多行正文文本
     * @param color 字体颜色
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextMultiLine(@NonNull String text, Color color, int marginRight) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)), null, true, marginRight);
    }

    /**
     * 在指定坐标绘制多行正文文本，不会自动移动绘图坐标
     *
     * @param text 多行正文文本
     * @param drawLocation 绘图坐标
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextMultiLine(@NonNull String text, Point drawLocation, int marginRight) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, Color.BLACK, Font.PLAIN)), drawLocation, true, marginRight);
    }

    /**
     * 在指定坐标绘制多行正文文本，不会自动移动绘图坐标
     *
     * @param text 多行正文文本
     * @param color 字体颜色
     * @param drawLocation 绘图坐标
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextMultiLine(@NonNull String text, Color color, Point drawLocation, int marginRight) {
        return drawTextWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)), drawLocation, true, marginRight);
    }

    /**
     * 在当前绘图坐标绘制含格式文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param texts 含格式文本列表
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextWithStyle(@NonNull List<TextWithStyle> texts) {
        return drawTextWithStyle(texts, null);
    }

    /**
     * 在指定坐标绘制含格式文本，不会自动移动绘图坐标
     *
     * @param texts 含格式文本列表
     * @param drawLocation 绘图坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextWithStyle(@NonNull List<TextWithStyle> texts, Point drawLocation) {
        return drawTextWithStyle(texts, drawLocation, false, 0);
    }

    /**
     * 在指定坐标绘制含格式文本，不会自动移动绘图坐标
     *
     * @param texts 含格式文本列表
     * @param drawLocation 绘图坐标
     * @param autoWrap 是否自动换行
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextWithStyle(@NonNull List<TextWithStyle> texts, Point drawLocation, boolean autoWrap, int marginRight) {
        if (texts.isEmpty()) {
            return this;
        }

        int maxLineWidth = this.width - marginRight;
        if (maxLineWidth <= 0) {
            throw new IllegalArgumentException("右边距不能大于画布宽度");
        }

        Point currentPoint = drawLocation != null ? drawLocation : new Point(this.xy.x, this.xy.y);
        int maxHeight = 0;

        for (TextWithStyle text : texts) {
            if (StringUtil.isEmpty(text.getText())) {
                continue;
            }

            Color originalColor = this.draw.getColor();
            Font originalFont = this.draw.getFont();

            try {
                this.draw.setColor(text.getColor() != null ? text.getColor() : Color.BLACK);

                for (int i = 0; i < text.getText().length(); ) {
                    int codePoint = text.getText().codePointAt(i);
                    String charStr = new String(Character.toChars(codePoint));

                    i += Character.charCount(codePoint);

                    Font font = text.getFont() != null ? text.getFont() : fontUtil.findFontForCharacter(codePoint).deriveFont(text.getStyle(), text.getSize());
                    this.draw.setFont(font);

                    FontMetrics metrics = this.draw.getFontMetrics();
                    int charWidth = metrics.stringWidth(charStr);
                    int charHeight = metrics.getHeight();
                    maxHeight = Math.max(maxHeight, charHeight);

                    if ("\n".equals(charStr) || (autoWrap && currentPoint.x + charWidth > maxLineWidth)) {
                        currentPoint.x = this.xy.x;
                        currentPoint.y += maxHeight + this.rowSpace;
                        maxHeight = charHeight;
                    }

                    expandHeightIfNeeded(currentPoint.y + maxHeight + this.rowSpace);

                    if (!"\n".equals(charStr)) {
                        this.draw.drawString(charStr, currentPoint.x, currentPoint.y + metrics.getAscent());
                        currentPoint.x += charWidth;
                    }
                }
            } finally {
                this.draw.setColor(originalColor);
                this.draw.setFont(originalFont);
            }
        }

        if (drawLocation == null) {
            setPos(this.xy.x, currentPoint.y + maxHeight + this.rowSpace);
        }
    
        return this;
    }

    /**
     * 在当前绘图坐标绘制右对齐正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 正文文本
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRight(@NonNull String text, int marginRight) {
        return drawTextRightWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, Color.BLACK, Font.PLAIN)), null, marginRight, null);
    }

    /**
     * 在当前绘图坐标绘制右对齐正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 正文文本
     * @param color 字体颜色
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRight(@NonNull String text, Color color, int marginRight) {
        return drawTextRightWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)), null, marginRight, null);
    }

    /**
     * 在当前绘图坐标绘制右对齐正文文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param text 正文文本
     * @param color 字体颜色
     * @param marginRight 右边距
     * @param noCoverLimit 不可覆盖限制坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRight(@NonNull String text, Color color, int marginRight, Point noCoverLimit) {
        return drawTextRightWithStyle(List.of(new TextWithStyle(text, TEXT_FONT_SIZE, color, Font.PLAIN)), null, marginRight, noCoverLimit);
    }

    /**
     * 在当前绘图坐标绘制右对齐含格式文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param texts 含格式文本列表
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRightWithStyle(@NonNull List<TextWithStyle> texts, int marginRight) {
        return drawTextRightWithStyle(texts, null, marginRight, null);
    }

    /**
     * 在当前绘图坐标绘制右对齐含格式文本，并自动移动绘图坐标至下次绘图适合位置
     *
     * @param texts 含格式文本列表
     * @param marginRight 右边距
     * @param noCoverLimit 不可覆盖限制坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRightWithStyle(@NonNull List<TextWithStyle> texts, int marginRight, Point noCoverLimit) {
        return drawTextRightWithStyle(texts, null, marginRight, noCoverLimit);
    }

    /**
     * 在指定坐标绘制右对齐含格式文本，不会自动移动绘图坐标
     *
     * @param texts 含格式文本列表
     * @param drawLocation 绘图坐标
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRightWithStyle(@NonNull List<TextWithStyle> texts, Point drawLocation, int marginRight) {
        return drawTextRightWithStyle(texts, drawLocation, marginRight, null);
    }

    /**
     * 在指定坐标绘制右对齐含格式文本，不会自动移动绘图坐标
     *
     * @param texts 含格式文本列表
     * @param drawLocation 绘图坐标
     * @param marginRight 右边距
     * @param noCoverLimit 不可覆盖限制坐标
     * @return 当前绘图器实例
     */
    public CommonPainter drawTextRightWithStyle(@NonNull List<TextWithStyle> texts, Point drawLocation, int marginRight, Point noCoverLimit) {
        if (texts.isEmpty()) {
            return this;
        }

        List<Pair<Integer, Integer>> sizes = texts.stream().map(text -> fontUtil.getStringWidthAndHeight(this.draw, text)).toList();

        for (int i = 0; i < sizes.size(); i++) {
            Pair<Integer, Integer> size = sizes.get(i);

            Point currentPoint = drawLocation != null ? drawLocation : new Point(this.xy.x, this.xy.y);
            currentPoint.x = this.width - size.getFirst() - marginRight;
            if (noCoverLimit != null) {
                currentPoint.y = Math.max(currentPoint.y, noCoverLimit.y);
            }

            drawTextWithStyle(List.of(texts.get(i)), currentPoint);

            if (drawLocation == null) {
                setPos(this.xy.x, currentPoint.y + size.getSecond() + this.rowSpace);
            }
        }

        return this;
    }

    /**
     * 绘制版权信息
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawCopyright(int marginRight) {
        return drawCopyright(List.of(), List.of(), marginRight);
    }

    /**
     * 绘制版权信息
     * @param extraMiddle 附加版权信息，会输出到默认两行版权信息之间
     * @param extraBottom 附加版权信息，会输出到默认两行版权信息之后
     * @param marginRight 右边距
     * @return 当前绘图器实例
     */
    public CommonPainter drawCopyright(List<List<TextWithStyle>> extraMiddle, List<List<TextWithStyle>> extraBottom, int marginRight) {
        // 底部默认版权信息。上游项目的致谢见 README 与 banner
        drawTextRight("Running on NovaBot v" + buildProperties.getVersion(), Color.LIGHT_GRAY, marginRight);

        for (List<TextWithStyle> line : extraMiddle) {
            drawTextRightWithStyle(line, marginRight);
        }

        drawTextRight("https://github.com/rossinova/NovaBot", COLOR_LINK, marginRight);

        for (List<TextWithStyle> line : extraBottom) {
            drawTextRightWithStyle(line, marginRight);
        }

        if (!CollectionUtils.isEmpty(properties.getPaint().getExtraCopyrights())) {
            drawTextRightWithStyle(properties.getPaint().getExtraCopyrights(), marginRight);
        }

        return this;
    }

    /**
     * 计算指定字符串绘制时的像素宽度和高度
     *
     * @param text 要计算宽度和高度的文本
     * @return 宽度，高度
     */
    public Pair<Integer, Integer> getStringWidthAndHeight(String text) {
        return fontUtil.getStringWidthAndHeight(this.draw, new TextWithStyle(text, TEXT_FONT_SIZE));
    }

    /**
     * 计算指定字符串绘制时的像素宽度和高度
     *
     * @param text 要计算宽度和高度的文本
     * @param size 字体大小
     * @return 宽度，高度
     */
    public Pair<Integer, Integer> getStringWidthAndHeight(String text, int size) {
        return fontUtil.getStringWidthAndHeight(this.draw, new TextWithStyle(text, size));
    }

    /**
     * 判断绘制字符是否需要换行
     * @param codePoint 字符编码
     * @param margin 左右边距
     * @return 是否需要换行
     */
    public boolean isNeedWrap(int codePoint, int margin)  {
        return isNeedWrap(codePoint, margin, null, null);
    }

    /**
     * 判断绘制字符是否需要换行
     * @param codePoint 字符编码
     * @param margin 左右边距
     * @param font 字体
     * @return 是否需要换行
     */
    public boolean isNeedWrap(int codePoint, int margin, @Nullable Font font)  {
        return isNeedWrap(codePoint, margin, font, null);
    }

    /**
     * 判断绘制字符是否需要换行
     * @param codePoint 字符编码
     * @param margin 左右边距
     * @param size 字体大小
     * @return 是否需要换行
     */
    public boolean isNeedWrap(int codePoint, int margin, @Nullable Integer size)  {
        return isNeedWrap(codePoint, margin, null, size);
    }

    /**
     * 判断绘制字符是否需要换行
     * @param codePoint 字符编码
     * @param margin 左右边距
     * @param font 字体
     * @param size 字体大小
     * @return 是否需要换行
     */
    public boolean isNeedWrap(int codePoint, int margin, @Nullable Font font, @Nullable Integer size)  {
        String charStr = new String(Character.toChars(codePoint));

        if ("\n".equals(charStr)) {
            return true;
        }

        Font originalFont = this.draw.getFont();

        if (font != null) {
            this.draw.setFont(font);
        } else {
            this.draw.setFont(fontUtil.findFontForCharacter(codePoint).deriveFont(Font.PLAIN, Objects.requireNonNullElse(size, TEXT_FONT_SIZE)));
        }
        FontMetrics metrics = this.draw.getFontMetrics();
        int charWidth = metrics.stringWidth(charStr);

        this.draw.setFont(originalFont);

        return this.xy.x + charWidth > this.width - margin;
    }

    /**
     * 判断绘制多个字符是否需要换行
     * @param codePoints 字符编码列表
     * @param margin 左右边距
     * @return 是否需要换行
     */
    public boolean isNeedWrap(List<Integer> codePoints, int margin)  {
        return isNeedWrap(codePoints, margin, null, null);
    }

    /**
     * 判断绘制多个字符是否需要换行
     * @param codePoints 字符编码列表
     * @param margin 左右边距
     * @param font 字体
     * @return 是否需要换行
     */
    public boolean isNeedWrap(List<Integer> codePoints, int margin, @Nullable Font font)  {
        return isNeedWrap(codePoints, margin, font, null);
    }

    /**
     * 判断绘制多个字符是否需要换行
     * @param codePoints 字符编码列表
     * @param margin 左右边距
     * @param size 字体大小
     * @return 是否需要换行
     */
    public boolean isNeedWrap(List<Integer> codePoints, int margin, @Nullable Integer size)  {
        return isNeedWrap(codePoints, margin, null, size);
    }

    /**
     * 判断绘制多个字符是否需要换行
     * @param codePoints 字符编码列表
     * @param margin 左右边距
     * @param font 字体
     * @param size 字体大小
     * @return 是否需要换行
     */
    public boolean isNeedWrap(List<Integer> codePoints, int margin, @Nullable Font font, @Nullable Integer size)  {
        Font originalFont = this.draw.getFont();

        int sum = 0;
        for (int codePoint : codePoints) {
            String charStr = new String(Character.toChars(codePoint));

            if ("\n".equals(charStr)) {
                return true;
            }

            if (font != null) {
                this.draw.setFont(font);
            } else {
                this.draw.setFont(fontUtil.findFontForCharacter(codePoint).deriveFont(Font.PLAIN, Objects.requireNonNullElse(size, TEXT_FONT_SIZE)));
            }
            FontMetrics metrics = this.draw.getFontMetrics();
            sum = sum + metrics.stringWidth(charStr);
        }

        this.draw.setFont(originalFont);

        return this.xy.x + sum > this.width - margin;
    }

    /**
     * 判断绘制图片是否需要换行
     * @param image 图片
     * @param margin 左右边距
     * @return 是否需要换行
     */
    public boolean isNeedWrap(BufferedImage image, int margin)  {
        return this.xy.x + image.getWidth() > this.width - margin;
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint) {
        return drawElement(codePoint, null, null, null);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param font 字体
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Font font) {
        return drawElement(codePoint, font, null, null);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Color color) {
        return drawElement(codePoint, null, color, null);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param size 字体大小
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Integer size) {
        return drawElement(codePoint, null, null, size);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param color 字体颜色
     * @param size 字体大小
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Color color, @Nullable Integer size) {
        return drawElement(codePoint, null, color, size);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param font 字体
     * @param color 字体颜色
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Font font, @Nullable Color color) {
        return drawElement(codePoint, font, color, null);
    }

    /**
     * 绘制字符元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param codePoint 字符编码
     * @param font 字体
     * @param color 字体颜色
     * @param size 字体大小
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(int codePoint, @Nullable Font font, @Nullable Color color, @Nullable Integer size) {
        String charStr = new String(Character.toChars(codePoint));

        if ("\n".equals(charStr)) {
            return this;
        }

        Font originalFont = this.draw.getFont();
        Color originalColor = this.draw.getColor();

        if (font != null) {
            this.draw.setFont(font);
        } else {
            this.draw.setFont(fontUtil.findFontForCharacter(codePoint).deriveFont(Font.PLAIN, Objects.requireNonNullElse(size, TEXT_FONT_SIZE)));
        }
        this.draw.setColor(color != null ? color : Color.BLACK);
        FontMetrics metrics = this.draw.getFontMetrics();
        int charWidth = metrics.stringWidth(charStr);
        int charHeight = metrics.getHeight();

        expandHeightIfNeeded(this.xy.y + charHeight, 1);

        this.draw.drawString(charStr, this.xy.x, this.xy.y + metrics.getAscent());
        this.xy.x += charWidth;

        this.draw.setFont(originalFont);
        this.draw.setColor(originalColor);

        return this;
    }

    /**
     * 绘制图片元素，绘制结束后会自动移动绘图坐标至下次绘制元素适合位置
     * @param element 图片
     * @return 当前绘图器实例
     */
    public CommonPainter drawElement(BufferedImage element) {
        int width = element.getWidth();
        int height = element.getHeight();

        expandHeightIfNeeded(this.xy.y + height, 1);

        this.draw.drawImage(element, this.xy.x, this.xy.y, null);
        this.xy.x += width;

        return this;
    }

    /**
     * 为当前画布创建一个圆角矩形背景
     *
     * @param setupDraw 设置绘制背景
     * @param radius 圆角矩形的圆角半径
     * @return 当前绘图器实例
     */
    private CommonPainter createRoundedRectangleBackground(@NonNull Consumer<Graphics2D> setupDraw, int radius) {
        int contentHeight = this.xy.y;

        BufferedImage newCanvas = new BufferedImage(this.width, contentHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D newDraw = newCanvas.createGraphics();
        newDraw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        setupDraw.accept(newDraw);
        newDraw.fillRoundRect(0, 0, this.width, contentHeight, radius, radius);

        BufferedImage clippedContent = this.canvas.getSubimage(0, 0, this.width, contentHeight);
        newDraw.drawImage(clippedContent, 0, 0, null);

        newDraw.setPaint(this.draw.getPaint());
        newDraw.setColor(this.draw.getColor());
        newDraw.setFont(this.draw.getFont());

        this.draw.dispose();
        this.canvas = newCanvas;
        this.draw = newDraw;

        return this;
    }

    /**
     * 为当前画布创建一个指定颜色的圆角矩形背景
     *
     * @param color 圆角矩形的背景颜色
     * @param radius 圆角矩形的圆角半径
     * @return 当前绘图器实例
     */
    public CommonPainter createSolidRoundedRectangleBackground(@NonNull Color color, int radius) {
        return createRoundedRectangleBackground(draw -> draw.setColor(color), radius);
    }

    /**
     * 为当前画布创建一个渐变颜色的圆角矩形背景
     *
     * @param color 圆角矩形的渐变背景颜色
     * @param radius 圆角矩形的圆角半径
     * @return 当前绘图器实例
     */
    public CommonPainter createGradientRoundedRectangleBackground(@NonNull LinearGradientPaint color, int radius) {
        return createRoundedRectangleBackground(draw -> draw.setPaint(color), radius);
    }

    /**
     * 保存图片，终端操作，保存完成后会关闭图片，无法再对图片进行操作
     *
     * @param path 保存路径
     */
    public void save(String path) {
        try {
            ImageIO.write(this.canvas, "PNG", Paths.get(path).toFile());
        } catch (Exception e) {
            log.error("图片保存失败", e);
        }
        this.draw.dispose();
    }

    /**
     * 获取 Base64 字符串，终端操作，获取后会关闭图片，无法再对图片进行操作
     *
     * @return Base64 字符串
     */
    public Optional<String> base64() {
        Optional<String> result;

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(this.canvas, "PNG", outputStream);
            result = Optional.ofNullable(Base64.getEncoder().encodeToString(outputStream.toByteArray()));
        } catch (Exception e) {
            log.error("图片转换 Base64 失败", e);
            result = Optional.empty();
        }

        this.draw.dispose();
        return result;
    }

    /**
     * 获取 BufferedImage 对象，终端操作，获取后会关闭图片，无法再对图片进行操作
     *
     * @return BufferedImage 对象
     */
    public BufferedImage getBufferedImage() {
        this.draw.dispose();
        return this.canvas;
    }

    /**
     * 保存图片并获取 Base64 字符串，终端操作，保存完成后会关闭图片，无法再对图片进行操作
     *
     * @param path 保存路径
     * @return Base64 字符串
     */
    public Optional<String> saveAndGetBase64(String path) {
        try {
            ImageIO.write(this.canvas, "PNG", Paths.get(path).toFile());
        } catch (Exception e) {
            log.error("图片保存失败", e);
        }

        return base64();
    }
}
