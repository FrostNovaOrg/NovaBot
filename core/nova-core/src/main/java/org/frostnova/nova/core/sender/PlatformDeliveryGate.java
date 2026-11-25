package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;

import java.time.Instant;

/** 平台停用时，已排队的消息和新提交的消息都按暂停丢弃。 */
public interface PlatformDeliveryGate {
    String BLOCKED = "platform_disabled";

    /** 返回停用原因。其它平台和当前允许投递的消息返回 null。 */
    String blockedReason(String platform, Instant createdAt);

    static JSONObject blockedResponse(String reason) {
        return JSONObject.of("code", -1, "message", reason, BLOCKED, true);
    }
}
