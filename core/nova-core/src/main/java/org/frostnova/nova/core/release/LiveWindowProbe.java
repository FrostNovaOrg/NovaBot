package org.frostnova.nova.core.release;

import org.frostnova.nova.core.properties.LiveProperties;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/** 供发版等窗从服务的工作目录读取直播数据，不启动 NovaBot。 */
public final class LiveWindowProbe {
    private static final String LIVE_DATA_PATH = "novabot.core.live.live-data-path";
    private static final String LIVE_STATUS_PREFIX = "LiveStatus:";
    private static final long MAX_AGE_MILLIS = Duration.ofMinutes(10).toMillis();

    private LiveWindowProbe() {
    }

    public static void main(String[] args) {
        Path path;
        try {
            path = liveDataPath();
        } catch (RuntimeException e) {
            // 解析异常可能含配置原文，探针只返回固定的原因。
            System.out.println(unknown("无法从配置读取直播数据文件路径"));
            return;
        }
        System.out.println(inspect(path));
    }

    private static Path liveDataPath() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String value = Binder.get(environment).bind(LIVE_DATA_PATH, String.class)
                .orElseGet(() -> new LiveProperties().getLiveDataPath());
        if (value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException();
        }
        return Path.of(value);
    }

    private static String inspect(Path path) {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                return unknown("无法读取直播数据文件");
            }
            // 快照还含有场次统计，逐项读取可跳过无关的数据。
            var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            try (InputStream input = Files.newInputStream(path);
                 JsonParser json = mapper.createParser(input)) {
                return inspectSnapshot(json);
            }
        } catch (NoSuchFileException e) {
            return unknown("直播数据文件不存在");
        } catch (IOException | JacksonIOException e) {
            return unknown("无法读取直播数据文件");
        } catch (JacksonException e) {
            return unknown("直播数据文件不是有效的 JSON");
        }
    }

    private static String inspectSnapshot(JsonParser json) {
        if (json.nextToken() != JsonToken.START_OBJECT) {
            return unknown("直播数据文件格式不对");
        }

        long lastSaveTime = 0;
        int liveCount = 0;
        boolean hasPlatform = false;
        while (json.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = json.currentName();
            JsonToken value = json.nextToken();
            if ("LastSaveTime".equals(key)) {
                if (value == JsonToken.VALUE_NUMBER_INT) {
                    lastSaveTime = json.getLongValue();
                }
            } else if (key.startsWith(LIVE_STATUS_PREFIX)) {
                if (value != JsonToken.START_OBJECT || key.length() == LIVE_STATUS_PREFIX.length()) {
                    return unknown("直播数据文件的直播状态格式不对");
                }
                hasPlatform = true;
                while (json.nextToken() == JsonToken.PROPERTY_NAME) {
                    JsonToken status = json.nextToken();
                    if (status == JsonToken.VALUE_TRUE) {
                        liveCount++;
                    } else if (status != JsonToken.VALUE_FALSE) {
                        return unknown("直播数据文件的直播状态格式不对");
                    }
                }
                if (json.currentToken() != JsonToken.END_OBJECT) {
                    return unknown("直播数据文件的直播状态格式不对");
                }
            }
            json.skipChildren();
        }
        if (json.currentToken() != JsonToken.END_OBJECT || json.nextToken() != null) {
            return unknown("直播数据文件不是有效的 JSON");
        }
        if (!hasPlatform) {
            return unknown("直播数据文件没有 LiveStatus:* 键");
        }
        long now = System.currentTimeMillis();
        if (lastSaveTime <= 0 || lastSaveTime > now) {
            return unknown("直播数据文件的 LastSaveTime 缺失或无效");
        }
        long age = now - lastSaveTime;
        if (age > MAX_AGE_MILLIS) {
            return unknown("直播数据文件至少 %d 分钟没更新".formatted(Duration.ofMillis(age).toMinutes()));
        }
        if (liveCount > 0) {
            return "LIVE %d 位主播在播，继续等".formatted(liveCount);
        }
        return "ALLIDLE 直播数据在 10 分钟内更新，全员空闲，可部署";
    }

    private static String unknown(String reason) {
        return "UNKNOWN " + reason + "，判不了在不在播，继续等";
    }
}
