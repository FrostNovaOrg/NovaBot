package com.example;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * 示例 1: 主程序启动后触发自定义逻辑
 * 示例 StarBot 启动事件监听器
 * 该监听器会在主程序启动完毕后触发一次
 */
@Slf4j
@StarBotComponent // 使用该注解将此类注册为 StarBot 组件，会被 StarBot 扫描并注册至 Spring 容器中
public class StarBotExampleStartEventListener {
    @Order(0) // 如需指定执行顺序，使用 @Order 注解，数值越小，执行时机越早
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        log.info("主程序已启动完毕，示例插件已开始监听弹幕事件");
    }
}
