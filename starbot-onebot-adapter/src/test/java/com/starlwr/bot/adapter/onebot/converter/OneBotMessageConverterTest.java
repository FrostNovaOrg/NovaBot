package com.starlwr.bot.adapter.onebot.converter;

import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 占位符消息转 OneBot 消息段
 *
 * <h2>为什么整段比字符串</h2>
 * 这把尺量的是<b>发到 OneBot 那一端的报文长什么样</b>，不是「转换器内部分了几个方法」。
 * 报文里键的先后顺序对 OneBot 无所谓，但它是<b>此刻真实发出去的字节</b>；
 * 拿整段 JSON 比，重写时任何一处漏掉、多出、改名的字段都当场现形，
 * 而逐字段断言只能看住写断言时想得到的那几个字段。
 *
 * <h2>已知的怪脾气，本尺一并钉住</h2>
 * 下面几条不是「应该这样」，是「现在就是这样」——重写不许改动它们：
 * <ul>
 *     <li>{@code {at=all}} 在这一层<b>不作特殊处理</b>，照直变成 {@code qq: "all"}；
 *         「@全体成员 发不出去怎么办」是发送器与 napcat 扩展那一层的事</li>
 *     <li>占位符取的是「从 <code>{</code> 到<b>最近一个</b> <code>}</code>」，
 *         所以 <code>{at=1}}</code> 会剩一个右花括号当正文——与
 *         {@code MessagePlaceholders} 的非贪婪口径一致</li>
 *     <li>值为空或纯空白的占位符<b>整段消失</b>，既不变文本也不留痕；
 *         而值解析不了的表情（{@code {face=abc}}）反而<b>原样留成文本</b>——
 *         两种「填错了」走的是两条相反的路</li>
 *     <li>认不出的占位符原样留成文本，不报错</li>
 * </ul>
 */
@DisplayName("OneBot 消息转换器")
class OneBotMessageConverterTest {
    private final OneBotMessageConverter converter = new OneBotMessageConverter();

    private String convert(String content) {
        JSONArray elements = converter.convert(content);
        return elements.toJSONString();
    }

