package org.frostnova.nova.interact.speaker;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用发言账号做事的入口
 * <p>
 * 本插件里要以发言账号身份向哔哩哔哩发请求的功能都走这里，答两件事：
 * 发言账号现在能不能用，以及用它的登录态把请求发出去。
 * <p>
 * <b>用不了就不发，也不换一个号发。</b>这里从不借用监听账号的凭据：发言账号没登录、凭据失效、
 * 或与监听账号成了同一个号时，请求直接拒掉，理由原样交给调用方——风险隔离的全部意义在这一条。
 * <p>
 * 凭据只在这一层里被读出来填进请求，不进日志、不进返回值、不进异常文本。
 */
@NovaComponent
public class BilibiliSpeakerService {
    private final BilibiliSpeakerAccount speaker;

    @Autowired
    public BilibiliSpeakerService(BilibiliSpeakerAccount speaker) {
        this.speaker = speaker;
    }

    /**
     * 发言账号现在能不能用
     * @return 能用时返回 true
     */
    public boolean available() {
        return speaker.unusableReason().isEmpty();
    }

    /**
     * 发言账号此刻为什么用不了
     * @return 理由，能用时为空
     */
    public Optional<String> unavailableReason() {
        return speaker.unusableReason();
    }

    /**
     * 发言账号的 uid
     * @return uid，用不了时为空
     */
    public Optional<Long> uid() {
        return available() ? Optional.ofNullable(speaker.uid()) : Optional.empty();
    }

    /**
     * 以发言账号的登录态发一个 GET
     * @param url 接口地址
     * @param params 查询参数，非空时附 WBI 签名，可为 null
     * @return 响应里的 data
     * @throws IllegalStateException 发言账号用不了时
     */
    public JSONObject get(String url, Map<String, Object> params) {
        requireAvailable();
        return speaker.api().requestBilibiliApi(url, "GET", speaker.api().getBilibiliHeaders(), params);
    }

    /**
     * 以发言账号的登录态发一个表单 POST
     * <p>
     * 跨站校验口令（csrf 与 csrf_token 两个参数）由这里填上，调用方不必、也拿不到它的值。
     * @param url 接口地址
     * @param form 表单参数，可为 null
     * @return 响应里的 data
     * @throws IllegalStateException 发言账号用不了时
     */
    public JSONObject post(String url, Map<String, Object> form) {
        requireAvailable();
        Map<String, Object> body = form == null ? new LinkedHashMap<>() : new LinkedHashMap<>(form);
        String csrf = speaker.csrf();
        body.put("csrf", csrf);
        body.put("csrf_token", csrf);
        return speaker.api().requestBilibiliApi(url, "POST", speaker.api().getBilibiliHeaders(), body);
    }

    private void requireAvailable() {
        Optional<String> reason = speaker.unusableReason();
        if (reason.isPresent()) {
            throw new IllegalStateException(reason.get());
        }
    }
}
