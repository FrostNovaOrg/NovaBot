package org.frostnova.nova.core.alert;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.core.util.UrlRedactor;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Webhook 告警通道
 * <p>
 * QQ 告警复用机器人自身的推送链路，因此 OneBot 掉线、QQ 掉登录时告警也一并失效——
 * 而那恰恰是最需要收到通知的时刻。本通道只依赖一个外部地址，不经过机器人链路，
 * 是这类故障下唯一还能把消息送出去的出口。
 * <p>
 * 字段名与请求方式均可配置，以适配 Bark、Server 酱、钉钉、飞书、Telegram 等不同约定。
 */
@Slf4j
@Component
public class WebhookAlertChannel implements AlertChannel {
    private final NovaCoreProperties properties;

    private final HttpUtil http;

    @Autowired
    public WebhookAlertChannel(NovaCoreProperties properties, HttpUtil http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public String id() {
        return "webhook";
    }

    @Override
    public String name() {
        return "Webhook";
    }

    @Override
    public boolean isAvailable() {
        return StringUtil.isNotBlank(properties.getAlert().getWebhookUrl());
    }

    @Override
    public void send(String subject, String content) {
        NovaCoreProperties.Alert alert = properties.getAlert();
        String url = alert.getWebhookUrl();
        String host = UrlRedactor.hostOf(url);

        // GET 一支在解析地址之前先看有没有空白：URI.create 抛出的原文带着整条地址，而剥地址
        // 的正则碰到空白就停，空格后面那段原样留在工程日志里——空格要是落在推送密钥前面，
        // 密钥就从那里漏。不去解析，直接按失败交出去，异常从外到内都不写这条地址。
        // 这一步必须在 try 之外抛：进了 try 会被出口收口换成「发送失败（异常名）」，
        // 「地址里有空格」这一句就到不了使用者眼前
        if ("GET".equalsIgnoreCase(alert.getWebhookMethod()) && hasWhitespace(url)) {
            throw new IllegalStateException(report(reportedHost(url), "地址里有空格"),
                    new IllegalArgumentException("Webhook GET 地址里混进了空白字符"));
        }

        Map<String, String> headers = new LinkedHashMap<>(alert.getWebhookHeaders());

        int status;
        try {
            if ("GET".equalsIgnoreCase(alert.getWebhookMethod())) {
                // 必须以 URI 传入：传字符串会被 RestTemplate 当作模板再编码一次，
                // 接收方收到的就是一串字面的百分号转义而非中文
                //
                // 两处都报「这条地址本身是凭据」：推送密钥拼在路径里，网络日志
                // （默认关、排障时才开）只记主机名，不把密钥随日志落盘
                status = http.getForStatus(URI.create(appendQuery(url, alert.getWebhookTitleField(), subject,
                        alert.getWebhookContentField(), content)), headers, HttpUtil.AddressIsCredential.YES);
            } else {
                JSONObject body = new JSONObject();
                body.put(alert.getWebhookTitleField(), subject);
                body.put(alert.getWebhookContentField(), content);

                // HttpUtil#postForStatus 自身已设置 JSON 的 Content-Type，此处不再重复指定
                status = http.postForStatus(url, headers, body, HttpUtil.AddressIsCredential.YES);
            }
        } catch (AlertBlockedException e) {
            // 被总开关拦下是预期内的情形，类型与原文一并原样交回：
            // 告警服务认这个类型决定「不重投」，换掉就变成一次要补发的故障
            throw e;
        } catch (Exception e) {
            // 出口收口：往外交的报错只留主机与失败原因。Bark、Server 酱把推送密钥拼在
            // 地址路径里，异常原文带着整条地址——测试回话、时间线详情、工程日志三处
            // 都会照原样交出去，密钥就从那里露。起因链也逐层换过：工程日志会打出整条链，
            // 起因里带地址也算漏。
            throw new IllegalStateException(report(host, reasonOf(e)), UrlRedactor.redact(e));
        }
        check(status, host);
    }

    /**
     * 成败只看 HTTP 状态码，响应体一概不解析
     * <p>
     * <b>这里踩过一次</b>：原先走的是把响应体转成 {@code String} 的重载，于是一个
     * 返回 200 但<b>不带 {@code Content-Type}</b> 的接收端（自建接口很常见）会让
     * RestTemplate 抛「无法提取响应」——告警明明送到了，却被判为失败。
     * 配上重投之后这个误判更贵：同一条告警会被反复补发，接收端收到一串重复。
     * @param status HTTP 状态码
     * @param host 接收方主机名（报错只报到主机名为止，路径与查询串不外带）
     */
    private void check(int status, String host) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(report(host, "回了状态 " + status));
        }
    }

    /**
     * 往外交的报错那一句：主机名加失败原因，别的不带
     */
    private static String report(String host, String reason) {
        return "Webhook 主机 " + host + " " + reason;
    }

    /**
     * 失败原因：连不上、超时、加密握手失败、对方回了什么状态码
     * <p>
     * <b>不抄异常原文</b>：原文里裹着整条地址（路径里可能正是推送密钥），而且真栈的原文
     * 常常根本说不出原因——JDK 的连接失败会把消息弄成一个字面的 {@code null}。
     * 原文不丢，它在起因链里跟着工程日志走。
     * @param e 失败
     * @return 原因那一句
     */
    private static String reasonOf(Exception e) {
        RestClientResponseException responseError = findCause(e, RestClientResponseException.class);
        if (responseError != null) {
            return "回了状态 " + responseError.getStatusCode().value();
        }
        if (findCause(e, SSLException.class) != null) {
            return "加密握手失败";
        }
        // 超时要排在连不上前面：连接超时（ConnectTimeoutException）是它俩的交叉地带，
        // 使用者等不到消息时最关心的是「还在等」而不是「根本没连上」
        if (findCause(e, SocketTimeoutException.class) != null
                || findCause(e, TimeoutException.class) != null
                || findCause(e, HttpTimeoutException.class) != null) {
            return "超时";
        }
        if (findCause(e, ConnectException.class) != null
                || findCause(e, UnknownHostException.class) != null
                || findCause(e, NoRouteToHostException.class) != null) {
            return "连不上";
        }
        // 起因链可能成环（A 的起因是 B、B 的起因又是 A）：走过的每一层按对象身份记下，
        // 最深一层停在环上最后一个没走过的那一层
        Throwable deepest = e;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(deepest);
        while (deepest.getCause() != null && seen.add(deepest.getCause())) {
            deepest = deepest.getCause();
        }
        return "发送失败（" + deepest.getClass().getSimpleName() + "）";
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        // 起因链可能成环（A 的起因是 B、B 的起因又是 A）：走过的每一层按对象身份记下，
        // 走到走过的就停——原来只挡「自己指自己」，两层以上互为起因时就绕着环出不来
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = e; current != null && seen.add(current); current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    /**
     * 把标题与内容拼进查询串
     * <p>
     * 地址中可能已带查询参数（如 Bark 的分组、铃声设置），因此需要判断用 ? 还是 &amp; 连接。
     */
    private String appendQuery(String url, String titleField, String subject, String contentField, String content) {
        return url
                + (url.contains("?") ? "&" : "?")
                + encode(titleField) + "=" + encode(subject)
                + "&" + encode(contentField) + "=" + encode(content);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 地址里有没有空白字符。URI 对空白一律拒收，而 URI.create 的报错原文会把整条地址带出来
     */
    private static boolean hasWhitespace(String url) {
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 混进空白的地址往外只报主机名，且主机名自身再按空白截短一道：
     * {@link UrlRedactor#hostOf} 只切 {@code /?#} 与 {@code @}，地址没写到路径就先撞上空白时
     * （如 {@code https://主机 密钥}），空格后面那段会整段被当主机名带回——那正是密钥可能待的地方
     */
    private static String reportedHost(String url) {
        String host = UrlRedactor.hostOf(url);
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                return host.substring(0, i);
            }
        }
        return host;
    }
}
