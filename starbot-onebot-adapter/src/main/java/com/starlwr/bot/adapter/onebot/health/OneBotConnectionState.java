package com.starlwr.bot.adapter.onebot.health;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OneBot 连接状态
 * <p>
 * 各处的连接检查此前只把结果写进日志，界面与告警都读不到，使用者只能靠翻日志判断「通没通」。
 * 此处集中记录各推送平台的连通状况，供健康探针读取——探针必须廉价且不阻塞，
 * 因此实际探测由既有的定时任务完成，探针只读这里缓存的结果。
 */
@StarBotComponent
public class OneBotConnectionState {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 记录 HTTP 连接正常
     * @param sender 推送平台名
     * @param detail 补充信息，例如 OneBot 实现版本与登录账号
     */
    public void httpOk(String sender, String detail) {
        entry(sender).http = new Status(Kind.OK, detail, Instant.now());
    }

    /**
     * 记录 HTTP 连接异常
     * @param sender 推送平台名
     * @param kind 异常类型
     * @param detail 失败原因
     */
    public void httpFailed(String sender, Kind kind, String detail) {
        entry(sender).http = new Status(kind, detail, Instant.now());
    }

    /**
     * 记录 Websocket 已连接
     * @param sender 推送平台名
     */
    public void websocketConnected(String sender) {
        entry(sender).websocket = new Status(Kind.OK, "已连接", Instant.now());
    }

    /**
     * 记录 Websocket 已断开
     * @param sender 推送平台名
     * @param detail 断开原因
     */
    public void websocketDisconnected(String sender, String detail) {
        entry(sender).websocket = new Status(Kind.UNREACHABLE, detail, Instant.now());
    }

    /**
     * 标记该推送平台未启用 Websocket
     * @param sender 推送平台名
     */
    public void websocketDisabled(String sender) {
        entry(sender).websocket = new Status(Kind.DISABLED, "未启用", Instant.now());
    }

    /**
     * 获取全部推送平台的连接状态
     * @return 推送平台名到状态的映射
     */
    public Map<String, Entry> all() {
        return Map.copyOf(entries);
    }

    /**
     * 获取全部已知的推送平台名
     * @return 推送平台名集合
     */
    public Collection<String> senders() {
        return entries.keySet();
    }

    private Entry entry(String sender) {
        return entries.computeIfAbsent(sender, key -> new Entry());
    }

    /**
     * 状态类型
     */
    public enum Kind {
        /**
         * 正常
         */
        OK,

        /**
         * Token 不正确
         */
        TOKEN_INVALID,

        /**
         * 无法连接
         */
        UNREACHABLE,

        /**
         * OneBot 实现自身状态异常，例如 QQ 账号掉线
         */
        SERVICE_ABNORMAL,

        /**
         * 未启用
         */
        DISABLED,

        /**
         * 尚未检查
         */
        UNKNOWN
    }

    /**
     * 单项状态
     *
     * @param kind 状态类型
     * @param detail 详情
     * @param at 记录时间
     */
    public record Status(Kind kind, String detail, Instant at) {
    }

    /**
     * 单个推送平台的连接状态
     */
    @Getter
    public static class Entry {
        private volatile Status http = new Status(Kind.UNKNOWN, "尚未检查", null);

        private volatile Status websocket = new Status(Kind.UNKNOWN, "尚未检查", null);
    }
}
