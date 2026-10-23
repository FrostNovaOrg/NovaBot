package org.frostnova.nova.report.handler;

import org.frostnova.nova.core.model.MessagePlaceholders;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.handler.PushHandlerSupport;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.HandlerPackageNames;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.report.painter.BilibiliOfflineRewardDigestPainter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 下播打赏播报处理器
 * <p>
 * 主播不在播时有人上舰、或送礼够多，攒一小阵后画成一张与下播报告同一套模板的
 * 播报图发出去，图里逐人写清谁上了什么舰、送了哪些礼物各几个。
 * 金额写不写跟随会话的「金额可见」：同一份内容推给主播私聊与推给大群，
 * 该说的事相同，该不该带金额相反——两张图各画各的。
 */
@Slf4j
@NovaComponent
public class BilibiliOfflineRewardDigestPushHandler implements NovaEventHandler {
    /**
     * 当前的默认消息模板：只放一张图，不再发文字感谢
     */
    private static final String DEFAULT_MESSAGE = "{picture}";

    private static final HandlerOption THRESHOLD = HandlerOption.integer("reward_threshold_yuan", "播报门槛（元）",
            "一个人这一阵的礼物合计达到这个数才播，0 表示有打赏就播；下播前 10 秒内的提醒也按它，上舰不受门槛限制。",
            50, 0, 1_000_000);

    private static final int REMINDER_LIMIT = 20;

    private final BilibiliApiUtil api;

    private final NovaMessageSender sender;

    private final RevenueVisibilityService revenueVisibility;

    private final BilibiliOfflineRewardDigestPainter painter;

    private final StreamerNames names;

    public BilibiliOfflineRewardDigestPushHandler(BilibiliApiUtil api, NovaMessageSender sender,
                                                  RevenueVisibilityService revenueVisibility,
                                                  BilibiliOfflineRewardDigestPainter painter) {
        this(api, sender, revenueVisibility, painter, StreamerNames.none());
    }

    /**
     * @param names 起动时没查到昵称时，从最近一场归档里取主播名
     */
    @Autowired
    public BilibiliOfflineRewardDigestPushHandler(BilibiliApiUtil api, NovaMessageSender sender,
                                                  RevenueVisibilityService revenueVisibility,
                                                  BilibiliOfflineRewardDigestPainter painter,
                                                  StreamerNames names) {
        this.api = api;
        this.sender = sender;
        this.revenueVisibility = revenueVisibility;
        this.painter = painter;
        this.names = names;
    }

    @Override
    public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
        JSONObject params = pushMessage.getParamsJsonObject();
        BilibiliOfflineRewardDigestEvent event = forTarget((BilibiliOfflineRewardDigestEvent) baseEvent, params);
        if (event.getContributions().isEmpty()) {
            return;
        }
        PushTarget target = pushMessage.getTarget();

        // 版式来自推送配置，金额可见性来自会话：前者是「说哪些事」，后者是「该不该带金额」。
        // 同一份内容推给主播私聊和推给大群，该说的事相同，该不该带金额则相反
        boolean showRevenue = revenueVisibility.isVisible(target);

        // 画图失败时改发文字版，而不是什么都不发——与下播报告同一条退路：
        // 这一阵谁送了什么不能跟着图片一起消失
        Optional<String> image = painter.paint(event, showRevenue);
        String picture = image
                .map(base64 -> "{image_base64=" + base64 + "}")
                .orElseGet(() -> {
                    log.warn("{} 的打赏播报图片绘制失败, 改发文字版", event.getSource().getUname());
                    return MessagePlaceholders.escapeExternalText(painter.textDigest(event, showRevenue));
                });

        String template = params.getString("message");
        String content = PushHandlerSupport.replaceOrDropClause(template, "{list}",
                        MessagePlaceholders.escapeExternalText(painter.renderList(event, showRevenue)))
                .replace("{uname}", PushHandlerSupport.resolveUname(api, names, event.getPlatform(), event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{picture}", picture);

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withoutAtBlocks(content));
    }

