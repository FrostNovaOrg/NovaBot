package org.frostnova.nova.bilibili.enums;

/** 观众在当前直播间的大航海等级。UNKNOWN 表示报文没有给足判断依据。 */
public enum RoomGuardLevel {
    UNKNOWN, NONE, CAPTAIN, COMMANDER, GOVERNOR;

    public static RoomGuardLevel fromLevel(Long level) {
        if (level == null || level < 0 || level > 3) {
            return UNKNOWN;
        }
        return switch (level.intValue()) {
            case 0 -> NONE;
            case 1 -> GOVERNOR;
            case 2 -> COMMANDER;
            case 3 -> CAPTAIN;
            default -> UNKNOWN;
        };
    }
}
