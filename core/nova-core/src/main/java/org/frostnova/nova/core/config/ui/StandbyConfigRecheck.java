package org.frostnova.nova.core.config.ui;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.StandbyWait;
import org.frostnova.nova.core.protocol.StandbyPhases;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 过门之后、并且这次真等过锁时，把 application.yml 再读一遍，和绑定那一刻对过的不一样的项，
 * 走设置页保存那条路：能当场生效的当场生效，要重启的记进待重启。不自动重启。
 * <p>
 * 开关关着，或一拿就拿到锁，不核。那种启动没有别人占着目录的空档，
 * 文件上的差别是本进程自己写回去的。
 */
@Slf4j
@Component
class StandbyConfigRecheck implements SmartLifecycle {

    private final RuntimeConfigurationApplier applier;

    private final ObjectProvider<ConfigurationFileService> files;

    private volatile boolean running;

    StandbyConfigRecheck(RuntimeConfigurationApplier applier, ObjectProvider<ConfigurationFileService> files) {
        this.applier = applier;
        this.files = files;
    }

    @Override
    public void start() {
        try {
            if (StandbyWait.actuallyWaited()) {
                recheck();
            }
        } catch (IOException e) {
            log.warn("过门后没能再读配置文件: {}", e.toString());
        }
        running = true;
    }

    private void recheck() throws IOException {
        Map<String, Object> baseline = applier.boundAtStartup();
        Map<String, Object> now = files.getObject().readAsLoaded();
        Map<String, String> changes = diff(baseline, now);
        if (changes.isEmpty()) {
            return;
        }
        applier.applyAndTrack(changes);
        log.info("候命期间配置改过：{}", String.join("、", changes.keySet()));
    }

    /**
     * 只比按启动那一路读出来的值。文字一样、类型不同也算改过——重启后再读会不会变，看的就是这一路。
     */
    private static Map<String, String> diff(Map<String, Object> baseline, Map<String, Object> now) {
        Map<String, Object> before = baseline == null ? Map.of() : baseline;
        Map<String, Object> after = now == null ? Map.of() : now;
        Map<String, String> changes = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                changes.put(key, asText(after.get(key)));
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                changes.put(key, "");
            }
        }
        return changes;
    }

    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            StringBuilder text = new StringBuilder();
            for (Object item : list) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(item == null ? "" : item);
            }
            return text.toString();
        }
        return String.valueOf(value);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return StandbyPhases.RECHECK;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
