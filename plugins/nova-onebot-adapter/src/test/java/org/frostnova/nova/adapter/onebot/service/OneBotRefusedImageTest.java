package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.PublicImageHosts;
import org.frostnova.nova.adapter.onebot.dto.MessageDTO;
import org.frostnova.nova.adapter.onebot.enums.ResultCode;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 指向内网或解析不出的图片不交给机器人程序：整条只剩这一张图时，与模板里留空的图片占位符一样按空消息处理
 */
@DisplayName("OneBot：被拒的图片不交给机器人程序")
class OneBotRefusedImageTest {
    private static final String PLATFORM = "qq-onebot";

    private final OneBotHttpAdapter bot = mock(OneBotHttpAdapter.class);

    private OneBotHttpService service() {
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        OneBotHttpService service = new OneBotHttpService(mock(TaskScheduler.class), mock(ThreadPoolTaskExecutor.class),
                properties, bot, PublicImageHosts.converter(), new OneBotConnectionState());
        OneBotSender sender = new OneBotSender();
        sender.setName(PLATFORM);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        service.register(sender);
        return service;
    }

    private static MessageDTO group(String content) {
        MessageDTO message = new MessageDTO();
        message.setPlatform(PLATFORM);
        message.setType(PushTargetType.GROUP);
        message.setNum(123456L);
        message.setContent(content);
        return message;
    }

    @Test
    @DisplayName("整条只有一张被拒的图片：不调机器人程序，回「消息内容为空」，与留空的图片占位符同一个结果")
    void messageWithOnlyARefusedImageIsNotSent() {
        OneBotHttpService service = service();
        JSONObject blank = service.send(group("{image_url=}"));
        for (String url : new String[]{"http://192.168.1.30/cover.png", "https://img.missing.example/cover.png?token=s3cr3t"}) {
            JSONObject result = service.send(group("{image_url=" + url + "}"));
            assertEquals(ResultCode.EMPTY_MESSAGE.getCode(), result.getIntValue("code"), result.toJSONString());
            assertEquals(blank, result, url);
        }
        verifyNoInteractions(bot);
    }

    @Test
    @DisplayName("带文字的消息：去掉被拒的图片，文字照发")
    void textAroundARefusedImageIsStillSent() {
        when(bot.sendGroupMsg(any(), any())).thenReturn(JSONObject.of("message_id", "7"));
        JSONObject result = service().send(group("主播开播了{image_url=http://10.0.0.8/cover.png}"));

        assertEquals(ResultCode.SUCCESS.getCode(), result.getIntValue("code"), result.toJSONString());
        ArgumentCaptor<JSONObject> params = ArgumentCaptor.forClass(JSONObject.class);
        verify(bot).sendGroupMsg(any(), params.capture());
        JSONArray message = params.getValue().getJSONArray("message");
        assertEquals("[{\"type\":\"text\",\"data\":{\"text\":\"主播开播了\"}}]", message.toJSONString());
        assertFalse(params.getValue().toJSONString().contains("10.0.0.8"));
    }
}
