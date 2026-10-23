package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.handler.WeeklyReportPushHandler;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.PushTarget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 周报通道版式；预览和发送使用同一份默认值与顺序解析。 */
public record WeeklyReportLayout(Map<String, Boolean> flags, List<String> cardOrder) {
    public static final List<String> CARD_KEYS = List.of("card_duration", "card_revenue", "card_messages",
            "card_audience", "card_interaction_audience", "card_fans");
    private static final HandlerOption ORDER = HandlerOption.order("card_order", "卡片顺序", "拖动调整卡片顺序", CARD_KEYS)
            .under("cards");
    private static final HandlerOption REVENUE = new HandlerOption("card_revenue", "流水", "本周流水；隐藏金额时改为场次",
            HandlerOption.Type.BOOLEAN, true, null, null, HandlerOption.RevenueVisibility.RESTYLED_WHEN_HIDDEN,
            "隐藏金额的会话里改写场次，不带金额", "cards");
    public static final List<HandlerOption> OPTIONS = List.of(
            HandlerOption.bool("cards", "数据卡片", "六张卡片与较上周的说明", true),
            HandlerOption.bool("card_duration", "时长", "本周直播时长", true).under("cards"),
            REVENUE,
            HandlerOption.bool("card_messages", "弹幕", "本周弹幕条数", true).under("cards"),
            HandlerOption.bool("card_audience", "观众", "本周去重观众", true).under("cards"),
            HandlerOption.bool("card_interaction_audience", "互动观众", "本周去重互动观众", true).under("cards"),
            HandlerOption.bool("card_fans", "粉丝净增", "本周粉丝净增", true).under("cards"),
            ORDER,
            HandlerOption.bool("days", "每天柱图", "按金额可见性显示每天的指标", true),
            HandlerOption.bool("audience", "本周观众", "首次记录与历史回访", true),
            HandlerOption.bool("calendar", "开播日历", "近五周开播日历", true),
            HandlerOption.bool("sessions", "本周场次", "本周场次明细", true));

    public WeeklyReportLayout {
        flags = Map.copyOf(flags);
        cardOrder = List.copyOf(cardOrder);
    }

    public static JSONObject defaults() {
        var params = new JSONObject();
        OPTIONS.forEach(o -> params.put(o.key(), o.defaultValue()));
        return params;
    }

    public static WeeklyReportLayout of(Map<String, ?> params) {
        var values = new JSONObject();
        if (params != null) values.putAll(params);
        var flags = new LinkedHashMap<String, Boolean>();
        for (var option : OPTIONS) if (option.type() == HandlerOption.Type.BOOLEAN) {
            Boolean value = values.getBoolean(option.key());
            flags.put(option.key(), value == null ? (Boolean) option.defaultValue() : value);
        }
        return new WeeklyReportLayout(flags, ORDER.orderOf(values.get("card_order")));
    }

    public static JSONObject savedParams(PushTarget channel) {
        if (channel == null) return new JSONObject();
        return channel.getMessages().stream()
                .filter(m -> WeeklyReportPushHandler.class.getName().equals(m.handlerClassName()))
                .findFirst().map(m -> {
                    if (m.getParamsJsonObject() != null) return new JSONObject(m.getParamsJsonObject());
                    return m.getParams() == null ? new JSONObject() : JSONObject.parseObject(m.getParams());
                }).orElseGet(JSONObject::new);
    }

    public boolean enabled(String key) { return flags.getOrDefault(key, false); }
    public boolean cardEnabled(String key) { return enabled("cards") && enabled(key); }
    public List<String> visibleCards() { return cardOrder.stream().filter(this::cardEnabled).toList(); }
    public boolean audienceCoverage() {
        return enabled("audience") || cardEnabled("card_audience") || cardEnabled("card_interaction_audience");
    }
}
