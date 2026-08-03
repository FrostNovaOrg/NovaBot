package com.starlwr.bot.core.model;

import lombok.*;

/**
 * 主播信息
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class LiveStreamerInfo extends UserInfo {
    /**
     * 房间号
     */
    private Long roomId;

    public LiveStreamerInfo(Long uid, String uname, Long roomId) {
        super(uid, uname);
        this.roomId = roomId;
    }

    public LiveStreamerInfo(Long uid, String uname, Long roomId, String face) {
        super(uid, uname, face);
        this.roomId = roomId;
    }

    /**
     * 获取兼容未开通直播间的房间号字符串
     * @return 兼容未开通直播间的房间号字符串
     */
    public String getRoomIdString() {
        return roomId == null ? "未开通" : roomId.toString();
    }
}
