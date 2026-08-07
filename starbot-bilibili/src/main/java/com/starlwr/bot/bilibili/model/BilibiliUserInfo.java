package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 哔哩哔哩用户信息，在通用用户信息之上补充粉丝勋章、大航海、荣耀等级与房管标志
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliUserInfo extends UserInfo {
    /**
     * 粉丝勋章
     */
    private FansMedal fansMedal;

    /**
     * 大航海信息
     */
    private Guard guard;

    /**
     * 荣耀等级
     */
    private Integer honorLevel;

    /**
     * 是否为本直播间的房管
     * <p>
     * <b>只有弹幕消息带这个标志</b>，礼物、上舰、进房这些消息的报文里根本没有它，
     * 因此那些场景下这里是 {@code null}。
     * <p>
     * <b>{@code null} 的含义是「这条消息没说」，不是「不是房管」。</b>
     * 同一个人发弹幕时是房管、送礼时是 {@code null} 属于正常现象，
     * 不要因为后者就撤掉前者已经显示出来的标识，更不要拿它做任何权限判断。
     */
    private Boolean roomAdmin;

    public BilibiliUserInfo(Long uid, String uname) {
        super(uid, uname);
    }

    public BilibiliUserInfo(Long uid, String uname, String face) {
        super(uid, uname, face);
    }
}
