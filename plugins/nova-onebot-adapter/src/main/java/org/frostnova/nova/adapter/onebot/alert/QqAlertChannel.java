package org.frostnova.nova.adapter.onebot.alert;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.core.alert.AlertBlockedException;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertRecipientField;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    private final NovaSenderService senderService;

    private final OneBotConnectionState connectionState;

    @Autowired
    public QqAlertChannel(OneBotAdapterPluginProperties properties, NovaMessageSender messageSender,
                          NovaSenderService senderService, OneBotConnectionState connectionState) {
        this.properties = properties;
        this.messageSender = messageSender;
        this.senderService = senderService;
        this.connectionState = connectionState;
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
    public List<AlertRecipientField> recipientFields() {
        return List.of(
                new AlertRecipientField(NovaBotPrefixes.ADAPTER + ".alert.platform",
                        "", "hidden", "", "", "sender"),
                new AlertRecipientField(NovaBotPrefixes.ADAPTER + ".alert.type",
                        "", "hidden", "", "", "kind"),
                new AlertRecipientField(NovaBotPrefixes.ADAPTER + ".alert.num",
                        "发给谁", "select", "", "^\\d+$", "num"));
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
        String cannotSend = validateCanSend();
        if (cannotSend != null) {
            throw new IllegalStateException(cannotSend);
        }

        // 走队列而非同步发送：告警不应阻塞探测线程，也不该与正常推送抢占顺序。
        // 入队走 sendAlert：静音时段照发——静音挡的是打扰，告警正是要叫人的那一条；
        // 全局开关关着时那边会抛出，让告警服务如实说「没发出去、为什么」并算作
        // 这一次的最终失败——不进重投队列，开关打开后也不补发
        messagesOf(subject, content).forEach(messageSender::sendAlert);
    }

    /**
     * 入队后等真结果再回报
     * <p>
     * QQ 这一路走的是推送队列，入队即返回；真发出还是发不出去要等发送线程跑完才知道。
     * 给每条消息挂成功／失败回调，由回调把结果交回告警服务——
     * 样子上是异步的，但对使用者来说只有「发出去了」与「发不出去（原因）」两种真话。
     */
    @Override
    public void sendReporting(String subject, String content, Consumer<SendResult> callback) {
        String cannotSend = validateCanSend();
        if (cannotSend != null) {
            callback.accept(SendResult.failed(cannotSend));
            return;
        }

        List<Message> messages = messagesOf(subject, content);
        if (messages.isEmpty()) {
            callback.accept(SendResult.sent());
            return;
        }

        AtomicInteger remaining = new AtomicInteger(messages.size());
        AtomicBoolean anySuccess = new AtomicBoolean(false);
        AtomicBoolean anyBlocked = new AtomicBoolean(false);
        AtomicBoolean anyUncertain = new AtomicBoolean(false);
        AtomicReference<String> failureReason = new AtomicReference<>("发送失败");

        for (Message message : messages) {
            message.addOnSuccessCallback(() -> {
                anySuccess.set(true);
                if (remaining.decrementAndGet() == 0) {
                    finish(callback, anySuccess, anyBlocked, anyUncertain, failureReason);
                }
            });
            message.addOnFailureCallback(() -> {
                String reason = message.getFailureReason();
                if (reason != null) {
                    failureReason.set(reason);
                }
                if (remaining.decrementAndGet() == 0) {
                    finish(callback, anySuccess, anyBlocked, anyUncertain, failureReason);
                }
            });
            message.addOnUncertainCallback(() -> {
                anyUncertain.set(true);
                String reason = message.getFailureReason();
                if (reason != null) {
                    failureReason.set(reason);
                }
                if (remaining.decrementAndGet() == 0) {
                    finish(callback, anySuccess, anyBlocked, anyUncertain, failureReason);
                }
            });

            try {
                messageSender.sendAlert(message);
            } catch (AlertBlockedException e) {
                anyBlocked.set(true);
                failureReason.set(e.getMessage());
                if (remaining.decrementAndGet() == 0) {
                    finish(callback, anySuccess, anyBlocked, anyUncertain, failureReason);
                }
            } catch (Exception e) {
                failureReason.set(e.getMessage() != null ? e.getMessage() : e.toString());
                if (remaining.decrementAndGet() == 0) {
                    finish(callback, anySuccess, anyBlocked, anyUncertain, failureReason);
                }
            }
        }
    }

    private static void finish(Consumer<SendResult> callback, AtomicBoolean anySuccess,
                               AtomicBoolean anyBlocked, AtomicBoolean anyUncertain,
                               AtomicReference<String> failureReason) {
        if (anySuccess.get()) {
            callback.accept(SendResult.sent());
        } else if (anyBlocked.get()) {
            callback.accept(SendResult.blocked(failureReason.get()));
        } else if (anyUncertain.get()) {
            callback.accept(SendResult.uncertain(failureReason.get()));
        } else {
            callback.accept(SendResult.failed(failureReason.get()));
        }
    }

    /**
     * 平台没添加，或这个机器人此刻连不上，消息到不了人手里。
     * 先把原因说出来：否则「发一条测试」会说已经发出，日志页也会记成已报出。
     * @return 送不出去时给使用者看的原因；送得出去时为空
     */
    private String validateCanSend() {
        OneBotAdapterPluginProperties.Alert alert = properties.getAlert();
        String platform = alert.getPlatform();

        if (senderService.getSender(platform).isEmpty()) {
            return "没有找到推送平台「" + platform
                    + "」。请先到「连接」页添加这个机器人，再到「设置 → 告警」里把「发给谁」重新选一次。";
        }
        return whyThisRobotCannotSend(platform);
    }

    private List<Message> messagesOf(String subject, String content) {
        OneBotAdapterPluginProperties.Alert alert = properties.getAlert();
        return Message.create(
                alert.getPlatform(),
                PushTargetType.of(alert.getType()),
                alert.getNum(),
                subject + "\n" + content);
    }

    /**
     * 这个机器人此刻能不能把消息送出去
     * <p>
     * 连不上，或 QQ 已经掉线，都算送不出去。
     * 只断了收群事件的那一路，或者还不知道账号在不在线，消息仍然送得出去，这里不拦。
     * @param platform 告警要发往的推送平台
     * @return 送不出去时给使用者看的原因；送得出去时为空
     */
    private String whyThisRobotCannotSend(String platform) {
        OneBotConnectionState.Entry entry = connectionState.all().get(platform);
        if (entry == null) {
            return "推送平台「" + platform + "」的机器人还没连上。请到「连接」页确认地址和端口，并确认机器人程序已启动、QQ 已登录。";
        }
        if (entry.getHttp().kind() != OneBotConnectionState.Kind.OK) {
            return httpCannotSend(platform, entry.getHttp().kind());
        }
        if (entry.getAccount().kind() == OneBotConnectionState.Kind.SERVICE_ABNORMAL) {
            return "推送平台「" + platform + "」的 QQ 已经掉线。请到机器人程序里重新扫码登录。";
        }
        return null;
    }

    /**
     * 机器人程序这一侧连不上时，按具体原因告诉人下一步改哪里
     */
    private static String httpCannotSend(String platform, OneBotConnectionState.Kind kind) {
        String name = "推送平台「" + platform + "」";
        return switch (kind) {
            case TOKEN_INVALID -> name + "的 Token 不对。请把「连接」页的 Token 改成和机器人程序里一样。";
            case UNREACHABLE -> name + "的机器人连不上。请确认机器人程序已启动，地址和端口与「连接」页一致；停在扫码页时端口是关的，要先登录。";
            case SERVICE_ABNORMAL -> name + "的机器人状态不正常。请看机器人程序是否在运行。";
            case UNKNOWN -> name + "的连接还没检查完。请稍等再试；若一直这样，到「连接」页看这一路是否填好。";
            case DISABLED -> name + "的连接没有启用。请到「连接」页把它打开。";
            case OK -> null;
        };
    }
}
