package org.frostnova.nova.report.factory;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.painter.CommonPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * NovaBot 绘图器工厂
 * <p>
 * 绘图器要三样东西才画得全：版本号（画在版权行上）、配置（版面尺寸与配色）、字体表（挑字与量宽）。
 * 报告图那一路自己不持有这三样，全靠这里在造绘图器时一并塞进去——
 * 少塞一样不会在造的时候报错，会等到画到那一处时才炸。
 * <p>
 * 每次都造一张<b>新画布</b>：绘图器带着当前坐标这个状态，共用一张会让两张报告画到对方身上。
 */
@Component
public class NovaCommonPainterFactory {
    private final BuildProperties buildProperties;

    private final NovaCoreProperties properties;

    private final FontUtil fontUtil;

    @Autowired
    public NovaCommonPainterFactory(BuildProperties buildProperties, NovaCoreProperties properties, FontUtil fontUtil) {
        this.buildProperties = buildProperties;
        this.properties = properties;
        this.fontUtil = fontUtil;
    }

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height) {
        // 不自动加高：高度事先算得出的那些图（卡片、榜单）用这一版，
        // 画超了就该在版面上看出来，而不是让画布悄悄长高
        return create(width, height, false);
    }

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @param autoExpand 是否自动扩展画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height, boolean autoExpand) {
        return new CommonPainter(buildProperties, properties, fontUtil, width, height, autoExpand);
    }
}
