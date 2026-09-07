package com.starlwr.bot.core.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片缩放与蒙版
 *
 * <h2>为什么会有这组判据</h2>
 * 报告图、动态图、数据查询图里的头像、封面、九宫格都从这里过。
 * 它改坏了<b>不会有任何异常</b>——只会在某一张发出去的图上变形、错位、或者露出方角。
 * <p>
 * 判据一律量<b>读数</b>（尺寸、指定坐标上的像素、透明度），不拿整张图做基线比对：
 * 基线图换一版字体或换一台机器就全红，红了也说明不了是哪里坏的。
 */
@DisplayName("图片缩放与蒙版")
class ImageUtilTest {
    private static final int OPAQUE = 255;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 造一张纯色不透明的图，用来量缩放后的尺寸与蒙版挖掉了哪里
     */
    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        draw.setColor(color);
        draw.fillRect(0, 0, width, height);
        draw.dispose();
        return image;
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Nested
    @DisplayName("从路径读图")
    class Reading {
        @Test
        @DisplayName("读得到的图应当带回原尺寸")
        void readsExistingImage(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("sample.png");
            ImageIO.write(solid(40, 20, Color.RED), "png", file.toFile());

            Optional<BufferedImage> image = ImageUtil.readImageFromPath(file.toString());

            assertTrue(image.isPresent());
            assertEquals(40, image.get().getWidth());
            assertEquals(20, image.get().getHeight());
        }

        @Test
        @DisplayName("路径不存在时给空，不抛异常")
        void missingFileYieldsEmpty(@TempDir Path directory) {
            Optional<BufferedImage> image =
                    ImageUtil.readImageFromPath(directory.resolve("not-exists.png").toString());

            assertTrue(image.isEmpty(), "画图那一路把读不到的图当成「没有这张图」继续画, 不是当场炸掉");
        }

        /**
         * 不是图片的文件走的是另一条路：{@code ImageIO.read} 不抛异常，直接给 null。
         * 两条路都必须收敛到「空」，不能一条给空一条抛。
         */
        @Test
        @DisplayName("文件在但不是图片时也给空")
        void nonImageFileYieldsEmpty(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("not-an-image.png");
            Files.writeString(file, "这不是 PNG");

            assertTrue(ImageUtil.readImageFromPath(file.toString()).isEmpty());
        }

        @Test
        @DisplayName("空路径给空，不抛异常")
        void blankPathYieldsEmpty() {
            assertTrue(ImageUtil.readImageFromPath("").isEmpty());
        }

        @Test
        @DisplayName("路径为 null 是调用方的错，直接抛空指针")
        void nullPathThrows() {
            assertThrows(NullPointerException.class, () -> ImageUtil.readImageFromPath(null));
        }
    }

    @Nested
    @DisplayName("缩放")
    class Resizing {
        @Test
        @DisplayName("按给定宽高缩放，出来的就是那个尺寸")
        void resizesToExactSize() {
            BufferedImage resized = ImageUtil.resize(solid(100, 50, Color.RED), 30, 70);

            assertEquals(30, resized.getWidth());
            assertEquals(70, resized.getHeight());
        }

        @Test
        @DisplayName("缩放结果一律带透明通道")
        void resizeKeepsAlphaChannel() {
            BufferedImage source = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            BufferedImage resized = ImageUtil.resize(source, 5, 5);

            assertEquals(BufferedImage.TYPE_INT_ARGB, resized.getType(),
                    "后面要在结果上挖圆角, 没有透明通道挖出来是黑的");
        }

        @Test
        @DisplayName("按宽度缩放时高度按原比例走")
        void resizeByWidthKeepsRatio() {
            BufferedImage resized = ImageUtil.resizeByWidth(solid(100, 50, Color.RED), 30);

            assertEquals(30, resized.getWidth());
            assertEquals(15, resized.getHeight(), "50 × (30/100) = 15");
        }

