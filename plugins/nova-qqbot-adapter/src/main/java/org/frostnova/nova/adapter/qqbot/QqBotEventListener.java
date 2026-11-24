package org.frostnova.nova.adapter.qqbot;

/** 接收 QQ 开放平台的事件。监听方法按收到的顺序调用，应尽快返回。 */
@FunctionalInterface
public interface QqBotEventListener {
    /** eventId 和 sequence 在报文没有对应字段时为 null。 */
    void onEvent(String name, Object data, String eventId, Long sequence) throws Exception;
}
