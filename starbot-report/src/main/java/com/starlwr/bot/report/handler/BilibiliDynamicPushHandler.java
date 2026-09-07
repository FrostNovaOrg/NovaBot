package com.starlwr.bot.report.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.handler.PushHandlerSupport;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.report.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态推送处理器
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final BilibiliDynamicPainter painter;

    private final StarBotMessageSender sender;

    private final AtSubscriptionService subscriptions;

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliDynamicPushHandler(BilibiliApiUtil api, BilibiliDynamicPainter painter, StarBotMessageSender sender,
                                      AtSubscriptionService subscriptions, LiveDataService liveDataService) {
        this.api = api;
        this.painter = painter;
        this.sender = sender;
        this.subscriptions = subscriptions;
        this.liveDataService = liveDataService;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliDynamicUpdateEvent event = (BilibiliDynamicUpdateEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        if (!shouldPush(event, params)) {
            return;
        }

        String picture = painter.paint(event.getDynamic())
                .map(base64 -> "{image_base64=" + base64 + "}")
                .orElse("");

        AtMode mode = AtMode.of(params);
        String template = params.getString("message");
        String subscriberAt = PushHandlerSupport.atSubscribers(subscriptions.list(
                target.getPlatform(), target.getNum(), event.getSource().getUid(), "dynamic"));

        String content = template
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{action}", Optional.ofNullable(event.getAction()).orElse("发布了动态"))
                .replace("{url}", Optional.ofNullable(event.getUrl()).orElse(""))
                .replace("{picture}", picture)
                .replace("{at}", subscriberAt);

        // 动态图没送到时与开播封面记在同一项上：报告里那一行说的是「本场有几条推送的图片没送达」，
        // 使用者要知道的是「有没有图丢了」，而不是「丢的是封面还是动态图」。
        // 🔴 分开记会让那一行只数得到其中一路，而一个少数了一半的 N，
        //    和一个数对了的 N，在报告上长得一样。
        //
        // ℹ️ 不在直播中时这一笔会落进一个没人读的本场桶，下次开播清零时随之丢掉——
        // 那是对的：它本来就不属于任何一场。
        Long uid = event.getSource().getUid();
        Runnable onImageDegraded = uid == null ? null : () -> liveDataService.incrementLiveMetric(
                event.getPlatform(), uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT, 1);

        PushHandlerSupport.send(sender, target,
                PushHandlerSupport.withAtBlock(mode, target, template, content, subscriberAt), onImageDegraded,
                PushHandlerSupport.atAllFallback(mode, target, template, subscriberAt));
    }

    /**
     * 依据黑白名单与转发过滤判断是否需要推送
     * @param event 动态更新事件
     * @param params 推送参数
     * @return 是否需要推送
     */
    private boolean shouldPush(BilibiliDynamicUpdateEvent event, JSONObject params) {
        String type = event.getDynamic().getType();

        JSONArray whiteList = params.getJSONArray("white_list");
        if (whiteList != null && !whiteList.isEmpty()) {
            if (!whiteList.contains(type)) {
                log.info("{} 的动态类型 {} 不在白名单中, 跳过推送", event.getSource().getUname(), type);
                return false;
            }
        } else {
            JSONArray blackList = params.getJSONArray("black_list");
            if (blackList != null && blackList.contains(type)) {
                log.info("{} 的动态类型 {} 在黑名单中, 跳过推送", event.getSource().getUname(), type);
                return false;
            }
        }

        // 仅推送转发自己动态的转发
        if (event.getDynamic().isForward() && params.getBooleanValue("only_self_origin")) {
            Long originUid = Optional.ofNullable(event.getDynamic().getOrigin())
                    .flatMap(origin -> origin.getAuthorUid())
                    .orElse(null);

            if (originUid == null || !originUid.equals(event.getSource().getUid())) {
                log.info("{} 转发的动态并非转发自己的动态, 跳过推送", event.getSource().getUname());
                return false;
            }
        }

        return true;
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliDynamicUpdateEvent.class;
    }

    /**
     * 默认参数
     * <p>
     * ℹ️ <b>默认模板里没有 {@code {at}}，「@ 谁」改由 {@code at_mode} 决定</b>，
     * 与开播通知同一套，理由见 {@link AtMode}；使用者改过的模板一个字不动。
     * <p>
     * ℹ️ <b>默认模板不再有 {@code {next}}，文字与动态图合成一条。</b>那个分条曾经是
     * 文字的可达性保护：<b>2026-08-02 真丢过一次动态图</b>，图片重试三次后整条放弃，
     * 文字因为分了条才幸存。可达性现在由发送侧兜底保证（含图消息发送失败时剥掉图片段
     * 重发纯文字，并在日志里明写降级），见 {@link com.starlwr.bot.core.sender.StarBotMessageSender}——
     * <b>推送文字的可达性不得依赖图片的可取性，任何模板写法下都必须成立。</b>
     * <p>
     * ⚠️ <b>别把动态的「组装失败」也算在兜底头上</b>——那一种本来就安全：
     * 渲染不出图时占位符是空串，转换器按 {@code isNotBlank} 跳过该段，文字照发。
     * <b>兜底管的是「组装成功但发送失败」那一种</b>（如 payload 过大），也就是 08-02 丢的那种。
     * 与开播那边的失败点也不同：开播的封面是把 URL 交给 OneBot 实现去下载，
     * 动态图是<b>本端渲染后按 base64 组装</b>，失败发生在我们这一侧，<b>两者不能互相推断</b>。
     */
    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("message", DEFAULT_MESSAGE);
        params.put("white_list", List.of());
        params.put("black_list", List.of());
        params.put("only_self_origin", false);
        return params;
    }

    /**
     * 当前的默认消息模板
     */
    private static final String DEFAULT_MESSAGE = "{uname} {action}\n{url}{picture}";

    /**
     * 历史上发过的两版默认模板：{@code {at}} 还写在模板里的那一版，与去掉它之后分两条的那一版
     *
     * @see StarBotEventHandler#supersededDefaults() 为什么改默认值必须连这张表一起改
     */
    @Override
    public Map<String, List<String>> supersededDefaults() {
        return Map.of("message", List.of(
                "{at}{uname} {action}\n{url}{next}{picture}",
                "{uname} {action}\n{url}{next}{picture}"));
    }

    @Override
    public String displayName() {
        return "动态通知";
    }

    @Override
    public String description() {
        return "主播发布新动态时推送";
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{action}", "{url}", "{picture}", "{at}", "{next}", "{at=all}");
    }

    /**
     * 动态图展开成一段图片占位符，在模板编辑器里是独占一行的附件块
     */
    @Override
    public List<String> attachmentPlaceholders() {
        return List.of("{picture}");
    }
}
