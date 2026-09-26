package org.frostnova.nova.report.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.handler.PushHandlerSupport;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.AtMode;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.HandlerPackageNames;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.FixedSizeSetQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 动态推送处理器
 */
@Slf4j
@NovaComponent
public class BilibiliDynamicPushHandler implements NovaEventHandler {
    private final BilibiliApiUtil api;

    private final BilibiliDynamicPainter painter;

    private final NovaMessageSender sender;

    private final AtSubscriptionService subscriptions;

    private final LiveDataService liveDataService;

    private final NovaBilibiliProperties properties;

    private final TimelineWriter timeline;

    /**
     * 已经为它们记过「屏蔽词挡下」的动态
     * <p>
     * 一条动态会展开成每个推送会话一次的调用，而时间线记的是「那条动态没推」——
     * 按动态说的事，不该按会话刷几行。窗口记最近若干条动态，装不下就把最老的忘掉：
     * 要比的只是眼下这几条同时间进来的。
     */
    private final FixedSizeSetQueue<String> blockedRecorded = new FixedSizeSetQueue<>(256);

    @Autowired
    public BilibiliDynamicPushHandler(BilibiliApiUtil api, BilibiliDynamicPainter painter, NovaMessageSender sender,
                                      AtSubscriptionService subscriptions, LiveDataService liveDataService,
                                      NovaBilibiliProperties properties, TimelineWriter timeline) {
        this.api = api;
        this.painter = painter;
        this.sender = sender;
        this.subscriptions = subscriptions;
        this.liveDataService = liveDataService;
        this.properties = properties;
        this.timeline = timeline;
    }

    @Override
    public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
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
     * 依据黑白名单、转发过滤与屏蔽词判断是否需要推送
     * <p>
     * 屏蔽词放在最后。类型名单或「只推转发自己动态的转发」已经跳过的动态，
     * 本来就不会推，不再记成屏蔽词挡下。挡下时在这里顺手记一行日志与一条时间线：
     * 判定与记录同处，「为什么没推」的答案才不会散到别处去。
     * 只有屏蔽词真把一条本来要推的动态挡住，才记这一行。
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

        // 屏蔽词放在类型白名单、黑名单和「只推转发自己动态」之后。
        // 前面几道已经跳过的动态，各会话名单不同，记不记这一行也就不同——
        // 要的就是这样：只有这个词真把推送挡住了，才记「屏蔽词挡下」。
        String blocked = hitBlockedWord(event.getDynamic());
        if (blocked != null) {
            log.info("{} 的动态命中屏蔽词 {}, 跳过推送", event.getSource().getUname(), blocked);
            recordBlockedWord(event, blocked);
            return false;
        }

        return true;
    }

    /**
     * 命中屏蔽词时返回那个词；没配、没命中时返回 {@code null}
     * <p>
     * 比的文字与画图取自同一处：这条动态的正文（desc 与图文 summary 两处都比）和标题
     * （含直播推荐解析出的标题），再加转发动态原文里的同样几处。
     * 转发动态自己那几行是转发评语，内容在原文里。
     * 英文不分大小写；名单里的空行不算词，{@code ""} 在 {@code contains} 里是「处处命中」，
     * 一行空行就能把所有动态都挡掉。
     * @param dynamic 动态
     * @return 命中的词
     */
    private String hitBlockedWord(Dynamic dynamic) {
        List<String> words = properties.getDynamic().getBlockWords();
        if (words == null || words.isEmpty()) {
            return null;
        }

        List<String> texts = new ArrayList<>(dynamic.texts());
        Dynamic origin = dynamic.getOrigin();
        if (origin != null) {
            texts.addAll(origin.texts());
        }

        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            String needle = word.strip().toLowerCase(Locale.ROOT);
            for (String text : texts) {
                if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                    return word.strip();
                }
            }
        }
        return null;
    }

    /**
     * 时间线上记一条「屏蔽词挡下」：主播、命中的词、动态链接
     * <p>
     * 同一条动态推给几个会话也只记一条——有没有推出去是按动态说的，
     * 按会话记的话一次没推能在时间线上刷出十几行。
     * @param event 动态更新事件
     * @param word 命中的词
     */
    private void recordBlockedWord(BilibiliDynamicUpdateEvent event, String word) {
        Dynamic dynamic = event.getDynamic();
        String id = dynamic.getId();
        synchronized (blockedRecorded) {
            if (id != null) {
                if (blockedRecorded.contains(id)) {
                    return;
                }
                blockedRecorded.add(id);
            }
        }

        String uname = event.getSource().getUname();
        String streamer = uname == null || uname.isBlank() ? String.valueOf(event.getSource().getUid()) : uname;
        String url = event.getUrl() == null || event.getUrl().isBlank() ? dynamic.getUrl() : event.getUrl();

        timeline.record(TimelineEvent.of(TimelineEventType.PUSH_BLOCKED_WORD, TimelineEvent.Level.WARN)
                .streamer(streamer)
                .text("命中屏蔽词「" + word + "」，没有推送" + streamer + "的这条动态")
                .detail("word", word)
                .detail("url", url)
                .build());
    }

    @Override
    public Class<? extends NovaExternalBaseEvent> getEventType() {
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
     * 重发纯文字，并在日志里明写降级），见 {@link org.frostnova.nova.core.sender.NovaMessageSender}——
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
     * @see NovaEventHandler#supersededDefaults() 为什么改默认值必须连这张表一起改
     */
    @Override
    public Map<String, List<String>> supersededDefaults() {
        return Map.of("message", List.of(
                "{at}{uname} {action}\n{url}{next}{picture}",
                "{uname} {action}\n{url}{next}{picture}"));
    }

    /**
     * 本类原在哔哩哔哩插件的 handler 包下，随报告插件拆出时搬到了本模块。
     * 使用者的 {@code datasource.json} 与 {@code template-defaults.json} 里存的仍是那一串。
     *
     * @see NovaEventHandler#legacyClassNames() 为什么搬包必须连这张表一起改
     */
    @Override
    public List<String> legacyClassNames() {
        return List.of(HandlerPackageNames.oldBot("bilibili.handler.BilibiliDynamicPushHandler"));
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
