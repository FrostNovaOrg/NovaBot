package org.frostnova.nova.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.AtMode;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * 开播推送处理器
 */
@Slf4j
@NovaComponent
public class BilibiliLiveOnPushHandler implements NovaEventHandler {
    private final BilibiliApiUtil api;

    private final NovaMessageSender sender;

    private final AtSubscriptionService subscriptions;

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveOnPushHandler(BilibiliApiUtil api, NovaMessageSender sender,
                                     AtSubscriptionService subscriptions, LiveDataService liveDataService) {
        this.api = api;
        this.sender = sender;
        this.subscriptions = subscriptions;
        this.liveDataService = liveDataService;
    }

    @Override
    public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
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
    public Class<? extends NovaExternalBaseEvent> getEventType() {
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
     * ℹ️ <b>默认模板不再有 {@code {next}}，文字与封面合成一条。</b>那个分条曾经是
     * 文字的可达性保护：<b>2026-08-11 实测</b>（NapCat / OneBot 11，专用测试群，三条真发）
     * 一条消息里图片下载失败会让<b>整条发送失败</b>而不是只丢图片段，
     * 两次失败响应的消息 id 均为空、文字一起没发出去；分成两条时文字才幸存。
     * <p>
     * <b>那个理由现在不成立了</b>：含图消息发送失败时发送侧会剥掉图片段重发一次纯文字
     * （{@link org.frostnova.nova.core.sender.NovaMessageSender} 的兜底），并在日志里明写降级。
     * 保护落到了程序层面，也就<b>不必再靠一个使用者随手就能改掉的默认值来维持</b>。
     * 剩下的差别只是观感：合并后封面坏掉时收到的是一条没有图的通知——
     * 而两条通知在群里是两次提示音，这一头的代价每次开播都在付。
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("message", DEFAULT_MESSAGE);
        params.put("reconnect_message", "检测到下播后短时间内重新开播,本次开播不再重复通知");
        return params;
    }

    /**
     * 当前的默认消息模板
     */
    private static final String DEFAULT_MESSAGE = "{uname} 正在直播 {title}\n{url}{cover}";

    /**
     * 历史上发过的两版默认模板：{@code {at}} 还写在模板里的那一版，与去掉它之后分两条的那一版
     * <p>
     * 存着其中一串的推送等于「使用者从没改过这个模板」，随新默认走；差一个字符都算改过。
     *
     * @see NovaEventHandler#supersededDefaults() 为什么改默认值必须连这张表一起改
     */
    @Override
    public Map<String, List<String>> supersededDefaults() {
        return Map.of("message", List.of(
                "{at}{uname} 正在直播 {title}\n{url}{next}{cover}",
                "{uname} 正在直播 {title}\n{url}{next}{cover}"));
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

    /**
     * 封面展开成一段图片占位符，在模板编辑器里是独占一行的附件块
     */
    @Override
    public List<String> attachmentPlaceholders() {
        return List.of("{cover}");
    }
}
