package com.example;

import com.starlwr.bot.core.event.live.common.DanmuEvent;
import com.starlwr.bot.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * 示例 2: 接收 NovaBot 事件，收到指定事件后触发自定义逻辑
 * 示例 NovaBot 弹幕事件监听器
 * 该监听器会在接收到弹幕事件时打印发送者和内容
 */
@Slf4j
@NovaComponent // 使用该注解将此类注册为 NovaBot 组件，会被 NovaBot 扫描并注册至 Spring 容器中
public class NovaExampleDanmuEventListener {
    @EventListener
    public void handle(DanmuEvent event) {
        log.info("示例插件接收到弹幕事件: {} -> {}", event.getSender().getUname(), event.getContent());
    }
}
