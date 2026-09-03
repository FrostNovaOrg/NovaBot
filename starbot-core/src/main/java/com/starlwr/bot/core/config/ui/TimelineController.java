package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 事件时间线接口
 * <p>
 * 「日志」页的数据来源。与运行日志文件不是一回事：那一份是给排障用的，
 * 按级别过滤后使用者关心的事恰好被滤掉；这一份记的就是使用者关心的事。
 * <p>
 * <b>挂在控制台自己的路径下</b>，与事件输出协议（{@code /nova/events}）无关：
 * 那一条是给外部消费方的实时流、有独立的认证与协议版本，
 * 把一个后台查询接口挂进去等于让它也背上协议兼容的包袱。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/timeline")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class TimelineController {
    private final TimelineStore store;

    @Autowired
    public TimelineController(TimelineStore store) {
        this.store = store;
    }

    /**
     * 按条件查询
     * @param date 只看某一天，格式 {@code YYYY-MM-DD}；留空则看保留期内的全部
     * @param problems 只看有问题的
     * @param type 只看某一类事件
     * @param streamer 只看某位主播
     * @param channel 只看某个推送通道
     * @param q 在正文、主播、通道与补充信息里搜关键词
     * @param limit 最多返回多少条
     * @return 命中的事件与本次筛选的说明
     */
    @GetMapping
    public JSONObject timeline(@RequestParam(required = false) String date,
                               @RequestParam(defaultValue = "false") boolean problems,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String streamer,
                               @RequestParam(required = false) String channel,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "0") int limit) {
        JSONObject result = new JSONObject();
        result.put("success", true);

        LocalDate day;
        try {
            day = parseDate(date);
        } catch (DateTimeParseException e) {
            // 日期不合格式就明说，而不是当成「没填」去查全部——
            // 后者会返回一大堆记录，看起来像是筛选没生效
            result.put("success", false);
            result.put("message", "日期格式应为 YYYY-MM-DD: " + date);
            return result;
        }

        // 认不出的类型名不当成「不筛」：那会把「筛了个不存在的类型」显示成「什么都没筛」
        TimelineEventType parsedType = TimelineEventType.parse(type);
        if (type != null && !type.isBlank() && parsedType == null) {
            result.put("success", false);
            result.put("message", "认不出的事件类型: " + type);
            return result;
        }

        TimelineStore.Result found = store.query(new TimelineStore.Filter(
                day, problems, parsedType, streamer, channel, q, limit));

        result.put("date", day == null ? null : day.toString());
        result.put("problemsOnly", problems);
        result.put("type", parsedType == null ? null : parsedType.name());
        result.put("streamer", streamer);
        result.put("channel", channel);
        result.put("q", q);

        JSONArray events = new JSONArray();
        for (TimelineEvent event : found.events()) {
            events.add(TimelineStore.toJson(event));
        }
        result.put("events", events);

        // 截断了就得说，否则界面看起来像是「一共就发生了这些」
        result.put("matched", found.matched());
        result.put("returned", found.events().size());
        result.put("truncated", found.truncated());
        result.put("limit", found.limit());
        result.put("types", types());
        result.put("retentionDays", store.retentionDays());

        return result;
    }

    /**
     * 有记录的日期与各自条数
     * @return 日期清单，最近的在前
     */
    @GetMapping("/days")
    public JSONObject days() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        JSONArray days = new JSONArray();
        for (TimelineStore.Day day : store.days()) {
            JSONObject item = new JSONObject();
            item.put("date", day.date().toString());
            item.put("count", day.count());
            days.add(item);
        }

        result.put("days", days);
        result.put("retentionDays", store.retentionDays());
        result.put("types", types());

        return result;
    }

    /**
     * 事件类型闭集
     * <p>
     * 由接口给出而不是让界面自己写一张表：界面那张表漏一项的表现是筛选框里少一个选项，
     * 而少了谁只有真发生过那类事件的人才看得见。
     */
    private JSONArray types() {
        JSONArray items = new JSONArray();

        for (TimelineEventType type : TimelineEventType.values()) {
            JSONObject item = new JSONObject();
            item.put("name", type.name());
            item.put("text", type.getDescription());
            items.add(item);
        }

        return items;
    }

    /**
     * 解析日期，留空返回 {@code null}
     */
    private LocalDate parseDate(String date) {
        return date == null || date.isBlank() ? null : LocalDate.parse(date.trim());
    }
}
