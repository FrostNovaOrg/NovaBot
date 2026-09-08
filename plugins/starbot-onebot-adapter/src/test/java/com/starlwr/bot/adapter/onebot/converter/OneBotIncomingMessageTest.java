package com.starlwr.bot.adapter.onebot.converter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收到的消息解析测试
 * <p>
 * 这一层判的是「群聊里这话是不是说给机器人听的」。判错的两个方向代价不对称：
 * 判漏了机器人从此不再应答，判宽了它会在别人互相 @ 的时候插嘴。
 */
@DisplayName("OneBot 收到的消息")
class OneBotIncomingMessageTest {
    private static final long SELF = 10000L;

    @Test
    @DisplayName("CQ 码里 @ 了机器人时应认出，并把该段从正文里剪掉")
    void recognisesAtInCqCode() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000] 直播报告 3493");

        assertTrue(message.mentionsBot());
        assertEquals("直播报告 3493", message.text());
    }

    @Test
    @DisplayName("@ 段带 name 等额外参数时同样认得出")
    void recognisesAtWithExtraParameters() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000,name=NovaBot] 菜单");

        assertTrue(message.mentionsBot());
        assertEquals("菜单", message.text());
    }

    @Test
    @DisplayName("只 @ 了机器人、没写别的时，正文为空")
    void emptyTextWhenOnlyMention() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000]");

        assertTrue(message.mentionsBot());
        assertEquals("", message.text());
    }

    @Test
    @DisplayName("@ 的是别人时不算叫机器人，且那一段要留在正文里当参数")
    void keepsOtherPeopleMentions() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=20000] 你好");

        assertFalse(message.mentionsBot());
        assertEquals("[CQ:at,qq=20000] 你好", message.text());
    }

    @Test
    @DisplayName("@全体成员 不算叫机器人")
    void atAllIsNotAMention() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=all] 都来看");

        assertFalse(message.mentionsBot());
    }

    @Test
    @DisplayName("先 @ 机器人再 @ 别人时，只剪掉机器人那一段")
    void stripsOnlyOwnMention() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000] 直播报告 [CQ:at,qq=20000]");

        assertTrue(message.mentionsBot());
        assertEquals("直播报告 [CQ:at,qq=20000]", message.text());
    }

    @Test
    @DisplayName("图片等其它 CQ 码原样留在正文里")
    void keepsOtherCqCodes() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000] 看这个 [CQ:image,file=abc.jpg]");

        assertTrue(message.mentionsBot());
        assertEquals("看这个 [CQ:image,file=abc.jpg]", message.text());
    }

    @Test
    @DisplayName("没 @ 任何人的普通消息原样通过")
    void plainMessagePassesThrough() {
        OneBotIncomingMessage message = parse("  今天天气不错  ");

        assertFalse(message.mentionsBot());
        assertEquals("今天天气不错", message.text());
    }

    @Test
    @DisplayName("分段数组里 @ 了机器人时也认得出")
    void recognisesAtInSegmentArray() {
        JSONObject event = JSON.parseObject("""
                {
                  "self_id": 10000,
                  "raw_message": "菜单",
                  "message": [
                    {"type": "at", "data": {"qq": "10000"}},
                    {"type": "text", "data": {"text": "菜单"}}
                  ]
                }
                """);

        OneBotIncomingMessage message = OneBotIncomingMessage.of(event);

        assertTrue(message.mentionsBot());
        assertEquals("菜单", message.text());
    }

    @Test
    @DisplayName("分段数组里 @ 的是别人时不算")
    void ignoresOtherMentionsInSegmentArray() {
        JSONObject event = JSON.parseObject("""
                {
                  "self_id": 10000,
                  "raw_message": "你好",
                  "message": [
                    {"type": "at", "data": {"qq": "20000"}},
                    {"type": "text", "data": {"text": "你好"}}
                  ]
                }
                """);

        assertFalse(OneBotIncomingMessage.of(event).mentionsBot());
    }

    @Test
    @DisplayName("缺 self_id 时一律当作没被 @，不猜")
    void withoutSelfIdNothingIsAMention() {
        JSONObject event = new JSONObject();
        event.put("raw_message", "[CQ:at,qq=10000] 菜单");

        OneBotIncomingMessage message = OneBotIncomingMessage.of(event);

        assertFalse(message.mentionsBot());
        assertEquals("[CQ:at,qq=10000] 菜单", message.text());
    }

    @Test
    @DisplayName("CQ 码没有收尾方括号时按普通文本处理，不吞掉后面的内容")
    void unterminatedCqCodeIsText() {
        OneBotIncomingMessage message = parse("[CQ:at,qq=10000 菜单");

        assertFalse(message.mentionsBot());
        assertEquals("[CQ:at,qq=10000 菜单", message.text());
    }

    private OneBotIncomingMessage parse(String rawMessage) {
        JSONObject event = new JSONObject();
        event.put("self_id", SELF);
        event.put("raw_message", rawMessage);
        return OneBotIncomingMessage.of(event);
    }
}
