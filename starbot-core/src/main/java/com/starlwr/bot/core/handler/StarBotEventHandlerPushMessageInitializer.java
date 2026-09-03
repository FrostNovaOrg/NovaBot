package com.starlwr.bot.core.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.datasource.PushMessageInitializer;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 按已注册的事件处理器补全推送消息
 * <p>
 * 这是推送侧对 {@link PushMessageInitializer} 的实现：数据源读到的只是处理器全类名，
 * 由本类查出实例、挂上事件类型、把使用者写的参数覆盖到处理器的默认参数上。
 * <p>
 * 处理器认不出来时返回 {@code false} 并写一条 error —— 使用者多半是类名写错了，
 * 而这种错在运行期只表现为「这位主播没有推送」，不说出来就得靠猜。
 */
@Slf4j
@Component
public class StarBotEventHandlerPushMessageInitializer implements PushMessageInitializer {
    private final StarBotEventHandlerService handlerService;

    @Autowired
    public StarBotEventHandlerPushMessageInitializer(StarBotEventHandlerService handlerService) {
        this.handlerService = handlerService;
    }

    @Override
    public boolean initialize(@NonNull PushMessage message) {
        Optional<StarBotEventHandler> optionalHandler = handlerService.getHandler(message.getHandler());
        if (optionalHandler.isEmpty()) {
            message.setHandlerInstance(null);
            message.setEventClass(null);
            message.setParamsJsonObject(null);
            log.error("不存在的事件处理器: {}, 请检查推送配置", message.getHandler());
            return false;
        }

        StarBotEventHandler handler = optionalHandler.get();
        message.setHandlerInstance(handler);
        message.setEventClass(handler.getEventType());
        message.setParamsJsonObject(handler.getDefaultParams());

        if (StringUtil.isNotBlank(message.getParams())) {
            try {
                JSONObject params = JSON.parseObject(message.getParams());
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    message.getParamsJsonObject().put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.error("解析推送消息参数失败, 请检查格式是否正确: {}", message.getParams(), e);
            }
        }

        return true;
    }
}
