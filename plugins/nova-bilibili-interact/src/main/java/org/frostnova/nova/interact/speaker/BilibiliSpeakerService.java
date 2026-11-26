package org.frostnova.nova.interact.speaker;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
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
     * 发言账号用不了的那几种情形
     */
    public enum UnusableKind {
        /**
         * 从没登录过（或已退出登录）
         */
        NEVER_LOGGED_IN,

        /**
         * 整个停用了（凭据文件与监听账号的是同一个）
         */
        DISABLED,

        /**
         * 与监听账号成了同一个号
         */
        SAME_AS_LISTENER,

        /**
         * 登录过，凭据已失效
         */
        EXPIRED,

        /**
         * 登着，但凭据暂未确认
         */
        UNVERIFIED
    }

    /**
     * 发言账号此刻为什么用不了
     * @param kind 哪一种情形
     * @param reason 说给使用者的理由，与 {@link #unavailableReason()} 同一句
     */
    public record Unusable(UnusableKind kind, String reason) {
    }

    /**
     * 发言账号此刻为什么用不了，分清是哪一种：前三种是「没设好」，后两种是「登录态不行」
     * @return 用不了的情形，能用时为空
     */
    public Optional<Unusable> unusable() {
        Optional<String> reason = speaker.unusableReason();
        if (reason.isEmpty()) {
            return Optional.empty();
        }
        UnusableKind kind;
        if (speaker.disabledReason().isPresent()) {
            kind = UnusableKind.DISABLED;
        } else if (!speaker.isLoggedIn()) {
            kind = speaker.hasCredentials() ? UnusableKind.EXPIRED : UnusableKind.NEVER_LOGGED_IN;
        } else if (speaker.uid() == null) {
            kind = UnusableKind.UNVERIFIED;
        } else {
            kind = UnusableKind.SAME_AS_LISTENER;
        }
        return Optional.of(new Unusable(kind, reason.get()));
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

    /**
     * 以发言账号的登录态发一次表单 POST：只发一次，回包整份交回
     * <p>
     * 给发出去就收不回的写接口用（往直播间发弹幕）。网络异常与业务码都不重发；
     * 跨站校验口令由发言账号自己的接口工具填，调用方拿不到它的值。见 {@link BilibiliApiUtil#postFormOnce}。
     * @param url 接口地址
     * @param form 表单参数，可为 null
     * @return 完整回包
     * @throws IllegalStateException 发言账号用不了时，一个请求都不发
     */
    public JSONObject postOnce(String url, Map<String, Object> form) {
        requireAvailable();
        return speaker.api().postFormOnce(url, form);
    }

    private void requireAvailable() {
        Optional<String> reason = speaker.unusableReason();
        if (reason.isPresent()) {
            throw new IllegalStateException(reason.get());
        }
    }
}
