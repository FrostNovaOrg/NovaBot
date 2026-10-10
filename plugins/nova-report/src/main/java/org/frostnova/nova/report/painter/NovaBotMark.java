package org.frostnova.nova.report.painter;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * NovaBot 图标：渐变气泡里一大一小两颗星，画在页脚版权行的左边
 * <p>
 * 几何取自 {@code docs/assets/icon.svg}，坐标照抄，viewBox 为 0 0 64 64。
 * 用代码画而不是往 jar 里放图片：图形只有三条路径，矢量画出来在任何高度上都清楚。
 * 改了那份 SVG，这里要一起改。
 */
final class NovaBotMark {
    /** viewBox 的边长 */
    private static final double VIEW_BOX = 64;

    private static final Color GRADIENT_START = new Color(0x7C, 0x5C, 0xFF);

    private static final Color GRADIENT_END = new Color(0xFF, 0x5F, 0x9E);

    /** 副星的不透明度 */
    private static final float SECONDARY_STAR_OPACITY = 0.85f;

    private static final Path2D BUBBLE = bubble();

    private static final Path2D PRIMARY_STAR = primaryStar();

    private static final Path2D SECONDARY_STAR = secondaryStar();

    private NovaBotMark() {
    }

    /**
     * 把图标画成 size×size 的一块（viewBox 是正方形，按原比例缩放）
     *
     * @param graphics 画笔，画完原样不动
     * @param x 左上角 x
     * @param y 左上角 y
     * @param size 边长，像素
     */
    static void paint(Graphics2D graphics, int x, int y, int size) {
        if (size <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size / VIEW_BOX, size / VIEW_BOX);

            g.setPaint(bubbleGradient());
            g.fill(BUBBLE);

            g.setColor(Color.WHITE);
            g.fill(PRIMARY_STAR);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SECONDARY_STAR_OPACITY));
            g.fill(SECONDARY_STAR);
        } finally {
            g.dispose();
        }
    }

    /**
     * 气泡的填色：左上到右下的对角渐变，按气泡外框算
     * <p>
     * SVG 里渐变的单位默认是外框（x1=0 y1=0 到 x2=1 y2=1）。外框不是正方形，
     * 所以把 (0,0)→(1,1) 经外框的变换放上去，而不是直接连外框的两个角——
     * 后者的等色线与对角线垂直，和 SVG 画出来的不一样
     */
    private static LinearGradientPaint bubbleGradient() {
        Rectangle2D box = BUBBLE.getBounds2D();
        AffineTransform toBox = new AffineTransform(box.getWidth(), 0, 0, box.getHeight(), box.getX(), box.getY());
        return new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(1, 1),
                new float[]{0f, 1f}, new Color[]{GRADIENT_START, GRADIENT_END},
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, toBox);
    }

    /**
     * M16 4h32a12 12 0 0 1 12 12v18a12 12 0 0 1-12 12H32L20.4 55.4a1.5 1.5 0 0 1-2.4-1.5
     * L20.6 46H16A12 12 0 0 1 4 34V16A12 12 0 0 1 16 4Z
     */
    private static Path2D bubble() {
        Path2D path = new Path2D.Double();
        path.moveTo(16, 4);
        path.lineTo(48, 4);
        arcTo(path, 12, false, true, 60, 16);
        path.lineTo(60, 34);
        arcTo(path, 12, false, true, 48, 46);
        path.lineTo(32, 46);
        path.lineTo(20.4, 55.4);
        arcTo(path, 1.5, false, true, 18, 53.9);
        path.lineTo(20.6, 46);
        path.lineTo(16, 46);
        arcTo(path, 12, false, true, 4, 34);
        path.lineTo(4, 16);
        arcTo(path, 12, false, true, 16, 4);
        path.closePath();
        return path;
    }

    /**
     * M28 11C28 20.8 31.6 25 40 25 31.6 25 28 29.2 28 39 28 29.2 24.4 25 16 25 24.4 25 28 20.8 28 11Z
     */
    private static Path2D primaryStar() {
        Path2D path = new Path2D.Double();
        path.moveTo(28, 11);
        path.curveTo(28, 20.8, 31.6, 25, 40, 25);
        path.curveTo(31.6, 25, 28, 29.2, 28, 39);
        path.curveTo(28, 29.2, 24.4, 25, 16, 25);
        path.curveTo(24.4, 25, 28, 20.8, 28, 11);
        path.closePath();
        return path;
    }

    /**
     * M45 29.5C45 34.05 46.5 36 50 36 46.5 36 45 37.95 45 42.5 45 37.95 43.5 36 40 36 43.5 36 45 34.05 45 29.5Z
     */
    private static Path2D secondaryStar() {
        Path2D path = new Path2D.Double();
        path.moveTo(45, 29.5);
        path.curveTo(45, 34.05, 46.5, 36, 50, 36);
        path.curveTo(46.5, 36, 45, 37.95, 45, 42.5);
        path.curveTo(45, 37.95, 43.5, 36, 40, 36);
        path.curveTo(43.5, 36, 45, 34.05, 45, 29.5);
        path.closePath();
        return path;
    }

    /**
     * SVG 的圆弧命令（rx = ry = r，不旋转）：从当前点画到 (x, y)
     * <p>
     * 圆心按 SVG 规范的端点式换算求出。sweep 为 true 是 SVG 坐标里角度增大的方向，
     * 即 y 轴朝下时的顺时针；{@link Arc2D} 的角度按 y 轴朝上算，所以起角与张角都取反
     */
    private static void arcTo(Path2D path, double r, boolean largeArc, boolean sweep, double x, double y) {
        Point2D from = path.getCurrentPoint();
        double halfX = (from.getX() - x) / 2;
        double halfY = (from.getY() - y) / 2;
        double squared = halfX * halfX + halfY * halfY;
        // 半径不够跨过两点时 SVG 把半径放大到刚好够，圆心落在弦的中点
        double radius = Math.max(r, Math.sqrt(squared));
        double factor = Math.sqrt(Math.max(0, (radius * radius - squared) / squared));
        double sign = largeArc == sweep ? -1 : 1;
        double cx = sign * factor * halfY + (from.getX() + x) / 2;
        double cy = sign * factor * -halfX + (from.getY() + y) / 2;

        double start = Math.atan2(from.getY() - cy, from.getX() - cx);
        double extent = Math.atan2(y - cy, x - cx) - start;
        if (sweep && extent < 0) {
            extent += 2 * Math.PI;
        } else if (!sweep && extent > 0) {
            extent -= 2 * Math.PI;
        }

        path.append(new Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2,
                -Math.toDegrees(start), -Math.toDegrees(extent), Arc2D.OPEN), true);
    }
}
