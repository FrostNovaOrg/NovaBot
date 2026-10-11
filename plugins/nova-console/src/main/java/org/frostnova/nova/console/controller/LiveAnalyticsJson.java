package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.LiveSessionAnalytics;
import org.frostnova.nova.core.model.LiveSession;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运营分析与主播详情共用的指标取法、指标说明与桶 JSON
 */
final class LiveAnalyticsJson {
    private LiveAnalyticsJson() {
    }

    static List<LiveMetricCatalog.Metric> metricsOf(List<LiveSession> sessions,
                                                   ObjectProvider<LiveMetricCatalog> catalogs, Logger log) {
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

    static JSONArray describe(List<LiveMetricCatalog.Metric> metrics) {
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

    static JSONArray toJson(List<LiveSessionAnalytics.Bucket> buckets) {
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
