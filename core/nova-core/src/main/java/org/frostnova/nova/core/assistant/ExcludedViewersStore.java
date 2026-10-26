package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.protocol.StandbyPhases;
import org.frostnova.nova.core.util.DurableFiles;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** 供外部只读查询使用的当前观众拒绝名单，不包含模型连接或凭据。 */
@Slf4j
public final class ExcludedViewersStore implements RuntimeConfigurationApplierContributor, SmartLifecycle {
    private static final String KEY = "novabot.assistant.excluded-uids";
    private static final int VERSION = 1;
    private static final Set<String> FIELDS = Set.of("version", "updatedAt", "excludedUids");
    private final AssistantProperties properties;
    private final Path file;
    private final Clock clock;
    private volatile boolean running;

    public ExcludedViewersStore(AssistantProperties properties, Path directory, Clock clock) {
        this.properties = properties;
        this.file = directory.resolve("excluded-viewers.json");
        this.clock = clock;
    }

    @Override
    public void start() {
        refresh();
        running = true;
    }

    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return StandbyPhases.AFTER_GATE; }

    /** 启动和名单生效后同步写入；失败只告警，保留已经生效的设置。 */
    public synchronized void refresh() {
        List<Long> excluded = properties.getExcludedUids().stream().distinct().sorted().toList();
        try {
            if (unchanged(excluded)) return;
            Files.createDirectories(file.toAbsolutePath().getParent());
            DurableFiles.replace(file, JSONObject.of("version", VERSION, "updatedAt", clock.millis(),
                    "excludedUids", excluded).toJSONString());
        } catch (IOException | RuntimeException error) {
            log.warn("写入观众拒绝名单副本 {} 失败，设置仍已生效: {}", file, error.toString());
        }
    }

    private boolean unchanged(List<Long> excluded) {
        if (!Files.isRegularFile(file)) return false;
        try {
            JSONObject saved = JSON.parseObject(Files.readString(file));
            return saved != null && saved.keySet().equals(FIELDS)
                    && saved.getIntValue("version") == VERSION && saved.getLongValue("updatedAt") > 0
                    && excluded.equals(saved.getList("excludedUids", Long.class));
        } catch (IOException | RuntimeException unreadable) {
            // 副本缺失或损坏时按当前设置重新生成，不把副本当成配置来源。
            return false;
        }
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        return Map.of(KEY, this::apply);
    }

    private synchronized void apply(String value) {
        properties.setExcludedUids(parse(value));
        refresh();
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        return Map.of(KEY, value -> {
            try {
                parse(value);
                return null;
            } catch (IllegalArgumentException invalid) {
                return invalid.getMessage();
            }
        });
    }

    private static List<Long> parse(String value) {
        List<String> lines = value.lines().toList();
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String item = lines.get(i).strip();
            if (item.isEmpty()) continue;
            try {
                long uid = Long.parseLong(item);
                if (uid <= 0) throw new IllegalArgumentException();
                result.add(uid);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("观众拒绝名单第 " + (i + 1) + " 行「" + lines.get(i)
                        + "」不是正整数编号", invalid);
            }
        }
        return List.copyOf(result);
    }
}
