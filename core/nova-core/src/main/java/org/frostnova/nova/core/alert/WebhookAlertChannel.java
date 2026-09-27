package org.frostnova.nova.core.alert;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.HttpUtil;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String host = hostOf(url);

        Map<String, String> headers = new LinkedHashMap<>(alert.getWebhookHeaders());

        int status;
        try {
            if ("GET".equalsIgnoreCase(alert.getWebhookMethod())) {
                // 必须以 URI 传入：传字符串会被 RestTemplate 当作模板再编码一次，
                // 接收方收到的就是一串字面的百分号转义而非中文
                status = http.getForStatus(URI.create(appendQuery(url, alert.getWebhookTitleField(), subject,
                        alert.getWebhookContentField(), content)), headers);
            } else {
                JSONObject body = new JSONObject();
                body.put(alert.getWebhookTitleField(), subject);
                body.put(alert.getWebhookContentField(), content);

                // HttpUtil#postForStatus 自身已设置 JSON 的 Content-Type，此处不再重复指定
                status = http.postForStatus(url, headers, body);
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
            throw new IllegalStateException(report(host, reasonOf(e)), stripChain(e));
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
        Throwable deepest = e;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        return "发送失败（" + deepest.getClass().getSimpleName() + "）";
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * 地址里允许出现的字符——拿它收边界，免得把中文叙述跟着吃进地址
     */
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s\"'()<>，。；、）】》]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEME = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    /**
     * 把文本里每个地址剥成主机名：路径与查询串一概不外带
     * <p>
     * 不猜哪一段路径是密钥——路径与查询串整段拿掉，主机名留下（哪台主机是排障要看的）。
     */
    private static String stripUrls(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(hostOf(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 从地址里取主机名：用户信息、端口、路径、查询串一概去掉
     * <p>
     * 用户信息（{@code https://用户:口令@主机}）同样是凭据，一并去掉。
     */
    private static String hostOf(String url) {
        String rest = SCHEME.matcher(url).replaceFirst("");
        int cut = firstIndexOf(rest, "/?#");
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("[")) {
            // IPv6 字面量，冒号是地址本身的一部分，不能当端口切
            int end = rest.indexOf(']');
            return end >= 0 ? rest.substring(0, end + 1) : rest;
        }
        int colon = rest.indexOf(':');
        return colon >= 0 ? rest.substring(0, colon) : rest;
    }

    private static int firstIndexOf(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 起因链逐层拷贝一份，每层印文都剥掉地址，供落日志与往上抛时代替原异常
     * <p>
     * 只处理最外层不够：工程日志会打出整条起因链（{@code Caused by:} 那几行），
     * 起因里带地址也算漏。栈帧原样保留——出错定位全在那里。
     */
    private static Throwable stripChain(Throwable e) {
        if (e == null) {
            return null;
        }
        Stripped copy = new Stripped(stripUrls(e.toString()), stripChain(e.getCause()));
        copy.setStackTrace(e.getStackTrace());
        return copy;
    }

    /**
     * 印文换过、栈帧留着的异常副本
     * <p>
     * {@code toString()} 直接回换过的那一句：日志里这一行读起来与原来同一条线，
     * 只是地址没了——不能让它印成本类的类名，那会让读日志的人以为异常类型变了。
     */
    private static final class Stripped extends Throwable {
        private final String rendered;

        private Stripped(String rendered, Throwable cause) {
            super(rendered, cause, false, true);
            this.rendered = rendered;
        }

        @Override
        public String toString() {
            return rendered;
        }
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
}
