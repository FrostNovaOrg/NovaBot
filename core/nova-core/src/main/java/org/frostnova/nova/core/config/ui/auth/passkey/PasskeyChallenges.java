package org.frostnova.nova.core.config.ui.auth.passkey;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
     * 同时存在的登录挑战数上限
     * <p>
     * 登录挑战由未登录者也能索取（这条路本来就得如此），因此必须有上限，
     * 否则反复索取就能把内存撑大。
     */
    private static final int MAX_LOGIN = 64;

    /**
     * 一个来源地址同时能占的登录挑战数
     * <p>
     * 🔴 这一条才是防挤的那道：满了只淘汰<b>这个地址自己</b>最早的那条。
     * 只有总上限的话，一个地址连要几十次就能把别人手上那条当成「最早的」挤掉——
     * 主人按完指纹，得到一句挑战失效。一个正常人同时开着的登录框不会超过几个，取 8 留足余地。
     * <p>
     * 来源地址与 {@code ConfigUiSecurityFilter} 认白名单用的是同一个（{@code getRemoteAddr()}）。
     * 放在反向代理后面、所有人看起来都是代理那一个地址时，这些人共用这 8 条，
     * 彼此仍挤得掉——那时分人要靠代理传来的真实地址，而这一侧至今不信转发头。
     */
    private static final int MAX_LOGIN_PER_SOURCE = 8;

    /**
     * 同时存在的登记挑战数上限
     * <p>
     * 🔴 登记挑战<b>单独计数</b>，不和登录挑战抢位置：拿登记挑战要先登录、再核一次现在的密码，
     * 未登录的人再怎么要登录挑战也碰不到这一份。
     */
    private static final int MAX_REGISTER = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 所有读写都在本对象的锁里：表最多几十条，一把锁换来「数了再删」之间不会被别的线程插队——
     * 不加锁的话，被持续灌入时「腾到不满为止」可能永远腾不完
     */
    private final Map<String, Pending> pending = new HashMap<>();

    /**
     * 发一个新挑战
     * @param purpose 这一个是给哪条路用的
     * @param source 索取者的来源地址；登录挑战按它分额度，登记挑战不看它
     * @param now 当前时刻
     * @return base64url 编码的挑战串
     */
    synchronized String issue(Purpose purpose, String source, Instant now) {
        String key = source == null ? "" : source;

        sweep(now);
        makeRoom(purpose, key);

        byte[] bytes = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(bytes);

        String challenge = PasskeyBytes.encode(bytes);
        pending.put(challenge, new Pending(purpose, key, now.plus(TTL)));

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
    synchronized boolean consume(String challenge, Purpose purpose, Instant now) {
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
     * 发新挑战前腾位置
     * <p>
     * 登记：满了淘汰最早的登记挑战。登录：先看这个地址自己满没满，满了只淘汰它自己最早的；
     * 总数也满了（要凑够这么多得有至少 {@code MAX_LOGIN / MAX_LOGIN_PER_SOURCE} 个地址），
     * 从占得最多的那个地址里淘汰最早的——只占一两条的正常使用者排在最后。
     */
    private void makeRoom(Purpose purpose, String source) {
        if (purpose == Purpose.REGISTER) {
            while (count(entry -> entry.purpose == Purpose.REGISTER) >= MAX_REGISTER) {
                evictOldest(entry -> entry.purpose == Purpose.REGISTER);
            }
            return;
        }

        while (count(entry -> entry.purpose == Purpose.LOGIN && entry.source.equals(source)) >= MAX_LOGIN_PER_SOURCE) {
            evictOldest(entry -> entry.purpose == Purpose.LOGIN && entry.source.equals(source));
        }

        while (count(entry -> entry.purpose == Purpose.LOGIN) >= MAX_LOGIN) {
            Map<String, Long> perSource = pending.values().stream()
                    .filter(entry -> entry.purpose == Purpose.LOGIN)
                    .collect(Collectors.groupingBy(Pending::source, Collectors.counting()));
            String heaviest = Collections.max(perSource.entrySet(), Map.Entry.comparingByValue()).getKey();
            evictOldest(entry -> entry.purpose == Purpose.LOGIN && entry.source.equals(heaviest));
        }
    }

    private long count(Predicate<Pending> filter) {
        return pending.values().stream().filter(filter).count();
    }

    private void evictOldest(Predicate<Pending> filter) {
        pending.entrySet().stream()
                .filter(entry -> filter.test(entry.getValue()))
                .min(Comparator.comparing(entry -> entry.getValue().expiresAt))
                .map(Map.Entry::getKey)
                .ifPresent(pending::remove);
    }

    /**
     * 表里此刻还有几条（含已过期、还没被下一次发放清掉的）
     */
    synchronized int size() {
        return pending.size();
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

    private record Pending(Purpose purpose, String source, Instant expiresAt) {
    }
}