    private BilibiliOfflineRewardDigestEvent forTarget(BilibiliOfflineRewardDigestEvent original, JSONObject params) {
        int threshold = (int) THRESHOLD.defaultValue();
        try {
            Integer stored = params.getInteger(THRESHOLD.key());
            if (stored != null) {
                threshold = Math.max(THRESHOLD.min(), Math.min(THRESHOLD.max(), stored));
            }
        } catch (RuntimeException e) {
            log.warn("打赏播报门槛无效，使用默认值 {}", threshold);
        }
        long thresholdFen = threshold * 100L;
        List<BilibiliOfflineRewardDigestEvent.Contribution> people = new ArrayList<>(original.getContributions().stream()
                .filter(person -> person.getGuardLevel() != null || (person.getGifts() != null && !person.getGifts().isEmpty()
                        && person.getGiftAmountFen() != null && person.getGiftAmountFen() >= thresholdFen))
                .toList());
        var event = new BilibiliOfflineRewardDigestEvent(original.getSource(), people,
                Instant.ofEpochMilli(original.getTimestamp()));
        event.setClosingReminder(original.isClosingReminder());
        if (original.isClosingReminder()) {
            people.sort(Comparator.comparingLong(BilibiliOfflineRewardDigestPushHandler::totalFen).reversed());
            if (people.size() > REMINDER_LIMIT) {
                event.setOmittedCount(people.size() - REMINDER_LIMIT);
                event.setOmittedAmountFen(people.subList(REMINDER_LIMIT, people.size()).stream()
                        .mapToLong(BilibiliOfflineRewardDigestPushHandler::totalFen).sum());
                event.setContributions(List.copyOf(people.subList(0, REMINDER_LIMIT)));
            }
        }
        return event;
    }

    private static long totalFen(BilibiliOfflineRewardDigestEvent.Contribution person) {
        return (person.getGiftAmountFen() == null ? 0 : person.getGiftAmountFen())
                + (person.getGuardAmountFen() == null ? 0 : person.getGuardAmountFen());
    }

    @Override
    public List<HandlerOption> options() {
        return List.of(THRESHOLD);
    }

    @Override
    public Class<? extends NovaExternalBaseEvent> getEventType() {
        return BilibiliOfflineRewardDigestEvent.class;
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", DEFAULT_MESSAGE);
        params.put(THRESHOLD.key(), THRESHOLD.defaultValue());
        return params;
    }

    /**
     * 历史上发过的默认模板：文字感谢的那一版（v5.7.3 及之前）
     *
     * @see NovaEventHandler#supersededDefaults() 为什么改默认值必须连这张表一起改
     */
    @Override
    public Map<String, List<String>> supersededDefaults() {
        return Map.of("message", List.of("感谢 {list}，{uname} 都收到啦"));
    }

    /**
     * 本类原在哔哩哔哩插件的 handler 包下，为取用报告插件的画图件搬到了本模块。
     * 使用者的 {@code datasource.json} 与 {@code template-defaults.json} 里存的仍是那一串。
     *
     * @see NovaEventHandler#legacyClassNames() 为什么搬包必须连这张表一起改
     */
    @Override
    public List<String> legacyClassNames() {
        return List.of("org.frostnova.nova.bilibili.handler.BilibiliOfflineRewardDigestPushHandler");
    }

    @Override
    public String displayName() {
        return "下播打赏播报";
    }

    @Override
    public int displayOrder() {
        return 40;
    }

    @Override
    public String description() {
        return "主播不在播时攒批播报打赏，下播后另行提醒下播前 10 秒内的打赏";
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public Map<String, String> placeholderLabels() {
        return Map.of(
                "{uname}", "主播昵称",
                "{list}", "逐人打赏明细",
                "{url}", "直播间链接",
                "{picture}", "打赏播报图",
                "{next}", "另起一条消息");
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{list}", "{url}", "{picture}", "{next}");
    }

    /**
     * 播报图展开成一段图片占位符，在模板编辑器里是独占一行的附件块
     */
    @Override
    public List<String> attachmentPlaceholders() {
        return List.of("{picture}");
    }
}
