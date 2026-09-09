package org.frostnova.nova.core.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「这条推送用的是哪个处理器」要问解析出来的实例，不要问配置里那一串
 * <p>
 * 两者只在一种情形下不同：配置里写的是处理器<b>搬包之前</b>的全类名。那一刻按字面串去比
 * 「这条推送是不是那一类通知」的地方全部落空——命令与联动安静地当作本群没配过这类推送，
 * 不报错，也没有任何一处显示不对。
 */
@DisplayName("推送消息的处理器类名")
class PushMessageHandlerClassNameTest {
    /**
     * 冒充「搬家之后那个类」的处理器实例。这里只要一个真实例：
     * 类名要真的从实例上取，用 mock 取到的是代理类的名字，与真类名并不相同
     */
    private static class MovedPushHandler implements PushMessageHandler {
    }

    private static final String LEGACY = "org.frostnova.nova.bilibili.handler.BilibiliDynamicPushHandler";

    @Test
    @DisplayName("配置里写旧名而实例已解析: 取实例的类名")
    void resolvedInstanceWins() {
        PushMessage message = new PushMessage();
        message.setHandler(LEGACY);
        message.setHandlerInstance(new MovedPushHandler());

        assertEquals(MovedPushHandler.class.getName(), message.handlerClassName(),
                "老配置写的是旧名, 按旧名去比就永远比不中搬家之后的那个类");
    }

    @Test
    @DisplayName("还没解析出实例: 退回配置里写的那一串")
    void fallsBackToConfiguredString() {
        PushMessage message = new PushMessage();
        message.setHandler(LEGACY);

        assertEquals(LEGACY, message.handlerClassName(),
                "解析之前无从知道它对应哪个类, 退回原串至少与解析之前的行为一致");
    }

    @Test
    @DisplayName("两头都空时答空, 不炸")
    void answersNullWhenNothingIsKnown() {
        assertNull(new PushMessage().handlerClassName());
    }

    @Test
    @DisplayName("它是算出来的, 不进线上形态")
    void isNotPartOfTheWireForm() {
        PushMessage message = new PushMessage();
        message.setHandler(LEGACY);
        message.setHandlerInstance(new MovedPushHandler());

        JSONObject serialized = JSON.parseObject(JSON.toJSONString(message));

        assertFalse(serialized.containsKey("handlerClassName"),
                "这一项若混进序列化结果, 使用者的 datasource.json 会被写进一个它从来没有过的字段");
        assertEquals(LEGACY, serialized.getString("handler"), "存出去的仍是使用者自己写的那一串");
    }
}
