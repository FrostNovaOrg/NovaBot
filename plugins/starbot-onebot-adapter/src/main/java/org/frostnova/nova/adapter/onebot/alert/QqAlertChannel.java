package org.frostnova.nova.adapter.onebot.alert;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QQ 告警通道
 * <p>
 * 复用既有的推送链路把告警发给管理员。相比邮件，这条通道不需要额外配置发件服务，
 * 而机器人本就已经连着 QQ。
 */
@Slf4j
@Component
@NovaComponent
public class QqAlertChannel implements AlertChannel {
    private final OneBotAdapterPluginProperties properties;

    private final NovaMessageSender messageSender;

    @Autowired
    public QqAlertChannel(OneBotAdapterPluginProperties properties, NovaMessageSender messageSender) {
        this.properties = properties;
        this.messageSender = messageSender;
    }

    @Override
    public String id() {
        return "qq";
    }

    @Override
    public String name() {
        return "QQ";
    }

    @Override
    public boolean isAvailable() {
        OneBotAdapterPluginProperties.Alert alert = properties.getAlert();

        if (StringUtil.isBlank(alert.getPlatform()) || alert.getNum() == null) {
            return false;
        }

        // 类型非法时消息会在发送阶段被静默丢弃。告警本就是「出问题时唯一的提示」，
        // 它自己失效却不作声是最糟的情况，因此在这里就判定为不可用并说清原因
        if (PushTargetType.of(alert.getType()) == PushTargetType.UNKNOWN) {
            log.error("QQ 告警通道的 novabot.adapter.onebot.alert.type 取值 {} 无效, 告警不会送达; "
                    + "应填 {}（群聊）或 {}（私聊）",
                    alert.getType(), PushTargetType.GROUP.getCode(), PushTargetType.FRIEND.getCode());
            return false;
        }

        return true;
    }

    @Override
    public void send(String subject, String content) {
        OneBotAdapterPluginProperties.Alert alert = properties.getAlert();

        // 走队列而非同步发送：告警不应阻塞探测线程，也不该与正常推送抢占顺序
        List<Message> messages = Message.create(
                alert.getPlatform(),
                PushTargetType.of(alert.getType()),
                alert.getNum(),
                subject + "\n" + content);

        messages.forEach(messageSender::send);
    }
}
