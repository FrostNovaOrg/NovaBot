package com.starlwr.bot.core.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.model.PushMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * StarBot 事件处理器接口，推送配置中配置的事件处理器实现均应实现此接口，并使用 {@link Component} 等注解注册至 Spring 容器中
 */
public interface StarBotEventHandler {
    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage);

    /**
     * 获取事件处理器处理的事件类型
     * @return 事件类型
     */
    Class<? extends StarBotExternalBaseEvent> getEventType();

    /**
     * 获取事件处理器默认参数
     * <p>
     * <b>每次调用都必须返回新的实例。</b>数据源加载推送配置时会把使用者自定义的参数
     * 直接写进该返回值，此处若返回缓存或静态实例，一个推送目标的自定义参数会串到其他
     * 目标上，且重载配置后依然残留。
     * @return 默认参数
     */
    JSONObject getDefaultParams();

    /**
     * 展示名称，例如「开播通知」
     * <p>
     * 配置界面据此渲染勾选项，使用者不必接触处理器的全限定类名——那本是实现细节，
     * 不该出现在面向使用者的界面上。
     * <p>
     * 以下几个方法均为默认方法：既有的第三方处理器无需改动即可继续工作，
     * 只是在界面上显示为类名而已。
     * @return 展示名称
     */
    default String displayName() {
        return getClass().getSimpleName();
    }

    /**
     * 一句话说明该处理器在什么时候推送什么
     * @return 说明
     */
    default String description() {
        return "";
    }

    /**
     * 所属平台，例如 bilibili，用于界面按平台分组
     * @return 平台名，未声明时为空
     */
    default String platform() {
        return "";
    }

    /**
     * 消息模板中可用的占位符，例如 {uname}
     * @return 占位符列表
     */
    default List<String> placeholders() {
        return List.of();
    }
}
