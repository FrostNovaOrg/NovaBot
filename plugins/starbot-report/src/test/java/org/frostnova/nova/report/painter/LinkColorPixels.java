package org.frostnova.nova.report.painter;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * 数一张图里有多少像素接近某个颜色，以及最近的那个像素离它有多远。
 *
 * <h2>怎么算距离</h2>
 * 把像素与目标色逐通道相减、取三者中最大的那个差（切比雪夫距离）。
 * 选逐通道而不是欧氏距离，是因为它能直接说人话：红差多少、绿差多少、蓝差多少，
 * 距离超了是超在哪一路，一眼看得见。
 *
 * <h2>口径（不是全量，别读过头）</h2>
 * <ul>
 *   <li><b>完全透明的像素不计</b>——它在图上看不见。这是一条口径选择，
 *       写在这里免得以后有人把 {@code visible} 当成 {@code total}。</li>
 *   <li>读数同时给出 {@code total} 与 {@code visible} 两个数，
 *       否则「命中 0」看不出分母是多大。</li>
 *   <li>没有任何可见像素时 {@code nearest} 记 {@link #NOTHING_VISIBLE}，
 *       不记 0——「一枚都没有」和「就在眼前」必须分得开。</li>
 * </ul>
 *
 * 这个入口只此一份：两处要数同一件事，就调同一个方法，别各写一份。
 */
final class LinkColorPixels {

    /** 图上没有任何可见像素时 {@code nearest} 记这个值。 */
    static final int NOTHING_VISIBLE = -1;

    private LinkColorPixels() {
    }

    /**
     * 一次读数。
     *
     * @param width   图宽
     * @param height  图高
     * @param total   像素总数
     * @param visible 其中不完全透明的
     * @param within  距离不超过容差的
     * @param nearest 可见像素里离目标色最近的那个距离
     */
    record Reading(int width, int height, int total, int visible, int within, int nearest) {
    }

    /**
     * 逐通道差的最大值。
     */
    static int distance(int rgb, Color target) {
        int dr = Math.abs(((rgb >> 16) & 0xFF) - target.getRed());
        int dg = Math.abs(((rgb >> 8) & 0xFF) - target.getGreen());
        int db = Math.abs((rgb & 0xFF) - target.getBlue());
        return Math.max(dr, Math.max(dg, db));
    }

    /**
     * 数一张图。
     *
     * @param image     被数的图
     * @param target    目标色
     * @param tolerance 容差：距离不超过它就算一枚
     */
    static Reading read(BufferedImage image, Color target, int tolerance) {
        int width = image.getWidth();
        int height = image.getHeight();
        int visible = 0;
        int within = 0;
        int nearest = Integer.MAX_VALUE;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                visible++;
                int d = distance(argb, target);
                if (d < nearest) {
                    nearest = d;
                }
                if (d <= tolerance) {
                    within++;
                }
            }
        }

        return new Reading(width, height, width * height, visible, within,
                visible == 0 ? NOTHING_VISIBLE : nearest);
    }
}
