package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 开播推送处理器
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveOnPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final StarBotMessageSender sender;

    private final AtSubscriptionService subscriptions;

    @Autowired
    public BilibiliLiveOnPushHandler(BilibiliApiUtil api, StarBotMessageSender sender, AtSubscriptionService subscriptions) {
        this.api = api;
        this.sender = sender;
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOnEvent event = (BilibiliLiveOnEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 短时间内断线重连视为同一场直播，避免重复通知
        if (event.isReconnect()) {
            PushHandlerSupport.send(sender, target, params.getString("reconnect_message"));
            return;
        }

        String uname = PushHandlerSupport.resolveUname(api, event.getSource());

        String title = "";
        String cover = "";
        try {
            Room room = api.getLiveInfoByRoomId(event.getSource().getRoomId());
            title = StringUtil.isBlank(room.getTitle()) ? "" : room.getTitle();
            if (StringUtil.isNotBlank(room.getCover())) {
                cover = "{image_url=" + room.getCover() + "}";
            }
        } catch (Exception e) {
            log.error("获取直播间 {} 的标题与封面失败: {}", event.getSource().getRoomIdString(), e.getMessage());
        }

        String content = params.getString("message")
                .replace("{uname}", uname)
                .replace("{title}", title)
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{cover}", cover)
                .replace("{at}", PushHandlerSupport.atSubscribers(subscriptions.list(
                        target.getPlatform(), target.getNum(), event.getSource().getUid(), "live")));

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withAtAll(params, target, content));
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOnEvent.class;
    }

    /**
     * 默认参数
     * <p>
     * ⚠️ <b>模板里的 {@code {next}} 落在文字与封面之间，那不是排版，是文字的可达性保护。
     * 想把开播合并成一条的人请先读完这一段。</b>
     * <p>
     * <b>2026-08-11 实测</b>（NapCat / OneBot 11，专用测试群，三条真发）：
     * 一条消息里文字与图片段混排时，<b>图片下载失败会让整条 {@code send_group_msg} 失败</b>，
     * 而不是只丢图片段。封面地址 404 返回「下载文件失败: Not Found」、主机不存在返回
     * 「getaddrinfo ENOTFOUND」，两种情形<b>消息 id 均为空，文字一起没发出去</b>。
     * 正例（真封面）合并成一条正常送达并渲染，所以原因不是「混排本身不行」。
     * <p>
     * 分成两条时封面拉不到只丢那一条图，文字照常送达。
     * {@code BilibiliDynamicPushHandler} 的 {@code {url}{next}{picture}} 同理，
     * 且那边 2026-08-02 真丢过一次图，文字正是靠分条才幸存。
     * <p>
     * ⚠️ <b>封面地址会不会变坏、多久变坏一次，没有实测。</b>这里<b>不</b>声称它「会过期」——
     * 那句话写过一次，查完发现反证：URL 是纯内容寻址路径
     * （{@code /bfs/live/new_room_cover/<40 位哈希>.jpg}），<b>没有签名与有效期参数</b>，
     * 而 8/9~8/10 抓到的三个同族 CDN 地址两天后仍然 HTTP 200。
     * 可信的机制是<b>主播更换封面后旧图被清理</b>或<b>推送那一刻 CDN 恰好取不到</b>，
     * 两者都只是推断。**已实测的只有「取不到时整条发不出去」这一件**，
     * 而这一件已经足够支撑本段的结论——兜底该做的理由不依赖失效频率。
     * <p>
     * <b>但这只是默认值，模板是使用者可以自己改的</b>——注释拦不住配置行为。
     * 真正的解法在程序层面：含图消息发送失败时剥掉图片段重发一次纯文字，
     * 并在日志里明写降级发生了（静默降级是看不见的谎言）。
     * <b>要求是「推送文字的可达性不得依赖图片的可取性，任何模板写法下都必须成立」。</b>
     * 兜底落地之后这一段的限制取消；在那之前别删这个 {@code {next}}。
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{at}{uname} 正在直播 {title}\n{url}{next}{cover}");
        params.put("reconnect_message", "检测到下播后短时间内重新开播,本次开播不再重复通知");
        return params;
    }

    @Override
    public String displayName() {
        return "开播通知";
    }

    @Override
    public String description() {
        return "主播开始直播时推送";
    }

    @Override
    public String platform() {
        return LivePlatform.BILIBILI.getName();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{title}", "{cover}", "{url}", "{at}", "{next}", "{at=all}");
    }
}
