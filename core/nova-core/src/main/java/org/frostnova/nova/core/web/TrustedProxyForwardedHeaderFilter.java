package org.frostnova.nova.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.util.IpMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 开着 {@code server.forward-headers-strategy=framework} 时，顶替 Spring 装的那道转发头过滤器
 *
 * <h2>为什么不用框架那一道</h2>
 * 框架那一道按请求头改写来源地址、主机名与协议，而且<b>不看直接连进来的是谁</b>：
 * 它优先认 {@code Forwarded}，于是反向代理只覆写了 {@code X-Forwarded-For}、没清 {@code Forwarded} 时，
 * 外面的人自带一个 {@code Forwarded: for=<任意地址>} 就成了那个地址；能直连后端端口的人自带什么头都被采信。
 * 控制台的来源名单、按来源的登录锁定、通行密钥的登录挑战看的都是改写之后的地址。
 *
 * <h2>这一道怎么做</h2>
 * <ul>
 *   <li>{@code Forwarded} 一律不拿来改写地址、主机名或协议，后面的各环也看不到它；</li>
 *   <li>直接对端不在可信代理的网段里：{@code X-Forwarded-*} 也不认，不改写，并从请求里去掉；</li>
 *   <li>直接对端是可信代理：{@code X-Forwarded-For}、{@code X-Forwarded-Proto}、{@code X-Forwarded-Host}、
 *       {@code X-Forwarded-Prefix} 等交给框架那一套照原样处理，取法一点不变（{@code X-Forwarded-For} 仍取最左一段）。</li>
 * </ul>
 * 可信代理的缺省网段与 Tomcat {@code RemoteIpValve} 缺省的 internal-proxies 同一套（{@link #DEFAULT_TRUSTED_PROXIES}），
 * 另外的网段写在 {@code novabot.core.config-ui.trusted-proxies}。拿不准的一律往挡的方向走：
 * 直接对端认不出是不是可信代理（地址写法不对、写错的那条网段）时，当作不可信。
 *
 * <h2>到达时的原样</h2>
 * 改写之前先把请求到达程序时的样子记在请求属性 {@link #ARRIVAL} 上（直接对端、带没带转发头）：
 * 改写之后后面看到的对端与请求头都已不是原样，程序接口判「本机」要按原样判（见 {@code NovaApiVisitor}）。
 * 请求属性只有程序自己写得进去，客户端伪造不了。
 */
@Slf4j
public class TrustedProxyForwardedHeaderFilter extends ForwardedHeaderFilter {
    /**
     * 缺省信任的代理网段：本机回环、私有网段、链路本地与运营商级地址转换网段
     * <p>
     * 与 Tomcat {@code RemoteIpValve} 缺省的 internal-proxies 同一套，顺序也照它。
     */
    public static final List<String> DEFAULT_TRUSTED_PROXIES = List.of(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
            "127.0.0.0/8", "::1/128", "fe80::/10", "fc00::/7");

    /** 请求到达时的原样记在这个请求属性上 */
    public static final String ARRIVAL = TrustedProxyForwardedHeaderFilter.class.getName() + ".ARRIVAL";

    static final String FORWARDED = "Forwarded";

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    static final String X_REAL_IP = "X-Real-IP";

    /** 框架那一道认、并从请求里删掉的 {@code X-Forwarded-*} 那几个 */
    static final List<String> X_FORWARDED = List.of("X-Forwarded-Proto", "X-Forwarded-Ssl", "X-Forwarded-Host",
            "X-Forwarded-Port", X_FORWARDED_FOR, "X-Forwarded-Prefix");

    /** 程序接口判「本机」时看的那三种转发头 */
    static final List<String> FORWARDING = List.of(FORWARDED, X_FORWARDED_FOR, X_REAL_IP);

    private final IpMatcher proxies;

    /**
     * 到达时的原样
     * @param peer 直接连进来的对端
     * @param trusted 对端在不在可信代理的网段里
     * @param forwarded 带没带 {@code Forwarded}、{@code X-Forwarded-For}、{@code X-Real-IP} 里的任何一种
     * @param rewritten 来源地址是不是按可信代理送来的 {@code X-Forwarded-For} 改写过
     */
    public record Arrival(String peer, boolean trusted, boolean forwarded, boolean rewritten) {
    }

    /**
     * @param extra 缺省网段之外另外信任的网段；写错的那条不收，并记一条警告
     */
    public TrustedProxyForwardedHeaderFilter(Collection<String> extra) {
        List<String> networks = new ArrayList<>(DEFAULT_TRUSTED_PROXIES);
        if (extra != null) {
            for (String entry : extra) {
                String problem = IpMatcher.problemOf(entry);
                if (problem != null) {
                    log.warn("novabot.core.config-ui.trusted-proxies 里有一条写得不对, 已忽略: {}", problem);
                    continue;
                }
                networks.add(entry.strip());
            }
        }
        this.proxies = new IpMatcher(networks);
    }

    /**
     * 这个请求到达时的原样
     * @param request 请求
     * @return 原样；这一道没经手过这个请求（没开 framework，或请求不带任何转发头）时为 null
     */
    public static Arrival arrival(HttpServletRequest request) {
        return request.getAttribute(ARRIVAL) instanceof Arrival arrival ? arrival : null;
    }

    /**
     * 不带任何转发头的请求不经手：与框架那一道一样原样放过。{@code X-Real-IP} 也算转发头，
     * 带着它的请求也要记下到达时的原样
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return Stream.concat(Stream.of(FORWARDED, X_REAL_IP), X_FORWARDED.stream())
                .noneMatch(name -> request.getHeader(name) != null);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Arrival arrival = arrival(request);
        if (arrival == null) {
            // 出错转发再进来一次时沿用第一次记下的：那时对端与请求头才是原样
            String peer = request.getRemoteAddr();
            boolean trusted = proxies.matches(peer);
            arrival = new Arrival(peer, trusted,
                    FORWARDING.stream().anyMatch(name -> request.getHeader(name) != null),
                    trusted && StringUtils.hasText(request.getHeader(X_FORWARDED_FOR)));
            request.setAttribute(ARRIVAL, arrival);
        }
        if (!arrival.trusted()) {
            chain.doFilter(new HiddenHeaders(request, Stream.concat(Stream.of(FORWARDED), X_FORWARDED.stream()).toList()),
                    response);
            return;
        }
        HttpServletRequest withoutForwarded = new HiddenHeaders(request, List.of(FORWARDED));
        if (X_FORWARDED.stream().noneMatch(name -> withoutForwarded.getHeader(name) != null)) {
            // 只带了 Forwarded（或只带了 X-Real-IP）：框架那一道对这样的请求本来就原样放过
            chain.doFilter(withoutForwarded, response);
            return;
        }
        super.doFilterInternal(withoutForwarded, response, chain);
    }

    /**
     * 看不见某几个请求头的请求
     */
    static final class HiddenHeaders extends HttpServletRequestWrapper {
        private final Set<String> hidden;

        HiddenHeaders(HttpServletRequest request, Collection<String> names) {
            super(request);
            this.hidden = names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        }

        private boolean isHidden(String name) {
            return name != null && hidden.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return isHidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            if (names == null) {
                return null;
            }
            List<String> visible = new ArrayList<>();
            for (String name : Collections.list(names)) {
                if (!isHidden(name)) {
                    visible.add(name);
                }
            }
            return Collections.enumeration(visible);
        }

        @Override
        public long getDateHeader(String name) {
            return isHidden(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return isHidden(name) ? -1 : super.getIntHeader(name);
        }
    }
}
