package com.starlwr.bot.core.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 请求工具类
 */
@Slf4j
@Component
public class HttpUtil {
    private final ThreadPoolTaskExecutor executor;

    private final RestTemplate restTemplate;

    private final StarBotCoreProperties properties;

    private static final Logger networkLogger = LoggerFactory.getLogger("NetworkLogger");

    @Autowired
    public HttpUtil(@Qualifier("networkThreadPool") ThreadPoolTaskExecutor executor, RestTemplate restTemplate, StarBotCoreProperties properties) {
        this.executor = executor;
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    // 这里原先有一个 getRandomUserAgent() 和一串 2007 年的浏览器 UA
    // （MSIE 7/8/9、Firefox 3、Arora、Kazehakase），零调用方，已于 2026-08-07 删除。
    // 真实请求一律用配置里的 network.user-agent。
    // 随机化古董 UA 起不到任何伪装作用，反而是最醒目的异常客户端特征——
    // 2026 年还在发 MSIE 7 的 UA，比不设 UA 更容易被挑出来。
    // 需要 UA 时请读配置，不要在这里重新引入一份。

    /**
     * 发起 HTTP 请求
     * @param url URL
     * @param method 请求方法
     * @param httpEntity 请求实体
     * @param responseType 响应类型
     * @return 请求结果
     * @param <T> 返回值类型
     */
    private <T> T request(String url, HttpMethod method, HttpEntity<?> httpEntity, Class<T> responseType) {
        return requestForEntity(url, method, httpEntity, responseType).getBody();
    }

    /**
     * 发起 HTTP 请求，返回包含响应头在内的完整响应
     * <p>
     * 绝大多数调用只关心响应体，因此对外只暴露返回响应体的重载。个别接口把结果放在响应头里
     * （例如哔哩哔哩的 Cookie 刷新把新凭据放在 Set-Cookie 中），才需要用到本方法。
     * @param url URL
     * @param method 请求方法
     * @param httpEntity 请求实体
     * @param responseType 响应类型
     * @return 完整响应
     * @param <T> 返回值类型
     */
    private <T> ResponseEntity<T> requestForEntity(URI uri, HttpMethod method, HttpEntity<?> httpEntity, Class<T> responseType) {
        long startTime = System.currentTimeMillis();
        if (properties.getLog().isNetworkLog()) {
            networkLogger.info("{} -> {}", method.name(), uri);
        }

        try {
            return restTemplate.exchange(uri, method, httpEntity, responseType);
        } catch (Exception e) {
            if (properties.getLog().isNetworkLog()) {
                long cost = System.currentTimeMillis() - startTime;
                networkLogger.error("{} <- [{}]({} ms): {}", method.name(), e.getMessage(), cost, uri, e);
            }
            throw e;
        }
    }

    private <T> ResponseEntity<T> requestForEntity(String url, HttpMethod method, HttpEntity<?> httpEntity, Class<T> responseType) {
        long startTime = System.currentTimeMillis();
        if (properties.getLog().isNetworkLog()) {
            networkLogger.info("{} -> {}", method.name(), url);
        }

        ResponseEntity<T> response = null;
        try {
            response = restTemplate.exchange(url, method, httpEntity, responseType);
            return response;
        } catch (Exception e) {
            if (properties.getLog().isNetworkLog()) {
                long cost = System.currentTimeMillis() - startTime;
                networkLogger.error("{} <- [{}]({} ms): {}", method.name(), e.getMessage(), cost, url, e);
            }
            throw e;
        } finally {
            if (properties.getLog().isNetworkLog()) {
                long cost = System.currentTimeMillis() - startTime;
                if (response != null) {
                    networkLogger.info("{} <- [{}]({} ms): {}", method.name(), response.getStatusCode().value(), cost, url);
                } else {
                    networkLogger.error("{} <- [无结果]({} ms): {}", method.name(), cost, url);
                }
            }
        }
    }

    /**
     * 同步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public String get(String url) {
        return get(url, new HashMap<>());
    }

    /**
     * 异步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<String> asyncGet(String url) {
        return CompletableFuture.supplyAsync(() -> get(url), executor);
    }

    /**
     * 自定义请求头的同步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public String get(String url, Map<String, String> headers) {
        return getForEntity(url, headers).getBody();
    }

    /**
     * 自定义请求头的同步 HTTP GET 请求，返回含响应头的完整响应
     * <p>
     * 与 {@link #get(String, Map)} 的差别仅在于保留响应头。个别接口把结果放在 Set-Cookie 等
     * 响应头中（例如哔哩哔哩扫码登录的凭据），只读响应体会拿不到。
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 完整响应
     */
    /**
     * 自定义请求头的同步 HTTP GET 请求，URL 以 {@link URI} 传入
     * <p>
     * 传字符串时 RestTemplate 会把它当作 URI 模板再编码一次，已编码好的查询参数会被二次编码
     * （{@code %E5} 变成 {@code %25E5}），接收方解出来就是一串字面的百分号转义。
     * 调用方自行拼好查询串时应改用本方法。
     * @param uri URI
     * @param headers HTTP 请求头
     * @return 请求结果
     */
    public String get(URI uri, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);

        return requestForEntity(uri, HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class).getBody();
    }

