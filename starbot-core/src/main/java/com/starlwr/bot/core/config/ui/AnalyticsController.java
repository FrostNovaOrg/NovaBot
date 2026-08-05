package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import com.starlwr.bot.core.analytics.LiveSessionAnalytics;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.service.LiveSessionArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营数据分析接口
 * <p>
 * 数据来自 {@code sessions.jsonl}，按周或月聚合。**只在管理后台呈现**——
 * 运营数据是给机器人主人做判断用的，往群里发既没人看也涉及主播的经营信息。
 * <p>
 * 聚合在服务端完成而非把全部场次丢给浏览器：跨零点归属、按本地时区分周、
 * 空周期补零这几条规则只该有一份实现，放在 {@link LiveSessionAnalytics} 里连同边界一起测。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/analytics")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsController {
    /**
     * 默认返回多少个周期
     * <p>
     * 周视图约半年、月视图一年多，够看趋势又不至于让表格长到没法读。
     */
    private static final int DEFAULT_LIMIT = 26;

    private static final int MAX_LIMIT = 120;

    private final LiveSessionArchive archive;

    private final LiveSessionAnalytics analytics;

    /**
     * 各平台插件提供的指标说明
     * <p>
     * 以 ObjectProvider 获取：说明来自插件，而插件的 Bean 定义由
     * BeanDefinitionRegistryPostProcessor 注册，延迟解析可避开注册与注入的先后顺序。
     */
    private final ObjectProvider<LiveMetricCatalog> catalogs;

    @Autowired
    public AnalyticsController(LiveSessionArchive archive, ObjectProvider<LiveMetricCatalog> catalogs) {
        this.archive = archive;
        this.catalogs = catalogs;
        this.analytics = new LiveSessionAnalytics();
    }

    /**
     * 周期统计
     * @param period 周期，week 或 month
     * @param uid 只看某位主播，为空则合并全部
     * @param limit 返回最近多少个周期
     * @return 周期汇总、指标说明与主播清单
     */
    @GetMapping
    public JSONObject analytics(@RequestParam(defaultValue = "week") String period,
                                @RequestParam(required = false) Long uid,
                                @RequestParam(defaultValue = "0") int limit) {
        JSONObject result = new JSONObject();
        result.put("success", true);

        LiveSessionAnalytics.Period parsed = "month".equalsIgnoreCase(period)
                ? LiveSessionAnalytics.Period.MONTH
                : LiveSessionAnalytics.Period.WEEK;
        result.put("period", parsed.name().toLowerCase());

        List<LiveSession> all = archive.find(0, Long.MAX_VALUE);
        result.put("streamers", streamers(all));

        List<LiveSession> selected = uid == null
                ? all
                : all.stream().filter(session -> uid.equals(session.uid())).toList();
        result.put("uid", uid);
        result.put("sessionCount", selected.size());

        // 指标说明按所选场次实际涉及的平台取。混着两个平台时把两份说明都给出来，
        // 各平台的指标键本就不同，合表展示不会串味
        List<LiveMetricCatalog.Metric> metrics = metricsOf(selected);
        result.put("metrics", describe(metrics));
        result.put("metricsKnown", !metrics.isEmpty());

        Set<String> keys = new LinkedHashSet<>();
        metrics.forEach(metric -> keys.add(metric.key()));

        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(selected, parsed, keys);

        int effective = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int dropped = Math.max(0, buckets.size() - effective);
        if (dropped > 0) {
            // 截断了就得说，否则界面看起来像是「一共就这些数据」
            buckets = buckets.subList(buckets.size() - effective, buckets.size());
        }
        result.put("droppedPeriods", dropped);
        result.put("buckets", toJson(buckets));

        LiveSessionArchive.Summary summary = archive.summary();
        JSONObject archived = new JSONObject();
        archived.put("count", summary.count());
        archived.put("earliestStart", summary.earliestStart());
        archived.put("latestStart", summary.latestStart());
        result.put("archive", archived);

        return result;
    }

    /**
     * 归档里出现过的主播
     * <p>
     * 取自归档而非推送配置：一位主播从推送配置里删掉之后，他过去的场次仍在归档里，
     * 也仍该能被查看——历史数据不会因为不再监听就失去意义。
     */
    private JSONArray streamers(List<LiveSession> sessions) {
        Map<Long, JSONObject> byUid = new LinkedHashMap<>();

        for (LiveSession session : sessions) {
            JSONObject item = byUid.computeIfAbsent(session.uid(), key -> {
                JSONObject created = new JSONObject();
                created.put("uid", session.uid());
                created.put("platform", session.platform());
                created.put("sessions", 0);
                return created;
            });
            // 昵称取最近一场的：主播改名后，界面上该显示现在的名字
            if (session.uname() != null && !session.uname().isBlank()) {
                item.put("uname", session.uname());
            }
            item.put("sessions", item.getIntValue("sessions") + 1);
        }

        return new JSONArray(new ArrayList<>(byUid.values()));
    }

    /**
     * 所选场次涉及平台的可累加指标
     */
    private List<LiveMetricCatalog.Metric> metricsOf(List<LiveSession> sessions) {
        Set<String> platforms = new LinkedHashSet<>();
        sessions.forEach(session -> platforms.add(session.platform()));

        List<LiveMetricCatalog.Metric> result = new ArrayList<>();
        for (String platform : platforms) {
            catalogs.orderedStream()
                    .filter(catalog -> catalog.platform().equals(platform))
                    .findFirst()
                    .map(LiveMetricCatalog::metrics)
                    .ifPresentOrElse(result::addAll,
                            () -> log.debug("未找到平台 {} 的指标说明, 该平台的场次只统计场次数与时长", platform));
        }
        return result;
    }

    private JSONArray describe(List<LiveMetricCatalog.Metric> metrics) {
        JSONArray items = new JSONArray();

        for (LiveMetricCatalog.Metric metric : metrics) {
            JSONObject item = new JSONObject();
            item.put("key", metric.key());
            item.put("name", metric.name());
            item.put("unit", metric.unit());
            item.put("money", metric.money());
            items.add(item);
        }

        return items;
    }

    private JSONArray toJson(List<LiveSessionAnalytics.Bucket> buckets) {
        JSONArray items = new JSONArray();

        for (LiveSessionAnalytics.Bucket bucket : buckets) {
            JSONObject item = new JSONObject();
            item.put("label", bucket.label());
            item.put("start", bucket.start());
            item.put("end", bucket.end());
            item.put("sessions", bucket.sessions());
            item.put("durationSeconds", bucket.durationSeconds());
            item.put("metrics", bucket.metrics());
            item.put("userTimes", bucket.userTimes());
            items.add(item);
        }

        return items;
    }
}
