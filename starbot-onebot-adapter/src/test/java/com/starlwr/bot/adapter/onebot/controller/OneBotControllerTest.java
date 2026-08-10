package com.starlwr.bot.adapter.onebot.controller;

import com.starlwr.bot.adapter.onebot.dto.MessageDTO;
import com.starlwr.bot.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推送参数到消息的转换测试
 * <p>
 * 进程内直调（任务 7a）不再经过 HTTP，也就不再由 Jackson 来装配 {@link MessageDTO}，
 * 这一步改成了手写。手写的风险是漏字段——而漏掉的字段不会报错，只会让下游少收到点东西。
 * 因此这里逐字段钉死，尤其是 {@code create_time}：
 * 它在 DTO 上挂的是 Jackson 的 {@code @JsonProperty}，换任何别的序列化器都会静默丢掉。
 */
@DisplayName("推送参数转换")
class OneBotControllerTest {
    /**
     * 与 {@code StarBotMessageSender} 实际构造的参数完全一致
     */
    private Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("platform", "qq-onebot");
        params.put("type", PushTargetType.GROUP.getCode());
        params.put("num", 10000002L);
        params.put("content", "开播啦");
        params.put("sequence", 37L);
        params.put("create_time", 1786331154000L);
        return params;
    }

    @Test
    @DisplayName("六个字段全部装上, 一个都不能少")
    void mapsEveryField() {
        MessageDTO message = OneBotController.toMessage(params());

        assertEquals("qq-onebot", message.getPlatform());
        assertEquals(PushTargetType.GROUP, message.getType());
        assertEquals(10000002L, message.getNum());
        assertEquals("开播啦", message.getContent());
        assertEquals(37L, message.getSequence());
        assertEquals(1786331154000L, message.getCreateTime());
    }

    @Test
    @DisplayName("⚠️ create_time 必须落到 createTime 上")
    void carriesTheSnakeCaseTimestamp() {
        // 这个字段是整个转换里最容易悄悄丢的：DTO 上挂的是 Jackson 的
        // @JsonProperty("create_time")，而本项目内部用的是 fastjson，它不认这个注解。
        // 丢了不会报错，只是下游收到的时间戳为空
        assertEquals(1786331154000L, OneBotController.toMessage(params()).getCreateTime());
    }

    @Test
    @DisplayName("推送目标类型按 code 取, 不是按枚举序号")
    void resolvesTypeByCodeNotOrdinal() {
        Map<String, Object> params = params();

        params.put("type", PushTargetType.FRIEND.getCode());
        assertEquals(PushTargetType.FRIEND, OneBotController.toMessage(params).getType());

        params.put("type", PushTargetType.GROUP.getCode());
        assertEquals(PushTargetType.GROUP, OneBotController.toMessage(params).getType());

        // UNKNOWN 的 code 是 -1 而序号是 2，两种取法在这里才分得出来
        params.put("type", PushTargetType.UNKNOWN.getCode());
        assertEquals(PushTargetType.UNKNOWN, OneBotController.toMessage(params).getType(),
                "按序号取会在这里翻车");
    }

    @Test
    @DisplayName("认不出的类型码归入 UNKNOWN, 不抛异常")
    void unknownTypeCodeFallsBack() {
        Map<String, Object> params = params();
        params.put("type", 99);

        assertEquals(PushTargetType.UNKNOWN, OneBotController.toMessage(params).getType());
    }

    @Test
    @DisplayName("数字以字符串形式传来也认")
    void acceptsNumbersAsText() {
        Map<String, Object> params = params();
        params.put("num", "10000002");
        params.put("create_time", "1786331154000");

        MessageDTO message = OneBotController.toMessage(params);

        assertEquals(10000002L, message.getNum());
        assertEquals(1786331154000L, message.getCreateTime());
    }

    @Test
    @DisplayName("字段缺失时留空, 不抛异常——投递路径上不该因为一个字段没了就整条炸掉")
    void toleratesMissingFields() {
        MessageDTO message = OneBotController.toMessage(new HashMap<>());

        assertNull(message.getPlatform());
        assertNull(message.getNum());
        assertNull(message.getContent());
        assertNull(message.getSequence());
        assertNull(message.getCreateTime());
        assertEquals(PushTargetType.UNKNOWN, message.getType());
    }

    @Test
    @DisplayName("几百 KB 的图片内容原样带过去, 不截断")
    void carriesLargeImageContentIntact() {
        String content = "{image_base64=" + "A".repeat(400 * 1024) + "}";
        Map<String, Object> params = params();
        params.put("content", content);

        // 图片正是旧的自环 HTTP 路径上丢掉的那种消息，这里确认直调不会动它
        assertEquals(content, OneBotController.toMessage(params).getContent());
    }
}
