package com.starlwr.bot.core.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 推送目标类型
 * <p>
 * <b>对外的线上取值是 {@link #getCode()}，不是枚举名、也不是枚举序号。</b>
 * 推送接口是公开接口，{@code code} 早已写进配置校验与手册，这里用 {@link JsonValue}
 * 与 {@link JsonCreator} 把它锁住，不再依赖 Jackson「整数按枚举序号解」的默认行为。
 * <p>
 * 此前是靠巧合工作的：默认行为按序号解，而 {@code FRIEND} 与 {@code GROUP} 的序号
 * 恰好等于各自的 code（0 与 1）。一旦有人在 {@code GROUP} 前面插入一个枚举值，
 * 线上的 {@code 1} 就会解成别的类型——<b>外部调用方静默错投，而我们的测试全绿</b>。
 */
@Getter
@AllArgsConstructor
public enum PushTargetType {
    FRIEND(0, "好友"),
    GROUP(1, "群"),
    UNKNOWN(-1, "未知");

    /**
     * 对外的线上取值
     */
    @JsonValue
    private final int code;

    private final String str;

    /**
     * 按 code 取，认不出时返回 {@link #UNKNOWN}
     * <p>
     * 供程序内部使用。<b>{@code UNKNOWN} 会让消息在运行期被丢弃</b>，
     * 所以凡是外部输入都该走 {@link #fromCode}，让它当场报错而不是静默少发一条推送。
     */
    public static PushTargetType of(int code) {
        for (PushTargetType pushTargetType : PushTargetType.values()) {
            if (pushTargetType.code == code) {
                return pushTargetType;
            }
        }

        return UNKNOWN;
    }

    /**
     * 从线上取值反解，只接受 {@link #FRIEND} 与 {@link #GROUP}
     * <p>
     * 供 Jackson 反序列化外部请求体使用，因此<b>刻意比 {@link #of} 严格</b>：认不出就抛。
     * <p>
     * 宽松在这里是有害的：解成 {@code UNKNOWN} 不会报错，只会让这条推送在运行期被
     * 安静丢掉——调用方收到成功响应、群里什么都没有、日志也不指向原因。
     * 实测「把 type 写成 2」正是这个下场（2 恰好是 {@code UNKNOWN} 的序号）。
     * {@code UNKNOWN} 本身也不该由外部发来，它是内部表示「认不出」的哨兵值。
     * @param code 线上取值
     * @return 推送目标类型
     */
    @JsonCreator
    public static PushTargetType fromCode(int code) {
        if (code == FRIEND.code) {
            return FRIEND;
        }
        if (code == GROUP.code) {
            return GROUP;
        }

        throw new IllegalArgumentException("推送目标类型只能是 " + GROUP.code + "（群聊）或 "
                + FRIEND.code + "（私聊），收到的是 " + code);
    }
}
