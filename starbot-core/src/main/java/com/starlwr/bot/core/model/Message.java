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

        return content.replaceAll("\\{face=.+?}", "[表情]")
                .replace("{at=all}", "@全体成员 ")
                .replaceAll("\\{at=(.*?)}", "@$1")
                .replaceAll("\\{image_url=.*?}", "[图片]")
                .replaceAll("\\{image_path=.*?}", "[图片]")
                .replaceAll("\\{image_base64=.*?}", "[图片]");
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
}
