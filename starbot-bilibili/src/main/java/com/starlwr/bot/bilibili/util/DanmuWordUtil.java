package com.starlwr.bot.bilibili.util;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.starlwr.bot.core.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * 收录的词长范围
     */
    private static final int MIN_WORD_LENGTH = 2;

    private static final int MAX_WORD_LENGTH = 8;

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
        if (StringUtil.isBlank(text)) {
            return List.of();
        }

        List<String> words = new ArrayList<>();
        for (String word : SEGMENTER.sentenceProcess(text)) {
            if (words.size() >= MAX_WORDS_PER_DANMU) {
                break;
            }

            String trimmed = word.trim();
            if (trimmed.length() < MIN_WORD_LENGTH || trimmed.length() > MAX_WORD_LENGTH) {
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

        return words;
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
