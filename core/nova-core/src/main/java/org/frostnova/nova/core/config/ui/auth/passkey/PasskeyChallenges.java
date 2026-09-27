package org.frostnova.nova.core.config.ui.auth.passkey;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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
     * IPv6 地址按 /64 前缀算一个来源，见 {@link #sourceKey}。
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
     * 发放序号：并列要淘汰时认「最新发出的」靠它，不靠到期时刻——同一毫秒里发出的几条到期时刻相同
     */
    private long issued;

    /**
     * 地址串里只许出现这些字符才拿去解析：{@link InetAddress#getByName} 碰上不像地址字面的串会去查 DNS，
     * 首字符是十六进制数字或冒号、又带冒号时它只按 IPv6 字面解析，解析不了直接抛异常，不查
     */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    /**
     * 发一个新挑战
     * @param purpose 这一个是给哪条路用的
     * @param source 索取者的来源地址；登录挑战按它分额度，登记挑战不看它
     * @param now 当前时刻
     * @return base64url 编码的挑战串
     */
    synchronized String issue(Purpose purpose, String source, Instant now) {
        String key = sourceKey(source);

        sweep(now);
        makeRoom(purpose, key);

        byte[] bytes = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(bytes);

        String challenge = PasskeyBytes.encode(bytes);
        pending.put(challenge, new Pending(purpose, key, now.plus(TTL), ++issued));

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
     * 登记：满了淘汰最早的登记挑战。登录：先看这个来源自己满没满，满了只淘汰它自己最早的；
     * 总数也满了，从占得最多的来源里淘汰：只有一个占得最多的，淘汰它最早的那条；
     * 几个来源并列最多（比如人人都只占一条），淘汰它们当中<b>最新发出</b>的那条——
     * 先要到手的挑战因此活到自然过期，后来换着地址刷的人挤的是自己。
     * <p>
     * 挡不住的：主人同时开着的框比别人多（占 2 条而别人各 1 条）时，主人就是占得最多的，
     * 仍先被淘汰；对手握着许多段前缀、在主人要挑战之前就一直在刷，表满后主人新要的那条就是最新的，
     * 仍会被挤掉。后一种不改成「满了拒发」：那样对手占满表就能让主人一条都要不到。
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
            long most = Collections.max(perSource.values());
            Predicate<Pending> heaviest = entry -> entry.purpose == Purpose.LOGIN && perSource.get(entry.source) == most;

            if (perSource.values().stream().filter(n -> n == most).count() == 1) {
                evictOldest(heaviest);
            } else {
                evictNewest(heaviest);
            }
        }
    }

    /**
     * 按来源计额度时认的那个「来源」
     * <p>
     * IPv6 一台机器通常分到整个 /64 前缀，地址可以随手换，所以按前 64 位归成一个；
     * IPv4 映射的 IPv6 地址（{@code ::ffff:a.b.c.d}）就是那个 IPv4 地址；IPv4 照单个地址算。
     * <p>
     * 解析不了的串原样当一个来源，与改前同：归成同一个的话，所有怪串会共用 8 条互相挤；
     * 这串来自 {@code getRemoteAddr()}，正常不会解析不了。
     */
    static String sourceKey(String source) {
        if (source == null) {
            return "";
        }

        int zone = source.indexOf('%');
        String literal = zone < 0 ? source : source.substring(0, zone);
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.indexOf(':') < 0 || !IPV6_LITERAL.matcher(literal).matches()) {
            return source;
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            return source;
        }

        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }

        byte[] bytes = address.getAddress();
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
        }
        return prefix.append(":/64").toString();
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

    private void evictNewest(Predicate<Pending> filter) {
        pending.entrySet().stream()
                .filter(entry -> filter.test(entry.getValue()))
                .max(Comparator.comparingLong(entry -> entry.getValue().order))
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

    private record Pending(Purpose purpose, String source, Instant expiresAt, long order) {
    }
}
