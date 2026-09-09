package org.frostnova.nova.report.painter;

import org.frostnova.nova.report.util.ImageUtil;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 报告图里那几张「本该向平台取、这次取不到」的占位图
 * <p>
 * 两处在用，尺寸必须一致，因此只留这一份：版式预览（夹具那个 uid 不是真人，不能拿去打接口）
 * 与历史场次重画（取到的会是今天的封面，而这一场是去年的）。
 * <p>
 * <b>不用纯色块</b>：纯色在报告里看起来像一处画崩了的空白，而渐变一眼就知道是占位。
 * <p>
 * 尺寸取自 {@link BilibiliLiveReportPainter} 的版式常量而不是各自写死——
 * 写死的那一版会在版式改宽的那天变成两张对不齐的图，且图上看不出哪张是错的。
 */
final class PainterPlaceholder {
    private PainterPlaceholder() {
    }

    /**
     * 占位图的配色：与报告主色系接近但明显是占位，免得被当成真封面
     */
    private static final Color FROM = new Color(203, 213, 225);

    private static final Color TO = new Color(148, 163, 184);

    /**
     * 封面横幅位的占位图，已裁成与真封面同样的圆角
     */
    static BufferedImage banner() {
        return ImageUtil.maskToRoundedRectangle(
                gradient(BilibiliLiveReportPainter.CONTENT_WIDTH, BilibiliLiveReportPainter.COVER_HEIGHT),
                BilibiliLiveReportPainter.CANVAS_RADIUS - 5);
    }

    /**
     * 主播头像位的占位图
     */
    static BufferedImage face() {
        return ImageUtil.maskToCircle(
                gradient(BilibiliLiveReportPainter.AVATAR_SIZE, BilibiliLiveReportPainter.AVATAR_SIZE));
    }

    /**
     * 排行榜头像位的占位图
     */
    static BufferedImage rankingFace() {
        return ImageUtil.maskToCircle(gradient(
                BilibiliLiveReportPainter.RANKING_AVATAR_SIZE, BilibiliLiveReportPainter.RANKING_AVATAR_SIZE));
    }

    /**
     * 画一张斜向渐变
     */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(new GradientPaint(0, 0, FROM, width, height, TO));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }
}
