package org.frostnova.nova.bilibili.util;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.frostnova.nova.core.lang.StringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕分词工具
 * <p>
 * 用 jieba 的 Java 移植做中文分词，供弹幕词云统计词频。
 * 词典在首次使用时加载（约一秒），聚合器会在后台线程预热，避免拖慢首条弹幕的处理。
 */
public final class DanmuWordUtil {
    /**
     * 单条弹幕最多收录的词数，防止超长弹幕拖垮统计
     */
    private static final int MAX_WORDS_PER_DANMU = 20;

    /**
     * 收录的词长范围，单位是<b>字</b>
     * <p>
     * 🔴 按码点数而不是 {@code String.length()} 量。后者数的是 UTF-16 单元，
     * 一个位于扩展区的汉字或一个 emoji 各占两个——「单字不入词云」这条对它们不成立，
     * 而这类字恰恰是弹幕里最多的
     */
    private static final int MIN_WORD_LENGTH = 2;

    private static final int MAX_WORD_LENGTH = 8;

    /**
     * 一个字形簇（用户看到的一个字）：带变体选择符的表情、两枚区域指示符拼成的国旗、
     * 一串 ZWJ 拼成的表情，各是一簇
     */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    /**
     * 停用词：高频但无信息量的功能词，进词云只会淹没真正的内容词
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "我", "你", "他", "她", "它", "这", "那",
            "这个", "那个", "什么", "怎么", "为什么", "不是", "就是", "还是", "但是", "可以",
            "没有", "不会", "不要", "知道", "觉得", "感觉", "现在", "时候", "今天", "一下",
            "一个", "有点", "真的", "所以", "因为", "如果", "然后", "已经", "自己", "我们",
            "你们", "他们", "这样", "那样", "怎么办", "哈哈", "哈哈哈", "哈哈哈哈", "啊啊", "啊啊啊"
    );

    /**
     * jieba 分词器，词典加载成本高，进程内共享一份；其分词方法本身线程安全
     */
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private DanmuWordUtil() {
    }

    /**
     * 预热分词词典
     */
    public static void warmUp() {
        SEGMENTER.sentenceProcess("预热");
    }

    /**
     * 把弹幕文本切分为可入词云的词语
     * @param text 弹幕文本
     * @return 过滤后的词语列表
     */
    public static List<String> extractWords(String text) {
        return extractWords(text, List.of());
    }

    /**
     * 把弹幕文本切分为可入词云的词语，词云屏蔽词在原文里出现时整个留成一个词
     * <p>
     * 🔴 分词器不认识的多字屏蔽词会被切成几段（「原神启动」切成「原神」「启动」），
     * 每段单看都不含屏蔽词，画图时按「含屏蔽词」挑词就挡不住它们。所以先在原文里认出
     * 屏蔽词、整个留下，两边的文字各自再切。整词照样记进词频，画图时按当下的表挑掉；
     * 以后从表里删了这个词，它就以整词出现，而不是永远丢了。认的时候英文不分大小写。
     * 只有一个字的屏蔽词不参与整词挑出：它没有会被切散的问题，提前挑出去只会把原文
     * 切断、切出本不成词的碎片；含它的词画图时按「含即屏蔽」照样挑掉。一个字按用户看到的
     * 字符（字形簇）数：❤️、国旗、ZWJ 拼成的表情这类多码点的单个表情也是一个字
     * @param text 弹幕文本
     * @param keepWhole 词云屏蔽词，可为空
     * @return 过滤后的词语列表
     */
    public static List<String> extractWords(String text, Collection<String> keepWhole) {
        if (StringUtil.isBlank(text)) {
            return List.of();
        }

        List<String> words = new ArrayList<>();
        List<String> whole = foldedNonBlank(keepWhole);
        if (whole.isEmpty()) {
            collect(SEGMENTER.sentenceProcess(text), words);
            return words;
        }

        String folded = foldAsciiLetters(text);
        int pending = 0;
        int i = 0;
        while (i < text.length() && words.size() < MAX_WORDS_PER_DANMU) {
            String hit = longestAt(folded, i, whole);
            if (hit == null) {
                i++;
                continue;
            }
            if (i > pending) {
                collect(SEGMENTER.sentenceProcess(text.substring(pending, i)), words);
            }
            collect(List.of(text.substring(i, i + hit.length())), words);
            i += hit.length();
            pending = i;
        }
        if (pending < text.length()) {
            collect(SEGMENTER.sentenceProcess(text.substring(pending)), words);
        }
        return words;
    }

