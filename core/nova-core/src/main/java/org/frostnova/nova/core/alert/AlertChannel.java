package org.frostnova.nova.core.alert;

import java.util.List;
import java.util.function.Consumer;

/**
 * 告警通道
 * <p>
 * 告警此前只有邮件一个出口，而这类机器人的使用者大多没有配置发件账号，等于没有告警。
 * 抽象出通道后可以直接推给管理员 QQ——对本项目的使用者比邮件实用得多。
 */
public interface AlertChannel {
    /**
     * 通道标识，只含 ASCII，用于接口参数与界面上的卡片锚点
     * <p>
     * 与 {@link #name()} 分开两栏而不是拿名字当标识：名字是给人看的、会因为措辞改动而变，
     * 而标识一旦进了接口参数就成了对外的约定。合成一栏的话，某天把「邮件」改成「邮件告警」，
     * 界面上那张卡的「发一条测试」就会静静地开始报「没有这一路通道」。
     * @return 通道标识
     */
    String id();

    /**
     * 通道名称，用于日志与界面标题
     * @return 通道名称
     */
    String name();

    /**
     * 当前是否可用
     * <p>
     * 未配置的通道应返回 false，以免每次告警都尝试并失败。
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 发送告警
     * @param subject 标题
     * @param content 内容
     */
    void send(String subject, String content);

    /**
     * 发送并回报结果
     * <p>
     * 同步通道在返回前就定下结果；异步通道（如 QQ 走推送队列）入队后立即返回，
     * 真结果由 {@code callback} 在之后回报——可能在别的线程上。
     * 覆写本方法的通道要保证 {@code callback} <b>恰好调一次</b>。
     * @param subject 标题
     * @param content 内容
     * @param callback 结果回报；请勿在其中做阻塞操作
     */
    default void sendReporting(String subject, String content, Consumer<SendResult> callback) {
        SendResult result;
        try {
            send(subject, content);
            result = SendResult.sent();
        } catch (AlertBlockedException e) {
            result = SendResult.blocked(e.getMessage());
        } catch (Exception e) {
            result = SendResult.failed(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
        callback.accept(result);
    }

    /**
     * 这一路在设置页要画的收件人栏
     * <p>
     * 默认空表：这一路没有要核心代填的收件人键（邮件、Webhook 走核心自有键，不经这里）。
     * 适配器覆写后，核心按申报顺序渲染，不写死任何一家的配置键。
     * @return 收件人栏；没有时不要返回 null
     */
    default List<AlertRecipientField> recipientFields() {
        return List.of();
    }

    /**
     * 一次发送的结果
     * @param status 结局
     * @param reason 没发出去时的原因；发出去了则为空
     * @param cause 没发出去时的异常，供工程日志打栈；不是异常造成时为空
     */
    record SendResult(Status status, String reason, Throwable cause) {
        public enum Status {
            /**
             * 发出去了
             */
            SENT,
            /**
             * 被全局推送开关拦下——这一次的最终失败，不重投
             */
            BLOCKED,
            /**
             * 发不出去
             */
            FAILED,
            /**
             * 送达不明：请求已交出去、没等到回包，可能已经发出
             */
            UNCERTAIN
        }

        public static SendResult sent() {
            return new SendResult(Status.SENT, null, null);
        }

        public static SendResult blocked(String reason) {
            return new SendResult(Status.BLOCKED, reason, null);
        }

        public static SendResult failed(String reason) {
            return new SendResult(Status.FAILED, reason, null);
        }

        public static SendResult failed(String reason, Throwable cause) {
            return new SendResult(Status.FAILED, reason, cause);
        }

        public static SendResult uncertain(String reason) {
            return new SendResult(Status.UNCERTAIN, reason, null);
        }
    }
}
