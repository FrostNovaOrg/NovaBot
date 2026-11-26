package org.frostnova.nova.interact.danmaku;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;

import java.util.Map;
import java.util.Optional;

/**
 * 和监听直播间用的同一个账号
 * <p>
 * 请求经监听账号现有的那一个 {@link BilibiliApiUtil} 发出，与监听共用它的签名与风控冷却；
 * 这里不另存、不复制它的凭据，跨站校验口令也由那个接口工具自己填。
 * 只读监听账号的三样状态（免登录与否、登着与否、uid），不改它的登录、续期与失效处理。
 */
final class ListenerSpeakingAccount implements SpeakingAccount {
    private final BilibiliAccountService listener;

    private final BilibiliApiUtil api;

    ListenerSpeakingAccount(BilibiliAccountService listener, BilibiliApiUtil api) {
        this.listener = listener;
        this.api = api;
    }

    @Override
    public DanmakuAccountChoice choice() {
        return DanmakuAccountChoice.LISTENER;
    }

    @Override
    public Optional<Refusal> refusal() {
        if (listener == null || api == null) {
            return Optional.of(new Refusal(DanmakuFailure.NO_ACCOUNT,
                    "选的是监听直播间用的账号，但这台机器上没有可用的监听账号，这条没发"));
        }
        if (listener.isAnonymous()) {
            return Optional.of(new Refusal(DanmakuFailure.NO_ACCOUNT,
                    "选的是监听直播间用的账号，但监听账号开着免登录模式，没有能发弹幕的登录态，这条没发。"
                            + "请改用单独的发言账号，或先关掉免登录模式、给监听账号扫码登录"));
        }
        if (!listener.isLoggedIn()) {
            return Optional.of(new Refusal(DanmakuFailure.ACCOUNT_UNAVAILABLE,
                    "监听账号没有登录或登录已失效，这条没发。请到「连接」页的「哔哩哔哩」卡上重新扫码登录"));
        }
        if (listener.getLoginUid() == null) {
            return Optional.of(new Refusal(DanmakuFailure.ACCOUNT_UNAVAILABLE,
                    "监听账号的登录凭据暂未确认，确认之前不用它发弹幕，这条没发。多半是网络不通，网络恢复后会自动确认"));
        }
        return Optional.empty();
    }

    @Override
    public Long uid() {
        return listener == null ? null : listener.getLoginUid();
    }

    @Override
    public JSONObject get(String url) {
        return api.requestBilibiliApi(url);
    }

    @Override
    public JSONObject postOnce(String url, Map<String, Object> form) {
        return api.postFormOnce(url, form);
    }
}
