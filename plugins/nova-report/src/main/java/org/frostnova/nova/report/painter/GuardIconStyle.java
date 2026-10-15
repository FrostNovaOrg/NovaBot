package org.frostnova.nova.report.painter;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * 大航海徽章专用的缩放与轮廓处理。
 */
final class GuardIconStyle {
    private GuardIconStyle() {
    }

    static BufferedImage render(BufferedImage source, int size) {
        boolean transparentCorners = transparent(source, 0, 0)
                && transparent(source, source.getWidth() - 1, 0)
                && transparent(source, 0, source.getHeight() - 1)
                && transparent(source, source.getWidth() - 1, source.getHeight() - 1);

        // 逐级缩小限制采样跨度，双三次插值兼顾缩小与放大。
        // 预乘透明通道避免透明背景的颜色渗入徽章边缘。
        BufferedImage scaled = source;
        int width = source.getWidth();
        int height = source.getHeight();
        do {
            width = width > size ? Math.max(size, width / 2) : size;
            height = height > size ? Math.max(size, height / 2) : size;
            BufferedImage next = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = next.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(scaled, 0, 0, width, height, null);
            g.dispose();
            scaled = next;
        } while (width != size || height != size);

        if (transparentCorners) {
            return scaled;
        }
        BufferedImage circle = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = circle.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.fill(new Ellipse2D.Double(0, 0, size, size));
        g.setComposite(AlphaComposite.SrcIn);
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return circle;
    }

    private static boolean transparent(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) == 0;
    }
}
