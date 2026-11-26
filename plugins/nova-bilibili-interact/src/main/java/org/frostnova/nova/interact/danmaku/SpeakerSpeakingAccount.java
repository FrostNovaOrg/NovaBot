package org.frostnova.nova.interact.danmaku;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerAccount;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;

import java.util.Map;
import java.util.Optional;

/**
 * 单独的发言账号
 * <p>
 * 请求全走 {@link BilibiliSpeakerService}：它只用发言账号自己的接口工具，用不了就不发，也从不换监听账号的凭据发。
 */
final class SpeakerSpeakingAccount implements SpeakingAccount {
    /**
     * 去哪儿登录
     */
    static final String WHERE_TO_LOGIN = "请到「连接」页的「" + BilibiliSpeakerAccount.DISPLAY_NAME + "」卡上扫码登录";

    private final BilibiliSpeakerService speaker;

    SpeakerSpeakingAccount(BilibiliSpeakerService speaker) {
        this.speaker = speaker;
    }

    @Override
    public DanmakuAccountChoice choice() {
        return DanmakuAccountChoice.SPEAKER;
    }

    @Override
    public Optional<Refusal> refusal() {
        return speaker.unusable().map(unusable -> switch (unusable.kind()) {
            case NEVER_LOGGED_IN -> new Refusal(DanmakuFailure.NO_ACCOUNT,
                    "选的是单独的发言账号，但还没有登录发言账号，这条没发。" + WHERE_TO_LOGIN);
            case DISABLED, SAME_AS_LISTENER -> new Refusal(DanmakuFailure.NO_ACCOUNT,
                    "选的是单独的发言账号，但它此刻用不了，这条没发：" + unusable.reason());
            case EXPIRED -> new Refusal(DanmakuFailure.ACCOUNT_UNAVAILABLE,
                    "发言账号的登录已失效，这条没发。" + WHERE_TO_LOGIN);
            case UNVERIFIED -> new Refusal(DanmakuFailure.ACCOUNT_UNAVAILABLE,
                    "发言账号的登录凭据暂未确认，确认之前不用它发弹幕，这条没发。多半是网络不通，网络恢复后会自动确认");
        });
    }

    @Override
    public Long uid() {
        return speaker.uid().orElse(null);
    }

    @Override
    public JSONObject get(String url) {
        return speaker.get(url, null);
    }

    @Override
    public JSONObject postOnce(String url, Map<String, Object> form) {
        return speaker.postOnce(url, form);
    }
}
