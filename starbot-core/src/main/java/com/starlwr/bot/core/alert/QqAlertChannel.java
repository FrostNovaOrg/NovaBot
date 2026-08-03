package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.StringUtil;
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
public class QqAlertChannel implements AlertChannel {
    private final StarBotCoreProperties properties;

    private final StarBotMessageSender messageSender;

    @Autowired
    public QqAlertChannel(StarBotCoreProperties properties, StarBotMessageSender messageSender) {
        this.properties = properties;
        this.messageSender = messageSender;
    }

    @Override
    public String name() {
        return "QQ";
    }

    @Override
    public boolean isAvailable() {
        StarBotCoreProperties.Alert alert = properties.getAlert();

        if (StringUtil.isBlank(alert.getQqPlatform()) || alert.getQqNum() == null) {
            return false;
        }

        // 类型非法时消息会在发送阶段被静默丢弃。告警本就是「出问题时唯一的提示」，
        // 它自己失效却不作声是最糟的情况，因此在这里就判定为不可用并说清原因
        if (PushTargetType.of(alert.getQqType()) == PushTargetType.UNKNOWN) {
            log.error("QQ 告警通道的 starbot.core.alert.qq-type 取值 {} 无效, 告警不会送达; "
                    + "应填 {}（群聊）或 {}（私聊）",
                    alert.getQqType(), PushTargetType.GROUP.getCode(), PushTargetType.FRIEND.getCode());
            return false;
        }

        return true;
    }

    @Override
    public void send(String subject, String content) {
        StarBotCoreProperties.Alert alert = properties.getAlert();

        // 走队列而非同步发送：告警不应阻塞探测线程，也不该与正常推送抢占顺序
        List<Message> messages = Message.create(
                alert.getQqPlatform(),
                PushTargetType.of(alert.getQqType()),
                alert.getQqNum(),
                subject + "\n" + content);

        messages.forEach(messageSender::send);
    }
}
