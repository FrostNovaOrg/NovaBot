package com.starlwr.bot.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * RestTemplate 配置
 * <p>
 * 使用 JDK 内置的 {@link HttpClient} 而非默认的 {@code SimpleClientHttpRequestFactory}。
 * 后者底层是 HttpURLConnection，其连接缓存的空闲超时固定为 5 秒且不可配置，
 * 而本项目的轮询间隔是 10 秒——每次轮询都恰好踩空缓存窗口，退化为每个请求重新握手。
 * <p>
 * 实测（间隔 10 秒、连续 8 次查询，每次查询内含约 3 个请求）：
 * 换用前新增 24 次 TLS 握手，换用后为 0——连接在各次轮询之间被完整复用。
 * 作为对照，改用前把间隔缩短到 1 秒（落在 5 秒缓存窗口内）时同样只有 2 次握手，
 * 可见问题确实出在「轮询间隔恰好超出缓存窗口」这一点上。
 * <p>
 * 选 JDK 自带实现而非 Apache HttpClient：无需引入新依赖，对以小内存 VPS 为目标的项目更合适。
 */
@Configuration
public class RestTemplateConfig {
    private final StarBotCoreProperties properties;

    @Autowired
    public RestTemplateConfig(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestTemplate restTemplate() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.of(properties.getNetwork().getConnectTimeout(), ChronoUnit.SECONDS))
                // 跟随重定向：哔哩哔哩的部分接口会在鉴权后跳转
                .followRedirects(HttpClient.Redirect.NORMAL)
                // 固定 HTTP/1.1。JDK 默认是 HTTP_2，对明文 http:// 会先发一个带
                // Connection: Upgrade, HTTP2-Settings 与 Upgrade: h2c 的升级请求。
                // OneBot 实现都是 HTTP/1.1，且 NapCat 若在 HTTP 端口上同时开了 WebSocket，
                // 其 ws 库会接管所有带 Upgrade 头的请求，并对非 GET 一律回 405 Invalid HTTP method——
                // 表现为「curl 手测正常、程序却报 OneBot HTTP 服务不可用」。真机实测复现并确认。
                // HTTPS 走 ALPN 协商不受此影响，但本项目请求量很小，统一 1.1 更省心
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.of(properties.getNetwork().getReadTimeout(), ChronoUnit.SECONDS));

        return new RestTemplate(factory);
    }
}
