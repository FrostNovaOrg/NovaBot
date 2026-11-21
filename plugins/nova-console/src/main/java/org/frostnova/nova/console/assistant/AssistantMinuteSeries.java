package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONReader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.frostnova.nova.core.service.LiveDataService.SERIES_BUCKET_MILLIS;

/** 明细还有排行榜、词频等内容。只解码请求窗口内的两条人数序列。 */
record AssistantMinuteSeries(BigDecimal[] online, BigDecimal[] watched) {
    private static final int MAX_DETAIL_BYTES = 16 * 1024 * 1024;

    static AssistantMinuteSeries read(Path path, long first, int minutes) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_DETAIL_BYTES + 1);
        }
        if (bytes.length > MAX_DETAIL_BYTES) {
            throw new AssistantAggregateRequest.Invalid("场次明细过大，请缩短时间范围后重试");
        }
        var series = new AssistantMinuteSeries(new BigDecimal[minutes], new BigDecimal[minutes]);
        try (JSONReader reader = JSONReader.of(bytes)) {
            requireObject(reader);
            while (!reader.nextIfObjectEnd()) {
                if (field(reader).equals("series") && reader.isObject()) series.readSeries(reader, first);
                else reader.skipValue();
            }
            if (!reader.isEnd()) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            return null;
        }
        return series;
    }

    private void readSeries(JSONReader reader, long first) {
        requireObject(reader);
        while (!reader.nextIfObjectEnd()) {
            var values = switch (field(reader)) {
                case "online_count" -> online;
                case "watched_count" -> watched;
                default -> null;
            };
            if (values != null && reader.isObject()) readMinutes(reader, first, values);
            else reader.skipValue();
        }
    }

    private static void readMinutes(JSONReader reader, long first, BigDecimal[] values) {
        requireObject(reader);
        while (!reader.nextIfObjectEnd()) {
            String key = field(reader);
            long at;
            try { at = Long.parseLong(key); }
            catch (NumberFormatException error) { reader.skipValue(); continue; }
            long index = at >= first ? (at - first) / SERIES_BUCKET_MILLIS : -1;
            if (index < 0 || index >= values.length || at % SERIES_BUCKET_MILLIS != 0 || !reader.isNumber()) {
                reader.skipValue();
                continue;
            }
            BigDecimal value = AssistantAggregateQuery.number(reader.readNumber());
            if (value != null && value.signum() >= 0) values[(int) index] = value;
        }
    }

    private static void requireObject(JSONReader reader) {
        if (!reader.nextIfObjectStart()) throw new IllegalArgumentException();
    }

    private static String field(JSONReader reader) {
        var name = reader.readFieldName();
        if (name == null) throw new IllegalArgumentException();
        return name;
    }
}
