package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.Executor;

/** 在网关心跳线程之外回复进群事件。 */
@Slf4j
@NovaComponent
final class QqBotWelcome {
    private static final String TEXT = "大家好，我是这个群的推送机器人。要在群里收到开播通知，请群主在手机 QQ 打开我的"
            + "『机器人主动在群聊内发言』：群成员列表里点我 → 右上角设置 → 打开。管理员可在控制台连接页查看这个群。";

    private final QqBotApiClient api;
    private final QqBotSendWindow window;
    private final Executor executor;

    @Autowired
    QqBotWelcome(QqBotApiClient api, QqBotSendWindow window, QqBotSender sender) {
        this(api, window, sender::submitEventReply);
    }

    QqBotWelcome(QqBotApiClient api, QqBotSendWindow window, Executor executor) {
        this.api = api;
        this.window = window;
        this.executor = executor;
    }

    void send(String appId, long group, String openId, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        executor.execute(() -> {
            var body = JSONObject.of("msg_type", 0, "content", TEXT, "event_id", eventId);
            try {
                window.retryWhenLimited(() -> {
                    window.acquire(appId, group);
                    return api.request("POST", QqBotDirectory.groupPath(openId) + "/messages", body, appId);
                });
            } catch (QqBotApiException e) {
                if (e.eventReplyRejected()) {
                    log.info("进群事件无法回复，已跳过欢迎语（错误码 {}）", e.code());
                } else {
                    log.warn("进群欢迎语未确认送达（错误码 {}）", e.code());
                }
            }
        });
    }
}