    public ResponseEntity<String> getForEntity(String url, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);

        HttpEntity<Void> httpEntity = new HttpEntity<>(httpHeaders);

        return requestForEntity(url, HttpMethod.GET, httpEntity, String.class);
    }

    /**
     * 自定义请求头的异步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<String> asyncGet(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> get(url, headers), executor);
    }

    /**
     * 读取 JSON 的同步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public JSONObject getJson(String url) {
        return JSON.parseObject(get(url));
    }

    /**
     * 读取 JSON 的异步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncGetJson(String url) {
        return CompletableFuture.supplyAsync(() -> getJson(url), executor);
    }

    /**
     * 自定义请求头读取 JSON 的同步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public JSONObject getJson(String url, Map<String, String> headers) {
        return JSON.parseObject(get(url, headers));
    }

    /**
     * 自定义请求头读取 JSON 的异步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncGetJson(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getJson(url, headers), executor);
    }

    /**
     * 读取字节的同步 HTTP GET 请求
     * @param url URL
     * @return 请求结果
     */
    public byte[] getBytes(String url) {
        return getBytes(url, new HashMap<>());
    }

    /**
     * 读取字节的异步 HTTP GET 请求
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<byte[]> asyncGetBytes(String url) {
        return CompletableFuture.supplyAsync(() -> getBytes(url), executor);
    }

    /**
     * 自定义请求头读取字节的同步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 请求结果
     */
    public byte[] getBytes(String url, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);

        HttpEntity<Void> httpEntity = new HttpEntity<>(httpHeaders);

        return request(url, HttpMethod.GET, httpEntity, byte[].class);
    }

    /**
     * 自定义请求头读取字节的异步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<byte[]> asyncGetBytes(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getBytes(url, headers), executor);
    }

    /**
     * 读取图片的同步 HTTP GET 请求
     * @param url URL
     * @return 图片
     */
    public Optional<BufferedImage> getBufferedImage(String url) {
        return getBufferedImage(url, new HashMap<>());
    }

    /**
     * 读取图片的异步 HTTP GET 请求
     * @param url URL
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBufferedImage(String url) {
        return CompletableFuture.supplyAsync(() -> getBufferedImage(url), executor);
    }

    /**
     * 自定义请求头读取图片的同步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 图片
     */
    public Optional<BufferedImage> getBufferedImage(String url, Map<String, String> headers) {
        try {
            byte[] bytes = getBytes(url, headers);

            if (bytes != null) {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return Optional.ofNullable(ImageIO.read(input));
            }
        } catch (Exception e) {
            log.error("从 {} 读取图片异常", url, e);
        }

        return Optional.empty();
    }

    /**
     * 自定义请求头读取图片的异步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBufferedImage(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getBufferedImage(url, headers), executor);
    }

    /**
     * 同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public String post(String url) {
        return post(url, new HashMap<>(), new HashMap<>());
    }

    /**
     * 异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPost(String url) {
        return CompletableFuture.supplyAsync(() -> post(url), executor);
    }

    /**
     * 自定义请求头的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public String postWithHeaders(String url, Map<String, String> headers) {
        return post(url, headers, new HashMap<>());
    }

    /**
     * 自定义请求头的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithHeaders(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postWithHeaders(url, headers), executor);
    }

    /**
     * 自定义请求参数的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String postWithParams(String url, Object params) {
        return post(url, new HashMap<>(), params);
    }

    /**
     * 自定义请求参数的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithParams(String url, Object params) {
        return CompletableFuture.supplyAsync(() -> postWithParams(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String post(String url, Map<String, String> headers, Object params) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> httpEntity = new HttpEntity<>(params, httpHeaders);

        return request(url, HttpMethod.POST, httpEntity, String.class);
    }

    /**
     * 自定义请求头和请求参数的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPost(String url, Map<String, String> headers, Object params) {
        return CompletableFuture.supplyAsync(() -> post(url, headers, params), executor);
    }

    /**
     * 读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public JSONObject postJson(String url) {
        return JSON.parseObject(post(url));
    }

    /**
     * 读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJson(String url) {
        return CompletableFuture.supplyAsync(() -> postJson(url), executor);
    }

    /**
     * 自定义请求头读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public JSONObject postJsonWithHeaders(String url, Map<String, String> headers) {
        return JSON.parseObject(postWithHeaders(url, headers));
    }

    /**
     * 自定义请求头读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithHeaders(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postJsonWithHeaders(url, headers), executor);
    }

    /**
     * 自定义请求参数读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJsonWithParams(String url, Object params) {
        return JSON.parseObject(postWithParams(url, params));
    }

    /**
     * 自定义请求参数读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithParams(String url, Object params) {
        return CompletableFuture.supplyAsync(() -> postJsonWithParams(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJson(String url, Map<String, String> headers, Object params) {
        return JSON.parseObject(post(url, headers, params));
    }

    /**
     * 自定义请求头和请求参数读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJson(String url, Map<String, String> headers, Object params) {
        return CompletableFuture.supplyAsync(() -> postJson(url, headers, params), executor);
    }

    /**
     * 以 form-urlencoded 格式提交的同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public String postAsForm(String url) {
        return postAsForm(url, new HashMap<>(), new HashMap<>());
    }

    /**
     * 以 form-urlencoded 格式提交的异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostAsForm(String url) {
        return CompletableFuture.supplyAsync(() -> postAsForm(url), executor);
    }

    /**
     * 自定义请求头以 form-urlencoded 格式提交的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public String postWithHeadersAsForm(String url, Map<String, String> headers) {
        return postAsForm(url, headers, new HashMap<>());
    }

    /**
     * 自定义请求头以 form-urlencoded 格式提交的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithHeadersAsForm(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postWithHeadersAsForm(url, headers), executor);
    }

    /**
     * 自定义请求参数以 form-urlencoded 格式提交的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String postWithParamsAsForm(String url, Map<String, Object> params) {
        return postAsForm(url, new HashMap<>(), params);
    }

    /**
     * 自定义请求参数以 form-urlencoded 格式提交的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithParamsAsForm(String url, Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> postWithParamsAsForm(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数以 form-urlencoded 格式提交的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String postAsForm(String url, Map<String, String> headers, Map<String, Object> params) {
        return postAsFormForEntity(url, headers, params).getBody();
    }

    /**
     * 自定义请求头和请求参数以 form-urlencoded 格式提交的同步 HTTP POST 请求，返回含响应头的完整响应
     * <p>
     * 与 {@link #postAsForm(String, Map, Map)} 的差别仅在于保留响应头。个别接口把结果放在
     * Set-Cookie 等响应头中，只读响应体会拿不到。
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 完整响应
     */
    public ResponseEntity<String> postAsFormForEntity(String url, Map<String, String> headers, Map<String, Object> params) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> formData.add(key, value.toString()));
        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(formData, httpHeaders);

        return requestForEntity(url, HttpMethod.POST, httpEntity, String.class);
    }

    /**
     * 自定义请求头和请求参数以 form-urlencoded 格式提交的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostAsForm(String url, Map<String, String> headers, Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> postAsForm(url, headers, params), executor);
    }

    /**
     * 以 form-urlencoded 格式提交读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public JSONObject postJsonAsForm(String url) {
        return JSON.parseObject(postAsForm(url));
    }

    /**
     * 以 form-urlencoded 格式提交读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonAsForm(String url) {
        return CompletableFuture.supplyAsync(() -> postJsonAsForm(url), executor);
    }

    /**
     * 自定义请求头以 form-urlencoded 格式提交读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public JSONObject postJsonWithHeadersAsForm(String url, Map<String, String> headers) {
        return JSON.parseObject(postWithHeadersAsForm(url, headers));
    }

    /**
     * 自定义请求头以 form-urlencoded 格式提交读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithHeadersAsForm(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postJsonWithHeadersAsForm(url, headers), executor);
    }

    /**
     * 自定义请求参数以 form-urlencoded 格式提交读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJsonWithParamsAsForm(String url, Map<String, Object> params) {
        return JSON.parseObject(postWithParamsAsForm(url, params));
    }

    /**
     * 自定义请求参数以 form-urlencoded 格式提交读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithParamsAsForm(String url, Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> postJsonWithParamsAsForm(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数以 form-urlencoded 格式提交读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJsonAsForm(String url, Map<String, String> headers, Map<String, Object> params) {
        return JSON.parseObject(postAsForm(url, headers, params));
    }

    /**
     * 自定义请求头和请求参数以 form-urlencoded 格式提交读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonAsForm(String url, Map<String, String> headers, Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> postJsonAsForm(url, headers, params), executor);
    }
}
