package org.frostnova.nova.core.config.ui.auth.passkey;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * 这套面板在通行密钥眼里叫什么
 * <p>
 * 两个值，各管一件事：
 * <ul>
 *   <li><b>rpId</b>——一把钥匙属于哪个站点。认证器把它的哈希写进签名里，
 *       浏览器则不允许页面为别的域名建钥匙。它<b>不带端口也不带协议</b></li>
 *   <li><b>origin</b>——浏览器写进客户端数据里的那一串，<b>带协议也带非默认端口</b>。
 *       校验时要一字不差地比它</li>
 * </ul>
 *
 * <h2>为什么现取而不写进配置</h2>
 * 面板的访问地址不是一件配置得出来的事：同一台机器可能被从 {@code localhost}、内网 IP、
 * 反代后的域名三种地址访问，而<b>钥匙是按 rpId 绑死的</b>。写死一个值意味着换一条路进来的人
 * 手上那把钥匙突然不认了，而报错只会是「验证失败」。现取的代价是：<b>换个地址进来就得重新登记一把</b>——
 * 这与浏览器那一侧的行为一致，使用者看到的是「这台设备还没登记过」，而不是一句无从下手的失败。
 *
 * <h2>反向代理</h2>
 * 代理之后，{@code Host} 里是代理转给我们的那个（多半是 {@code 127.0.0.1:7827}），
 * 而浏览器看到的是代理对外的域名。这两者一旦不同，签名里那个 rpId 哈希就永远对不上，
 * 因此这里优先认 {@code X-Forwarded-Host} 与 {@code X-Forwarded-Proto}。
 * <p>
 * 🔴 <b>这两个头是代理写的，不是使用者能定的。</b>直接暴露端口（没有代理）时，
 * 任何人都能自己带上这两个头——但那样做只能让他自己那一趟的 rpId 变成别的值，
 * 于是他手上的钥匙对不上、验签不过。<b>伪造它换不来任何东西</b>：
 * 钥匙的公钥存在这一侧，rpId 换了只会让比对失败。
 */
public record PasskeyRelyingParty(String rpId, String origin) {
    private static final String FORWARDED_HOST = "X-Forwarded-Host";

    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    /**
     * 从请求里认出这套面板此刻的身份
     * @param request 当前请求
     * @return rpId 与 origin
     */
    public static PasskeyRelyingParty of(HttpServletRequest request) {
        String host = firstValue(request.getHeader(FORWARDED_HOST));
        if (host == null) {
            host = firstValue(request.getHeader(HttpHeaders.HOST));
        }
        if (host == null) {
            // Host 是 HTTP/1.1 的必填头，走到这里说明请求本身就不合规
            throw new IllegalStateException("请求里没有 Host，无法确定通行密钥的适用范围");
        }

        String scheme = firstValue(request.getHeader(FORWARDED_PROTO));
        if (scheme == null) {
            scheme = request.isSecure() ? "https" : "http";
        }

        return new PasskeyRelyingParty(stripPort(host), scheme.toLowerCase() + "://" + host);
    }

    /**
     * 这个地址用得了通行密钥吗
     * <p>
     * 🔴 <b>rpId 必须是域名，不能是 IP 地址。</b>浏览器按这一条直接拒绝，
     * 而它抛出来的是一句与「地址是个 IP」毫无关系的异常——使用者只会看到登记按钮点了没反应。
     * 因此这一句判在服务端，好让界面能说出「换个域名进来」这句有用的话。
     * <p>
     * {@code localhost} 不在此列：它是域名，且浏览器给它开了安全上下文的例外，
     * 本机直接访问时通行密钥是能用的——这恰好是最常见的那一种用法。
     * @return 是否可用
     */
    public boolean usable() {
        return !isIpLiteral(rpId);
    }

    /**
     * 说清为什么用不了
     * @return 一句给使用者看的话，可用时为 null
     */
    public String unusableReason() {
        return usable() ? null
                : "通行密钥只能绑定域名，而现在是用 IP 地址（" + rpId + "）访问的。"
                + "请改用 localhost 或一个域名访问控制台后再试。";
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            // 带方括号或带冒号的只可能是 IPv6 字面量：端口在上一步已经去掉了
            return true;
        }

        // IPv4 字面量：四段纯数字。域名不会长成这样——顶级域不允许全是数字
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * 取逗号分隔的头里的第一个值
     * <p>
     * 串了两层代理时 {@code X-Forwarded-Host} 会是 {@code a.example.com, b.internal}，
     * 而<b>最靠近浏览器的那一个才是浏览器看到的地址</b>——按规范它排在最前。
     */
    private static String firstValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        String value = header.split(",")[0].strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * 去掉端口
     * <p>
     * IPv6 的地址本身带冒号（{@code [::1]:7827}），因此不能见冒号就截——
     * 那样 {@code [::1]} 会被截成 {@code [}，而表现是这个地址下建的钥匙一把也认不出来。
     */
    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }

        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
