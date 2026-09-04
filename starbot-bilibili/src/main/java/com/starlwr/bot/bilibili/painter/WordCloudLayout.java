package com.starlwr.bot.bilibili.painter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 弹幕词云的排版器：只算「哪个词、多大、放在哪、横着还是竖着、什么颜色」
 * <p>
 * 这里<b>不画一个像素</b>，也不认识 {@code Graphics2D}。字体的宽高从外面传进来的
 * {@link Measurer} 问，于是这一整套版式规则可以脱离渲染单独被量——判据读的是
 * {@link Result} 里的矩形，不是图上的墨。
 *
 * <h2>为什么自己写</h2>
 * 原先用的第三方词云库按像素级碰撞随机撒点，两次跑出来的图不一样：
 * 同一场直播重发一次报告，主播看到的是另一张图。它也没有「离边框留多少」的概念，
 * 词会贴着框边甚至压上圆角。这两件事都不是配置项能调的。
 *
 * <h2>版式规则</h2>
 * <ol>
 *   <li>字号 {@code 18 + 54·√((c−cmin)/(cmax−cmin))}，取整后钳在 18～72；
 *       全场词频相同时分母为零，一律取下限，靠下面的整体放大去填满</li>
 *   <li>按词频从高到低逐个放，位置沿中心向外的<b>椭圆</b>螺旋试探——椭圆的长短轴比
 *       取自框本身，否则在 830×380 这样的扁框里词会挤成一个圆、四角空着</li>
 *   <li>每个词的包围盒四周留 {@value #PADDING}px：两两不许相交，也不许出框</li>
 *   <li>放不下的词丢掉。从大到小放，丢掉的自然是最小的那几个</li>
 *   <li>全部放完后若外包围盒不足框的 {@value #FILL_TARGET_PERCENT}%，字号整体放大一档重排，
 *       直到够了、或者再放大反而更差</li>
 *   <li>前 {@value #HORIZONTAL_HEAD} 名一律横排；其余每 4 个允许 1 个竖排，竖排是汉字直立地摞起来，
 *       不是把整个词转 90 度</li>
 *   <li>颜色按名次分四档，取设计语言册的 token</li>
 * </ol>
 * 第 2 步的角度起点与第 6 步的「这一组里竖排哪一个」都由种子决定：种子相同，结果逐字节相同。
 */
final class WordCloudLayout {
    /**
     * 词与词、词与框之间至少留出的空白
     */
    static final int PADDING = 8;

    /**
     * 字号下限与可加的最大增量：{@code 18 + 54} 即上限 72
     */
    private static final int FONT_SIZE_MIN = 18;

    private static final int FONT_SIZE_SPAN = 54;

    /**
     * 外包围盒至少要占到框的这个比例，不足则整体放大字号重排
     */
    static final int FILL_TARGET_PERCENT = 85;

    /**
     * 每次整体放大的倍率与最多放大次数
     */
    private static final double SCALE_STEP = 1.06;

    private static final int SCALE_ATTEMPTS = 40;

    /**
     * 前几名一律横排：最大的那几个词竖着摞起来会把整块版式拉成一根柱子
     */
    private static final int HORIZONTAL_HEAD = 12;

    /**
     * 第 13 名往后，每几个词里允许一个竖排
     */
    private static final int VERTICAL_GROUP = 4;

    /**
     * 螺旋每转过一弧度，半径向外走多少像素（一圈约 19px，与最小字号同量级）
     */
    private static final double SPIRAL_GAIN = 3.0;

    /**
     * 螺旋上相邻两个试探点之间的弧长，单位像素
     */
    private static final double SPIRAL_ARC_STEP = 8.0;

    /**
     * 名次分档的四种颜色，取自设计语言册（丙·星云）的亮色值：
     * accent2 / accent / cloud3 / dim。成图是白底，用亮色那一列
     */
    private static final Color COLOR_RANK_TOP = new Color(0xE0, 0x47, 0x9E);

    private static final Color COLOR_RANK_HIGH = new Color(0x7A, 0x4D, 0xFF);

    private static final Color COLOR_RANK_MID = new Color(0xA3, 0x8B, 0xFF);

    private static final Color COLOR_RANK_REST = new Color(0x6E, 0x6A, 0x86);

    private WordCloudLayout() {
    }

    /**
     * 一个词及其出现次数
     */
    record Word(String text, int count) {
    }

    /**
     * 一个词最终落在哪儿
     *
     * @param text     词
     * @param rank     名次，从 1 起
     * @param fontSize 字号
     * @param vertical 是否竖排
     * @param box      包围盒，不含四周留白
     * @param color    颜色
     */
    record Placement(String text, int rank, int fontSize, boolean vertical, Rectangle box, Color color) {
    }

    /**
     * 排版结果
     *
     * @param placements 放下了的词，按名次升序
     * @param bounds     所有包围盒的外包围盒；一个词都没放下时为空矩形
     * @param fillRatio  外包围盒面积占整框的比例
     * @param dropped    因放不下被丢掉的词数
     */
    record Result(List<Placement> placements, Rectangle bounds, double fillRatio, int dropped) {
    }

    /**
     * 量一个词占多大地方。实现方负责让「量出来的框」与「画出来的墨」对得上
     */
    interface Measurer {
        /**
         * @param text     词
         * @param fontSize 字号
         * @param vertical 竖排为真
         * @return 该词的包围盒尺寸
         */
        Dimension measure(String text, int fontSize, boolean vertical);
    }

    /**
     * 排版
     *
     * @param words    词与词频，顺序不限
     * @param width    框宽
     * @param height   框高
     * @param seed     种子，同一场直播取同一个值
     * @param measurer 字体测量
     * @return 排版结果
     */
    static Result layout(List<Word> words, int width, int height, long seed, Measurer measurer) {
        List<Word> sorted = new ArrayList<>(words);
        // 词频相同时按词本身排，否则同样的输入换个遍历顺序就换一张图
        sorted.sort(Comparator.comparingInt(Word::count).reversed().thenComparing(Word::text));

        if (sorted.isEmpty()) {
            return new Result(List.of(), new Rectangle(), 0, 0);
        }

        int[] baseSizes = baseFontSizes(sorted);
        boolean[] vertical = verticalFlags(sorted.size(), seed);

        Result best = null;
        double scale = 1.0;
        for (int attempt = 0; attempt < SCALE_ATTEMPTS; attempt++) {
            Result result = placeAll(sorted, baseSizes, vertical, scale, width, height, seed, measurer);
            if (best == null || result.fillRatio() > best.fillRatio()) {
                best = result;
            }
            if (result.fillRatio() * 100 >= FILL_TARGET_PERCENT) {
                return result;
            }
            scale *= SCALE_STEP;
        }

        return best;
    }

    /**
     * 按词频算基准字号
     */
    private static int[] baseFontSizes(List<Word> sorted) {
        int max = sorted.get(0).count();
        int min = sorted.get(sorted.size() - 1).count();
        int span = max - min;

        int[] sizes = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            // 全场词频一样时没有「相对大小」可言，一律取下限，由整体放大去决定最终多大
            double ratio = span == 0 ? 0 : (double) (sorted.get(i).count() - min) / span;
            sizes[i] = clamp((int) Math.round(FONT_SIZE_MIN + FONT_SIZE_SPAN * Math.sqrt(ratio)));
        }
        return sizes;
    }

    private static int clamp(int size) {
        return Math.max(FONT_SIZE_MIN, Math.min(FONT_SIZE_MIN + FONT_SIZE_SPAN, size));
    }

    /**
     * 定哪些名次竖排：前 {@value #HORIZONTAL_HEAD} 名全横，其后每满 {@value #VERTICAL_GROUP} 个抽一个竖
     * <p>
     * 只在<b>凑满一组</b>时抽，于是竖排数恰为 {@code ⌊(n−12)/4⌋}：不凑满也抽的话，
     * 13 个词就会出现 1 个竖排，而 {@code ⌊1/4⌋} 是 0
     */
    private static boolean[] verticalFlags(int size, long seed) {
        boolean[] flags = new boolean[size];
        Random random = new Random(seed);
        for (int start = HORIZONTAL_HEAD; start + VERTICAL_GROUP <= size; start += VERTICAL_GROUP) {
            flags[start + random.nextInt(VERTICAL_GROUP)] = true;
        }
        return flags;
    }

    /**
     * 按名次取颜色：1～3 / 4～10 / 11～25 / 其余
     */
    private static Color colorOf(int rank) {
        if (rank <= 3) {
            return COLOR_RANK_TOP;
        }
        if (rank <= 10) {
            return COLOR_RANK_HIGH;
        }
        if (rank <= 25) {
            return COLOR_RANK_MID;
        }
        return COLOR_RANK_REST;
    }

    /**
     * 按给定的整体倍率排一遍
     */
    private static Result placeAll(List<Word> sorted, int[] baseSizes, boolean[] vertical, double scale,
                                   int width, int height, long seed, Measurer measurer) {
        List<Placement> placements = new ArrayList<>();
        List<Rectangle> occupied = new ArrayList<>();
        int dropped = 0;

        // 角度起点逐词取，同一颗种子给出同一串起点
        Random random = new Random(seed * 31 + 17);

        for (int i = 0; i < sorted.size(); i++) {
            int size = Math.max(FONT_SIZE_MIN, (int) Math.round(baseSizes[i] * scale));
            Dimension extent = measurer.measure(sorted.get(i).text(), size, vertical[i]);
            double phase = random.nextDouble() * Math.PI * 2;

            if (extent.width <= 0 || extent.height <= 0) {
                dropped++;
                continue;
            }

            Rectangle box = spiralSearch(extent, occupied, width, height, phase);
            if (box == null) {
                dropped++;
                continue;
            }

            occupied.add(inflate(box));
            placements.add(new Placement(sorted.get(i).text(), i + 1, size, vertical[i], box, colorOf(i + 1)));
        }

        Rectangle bounds = new Rectangle();
        for (Placement placement : placements) {
            if (bounds.isEmpty()) {
                bounds = new Rectangle(placement.box());
            } else {
                bounds = bounds.union(placement.box());
            }
        }

        double fill = (double) bounds.width * bounds.height / ((double) width * height);
        return new Result(List.copyOf(placements), bounds, fill, dropped);
    }

    /**
     * 沿中心向外的椭圆螺旋找第一个放得下的位置，找不到返回 null
     */
    private static Rectangle spiralSearch(Dimension extent, List<Rectangle> occupied,
                                          int width, int height, double phase) {
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        // 椭圆的扁平程度跟着框走，词才铺得到左右两头
        double aspect = (double) width / height;
        // 半径超过半个框高时，椭圆上的点在两个轴向上都已经跑出框外，
        // 而词是以该点为中心摆的，再往外试没有一个位置可能合规
        double maxRadius = height / 2.0;

        for (double t = 0; ; ) {
            double radius = SPIRAL_GAIN * t;
            if (radius > maxRadius) {
                return null;
            }

            int x = (int) Math.round(centerX + radius * aspect * Math.cos(t + phase) - extent.width / 2.0);
            int y = (int) Math.round(centerY + radius * Math.sin(t + phase) - extent.height / 2.0);
            Rectangle candidate = new Rectangle(x, y, extent.width, extent.height);

            if (fits(candidate, occupied, width, height)) {
                return candidate;
            }

            t += Math.max(0.05, SPIRAL_ARC_STEP / Math.max(SPIRAL_ARC_STEP, radius));
        }
    }

    /**
     * 这个位置放得下吗：连同四周留白一起，既要在框内，又不能压到已放下的词
     */
    private static boolean fits(Rectangle candidate, List<Rectangle> occupied, int width, int height) {
        Rectangle padded = inflate(candidate);
        if (padded.x < 0 || padded.y < 0 || padded.x + padded.width > width || padded.y + padded.height > height) {
            return false;
        }

        for (Rectangle taken : occupied) {
            // occupied 里存的已经是涨过的框，这里再涨一次候选框：
            // 「每个词四周留 8px」两边都要留，于是两个词的墨之间隔的是 16px。
            // 判据读的也是这个形——两个各自涨 8px 的框不相交
            if (taken.intersects(padded)) {
                return false;
            }
        }

        return true;
    }

    private static Rectangle inflate(Rectangle box) {
        return new Rectangle(box.x - PADDING, box.y - PADDING,
                box.width + PADDING * 2, box.height + PADDING * 2);
    }
}
