package com.starlwr.bot.core.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.datasource.PushMessageInitializer;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
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

    private final PushTemplateDefaults templateDefaults;

    @Autowired
    public StarBotEventHandlerPushMessageInitializer(StarBotEventHandlerService handlerService,
                                                     PushTemplateDefaults templateDefaults) {
        this.handlerService = handlerService;
        this.templateDefaults = templateDefaults;
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
        // 不是 handler.getDefaultParams()：控制台上改过的默认模板要落到「所有用默认的通道」，
        // 而那些通道的配置里本来就没有 message 这个键——它是在这一行被补上的
        message.setParamsJsonObject(templateDefaults.paramsOf(handler));

        if (StringUtil.isNotBlank(message.getParams())) {
            try {
                JSONObject params = JSON.parseObject(message.getParams());
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (isSupersededDefault(handler, entry.getKey(), entry.getValue())) {
                        log.info("推送参数 {} 存的还是旧版默认值, 已改用新的默认值: {}",
                                entry.getKey(), message.getHandler());
                        continue;
                    }
                    message.getParamsJsonObject().put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.error("解析推送消息参数失败, 请检查格式是否正确: {}", message.getParams(), e);
            }
        }

        return true;
    }

    /**
     * 存着的这个值是不是某一版旧默认值
     * <p>
     * 是的话就<b>不覆盖</b>，于是这一项跟着新默认走。改默认值这件事只有走这一步才落得到
     * <b>已经存在的配置</b>上：使用者的参数是整份存下来的，里面那串多半就是当初界面预填的
     * 默认值，不认得它的话，改了默认值也只对今后新建的推送生效。
     * <p>
     * 比的是<b>整串一字不差</b>，不是「像不像」：差一个空格就算使用者改过，原样保留。
     * 方向是刻意的——迁错的那一次会安静地覆盖掉使用者写了很久的模板，
     * 而不迁的那一次只是没跟上默认值，后者可逆、前者不可逆。
     */
    private boolean isSupersededDefault(StarBotEventHandler handler, String key, Object value) {
        return value instanceof String text && handler.supersededDefaults()
                .getOrDefault(key, List.of())
                .contains(text);
    }
}
