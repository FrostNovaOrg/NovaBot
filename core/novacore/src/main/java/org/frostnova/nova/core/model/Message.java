package org.frostnova.nova.core.model;

import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 消息，请使用 create 方法创建消息列表以自动处理 {next} 占位符
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Message {
    /**
     * 全局顺序号
     */
    private static final AtomicLong globalSequence = new AtomicLong(0);

    /**
     * 推送平台
     */
    private String platform;

    /**
     * 推送目标类型
     */
    private PushTargetType type;

    /**
     * 账号或群号，根据推送目标类型而定
     */
    private Long num;

    /**
     * 可包含占位符的消息内容
     */
    private String content;

    /**
     * NovaBot 内部消息创建顺序号，通过 create 方法创建时自动生成，无需手动设置
     */
    private Long sequence;

    /**
     * 创建时间戳，通过 create 方法创建时自动生成，无需手动设置
     */
    private Instant createTime;

    /**
     * 下一条消息，用于连接通过 {next} 占位符拆分的消息列表，通过 create 方法创建时自动生成，无需手动设置
     */
    private Message next;

    /**
     * 前一条消息，用于连接通过 {next} 占位符拆分的消息列表，通过 create 方法创建时自动生成，无需手动设置
     */
    private Message previous;

    /**
     * 消息 ID，消息发送成功后自动设置，无需手动设置
     * <p>
     * 写成 volatile：发送线程写上编号之后，别的线程马上读得到。
     * 漏了这一笔，另一边会一直看见空编号，把已经发出的当成还没发。
     */
    private volatile String id;

    /**
     * 发送完毕时间戳，不论发送是否成功，消息发送后自动设置，无需手动设置
     * <p>
     * 与编号一样写成 volatile，写下去的时刻对别的线程立刻可见。
     */
    private volatile Instant completeTime;

    /**
     * 这一条是否为对使用者命令的回复
     * <p>
     * 与推送分开，是因为有些事只对<b>主动推送</b>成立：例如「向某个会话推出第一条之后
     * 附一句用法说明」——那句话是说给「只见过通知、没跟机器人说过话」的人听的，
     * 而回复的收件人刚刚才发过一条命令，再教他一遍怎么发命令只是打扰。
     * <p>
     * 默认 false，也就是<b>默认按推送算</b>。方向是刻意的：新开一条推送路径忘了标记，
     * 结果是它照常被当成推送对待；反过来把默认设成「回复」的话，新推送路径会安静地
     * 从这类统计与提示里消失，而消失这件事没有任何现象。
     */
    private boolean reply;

    /**
     * 这一条是否为告警
     * <p>
     * 与 {@link #reply} 同族：有些事只对<b>普通推送</b>成立——例如「向某个会话推出第一条
     * 之后附一句用法说明」。告警说的是出了的事，跟在它后面教人怎么用机器人是另一件事，
     * 因此告警的发送入口会把这一标记打上，用法提示不再跟着告警走。
     * <p>
     * 默认 false，也就是<b>默认按推送算</b>，方向与 {@link #reply} 一致：新开一条告警
     * 路径忘了标记，结果是它照常被当成推送对待（多附一句提示），而不是提示从此不发。
     */
    private boolean alert;

    /**
     * {@code {at=all}} 没能发出时用来顶替它的文本，为空表示直接摘掉
     * <p>
     * @全体成员 会因为两件事发不出去：机器人不是管理员，或当天的额度已经用完。
     * 两件事都<b>只有到了发送那一刻才知道</b>，而「该改 @ 谁」是配置那一端的事——
     * 于是替代文本必须由造消息的一方先备好，随消息一起交下来。
     * <p>
     * 默认为空，也就是<b>默认摘掉</b>：方向是刻意的。新的推送路径忘了备替代文本，
     * 结果是那一次不 @ 人；反过来若默认去猜一份名单，猜错的那次会 @ 到一群
     * 从没订阅过的人，而这件事没有任何现象。
     */
    private String atAllFallback;

    /**
     * 发送前拦截回调列表，返回 false 会拦截消息发送，请勿调用阻塞操作
     */
    private List<Predicate<Message>> onBeforeSendInterceptors = new ArrayList<>();

    /**
     * 发送完毕回调列表，无论发送是否成功均会调用，请勿调用阻塞操作
     */
    private List<Runnable> onCompleteCallbacks = new ArrayList<>();

    /**
     * 发送成功回调列表，请勿调用阻塞操作
     */
    private List<Runnable> onSuccessCallbacks = new ArrayList<>();

    /**
     * 发送失败回调列表，请勿调用阻塞操作
     * <p>
     * 没确认送达就跑：没送到，或送达不明（请求已交出去、没等到回包，可能已经在群里）。
     * 两种都没有消息编号。回调里别把内容再发一次，送达不明时那就是两条。
     */
    private List<Runnable> onFailureCallbacks = new ArrayList<>();

    /**
     * 发送结果。和下面两份回调名单共用一把锁。
     * <p>
     * 结果定下来的那一下就把当时的名单抄走；这之后才登记的，当场执行，不再排进名单。
     * 两件事要是拆开，就会出现「已经看过名单、回调还没放进去」的空当——这次回调谁也不跑。
     * 一边遍历名单一边往里登记，还会把这次发送冲掉。
     */
    private enum Delivery {
        PENDING, DELIVERED, FAILED
    }

    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Object deliveryLock = new Object();

    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Delivery delivery = Delivery.PENDING;

    /**
     * 图片降级回调列表，请勿调用阻塞操作
     * <p>
     * 在「原内容含图、发送失败、剥掉图片段重发纯文字并送达」时触发，
     * 也就是<b>文字到了、图没到</b>那一种结局。
     * <p>
     * 之所以单独出一个回调而不是复用成功/失败回调：这件事对使用者是
     * <b>可感知的数据不完整</b>，而日志只有运维看得见。core 只管发信号，
     * 谁关心谁自己记账——例如哔哩哔哩那侧据此在下播报告里注明本场有几条推送的图片没送到。
     */
    private List<Runnable> onImageDegradedCallbacks = new ArrayList<>();

    /**
     * 创建通过 next 字段和 previous 字段相连接的消息列表，自动处理 {next} 占位符
     * @param platform 推送平台
     * @param type 推送目标类型
     * @param num 账号或群号，根据推送目标类型而定
     * @param content 可包含占位符的消息内容
     * @return 通过 next 字段和 previous 字段相连接的消息列表
     */
    public static List<Message> create(String platform, PushTargetType type, Long num, String content) {
        List<Message> messages = new ArrayList<>();

        String[] parts = content.split("\\{next}");
        for (String part : parts) {
            if (StringUtil.isEmpty(part)) {
                continue;
            }

            Message message = new Message();
            message.setPlatform(platform);
            message.setType(type);
            message.setNum(num);
            message.setContent(part);
            message.setSequence(globalSequence.incrementAndGet());
            message.setCreateTime(Instant.now());
            messages.add(message);
        }

        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                messages.get(i).setPrevious(messages.get(i - 1));
            }
            if (i < messages.size() - 1) {
                messages.get(i).setNext(messages.get(i + 1));
            }
        }

        return messages;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Message message)) return false;
        return Objects.equals(platform, message.platform) && type == message.type && Objects.equals(num, message.num) && Objects.equals(content, message.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(platform, type, num, content);
    }

    /**
     * 获取消息的展示字符串
     * @return 消息的展示字符串
     */
    public String getDisplay() {
        if (StringUtil.isBlank(content)) {
            return "";
        }

        String replaced = content.replaceAll("\\{face=.+?}", "[表情]")
                .replace(MessagePlaceholders.AT_ALL, "@全体成员 ")
                .replaceAll("\\{at=(.*?)}", "@$1");
        // 图片三种走同一个模式，与发送器剥段用的是同一份定义（MessagePlaceholders）——
        // 这里曾是三条各写一遍的 replaceAll，改一处漏一处
        return MessagePlaceholders.replaceImages(replaced, "[图片]");
    }

    /**
     * 添加发送前拦截回调，返回 false 会拦截消息发送
     * @param interceptor 发送前拦截回调，返回 false 会拦截消息发送
     */
    public void addOnBeforeSendInterceptor(Predicate<Message> interceptor) {
        this.onBeforeSendInterceptors.add(interceptor);
    }

    /**
     * 添加发送完毕回调，无论发送是否成功均会调用
     * @param callback 发送完毕回调，无论发送是否成功均会调用
     */
    public void addOnCompleteCallback(Runnable callback) {
        this.onCompleteCallbacks.add(callback);
    }

    /**
     * 添加发送成功回调。这条消息的发送结果如果已经是送达，回调当场执行，不再排队。
     * @param callback 发送成功回调
     */
    public void addOnSuccessCallback(Runnable callback) {
        boolean runNow = false;
        synchronized (deliveryLock) {
            if (delivery == Delivery.DELIVERED || (delivery == Delivery.PENDING && StringUtil.isNotBlank(id))) {
                delivery = Delivery.DELIVERED;
                runNow = true;
            } else if (delivery == Delivery.PENDING) {
                onSuccessCallbacks.add(callback);
            }
        }
        if (runNow) {
            callback.run();
        }
    }

    /**
     * 添加发送失败回调。这条消息的发送结果如果已经是没送达，回调当场执行，不再排队。
     * @param callback 发送失败回调
     */
    public void addOnFailureCallback(Runnable callback) {
        boolean runNow = false;
        synchronized (deliveryLock) {
            if (delivery == Delivery.FAILED) {
                runNow = true;
            } else if (delivery == Delivery.PENDING && StringUtil.isBlank(id)) {
                onFailureCallbacks.add(callback);
            }
        }
        if (runNow) {
            callback.run();
        }
    }

    /**
     * 发送结果定为送达，并写下编号。返回登记时已经在名单里的成功回调，由调用方执行。
     * <p>
     * 编号在这把锁里写上，随后才把名单交出去。锁外读编号的线程因此看得到它。
     * @param messageId 送达那一条的编号，没有则为空
     * @return 应当现在执行的成功回调
     */
    public List<Runnable> markDelivered(String messageId) {
        synchronized (deliveryLock) {
            if (delivery != Delivery.PENDING) {
                return List.of();
            }
            this.id = messageId;
            delivery = Delivery.DELIVERED;
            return List.copyOf(onSuccessCallbacks);
        }
    }

    /**
     * 发送结果定为没送达。返回登记时已经在名单里的失败回调，由调用方执行。
     * @return 应当现在执行的失败回调
     */
    public List<Runnable> markFailed() {
        synchronized (deliveryLock) {
            if (delivery != Delivery.PENDING) {
                return List.of();
            }
            delivery = Delivery.FAILED;
            return List.copyOf(onFailureCallbacks);
        }
    }

    /**
     * 成功回调的一份快照。遍历这一份时，别人再登记也不会把名单改乱。
     */
    public List<Runnable> getOnSuccessCallbacks() {
        synchronized (deliveryLock) {
            return List.copyOf(onSuccessCallbacks);
        }
    }

    /**
     * 失败回调的一份快照。遍历这一份时，别人再登记也不会把名单改乱。
     */
    public List<Runnable> getOnFailureCallbacks() {
        synchronized (deliveryLock) {
            return List.copyOf(onFailureCallbacks);
        }
    }

    /**
     * 添加图片降级回调
     * @param callback 图片降级回调，在「剥掉图片段重发纯文字并送达」时触发
     */
    public void addOnImageDegradedCallback(Runnable callback) {
        this.onImageDegradedCallbacks.add(callback);
    }
}
