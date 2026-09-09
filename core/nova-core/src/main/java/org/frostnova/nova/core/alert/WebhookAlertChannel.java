package org.frostnova.nova.core.alert;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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

        Map<String, String> headers = new LinkedHashMap<>(alert.getWebhookHeaders());

        if ("GET".equalsIgnoreCase(alert.getWebhookMethod())) {
            // 必须以 URI 传入：传字符串会被 RestTemplate 当作模板再编码一次，
            // 接收方收到的就是一串字面的百分号转义而非中文
            check(http.getForStatus(URI.create(appendQuery(url, alert.getWebhookTitleField(), subject,
                    alert.getWebhookContentField(), content)), headers));
            return;
        }

        JSONObject body = new JSONObject();
        body.put(alert.getWebhookTitleField(), subject);
        body.put(alert.getWebhookContentField(), content);

        // HttpUtil#postForStatus 自身已设置 JSON 的 Content-Type，此处不再重复指定
        check(http.postForStatus(url, headers, body));
    }

    /**
     * 成败只看 HTTP 状态码，响应体一概不解析
     * <p>
     * <b>这里踩过一次</b>：原先走的是把响应体转成 {@code String} 的重载，于是一个
     * 返回 200 但<b>不带 {@code Content-Type}</b> 的接收端（自建接口很常见）会让
     * RestTemplate 抛「无法提取响应」——告警明明送到了，却被判为失败。
     * 配上重投之后这个误判更贵：同一条告警会被反复补发，接收端收到一串重复。
     * @param status HTTP 状态码
     */
    private void check(int status) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Webhook 返回 " + status);
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
