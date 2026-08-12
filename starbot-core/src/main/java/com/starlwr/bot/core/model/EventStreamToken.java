package com.starlwr.bot.core.model;

/**
 * 一把只读口令的记录
 * <p>
 * 它只能读事件流，<b>既不给服务器 shell，也不给配置控制台</b>——这是它与控制台令牌的全部区别，
 * 也是「面板远程接入」这条路线的价值所在：面板那台机器不在我们的运维范围内，
 * 它上面的副本泄漏时，损失必须被限制在「别人能看直播事件」，而不是「别人能改机器人配置」。
 *
 * @param hash 口令的指纹。<b>只存哈希不存明文</b>——明文一旦落盘，这个文件就等于又一个凭据文件，
 *             而它偏偏是要长期留着做审计的那一个
 * @param label 签给谁，如「面板-朋友甲」。<b>没有它就无法定向吊销</b>，只能一次全撤，
 *              那样就退回了「复用控制台令牌」时的粒度
 * @param issuedAt 签发时刻（毫秒）
 * @param revokedAt 吊销时刻（毫秒），未吊销时为 0
 */
public record EventStreamToken(
        String hash,
        String label,
        long issuedAt,
        long revokedAt
) {
    /**
     * 这把口令现在还能用吗
     */
    public boolean active() {
        return revokedAt <= 0;
    }
}
