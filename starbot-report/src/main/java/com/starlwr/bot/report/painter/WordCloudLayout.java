package com.starlwr.bot.report.painter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 弹幕词云的排版器：只算「哪个词、多大、放在哪、什么颜色」
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
 *   <li>字号 {@code 18 + 38·(c−cmin)/(cmax−cmin)}，取整后钳在 18～56；
 *       全场词频相同时分母为零，一律取下限，靠下面的整体放大去填满</li>
 *   <li>按词频从高到低逐个放，位置沿中心向外的<b>椭圆</b>螺旋试探——椭圆的长短轴比
 *       取自框本身，否则在 830×380 这样的扁框里词会挤成一个圆、四角空着</li>
 *   <li>相邻两个词之间至少隔开 {@value #WORD_GAP}px，再往上随两词中较大的字号走
 *       （见 {@link #WORD_GAP_PER_FONT}）；词与框边之间留 {@value #FRAME_MARGIN}px</li>
 *   <li>放不下的词丢掉。从大到小放，丢掉的自然是最小的那几个</li>
 *   <li>全部放完后若外包围盒不足框的 {@value #FILL_TARGET_PERCENT}%，字号整体放大一档重排，
 *       直到够了、或者再放大反而更差；放大之后照旧钳在 18～56，头名再多压一道（见
 *       {@link #effectiveFontSizes}）</li>
 *   <li>一律横排</li>
 *   <li>颜色按名次分六档，取设计语言册的 token</li>
 * </ol>
 * 第 2 步的角度起点与第 7 步的「这一组里给哪一个点暖色」都由种子决定：种子相同，结果逐字节相同。
 */
final class WordCloudLayout {
    /**
     * 相邻两个词的包围盒之间至少隔开的空白<b>下限</b>；字号大过它的词按
     * {@link #WORD_GAP_PER_FONT} 折出更大的间距
     * <p>
     * 🔴 这是<b>两个词之间实际隔开多少</b>，不是每个词自己四周涨多少。头一版按后者写：
     * 两个词各涨 8px 再判不相交，于是墨与墨之间隔的其实是 16px，看上去空得发散。
     * 判据也要按这个形去量——量「各涨 8px 后不相交」的尺子读的是 16
     */
    static final int WORD_GAP = 8;

    /**
     * 词距随字号的系数：相邻两词最小间距＝{@code max(WORD_GAP, 本系数 × 两词中较大字号)}
     * <p>
     * 固定 8px 是按 18px 的尾词定的：小词隔 8px 恰好，可同一道间距落在 56px 的头名上
     * 就是近乎粘连——dm-01 场次里 {@code dog} 与 {@code 鬼魂} 被读成一个连写的词，
     * 冷清场次十几个 56px 的词排成整行连读。系数取 0.35：56px 的相邻两词隔开 20px
     * （约字高的三分之一，肉眼分得开），18px 的小词折出来 6px、仍走 {@value #WORD_GAP} 下限，
     * 不为小词多让版面。三份真实语料邻档实测（落词三档都是 72）：0.30 时 56px 词对隔
     * 17px，头几名仍近得像一句话；0.40 时 22px 分得更开，但最密的一份语料填充掉到
     * 85% 贴线、最小字号被压到 24，同一块版面少站词
     */
    static final double WORD_GAP_PER_FONT = 0.35;

    /**
     * 词与框边之间至少留出的空白
     */
    static final int FRAME_MARGIN = 8;

    /**
     * 字号下限与可加的最大增量：{@code 18 + 38} 即上限 56
     * <p>
     * 下限 18 是<b>可读线</b>：报告里最小的正文字号是 22，词云的尾词比正文再小一档还认得出，
     * 再往下就只是一团色块了——要往里多塞词先动上限，这一头不动
     */
    private static final int FONT_SIZE_MIN = 18;

    private static final int FONT_SIZE_SPAN = 38;

    /**
     * 🔴 上限管的是<b>最终落到图上的字号</b>，不只是按词频算出来的那一档。
     * 底下的整体放大是乘上去的：只钳基准字号的话，一句刷屏弹幕把头名顶到基准上限，
     * 再乘 1.5 倍的放大，图上就是 84px 的一个词压着一片小字——「上限 66」写在常量里，
     * 量出来是 99
     */
    private static final int FONT_SIZE_MAX = FONT_SIZE_MIN + FONT_SIZE_SPAN;

    /**
     * 头名字号最多是第二名的这个倍数
     * <p>
     * 词频悬殊时（首词 99 次、次词 50 次）线性映射会让头名独占一大块，旁边全是小字。
     * 压的是<b>看上去的悬殊</b>，词频本身照旧决定名次与色档
     */
    private static final double TOP_FONT_SIZE_RATIO = 1.3;

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
     * 第几名往后开始点暖色
     */
    private static final int ACCENT_FROM_RANK = 26;

    /**
     * 尾档每几个词里点一个暖色，即 {@code dim} 与 {@code c-sc} 之比 3:1
     */
    private static final int ACCENT_GROUP = 4;

    /**
     * 螺旋每转过一弧度，半径向外走多少像素（一圈约 9px，半个最小字号）
     * <p>
     * 一圈走得比最小的字还宽的话，小词只能落在圈与圈之间那几个位置上，
     * 明明放得下的空当会被整圈跨过去
     */
    private static final double SPIRAL_GAIN = 1.5;

    /**
     * 螺旋上相邻两个试探点之间的弧长，单位像素
     */
    private static final double SPIRAL_ARC_STEP = 5.0;

    /**
     * 名次分档的六种颜色，取自设计语言册（丙·星云）的亮色值：
     * accent2 / accent / cloud3 / c-guard / dim / c-sc。成图是白底，用亮色那一列。
     * <p>
     * 🔴 只许取册上在册的值。词云配色若自己调一个「好看点的」出来，它就不再随主题走，
     * 报告图与控制台会慢慢变成两套色
     */
    private static final Color COLOR_RANK_TOP = new Color(0xE0, 0x47, 0x9E);

    private static final Color COLOR_RANK_HIGH = new Color(0x7A, 0x4D, 0xFF);

    private static final Color COLOR_RANK_MID = new Color(0xA3, 0x8B, 0xFF);

    private static final Color COLOR_RANK_MID_ALT = new Color(0x00, 0xA6, 0xD6);

    private static final Color COLOR_RANK_REST = new Color(0x6E, 0x6A, 0x86);

    private static final Color COLOR_RANK_REST_ACCENT = new Color(0xF2, 0xA9, 0x3B);

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
     * @param box      包围盒，不含四周留白
     * @param color    颜色
     */
    record Placement(String text, int rank, int fontSize, Rectangle box, Color color) {
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
         * @return 该词的包围盒尺寸
         */
        Dimension measure(String text, int fontSize);
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
        boolean[] accented = accentFlags(sorted.size(), seed);

        Result best = null;
        double scale = 1.0;
        for (int attempt = 0; attempt < SCALE_ATTEMPTS; attempt++) {
            Result result = placeAll(sorted, baseSizes, accented, scale, width, height, seed, measurer);
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
     * 按词频算基准字号：<b>字高</b>与词频成正比
     * <p>
     * 头一版取的是 √词频，那是让<b>面积</b>与词频成正比。看上去更「公道」，代价是
     * 排在中游的词个个都还很大：830×380 的框被二十来个大词占满，剩下的全丢掉。
     * 改成字高成正比之后，头几名照旧醒目，中游往后收得快，同一块地方能多站进一半的词
     */
    private static int[] baseFontSizes(List<Word> sorted) {
        int max = sorted.get(0).count();
        int min = sorted.get(sorted.size() - 1).count();
        int span = max - min;

        int[] sizes = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            // 全场词频一样时没有「相对大小」可言，一律取下限，由整体放大去决定最终多大
            double ratio = span == 0 ? 0 : (double) (sorted.get(i).count() - min) / span;
            sizes[i] = clamp((int) Math.round(FONT_SIZE_MIN + FONT_SIZE_SPAN * ratio));
        }
        return sizes;
    }

    private static int clamp(int size) {
        return Math.max(FONT_SIZE_MIN, Math.min(FONT_SIZE_MAX, size));
    }

    /**
     * 这一趟真正用的字号：基准字号乘上整体倍率、钳回 18～56，再把头名压到第二名的
     * {@value #TOP_FONT_SIZE_RATIO} 倍以内
     * <p>
     * 🔴 压顶必须落在<b>放大之后</b>。落在基准字号上的话，两个词的比例被整体放大原样带走，
     * 而钳上限只钳得住头名一个——第二名跟着放大追上来，比例反倒又开了
     */
    private static int[] effectiveFontSizes(int[] baseSizes, double scale) {
        int[] sizes = new int[baseSizes.length];
        for (int i = 0; i < baseSizes.length; i++) {
            sizes[i] = clamp((int) Math.round(baseSizes[i] * scale));
        }

        if (sizes.length >= 2) {
            // 只会往小里改：第二名字号不小于下限 18，1.3 倍之后仍在下限之上，压不穿
            sizes[0] = Math.min(sizes[0], (int) (sizes[1] * TOP_FONT_SIZE_RATIO));
        }
        return sizes;
    }

    /**
     * 定尾档里哪些词点暖色：第 {@value #ACCENT_FROM_RANK} 名起，每满 {@value #ACCENT_GROUP} 个抽一个
     * <p>
     * 只在<b>凑满一组</b>时抽，于是暖色数恰为 {@code ⌊(n−25)/4⌋}：不凑满也抽的话，
     * 26 个词就会点出 1 个暖色，而 {@code ⌊1/4⌋} 是 0
     */
    private static boolean[] accentFlags(int size, long seed) {
        boolean[] flags = new boolean[size];
        Random random = new Random(seed);
        for (int start = ACCENT_FROM_RANK - 1; start + ACCENT_GROUP <= size; start += ACCENT_GROUP) {
            flags[start + random.nextInt(ACCENT_GROUP)] = true;
        }
        return flags;
    }

    /**
     * 按名次取颜色：1～3 / 4～10 / 11～25 两色交替 / 其余以 3:1 点暖色
     * <p>
     * 中段交替按名次的奇偶走而不再问种子：同一档里挨着的两个词换色，看的人才觉得「颜色多」，
     * 随机撒的话常常连着三四个同色
     */
    private static Color colorOf(int rank, boolean accented) {
        if (rank <= 3) {
            return COLOR_RANK_TOP;
        }
        if (rank <= 10) {
            return COLOR_RANK_HIGH;
        }
        if (rank <= 25) {
            return rank % 2 == 1 ? COLOR_RANK_MID : COLOR_RANK_MID_ALT;
        }
        return accented ? COLOR_RANK_REST_ACCENT : COLOR_RANK_REST;
    }

    /**
     * 按给定的整体倍率排一遍
     */
    private static Result placeAll(List<Word> sorted, int[] baseSizes, boolean[] accented, double scale,
                                   int width, int height, long seed, Measurer measurer) {
        List<Placement> placements = new ArrayList<>();
        List<PlacedWord> occupied = new ArrayList<>();
        int dropped = 0;

        int[] sizes = effectiveFontSizes(baseSizes, scale);

        // 角度起点逐词取，同一颗种子给出同一串起点
        Random random = new Random(seed * 31 + 17);

        for (int i = 0; i < sorted.size(); i++) {
            Dimension extent = measurer.measure(sorted.get(i).text(), sizes[i]);
            double phase = random.nextDouble() * Math.PI * 2;

            if (extent.width <= 0 || extent.height <= 0) {
                dropped++;
                continue;
            }

            int fontGap = fontGapOf(sizes[i]);
            Rectangle box = spiralSearch(extent, fontGap, occupied, width, height, phase);
            if (box == null) {
                dropped++;
                continue;
            }

            occupied.add(new PlacedWord(box, fontGap));
            placements.add(new Placement(sorted.get(i).text(), i + 1, sizes[i], box, colorOf(i + 1, accented[i])));
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
     * 一个已放下的词：包围盒，与它按自己字号折出的词距
     * <p>
     * 只存原框不存涨过的框：与下一个词隔多少要按<b>两个词</b>的字号现算，
     * 存成涨过的就把它与小组词的间距焊死在大词那一档上
     */
    private record PlacedWord(Rectangle box, int fontGap) {
    }

    /**
     * 这个字号的词按系数折出的词距（不含 {@value #WORD_GAP} 下限——下限在与已放词合算时取 max）
     */
    private static int fontGapOf(int fontSize) {
        return (int) Math.round(WORD_GAP_PER_FONT * fontSize);
    }

    /**
     * 沿中心向外的椭圆螺旋找第一个放得下的位置，找不到返回 null
     */
    private static Rectangle spiralSearch(Dimension extent, int fontGap, List<PlacedWord> occupied,
                                          int width, int height, double phase) {
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        // 椭圆的扁平程度跟着框走，词才铺得到左右两头
        double aspect = (double) width / height;
        // 🔴 停在半个框高上会把四个角整片让出去。螺旋上的点是
        // (r·aspect·cos t, r·sin t)，横向要在框内即 r·cos t ≤ height/2，
        // 纵向要在框内即 r·sin t ≤ height/2——两条合起来是个半边长 height/2 的正方形，
        // 它的外接圆半径是 √2 倍。停在 height/2 只走到正方形的内切圆，
        // 830×380 的框里那是 78% 的面积，剩下 22% 在四角，一个词也放不进去
        double maxRadius = height / 2.0 * Math.sqrt(2);

        for (double t = 0; ; ) {
            double radius = SPIRAL_GAIN * t;
            if (radius > maxRadius) {
                return null;
            }

            int x = (int) Math.round(centerX + radius * aspect * Math.cos(t + phase) - extent.width / 2.0);
            int y = (int) Math.round(centerY + radius * Math.sin(t + phase) - extent.height / 2.0);
            Rectangle candidate = new Rectangle(x, y, extent.width, extent.height);

            if (fits(candidate, fontGap, occupied, width, height)) {
                return candidate;
            }

            // 角度下限只为保证走得动。定得太大（原先 0.05）时，外圈半径几百像素，
            // 一步就跨过十几个像素，而空当最多的恰恰是外圈
            t += Math.max(0.01, SPIRAL_ARC_STEP / Math.max(SPIRAL_ARC_STEP, radius));
        }
    }

    /**
     * 这个位置放得下吗：离框边够远，也不能贴上已放下的词
     */
    private static boolean fits(Rectangle candidate, int fontGap, List<PlacedWord> occupied,
                                int width, int height) {
        if (candidate.x < FRAME_MARGIN || candidate.y < FRAME_MARGIN
                || candidate.x + candidate.width > width - FRAME_MARGIN
                || candidate.y + candidate.height > height - FRAME_MARGIN) {
            return false;
        }

        // 🔴 还是只涨候选框这一次，occupied 里存的是原框。涨多少逐个已放词算：
        // 候选与已放词 i 之间隔 max(下限, 系数×两词中较大字号)——间距跟着大词走，
        // 小词挨着大词也让出大词的间距。两边都涨会把这个间距翻倍
        for (PlacedWord taken : occupied) {
            int gap = Math.max(Math.max(WORD_GAP, fontGap), taken.fontGap());
            Rectangle spaced = new Rectangle(candidate.x - gap, candidate.y - gap,
                    candidate.width + gap * 2, candidate.height + gap * 2);
            if (taken.box().intersects(spaced)) {
                return false;
            }
        }

        return true;
    }
}
