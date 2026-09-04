package com.starlwr.bot.core.model;

/**
 * 主播指代：一段链接里认出来的那串数字，以及它是哪一种
 * <p>
 * 同一串数字在不同链接里指的不是同一个人：个人空间链接里的是 uid，直播间链接里的是直播间号，
 * 而直播间号与 uid 属于两套编号，同一个值在两边各有主。<b>两类必须分得开</b>——
 * 合成一个「就是那串数字」的结果，调用方只能挨个试，试出来的很可能是另一位真实存在的主播，
 * 而这种认错在推送真正发出去之前看不出来。
 *
 * @param kind 这串数字是哪一种
 * @param id 数字本身
 */
public record StreamerReference(Kind kind, long id) {
    /**
     * 编号的种类
     */
    public enum Kind {
        /**
         * 主播 UID
         */
        UID,

        /**
         * 直播间号，可能是短号
         */
        ROOM_ID
    }

    /**
     * 认出的是 uid
     * @param id UID
     * @return 主播指代
     */
    public static StreamerReference uid(long id) {
        return new StreamerReference(Kind.UID, id);
    }

    /**
     * 认出的是直播间号
     * @param id 直播间号
     * @return 主播指代
     */
    public static StreamerReference roomId(long id) {
        return new StreamerReference(Kind.ROOM_ID, id);
    }
}
