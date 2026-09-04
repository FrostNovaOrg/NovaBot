package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.util.StringUtil;
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
     * StarBot 内部消息创建顺序号，通过 create 方法创建时自动生成，无需手动设置
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
     */
    private String id;

    /**
     * 发送完毕时间戳，不论发送是否成功，消息发送后自动设置，无需手动设置
     */
    private Instant completeTime;

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
     */
    private List<Runnable> onFailureCallbacks = new ArrayList<>();

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
     * 添加发送成功回调
     * @param callback 发送成功回调
     */
    public void addOnSuccessCallback(Runnable callback) {
        this.onSuccessCallbacks.add(callback);
    }

    /**
     * 添加发送失败回调
     * @param callback 发送失败回调
     */
    public void addOnFailureCallback(Runnable callback) {
        this.onFailureCallbacks.add(callback);
    }

    /**
     * 添加图片降级回调
     * @param callback 图片降级回调，在「剥掉图片段重发纯文字并送达」时触发
     */
    public void addOnImageDegradedCallback(Runnable callback) {
        this.onImageDegradedCallbacks.add(callback);
    }
}