        @Test
        @DisplayName("按高度缩放时宽度按原比例走")
        void resizeByHeightKeepsRatio() {
            BufferedImage resized = ImageUtil.resizeByHeight(solid(100, 50, Color.RED), 25);

            assertEquals(50, resized.getWidth(), "100 × (25/50) = 50");
            assertEquals(25, resized.getHeight());
        }

        @Test
        @DisplayName("算出来的边长带小数时向下取整")
        void ratioTruncatesTowardsZero() {
            BufferedImage resized = ImageUtil.resizeByWidth(solid(100, 51, Color.RED), 30);

            assertEquals(15, resized.getHeight(), "51 × 0.3 = 15.3, 取 15 不是 16");
        }

        /**
         * 细长图缩得太狠时，按比例算出来的另一条边会是 0，而画布不接受 0。
         * 现码不拦，抛的是 {@code BufferedImage} 自己的报错——钉住是因为调用方
         * （封面、九宫格）传的宽度来自版面常量，改版面时可能踩到。
         */
        @Test
        @DisplayName("比例算出零边长时会抛，而不是给一张空图")
        void zeroDerivedSideThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ImageUtil.resizeByWidth(solid(100, 3, Color.RED), 10),
                    "3 × 0.1 = 0.3 → 0, 画布不接受 0");
        }

        @Test
        @DisplayName("宽高给 0 或负数时会抛")
        void nonPositiveSizeThrows() {
            assertThrows(IllegalArgumentException.class, () -> ImageUtil.resize(solid(10, 10, Color.RED), 0, 10));
            assertThrows(IllegalArgumentException.class, () -> ImageUtil.resize(solid(10, 10, Color.RED), 10, -1));
        }
    }

    @Nested
    @DisplayName("避让指定的点")
    class AutoSizeByLimit {
        @Test
        @DisplayName("画的位置在限制点下方时原样返回")
        void belowLimitReturnsSourceUntouched() {
            BufferedImage source = solid(100, 50, Color.RED);

            BufferedImage result = ImageUtil.autoSizeImageByLimit(source, new Point(80, 20), new Point(0, 20));

            assertSame(source, result, "不需要缩就不该重画一张, 重画会掉一次画质");
        }

        @Test
        @DisplayName("横向压根盖不到时原样返回")
        void noOverlapReturnsSourceUntouched() {
            BufferedImage source = solid(100, 50, Color.RED);

            BufferedImage result = ImageUtil.autoSizeImageByLimit(source, new Point(200, 100), new Point(0, 0));

            assertSame(source, result);
        }

        @Test
        @DisplayName("会盖住时缩到刚好不盖")
        void shrinksJustEnough() {
            BufferedImage source = solid(100, 50, Color.RED);

            // 从 x=10 画起，右边界会到 110，越过限制点 x=80 共 30
            BufferedImage result = ImageUtil.autoSizeImageByLimit(source, new Point(80, 100), new Point(10, 0));

            assertEquals(70, result.getWidth(), "100 − 30 = 70, 右边界正好落在 80");
            assertEquals(35, result.getHeight(), "高度按比例跟着走: 50 × 0.7 = 35");
            assertNotEquals(source, result);
        }

        @Test
        @DisplayName("恰好贴着限制点时算不盖，原样返回")
        void exactlyTouchingCountsAsNoOverlap() {
            BufferedImage source = solid(100, 50, Color.RED);

            BufferedImage result = ImageUtil.autoSizeImageByLimit(source, new Point(100, 100), new Point(0, 0));

            assertSame(source, result, "右边界等于限制点 x 时 xCover = 0, 不缩");
        }

        /**
         * 起画位置已经越过限制点时，算出来的宽度是 0 或负数，现码不拦。
         * 调用方（报告图的角标）目前不会走到这里，钉住是防止改写时以为「这里返回 null 也行」。
         */
        @Test
        @DisplayName("起画位置已越过限制点时会抛")
        void drawingPastTheLimitThrows() {
            BufferedImage source = solid(100, 50, Color.RED);

            assertThrows(IllegalArgumentException.class,
                    () -> ImageUtil.autoSizeImageByLimit(source, new Point(50, 100), new Point(60, 0)));
        }
    }

    @Nested
    @DisplayName("蒙版")
    class Masking {
        @Test
        @DisplayName("圆形蒙版：尺寸不变，四角挖空，中心留住")
        void circleKeepsCenterAndClearsCorners() {
            BufferedImage circle = ImageUtil.maskToCircle(solid(100, 100, Color.RED));

            assertEquals(100, circle.getWidth());
            assertEquals(100, circle.getHeight());
            assertEquals(0, alphaAt(circle, 0, 0), "左上角要被挖成全透明");
            assertEquals(0, alphaAt(circle, 99, 99), "右下角同理");
            assertEquals(OPAQUE, alphaAt(circle, 50, 50), "中心要留住");
            assertEquals(Color.RED.getRGB(), circle.getRGB(50, 50), "中心的颜色不该被蒙版改掉");
        }

        @Test
        @DisplayName("圆形蒙版对长方形也按外接椭圆挖")
        void circleMaskFollowsTheBoundingBox() {
            BufferedImage ellipse = ImageUtil.maskToCircle(solid(120, 40, Color.BLUE));

            assertEquals(120, ellipse.getWidth());
            assertEquals(40, ellipse.getHeight());
            assertEquals(0, alphaAt(ellipse, 0, 0));
            assertEquals(OPAQUE, alphaAt(ellipse, 60, 20));
            assertEquals(OPAQUE, alphaAt(ellipse, 5, 20), "长边中线上靠边的点仍在椭圆内");
        }

        @Test
        @DisplayName("圆角矩形：只挖角，边中点留住")
        void roundedRectangleClearsOnlyCorners() {
            BufferedImage rounded = ImageUtil.maskToRoundedRectangle(solid(100, 100, Color.GREEN), 40);

            assertEquals(100, rounded.getWidth());
            assertEquals(100, rounded.getHeight());
            assertEquals(0, alphaAt(rounded, 0, 0), "半径 40 时左上角落在圆角外");
            assertEquals(OPAQUE, alphaAt(rounded, 50, 0), "上边中点不该被挖掉");
            assertEquals(OPAQUE, alphaAt(rounded, 0, 50), "左边中点不该被挖掉");
            assertEquals(OPAQUE, alphaAt(rounded, 50, 50));
        }

        /**
         * 🔴 阳性对照：半径 0 时四角是留着的。
         * <p>
         * 少了它，上面那条在「蒙版根本没生效」与「蒙版把整张图都挖了」之间分不出来——
         * 前者角上不透明会红，但只有这条能证明「角透明」确实是半径带来的。
         */
        @Test
        @DisplayName("半径为零时不挖角，四角原样留着")
        void zeroRadiusKeepsCorners() {
            BufferedImage rounded = ImageUtil.maskToRoundedRectangle(solid(100, 100, Color.GREEN), 0);

            assertEquals(OPAQUE, alphaAt(rounded, 0, 0), "半径 0 就是普通矩形, 角上不该透明");
            assertEquals(Color.GREEN.getRGB(), rounded.getRGB(0, 0));
        }

        @Test
        @DisplayName("蒙版结果一律带透明通道")
        void masksProduceArgb() {
            BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);

            assertEquals(BufferedImage.TYPE_INT_ARGB, ImageUtil.maskToCircle(source).getType());
            assertEquals(BufferedImage.TYPE_INT_ARGB, ImageUtil.maskToRoundedRectangle(source, 5).getType());
        }

        @Test
        @DisplayName("蒙版不改原图")
        void masksDoNotTouchTheSource() {
            BufferedImage source = solid(50, 50, Color.RED);

            ImageUtil.maskToCircle(source);

            assertEquals(Color.RED.getRGB(), source.getRGB(0, 0), "原图的角不该被挖");
        }
    }
}