    /**
     * 只把 ASCII 大写字母折成小写，长度不变，折完的下标与原文一一对应
     * <p>
     * 词云屏蔽词不分大小写就靠它：词频里纯 ASCII 的词已折成小写，带汉字的词（如「Yyds好」）
     * 照原样存着，两边都折一遍再比才不漏
     */
    public static String foldAsciiLetters(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    private static List<String> foldedNonBlank(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> folded = new ArrayList<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                String stripped = foldAsciiLetters(item.strip());
                // 只有一个字（按字形簇数，多码点的单个表情也算一个字）的屏蔽词不进这张表：
                // 含它的词画图时按「含即屏蔽」照样挡得住，提前挑出去只会把原文切断
                if (graphemeCount(stripped) > 1) {
                    folded.add(stripped);
                }
            }
        }
        return folded;
    }

    /**
     * 这串字按用户看到的字符（字形簇）算几个字
     * <p>
     * 🔴 不按码点数：码点数会把 ❤️（两个码点）、国旗（两枚区域指示符）、ZWJ 拼成的
     * 家庭表情（五个码点）算成好多个字，「只有一个字的屏蔽词不参与整词挑出」这条就对
     * 它们不成立。也不用 {@link java.text.BreakIterator#getCharacterInstance}：本项目
     * 跑的 JDK 17 上实测它把一面国旗算成两个字、家庭表情算成五个（带变体选择符的 ❤️
     * 能认成一个），只有正则 {@code \X} 把三类都认成一个字
     */
    private static int graphemeCount(String text) {
        Matcher matcher = GRAPHEME.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 原文这一位上起头的最长那个屏蔽词。一个都不是时为 {@code null}
     */
    private static String longestAt(String folded, int at, List<String> whole) {
        String hit = null;
        for (String word : whole) {
            if (folded.startsWith(word, at) && (hit == null || word.length() > hit.length())) {
                hit = word;
            }
        }
        return hit;
    }

    private static void collect(List<String> segments, List<String> words) {
        for (String word : segments) {
            if (words.size() >= MAX_WORDS_PER_DANMU) {
                break;
            }

            String trimmed = foldCase(word.trim());
            int length = trimmed.codePointCount(0, trimmed.length());
            if (length < MIN_WORD_LENGTH || length > MAX_WORD_LENGTH) {
                continue;
            }
            if (STOP_WORDS.contains(trimmed)) {
                continue;
            }
            if (!containsLetterOrCjk(trimmed)) {
                continue;
            }

            words.add(trimmed);
        }
    }

    /**
     * 纯 ASCII 的词一律转小写，{@code Dog} 与 {@code dog} 才并成一个词
     * <p>
     * 只折 ASCII：全角字母、希腊字母这些在弹幕里出现时多半是在当符号用，转了小写反倒把
     * 原样改掉。{@link Locale#ROOT} 是必须的——土耳其语环境下 {@code "I"} 的小写不是 {@code "i"}，
     * 同一条弹幕在两台机器上会切出两个词
     */
    private static String foldCase(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) > 0x7F) {
                return word;
            }
        }
        return word.toLowerCase(Locale.ROOT);
    }

    /**
     * 判断词语是否含有汉字或字母，排除纯数字与纯符号
     */
    private static boolean containsLetterOrCjk(String word) {
        for (int i = 0; i < word.length(); ) {
            int codePoint = word.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }
}
