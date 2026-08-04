package com.starlwr.bot.bilibili.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕分词测试
 */
@DisplayName("弹幕分词")
class DanmuWordUtilTest {
    @Test
    @DisplayName("应切分出有意义的内容词")
    void extractsMeaningfulWords() {
        List<String> words = DanmuWordUtil.extractWords("主播今天唱歌真好听");

        assertTrue(words.contains("唱歌"), "应包含内容词「唱歌」，实际: " + words);
        assertTrue(words.contains("好听"), "应包含内容词「好听」，实际: " + words);
    }

    @Test
    @DisplayName("应过滤停用词与单字")
    void filtersStopWordsAndSingleChars() {
        List<String> words = DanmuWordUtil.extractWords("这个就是我的了");

        assertTrue(words.isEmpty(), "全部为停用词与单字时应为空，实际: " + words);
    }

    @Test
    @DisplayName("应过滤纯数字")
    void filtersPureDigits() {
        List<String> words = DanmuWordUtil.extractWords("666666 233333");

        assertTrue(words.isEmpty(), "纯数字不应入词云，实际: " + words);
    }

    @Test
    @DisplayName("空白文本应返回空列表")
    void handlesBlankText() {
        assertTrue(DanmuWordUtil.extractWords(null).isEmpty());
        assertTrue(DanmuWordUtil.extractWords("  ").isEmpty());
    }

    @Test
    @DisplayName("超长弹幕收录的词数应有上限")
    void capsWordsPerDanmu() {
        String text = "唱歌好听 ".repeat(50);

        assertFalse(DanmuWordUtil.extractWords(text).size() > 20, "单条弹幕最多收录 20 个词");
    }
}
