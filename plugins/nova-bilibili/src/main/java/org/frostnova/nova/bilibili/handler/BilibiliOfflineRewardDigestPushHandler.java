package org.frostnova.nova.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.AtMode;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 下播打赏播报处理器
 * <p>
 * 主播不在播时有人上舰、或送礼够多，攒一小阵后在这里合成一条消息发出去。
 * 金额写不写跟随会话的「金额可见」：同一份内容推给主播私聊与推给大群，该说的事相同，该不该带金额相反。
 */
@Slf4j
@NovaComponent
public class BilibiliOfflineRewardDigestPushHandler implements NovaEventHandler {
    /**
     * 当前的默认消息模板。不提「下播」：这类打赏常在下播几小时后才来，
     * 一阵没攒完主播又开播了也不该把话说反
     */
    private static final String DEFAULT_MESSAGE = "感谢 {list}，{uname} 都收到啦";

    private final BilibiliApiUtil api;

    private final NovaMessageSender sender;

    private final RevenueVisibilityService revenueVisibility;

    @Autowired
    public BilibiliOfflineRewardDigestPushHandler(BilibiliApiUtil api, NovaMessageSender sender,
                                                  RevenueVisibilityService revenueVisibility) {
        this.api = api;
        this.sender = sender;
        this.revenueVisibility = revenueVisibility;
    }

    @Override
    public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliOfflineRewardDigestEvent event = (BilibiliOfflineRewardDigestEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 版式来自推送配置，金额可见性来自会话：前者是「说哪些事」，后者是「该不该带金额」。
        // 同一份内容推给主播私聊和推给大群，该说的事相同，该不该带金额则相反
        boolean showRevenue = revenueVisibility.isVisible(target.getPlatform(), target.getType(), target.getNum());

        String template = params.getString("message");
        String content = PushHandlerSupport.replaceOrDropClause(template, "{list}",
                        renderList(event.getContributions(), showRevenue))
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId());

        // 与下播通知同理：这类播报没有订阅名单这回事，订阅串给空串
        PushHandlerSupport.send(sender, target,
                PushHandlerSupport.withAtBlock(AtMode.of(params), target, template, content, ""));
    }

    /**
     * 把够格的人拼成一句感谢
     * <p>
     * 一人一段，段内先写上舰、再写礼物；段与段之间用顿号。
     * 金额可见性只影响括号里的数字，谁做了什么两边都写。
     */
    private String renderList(List<BilibiliOfflineRewardDigestEvent.Contribution> people, boolean showRevenue) {
        return people.stream()
                .map(person -> renderPerson(person, showRevenue))
                .collect(Collectors.joining("、"));
    }

    private String renderPerson(BilibiliOfflineRewardDigestEvent.Contribution person, boolean showRevenue) {
        String name = StringUtil.isBlank(person.getUname()) ? String.valueOf(person.getUid()) : person.getUname();
        List<String> parts = new ArrayList<>();

        if (person.getGuardLevel() != null) {
            String text = guardVerb(person.getOperateType()) + guardLevelName(person.getGuardLevel());
            if (showRevenue && person.getGuardAmountFen() != null) {
                text += "（¥" + yuan(person.getGuardAmountFen()) + "）";
            }
            parts.add(text);
        }

        List<BilibiliOfflineRewardDigestEvent.GiftLine> gifts = person.getGifts();
        if (gifts != null && !gifts.isEmpty()) {
            String text = "送了 " + gifts.stream()
                    .map(line -> line.name() + "×" + line.count())
                    .collect(Collectors.joining("、"));
            if (showRevenue && person.getGiftAmountFen() != null) {
                text += "（合计 ¥" + yuan(person.getGiftAmountFen()) + "）";
            }
            parts.add(text);
        }

        return name + " " + String.join("，", parts);
    }

    private static String guardLevelName(int level) {
        return switch (level) {
            case 1 -> "总督";
            case 2 -> "提督";
            case 3 -> "舰长";
            default -> "大航海";
        };
    }

    private static String guardVerb(GuardOperateType operateType) {
        return switch (operateType == null ? GuardOperateType.UNKNOWN : operateType) {
            case ACTIVATION -> "开通了";
            case RENEWAL -> "续费了";
            case UNKNOWN -> "上了";
        };
    }

    /**
     * 金额写法与下播报告一致：保留一位小数，整数不带小数点。
     * 事件里金额按「分」整数记账，显示这一刻才换回元
     */
    private static String yuan(long amountFen) {
        long rounded = Math.round(amountFen / 10.0);
        if (rounded % 10 == 0) {
            return String.valueOf(rounded / 10);
        }
        return String.valueOf(rounded / 10.0);
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
        return params;
    }

    @Override
    public String displayName() {
        return "下播打赏播报";
    }

    @Override
    public String description() {
        return "主播不在播时有人上舰或送礼够多，攒一小阵后播报一条";
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{list}", "{url}", "{next}", "{at=all}");
    }
}
