package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
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

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveOnPushHandler(BilibiliApiUtil api, StarBotMessageSender sender,
                                     AtSubscriptionService subscriptions, LiveDataService liveDataService) {
        this.api = api;
        this.sender = sender;
        this.subscriptions = subscriptions;
        this.liveDataService = liveDataService;
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

        AtMode mode = AtMode.of(params);
        String template = params.getString("message");
        String subscriberAt = PushHandlerSupport.atSubscribers(subscriptions.list(
                target.getPlatform(), target.getNum(), event.getSource().getUid(), "live"));

        String content = template
                .replace("{uname}", uname)
                .replace("{title}", title)
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{cover}", cover)
                .replace("{at}", subscriberAt);

        // 封面没送到时在本场记一笔，下播报告据此注明——日志里那行 WARN 只有运维看得见，
        // 而「今天的开播图怎么没了」是主播能感知的事
        Long uid = event.getSource().getUid();
        Runnable onImageDegraded = uid == null ? null : () -> liveDataService.incrementLiveMetric(
                event.getPlatform(), uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT, 1);

        PushHandlerSupport.send(sender, target,
                PushHandlerSupport.withAtBlock(mode, target, template, content, subscriberAt), onImageDegraded,
                PushHandlerSupport.atAllFallback(mode, target, template, subscriberAt));
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOnEvent.class;
    }

    /**
     * 默认参数
     * <p>
     * ℹ️ <b>默认模板里没有 {@code {at}}，「@ 谁」改由 {@code at_mode} 决定</b>
     * （{@link AtMode}，三档：只 @ 订阅的人／@全体成员／@全体成员不行就 @ 订阅的人）。
     * 键<b>不写进这里</b>：没写过它就等于没在界面上选过，此时才回头认旧的 {@code at_all}——
     * 详见 {@link AtMode} 里那段判定顺序。使用者自己改过的模板一个字都不动，
     * 里面的 {@code {at}} 与 {@code {at=all}} 照旧生效。
     * <p>
     * ℹ️ <b>模板里的 {@code {next}} 落在文字与封面之间。曾经那是文字的可达性保护，
     * 现在不是了</b>——可达性已由发送侧兜底保证（见本段末尾），<b>模板可以自由合并</b>。
     * 下面这段实测记录保留，因为它解释了这个默认值为什么长这样，也是「混排会整条失败」的机构记忆。
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
     * 所以解法放在程序层面而不是这段注释里：
     * <b>含图消息发送失败时，发送侧会剥掉图片段重发一次纯文字</b>
     * （{@link com.starlwr.bot.core.sender.StarBotMessageSender} 的兜底），
     * 并在日志里明写降级发生了——静默降级是看不见的谎言。
     * <b>要求「推送文字的可达性不得依赖图片的可取性，任何模板写法下都必须成立」已经兑现，
     * 删掉这个 {@code {next}} 不再有丢文字的风险。</b>
     * <p>
     * 默认值仍然分两条，理由变成了观感与兼容而不是健壮性：合并之后封面坏掉时
     * 观众看到的是一条没有图的通知，与「文字一条 + 图那条没来」观感不同。
     * 那是产品口味问题，<b>要不要顺势合并单独裁，不由一个健壮性修复顺手改掉</b>。
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("message", "{uname} 正在直播 {title}\n{url}{next}{cover}");
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
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{title}", "{cover}", "{url}", "{at}", "{next}", "{at=all}");
    }
}