    @Nested
    @DisplayName("纯文本")
    class PlainText {
        @Test
        @DisplayName("整段没有占位符时只出一个文本段")
        void singleTextElement() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"主播开播啦\"}}]", convert("主播开播啦"));
        }

        @Test
        @DisplayName("空串出空数组, 不是一个空文本段")
        void emptyContentYieldsEmptyArray() {
            assertEquals("[]", convert(""));
        }

        @Test
        @DisplayName("换行与空白照原样留在文本里")
        void keepsWhitespace() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"上一行\\n 下一行 \"}}]", convert("上一行\n 下一行 "));
        }
    }

    @Nested
    @DisplayName("表情")
    class Face {
        @Test
        @DisplayName("表情 ID 转成整数, 不是字符串")
        void faceIdIsANumber() {
            assertEquals("[{\"type\":\"face\",\"data\":{\"id\":123}}]", convert("{face=123}"));
        }

        @Test
        @DisplayName("⚠️ 表情 ID 不是数字时原样留成文本, 不抛异常也不丢内容")
        void malformedFaceIdBecomesText() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"{face=abc}\"}}]", convert("{face=abc}"));
        }

        @Test
        @DisplayName("⚠️ 表情 ID 留空同样原样留成文本")
        void emptyFaceIdBecomesText() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"{face=}\"}}]", convert("{face=}"));
        }
    }

    @Nested
    @DisplayName("@ 成员")
    class At {
        @Test
        @DisplayName("@ 指定成员, 账号是字符串")
        void atSomeone() {
            assertEquals("[{\"type\":\"at\",\"data\":{\"qq\":\"123456\"}}]", convert("{at=123456}"));
        }

        @Test
        @DisplayName("⚠️ {at=all} 在这一层不特判, 照直交给 OneBot")
        void atAllPassesThroughAsIs() {
            assertEquals("[{\"type\":\"at\",\"data\":{\"qq\":\"all\"}}]", convert("{at=all}"));
        }

        @Test
        @DisplayName("⚠️ @ 目标为空或纯空白时整段消失, 不留文本")
        void blankAtTargetIsDropped() {
            assertEquals("[]", convert("{at=}"));
            assertEquals("[]", convert("{at=   }"));
        }
    }

    @Nested
    @DisplayName("三种图片")
    class Image {
        @Test
        @DisplayName("网络图片直接用 URL 当 file")
        void urlImage() {
            assertEquals("[{\"type\":\"image\",\"data\":{\"file\":\"https://example.com/a.jpg\"}}]",
                    convert("{image_url=https://example.com/a.jpg}"));
        }

        @Test
        @DisplayName("本地图片补 file:// 前缀")
        void pathImage() {
            assertEquals("[{\"type\":\"image\",\"data\":{\"file\":\"file:///opt/a.jpg\"}}]",
                    convert("{image_path=/opt/a.jpg}"));
        }

        @Test
        @DisplayName("Base64 图片补 base64:// 前缀")
        void base64Image() {
            assertEquals("[{\"type\":\"image\",\"data\":{\"file\":\"base64://QUJD\"}}]",
                    convert("{image_base64=QUJD}"));
        }

        @Test
        @DisplayName("⚠️ 三种图片的值留空时整段消失, 不留文本")
        void blankImageValueIsDropped() {
            assertEquals("[]", convert("{image_url=}"));
            assertEquals("[]", convert("{image_path=}"));
            assertEquals("[]", convert("{image_base64=}"));
        }
    }

    @Nested
    @DisplayName("认不出的写法")
    class Unrecognised {
        @Test
        @DisplayName("认不出的占位符原样留成文本")
        void unknownPlaceholderBecomesText() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"{video=1}\"}}]", convert("{video=1}"));
        }

        @Test
        @DisplayName("左花括号没有配对的右花括号时, 从它到结尾整段当文本")
        void unclosedBraceBecomesText() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"前缀\"}},{\"type\":\"text\",\"data\":{\"text\":\"{face=1\"}}]",
                    convert("前缀{face=1"));
        }

        @Test
        @DisplayName("⚠️ 取到最近一个右花括号为止, 多出来的那个留在正文里")
        void stopsAtNearestClosingBrace() {
            assertEquals("[{\"type\":\"at\",\"data\":{\"qq\":\"1\"}},{\"type\":\"text\",\"data\":{\"text\":\"}\"}}]",
                    convert("{at=1}}"));
        }

        @Test
        @DisplayName("单个右花括号只是普通文本")
        void loneClosingBraceIsText() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"a}b\"}}]", convert("a}b"));
        }
    }

    @Nested
    @DisplayName("混排")
    class Mixed {
        @Test
        @DisplayName("文字与占位符按原顺序逐段出, 一段都不合并")
        void keepsOrder() {
            assertEquals("["
                            + "{\"type\":\"text\",\"data\":{\"text\":\"开播了 \"}},"
                            + "{\"type\":\"at\",\"data\":{\"qq\":\"all\"}},"
                            + "{\"type\":\"text\",\"data\":{\"text\":\" 快来看 \"}},"
                            + "{\"type\":\"face\",\"data\":{\"id\":66}},"
                            + "{\"type\":\"image\",\"data\":{\"file\":\"https://example.com/cover.jpg\"}},"
                            + "{\"type\":\"text\",\"data\":{\"text\":\" 完\"}}"
                            + "]",
                    convert("开播了 {at=all} 快来看 {face=66}{image_url=https://example.com/cover.jpg} 完"));
        }

        @Test
        @DisplayName("相邻两个占位符之间没有文字时不插空文本段")
        void noEmptyTextBetweenAdjacentPlaceholders() {
            assertEquals("[{\"type\":\"at\",\"data\":{\"qq\":\"1\"}},{\"type\":\"at\",\"data\":{\"qq\":\"2\"}}]",
                    convert("{at=1}{at=2}"));
        }

        @Test
        @DisplayName("被丢弃的占位符不影响两侧文字的分段")
        void droppedPlaceholderKeepsNeighbours() {
            assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"甲\"}},{\"type\":\"text\",\"data\":{\"text\":\"乙\"}}]",
                    convert("甲{at=}乙"));
        }
    }
}
