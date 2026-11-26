package org.frostnova.nova.core.protocol;

/**
 * 首帧认证认的第二种凭据：外壳按账号签发的访问令牌
 * <p>
 * 核心只定这几个口子：令牌怎么存、账号此刻能不能用、哪一项范围管事件流，都由外壳实现并注册为 Bean。
 * 没有注册时首帧只认只读口令，与从前完全一样。
 *
 * <h2>与只读口令怎么分</h2>
 * 端点先问 {@link #claims}：归它管的（按开头认，不查表）才交给 {@link #check}；
 * {@link Check#notMine()} 时照旧交给只读口令去核，出示的东西对只读口令来说与从前一模一样。
 * 所以一把从没被当成事件流凭据的访问令牌（表里没有，或没勾事件流那一项范围），
 * 客户端看到的回应与出示一串乱码相同，看不出它在不在。
 *
 * <h2>实现须知</h2>
 * <ul>
 *   <li>不抛异常、不阻塞在网络上；端点在入站线程与自己的复核线程上调它。</li>
 *   <li>不把出示的令牌原文写进日志、异常或 {@link Grant#label()}。</li>
 *   <li>令牌或账号变了（吊销、停用、删除、改角色）就调一遍 {@link #addChangeListener} 登记的通知，
 *       端点据此当场复核连着的连接；没通知到的，也会在端点的定时复核里被发现。</li>
 * </ul>
 */
public interface EventStreamAccessTokens {
    /**
     * 出示的这一串归不归访问令牌管
     * <p>
     * 只看写法（例如开头），不查表、不计数。
     * @param presented 首帧里的 {@code token}，可为 null
     * @return 归它管时为 true
     */
    boolean claims(String presented);

    /**
     * 核对一把出示的访问令牌
     * @param presented 首帧里的 {@code token}，{@link #claims} 已认过
     * @return 结论
     */
    Check check(String presented);

    /**
     * 连着的时候再核一次
     * @param grant 认证通过时拿到的那一把
     * @return 此刻还能用为 {@link EventStreamTokenService.Verdict#OK}，否则是该用的失败原因
     */
    EventStreamTokenService.Verdict recheck(Grant grant);

    /**
     * 登记「令牌或账号刚变过」的通知
     * <p>
     * 通知在改动方的线程上调，<b>调用方只能做不阻塞的事</b>（端点只是排一次复核）。
     * @param listener 通知
     */
    void addChangeListener(Runnable listener);

    /**
     * 撤掉 {@link #addChangeListener} 登记的通知
     * @param listener 通知
     */
    void removeChangeListener(Runnable listener);

    /**
     * 一把通过了核对的令牌
     * @param id    令牌的编号（不是令牌本身，也不是摘要），复核时按它找
     * @param label 写日志用的说明：哪个账号的哪一把，不含令牌本身
     */
    record Grant(String id, String label) {
    }

    /**
     * 一次核对的结论
     * <p>
     * 三种之一：通过（{@code grant} 非空）；是事件流凭据但此刻不能用（{@code verdict} 是失败原因）；
     * 不是事件流凭据（{@link #notMine()}，端点交给只读口令接着核）。
     *
     * @param verdict 结论；不是事件流凭据时为 {@link EventStreamTokenService.Verdict#BAD_TOKEN}
     * @param grant   通过时的那一把，其余为 null
     * @param mine    是不是事件流凭据
     */
    record Check(EventStreamTokenService.Verdict verdict, Grant grant, boolean mine) {
        public Check {
            if (verdict == null) {
                throw new IllegalArgumentException("核对结论不能为空");
            }
            if ((verdict == EventStreamTokenService.Verdict.OK) != (grant != null)) {
                throw new IllegalArgumentException("只有通过的结论带令牌");
            }
            if (!mine && verdict == EventStreamTokenService.Verdict.OK) {
                throw new IllegalArgumentException("不是事件流凭据就不能通过");
            }
        }

        /** 通过 */
        public static Check granted(Grant grant) {
            return new Check(EventStreamTokenService.Verdict.OK, grant, true);
        }

        /** 是事件流凭据，但此刻不能用 */
        public static Check refused(EventStreamTokenService.Verdict verdict) {
            if (verdict == EventStreamTokenService.Verdict.OK) {
                throw new IllegalArgumentException("拒绝时要给出失败原因");
            }
            return new Check(verdict, null, true);
        }

        /** 不是事件流凭据：表里没有，或没勾管事件流的那一项范围 */
        public static Check notMine() {
            return new Check(EventStreamTokenService.Verdict.BAD_TOKEN, null, false);
        }
    }
}
