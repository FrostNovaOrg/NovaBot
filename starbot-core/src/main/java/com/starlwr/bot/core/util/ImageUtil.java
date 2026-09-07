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
 * <p>
 * 报告图、动态图、数据查询图里的头像、封面、九宫格都从这里过。
 * 这里每个方法都<b>另造一张新图</b>而不改传进来的那张：同一张头像会在一张报告里用到好几次，
 * 就地改的话，第二次用到它的地方拿到的就是已经被挖过的那张。
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
            // 「文件打不开」抛异常，而「文件在但不是图片」给的是 null——
            // 两条路都得收敛成空：画图那一路把读不到的图当成「没有这张图」继续画，不当场停下
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
        BufferedImage resized = newCanvas(width, height);

        Graphics2D draw = resized.createGraphics();
        // 双线性插值：头像常常从几百像素缩到几十像素，按最近邻缩会缩出锯齿
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
        return resize(sourceImage, width, scaled(sourceImage.getHeight(), width, sourceImage.getWidth()));
    }

    /**
     * 根据指定高度按比例缩放图片
     * @param sourceImage 源图片
     * @param height 高度
     * @return 缩放后的图片
     */
    public static BufferedImage resizeByHeight(@NonNull BufferedImage sourceImage, int height) {
        return resize(sourceImage, scaled(sourceImage.getWidth(), height, sourceImage.getHeight()), height);
    }

    /**
     * 将指定的图片限制为不可覆盖指定的点，若其将要覆盖指定的点，会自适应缩小图片至不会覆盖指定的点
     * @param sourceImage 要限制的图片
     * @param limitPoint 指定不可被覆盖的点
     * @param drawLocation 图片将要被绘制到的坐标
     * @return 调整大小后的图片
     */
    public static BufferedImage autoSizeImageByLimit(@NonNull BufferedImage sourceImage, @NonNull Point limitPoint, @NonNull Point drawLocation) {
        // 限制点只挡它那一行以上的东西：画到它下面去的图，横向盖多远都不管
        boolean startsBelowTheLimit = drawLocation.y >= limitPoint.y;
        int cover = drawLocation.x + sourceImage.getWidth() - limitPoint.x;

        if (startsBelowTheLimit || cover <= 0) {
            // 不用缩就原样交回去，不重画一张：每重画一次就掉一次画质
            return sourceImage;
        }

        return resizeByWidth(sourceImage, sourceImage.getWidth() - cover);
    }

    /**
     * 将图片转换为圆形
     * @param sourceImage 原图片
     * @return 圆形图片
     */
    public static BufferedImage maskToCircle(@NonNull BufferedImage sourceImage) {
        return mask(sourceImage, new Ellipse2D.Float(0, 0, sourceImage.getWidth(), sourceImage.getHeight()));
    }

    /**
     * 将图片转换为圆角矩形
     * @param sourceImage 原图片
     * @param radius 圆角矩形的圆角半径
     * @return 圆角矩形图片
     */
    public static BufferedImage maskToRoundedRectangle(@NonNull BufferedImage sourceImage, int radius) {
        return mask(sourceImage, new RoundRectangle2D.Float(0, 0, sourceImage.getWidth(), sourceImage.getHeight(), radius, radius));
    }

    /**
     * 把图片按给定形状裁出来，形状之外的部分变成透明
     */
    private static BufferedImage mask(BufferedImage sourceImage, Shape shape) {
        BufferedImage masked = newCanvas(sourceImage.getWidth(), sourceImage.getHeight());

        Graphics2D draw = masked.createGraphics();
        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        draw.setClip(shape);
        draw.drawImage(sourceImage, 0, 0, null);
        draw.dispose();

        return masked;
    }

    /**
     * 造一张带透明通道的空画布
     * <p>
     * 一律 ARGB：缩放出来的图后面多半还要被挖成圆形或圆角，
     * 没有透明通道的话挖掉的地方是黑的而不是透的
     */
    private static BufferedImage newCanvas(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * 按另一条边的缩放比例算这条边
     * <p>
     * ⚠️ 结果向下取整，细长的图缩得太狠时会算出 0，而画布不接受 0 —— 那时抛的是画布自己的报错。
     * 这一层不兜：兜住只会让版面常量算错的那一处安静地画出一张空图
     */
    private static int scaled(int side, int targetOtherSide, int otherSide) {
        return (int) (side * ((double) targetOtherSide / otherSide));
    }
}
