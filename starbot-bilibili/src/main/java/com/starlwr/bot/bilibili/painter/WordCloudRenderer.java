package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.core.util.FontUtil;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词云的字体测量与绘制
 * <p>
 * 量与画走的是<b>同一趟摆字</b>（{@link #compose}）：{@link #measure} 只取那趟结果的外框，
 * {@link #draw} 按那趟结果逐字落笔。分成两套代码写的话，判据量的是一个形、
 * 图上出来的是另一个形，而两边都不会报错。
 * <p>
 * 逐字取框而不是整串取框，是因为 {@link FontUtil#findFontForCharacter} 本来就<b>按字选字体</b>：
 * 一个词里的汉字与 emoji 可能落在不同字体上，整串交给一个字体去量会量到豆腐块的宽度。
 */
final class WordCloudRenderer implements WordCloudLayout.Measurer {
    private final FontUtil fontUtil;

    /**
     * 只为拿到 {@link FontRenderContext} 与 {@link java.awt.FontMetrics}，不往上面画东西
     */
    private final Graphics2D scratch;

    private final Map<Long, Font> fontCache = new HashMap<>();

    WordCloudRenderer(FontUtil fontUtil) {
        this.fontUtil = fontUtil;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.scratch = probe.createGraphics();
        applyHints(this.scratch);
    }

    /**
     * 量与画必须用同一套渲染提示，否则字距会差
     */
    private static void applyHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    @Override
    public Dimension measure(String text, int fontSize, boolean vertical) {
        Composed composed = compose(text, fontSize, vertical);
        return new Dimension(composed.width, composed.height);
    }

    /**
     * 把一个词画到 {@code graphics} 上，{@code x} {@code y} 是包围盒左上角
     */
    void draw(Graphics2D graphics, WordCloudLayout.Placement placement) {
        Composed composed = compose(placement.text(), placement.fontSize(), placement.vertical());
        graphics.setColor(placement.color());
        for (Stroke stroke : composed.strokes) {
            graphics.setFont(stroke.font);
            graphics.drawString(stroke.text,
                    placement.box().x + stroke.x - composed.offsetX,
                    placement.box().y + stroke.y - composed.offsetY);
        }
    }

    /**
     * 一次落笔：在哪个位置、用哪个字体、写哪个字
     *
     * @param x 相对起点的横坐标
     * @param y 相对起点的<b>基线</b>纵坐标
     */
    private record Stroke(String text, Font font, int x, int y) {
    }

    /**
     * 摆好的一个词
     *
     * @param offsetX 墨迹最左端相对起点的偏移，落笔时要减掉它，包围盒才紧贴着墨
     */
    private record Composed(List<Stroke> strokes, int width, int height, int offsetX, int offsetY) {
    }

    private Font fontFor(int codePoint, int fontSize) {
        // 字体查找要遍历字体表并逐个问 canDisplay，而排版会反复量同一批词
        long key = ((long) fontSize << 32) | (codePoint & 0xFFFFFFFFL);
        return fontCache.computeIfAbsent(key,
                ignored -> fontUtil.findFontForCharacter(codePoint).deriveFont(Font.PLAIN, (float) fontSize));
    }

    /**
     * 逐字摆开一个词，算出每一笔的落点与整体的墨迹外框
     */
    private Composed compose(String text, int fontSize, boolean vertical) {
        FontRenderContext context = scratch.getFontRenderContext();
        int[] codePoints = text.codePoints().toArray();

        List<Stroke> strokes = new ArrayList<>(codePoints.length);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        // 竖排要让每个字在列内居中，先量一遍各字宽度
        int columnWidth = 0;
        if (vertical) {
            for (int codePoint : codePoints) {
                columnWidth = Math.max(columnWidth, advanceOf(codePoint, fontSize, context));
            }
        }

        int pen = 0;
        for (int codePoint : codePoints) {
            Font font = fontFor(codePoint, fontSize);
            String glyph = new String(Character.toChars(codePoint));
            int advance = advanceOf(codePoint, fontSize, context);

            int x;
            int baseline;
            if (vertical) {
                // 汉字直立地摞起来，不是把整个词转 90 度
                x = (columnWidth - advance) / 2;
                baseline = pen + scratch.getFontMetrics(font).getAscent();
                pen += scratch.getFontMetrics(font).getHeight();
            } else {
                x = pen;
                baseline = 0;
                pen += advance;
            }

            strokes.add(new Stroke(glyph, font, x, baseline));

            // 🔴 取的是「落笔之后哪些像素会变色」而不是字形轮廓的外框。
            // 轮廓外框（getVisualBounds）比真正着色的范围小：抗锯齿会在轮廓外再抹一圈，
            // 于是量出来的框贴着边、画出来的墨压过边——判据是绿的，图上照样越界
            Rectangle ink = font.createGlyphVector(context, glyph).getPixelBounds(context, x, baseline);
            if (ink.width > 0 && ink.height > 0) {
                minX = Math.min(minX, ink.x);
                minY = Math.min(minY, ink.y);
                maxX = Math.max(maxX, ink.x + ink.width);
                maxY = Math.max(maxY, ink.y + ink.height);
            }
        }

        if (minX > maxX) {
            // 整个词都是空白字符，没有墨可量
            return new Composed(List.of(), 0, 0, 0, 0);
        }

        return new Composed(strokes, maxX - minX, maxY - minY, minX, minY);
    }

    /**
     * 一个字占的推进量，取整后落笔位置才与量出来的一致
     */
    private int advanceOf(int codePoint, int fontSize, FontRenderContext context) {
        GlyphVector vector = fontFor(codePoint, fontSize)
                .createGlyphVector(context, new String(Character.toChars(codePoint)));
        return (int) Math.ceil(vector.getLogicalBounds().getWidth());
    }

    /**
     * 按排版结果出一张透明底的词云图
     */
    BufferedImage render(WordCloudLayout.Result result, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        applyHints(graphics);
        try {
            for (WordCloudLayout.Placement placement : result.placements()) {
                draw(graphics, placement);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
