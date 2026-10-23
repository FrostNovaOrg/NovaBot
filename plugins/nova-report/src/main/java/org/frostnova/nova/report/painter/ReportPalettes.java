package org.frostnova.nova.report.painter;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.ReportPaletteSettings;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 某个通道的报告图该用哪一套配色
 * <p>
 * 存着的是预设 id（见 {@link ReportPaletteSettings}），这里把它换成 {@link ReportPalette.Preset}。
 * 读到不认识的 id（手改过配置、或那一套以后改了名）照默认模板的配色出图，
 * 并在日志里说一句；同一个 id 只说一次，不按每张图刷屏。
 */
@Slf4j
@NovaComponent
public class ReportPalettes {
    private final ReportPaletteSettings settings;

    /**
     * 已经在日志里说过的不认识的 id
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReportPalettes(ReportPaletteSettings settings) {
        this.settings = settings;
    }

    /**
     * 不读默认模板的一份：没选过的一律是出厂那一套。给不带这项设置构造的调用方
     */
    public static ReportPalettes factory() {
        return new ReportPalettes(ReportPaletteSettings.factory());
    }

    /**
     * @param channel 通道，可为 null（命令打在没配推送的会话里）
     * @return 通道自己选的一套，没选时为默认模板的
     */
    public ReportPalette.Preset forChannel(PushTarget channel) {
        return forId(settings.idFor(channel));
    }

    /**
     * @return 默认模板里的那一套
     */
    public ReportPalette.Preset byDefault() {
        return forId(settings.defaultId());
    }

    /**
     * @param id 预设 id；null 即默认模板的
     * @return 认得出就是那一套，认不出照默认模板的
     */
    public ReportPalette.Preset forId(String id) {
        if (id == null) {
            return byDefault();
        }
        Optional<ReportPalette.Preset> known = ReportPalette.preset(id);
        if (known.isPresent()) {
            return known.get();
        }

        String fallback = settings.defaultId();
        Optional<ReportPalette.Preset> byDefault = Objects.equals(id, fallback)
                ? Optional.empty() : ReportPalette.preset(fallback);
        if (byDefault.isPresent()) {
            if (reported.add(id)) {
                log.warn("报告图配色「{}」不在可选的几套里，按默认模板的「{}」出图", id, byDefault.get().name());
            }
            return byDefault.get();
        }

        // 默认模板里存的那一个也认不出，只剩出厂那一套
        ReportPalette.Preset factory = ReportPalette.preset(ReportPaletteSettings.FACTORY_DEFAULT).orElseThrow();
        if (reported.add(id)) {
            log.warn("报告图配色「{}」不在可选的几套里，默认模板里也没有认得出的一套，按「{}」出图", id, factory.name());
        }
        return factory;
    }
}
