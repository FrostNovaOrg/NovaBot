package org.frostnova.nova.interact.danmaku;

/**
 * 发一条弹幕的结果
 *
 * @param sent 平台回了「已发出」时为 true
 * @param failure 没发出的分类，发出时为 null；{@link DanmakuFailure#UNCERTAIN} 表示不知道发没发出
 * @param message 给人看的一句话
 * @param roomId 房间号
 * @param text 去掉两头空白后的正文
 * @param account 用的哪个账号，还没选到能用的账号就被拒时为 null
 * @param platformCode 平台回的代码，没收到回包时为 null
 * @param platformMessage 平台回的提示原文，没有时为 null
 */
public record DanmakuResult(boolean sent, DanmakuFailure failure, String message, long roomId, String text,
                            DanmakuAccountChoice account, Integer platformCode, String platformMessage) {
    /**
     * 是不是「不知道发没发出」：可能已经出现在直播间里，再发一次就可能是两条
     * @return 结果不明时为 true
     */
    public boolean uncertain() {
        return failure == DanmakuFailure.UNCERTAIN;
    }
}
