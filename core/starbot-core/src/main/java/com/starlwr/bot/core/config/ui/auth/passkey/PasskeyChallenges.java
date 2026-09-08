package com.starlwr.bot.core.config.ui.auth.passkey;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发出去还没用掉的那些挑战串
 * <p>
 * 挑战是通行密钥防重放的全部依靠：认证器签的内容里包含它，而它由服务端现发。
 * 因此这里只有两条规矩，缺一条整套就不成立：
 * <ol>
 *   <li><b>一次性</b>——用过即销。留着的话，截获过一次签名的人可以把同一份响应再交一遍</li>
 *   <li><b>有期限</b>——过期即销。没有期限的挑战等于一张永久有效的门票，
 *       而使用者点开登记框之后走开半小时这种事再正常不过</li>
 * </ol>
 * <p>
 * 只在内存里，进程重启即全部失效。这与会话存储是同一副形状，理由也相同：
 * 重来一次的代价只是点一下按钮。
 * <p>
 * 🔴 <b>登记用的挑战与登录用的挑战分开记</b>。合成一本账的话，一个只被允许登录的人
 * 可以拿登录时发给他的挑战去走登记那条路——而登记这条路的产物是<b>一把新的、永久有效的钥匙</b>。
 */
class PasskeyChallenges {
    /**
     * 挑战的字节数，取自 WebAuthn 规范给的下限的两倍
     */
    private static final int CHALLENGE_BYTES = 32;

    /**
     * 挑战有效期
     * <p>
     * 与浏览器那一侧给认证器的超时（60 秒）不是一回事，取得比它宽：
     * 使用者可能要先去拿手机、再去按指纹。取五分钟是让正常操作绝不会被自己这一侧卡住，
     * 同时把一份被截获的挑战的可用窗口压在一个明确的数上。
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 同时存在的挑战数上限
     * <p>
     * 挑战由未登录者也能索取（登录那一条路本来就得如此），因此必须有上限，
     * 否则反复索取就能把内存撑大。超出时淘汰最早发出的那些。
     */
    private static final int MAX_PENDING = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 发一个新挑战
     * @param purpose 这一个是给哪条路用的
     * @param now 当前时刻
     * @return base64url 编码的挑战串
     */
    String issue(Purpose purpose, Instant now) {
        sweep(now);
        evictOldestIfFull();

        byte[] bytes = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(bytes);

        String challenge = PasskeyBytes.encode(bytes);
        pending.put(challenge, new Pending(purpose, now.plus(TTL)));

        return challenge;
    }

    /**
     * 用掉一个挑战
     * <p>
     * <b>先删再判</b>：不管这一趟最终验没验过，这个挑战都不能再用第二次。
     * 反过来（验过了才删）会留下一个口子——签名不对时挑战还在，可以拿它反复试。
     * @param challenge 客户端回传的挑战串
     * @param purpose 这一趟走的是哪条路
     * @param now 当前时刻
     * @return 是否是一个有效且用途相符的挑战
     */
    boolean consume(String challenge, Purpose purpose, Instant now) {
        if (challenge == null || challenge.isBlank()) {
            return false;
        }

        Pending entry = pending.remove(challenge);
        return entry != null && entry.purpose == purpose && now.isBefore(entry.expiresAt);
    }

    private void sweep(Instant now) {
        pending.values().removeIf(entry -> !now.isBefore(entry.expiresAt));
    }

    /**
     * 腾位置
     * <p>
     * 循环次数写死上限而不是「一直腾到不满为止」：这张表另有线程在写，
     * 「不满为止」在被持续灌入时就是一个不会结束的循环——而那正是它本要防的那种情形。
     */
    private void evictOldestIfFull() {
        for (int i = 0; i < MAX_PENDING && pending.size() >= MAX_PENDING; i++) {
            pending.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().expiresAt))
                    .map(Map.Entry::getKey)
                    .ifPresent(pending::remove);
        }
    }

    /**
     * 挑战是给哪条路发的
     */
    enum Purpose {
        /**
         * 登记一把新钥匙
         */
        REGISTER,

        /**
         * 用已有的钥匙登录
         */
        LOGIN
    }

    private record Pending(Purpose purpose, Instant expiresAt) {
    }
}
