package org.frostnova.nova.core.service;

import org.frostnova.nova.core.model.PushTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 报告图配色：默认模板里一项，各通道可以另选一套
 * <p>
 * 与消息模板同一种「默认＋通道另设」：通道没选（{@link PushTarget#getReportPalette()} 为空）
 * 就跟默认模板走，默认模板没改过就是出厂的 {@link #FACTORY_DEFAULT}。
 * 存的一律是预设 id，不存颜色值：配色以后调一个颜色，存着的选择照样指得到它。
 * <p>
 * 老配置里两处都没有这一项，所以升级后没选过的通道就是出厂那一套，不用迁移。
 * 认不认得某个 id 不在这里判：哪几套可选、读到不认识的怎么办，是画图那一侧的事。
 * 这里只答「这个通道选的是哪个 id」。
 */
@Service
public class ReportPaletteSettings {
    /**
     * 出厂默认：粉蓝
     */
    public static final String FACTORY_DEFAULT = "pink-blue";

    private final PushTemplateDefaults defaults;

    @Autowired
    public ReportPaletteSettings(PushTemplateDefaults defaults) {
        this.defaults = defaults;
    }

    /**
     * 没有默认模板可读的一份：默认一律是出厂那一套。给不带这项设置构造的调用方
     */
    public static ReportPaletteSettings factory() {
        return new ReportPaletteSettings(null);
    }

    /**
     * @return 默认模板里的配色 id，没改过时为出厂默认
     */
    public String defaultId() {
        String stored = defaults == null ? null : defaults.reportPalette();
        return stored == null ? FACTORY_DEFAULT : stored;
    }

    /**
     * 这个通道该用哪一套
     * @param channel 通道，可为 null（命令打在没配推送的会话里）
     * @return 通道自己选的 id，没选时为默认模板的
     */
    public String idFor(PushTarget channel) {
        String own = channel == null ? null : channel.getReportPalette();
        return own == null || own.isBlank() ? defaultId() : own;
    }

    /**
     * @return 默认模板里存着的 id；没改过时为 null
     */
    public String storedDefault() {
        return defaults == null ? null : defaults.reportPalette();
    }

    /**
     * 改默认模板里的报告图配色。认不认得这个 id 由调用方先判
     * @param id 预设 id；null 或空串即回到出厂
     * @return 问题清单，为空即已写盘
     */
    public List<String> saveDefault(String id) {
        if (defaults == null) {
            return List.of("这里没有默认模板可写");
        }
        return defaults.saveReportPalette(id);
    }
}
