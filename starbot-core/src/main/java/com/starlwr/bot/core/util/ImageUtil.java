package com.starlwr.bot.core.util;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 图片工具类
 */
@Slf4j
public class ImageUtil {
    /**
     * 从指定路径读取图片
     * @param imagePath 图片路径
     * @return 图片
     */
    public static Optional<BufferedImage> readImageFromPath(@NonNull String imagePath) {
        try {
            return Optional.ofNullable(ImageIO.read(Paths.get(imagePath).toFile()));
        } catch (IOException e) {
            log.error("从路径 {} 读取图片失败", imagePath, e);
        }

        return Optional.empty();
    }

    /**
     * 缩放图片
     * @param sourceImage 源图片
     * @param width 宽度
     * @param height 高度
     * @return 缩放后的图片
     */
    public static BufferedImage resize(@NonNull BufferedImage sourceImage, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = resized.createGraphics();
        draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        draw.drawImage(sourceImage, 0, 0, width, height, null);
        draw.dispose();

        return resized;
    }

    /**
     * 根据指定宽度按比例缩放图片
     * @param sourceImage 源图片
     * @param width 宽度
     * @return 缩放后的图片
     */
    public static BufferedImage resizeByWidth(@NonNull BufferedImage sourceImage, int width) {
        int height = (int) (sourceImage.getHeight() * ((double) width / sourceImage.getWidth()));
        return resize(sourceImage, width, height);
    }

    /**
     * 根据指定高度按比例缩放图片
     * @param sourceImage 源图片
     * @param height 高度
     * @return 缩放后的图片
     */
    public static BufferedImage resizeByHeight(@NonNull BufferedImage sourceImage, int height) {
        int width = (int) (sourceImage.getWidth() * ((double) height / sourceImage.getHeight()));
        return resize(sourceImage, width, height);
    }

    /**
     * 将指定的图片限制为不可覆盖指定的点，若其将要覆盖指定的点，会自适应缩小图片至不会覆盖指定的点
     * @param sourceImage 要限制的图片
     * @param limitPoint 指定不可被覆盖的点
     * @param drawLocation 图片将要被绘制到的坐标
     * @return 调整大小后的图片
     */
    public static BufferedImage autoSizeImageByLimit(@NonNull BufferedImage sourceImage, @NonNull Point limitPoint, @NonNull Point drawLocation) {
        int xCover = drawLocation.x + sourceImage.getWidth() - limitPoint.x;
        if (drawLocation.y >= limitPoint.y || xCover <= 0) {
            return sourceImage;
        }

        int width = sourceImage.getWidth() - xCover;

        return resizeByWidth(sourceImage, width);
    }

    /**
     * 将图片转换为圆形
     * @param sourceImage 原图片
     * @return 圆形图片
     */
    public static BufferedImage maskToCircle(@NonNull BufferedImage sourceImage) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();

        BufferedImage circleImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = circleImage.createGraphics();

        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        Shape circle = new Ellipse2D.Float(0, 0, width, height);
        draw.setClip(circle);
        draw.drawImage(sourceImage, 0, 0, null);

        draw.dispose();

        return circleImage;
    }

    /**
     * 将图片转换为圆角矩形
     * @param sourceImage 原图片
     * @param radius 圆角矩形的圆角半径
     * @return 圆角矩形图片
     */
    public static BufferedImage maskToRoundedRectangle(@NonNull BufferedImage sourceImage, int radius) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();

        BufferedImage roundedRectangleImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = roundedRectangleImage.createGraphics();

        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        Shape roundedRectangle = new RoundRectangle2D.Float(0, 0, width, height, radius, radius);
        draw.setClip(roundedRectangle);
        draw.drawImage(sourceImage, 0, 0, null);

        draw.dispose();

        return roundedRectangleImage;
    }
}
