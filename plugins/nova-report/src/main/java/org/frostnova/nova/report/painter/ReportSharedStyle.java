package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.util.ImageUtil;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 各报告图共用的版面件：宽度与留边、页头、标识图、配色
 * <p>
 * 下播报告先有的这一套，打赏播报图随后照用。此前页头、标识图、配色都是
 * {@link BilibiliLiveReportPainter} 一家的私有件，第二张图要用就只得抄一份——
 * 两份各改各的，图与图会慢慢长得不像一个程序出的。收在这里之后只有一份：
 * 改宽度、换配色，所有报告图一起变。
 * <p>
 * 下播报告的旧常量与私有方法改为引用本类，画出的字节不变；新图直接取用。
 */
@Slf4j
public final class ReportSharedStyle {
    /**
     * 图片总宽度，各报告图一致
     */
    public static final int WIDTH = 900;

    /**
     * 画布圆角半径
     */
    public static final int CANVAS_RADIUS = 25;

    /**
     * 内容区左右留白
     */
    public static final int MARGIN = 35;

    /**
     * 内容区宽度
     */
    public static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;

    /**
     * 页头头像尺寸
     */
    public static final int AVATAR_SIZE = 100;

    /**
     * 底部标识的绘制高度
     */
    public static final int LOGO_HEIGHT = 45;

    /**
     * 名字的主题粉
     */
    public static final Color COLOR_NAME = new Color(251, 114, 153);

    /**
     * 提示的浅灰
     */
    public static final Color COLOR_TIP = new Color(153, 162, 170);

    /**
     * 正文深灰
     */
    public static final Color COLOR_TEXT = new Color(51, 51, 51);

    private ReportSharedStyle() {
    }

    /**
     * 无封面时的页头：圆形头像、名字、名字下面一行副标题，画完把坐标挪到页头下方
     * <p>
     * 与下播报告封面缺失时的退化版式同一份。下播报告的副标题是
     * 「直播报告 · 起止时间」，打赏播报是「打赏播报 · 时刻」，说的都是自己那件事。
     *
     * @param painter 绘制器
     * @param source 主播信息
     * @param face 已取好的圆形头像，取不到时传 {@code null}（页头只剩名字）
     * @param subtitle 名字下面那一行
     */
    public static void drawSimpleHeader(CommonPainter painter, LiveStreamerInfo source,
                                        BufferedImage face, String subtitle) {
        int top = painter.getY();
        int textX = MARGIN + AVATAR_SIZE + 25;
        if (face != null) {
            painter.drawImage(face, new Point(MARGIN, top));
        }
        painter.drawSection(unameWithin(painter, source, textX), COLOR_NAME, new Point(textX, top + 8));
        painter.drawTip(subtitle, COLOR_TIP, new Point(textX, top + 58));
        painter.setPos(MARGIN, top + AVATAR_SIZE + 30);
    }

    /**
     * 取主播名，并按版心剩下的宽度截断
     * <p>
     * B 站昵称<b>没有长度上限</b>，而页头这一行原先一个字都不截。常见昵称都短，
     * 没撞上不等于没有：实测 30 个字的昵称会顶出画布 338 像素。
     *
     * @param textX 这一行的起始 x，可用宽度是从这里到版心右边界
     */
    public static String unameWithin(CommonPainter painter, LiveStreamerInfo source, int textX) {
        String uname = Optional.ofNullable(source.getUname()).orElse("未知主播");

        return painter.truncateToWidth(
                new TextWithStyle(uname, CommonPainter.SECTION_FONT_SIZE, COLOR_NAME, Font.BOLD),
                WIDTH - MARGIN - textX);
    }

    /**
     * 主播头像：取地址、下图、裁成圆形
     * <p>
     * 地址事件里没带时通过接口补取。取不到头像不是画不下去的理由，页头退化为只剩名字。
     */
    public static BufferedImage faceImage(BilibiliApiUtil api, LiveStreamerInfo source) {
        return Optional.ofNullable(resolveFace(api, source))
                .flatMap(url -> api.getBilibiliImage(atSize(url, AVATAR_SIZE)))
                .map(image -> ImageUtil.maskToCircle(ImageUtil.resize(image, AVATAR_SIZE, AVATAR_SIZE)))
                .orElse(null);
    }

    /**
     * 主播头像地址：事件中缺失时通过接口取
     */
    private static String resolveFace(BilibiliApiUtil api, LiveStreamerInfo source) {
        if (StringUtil.isNotBlank(source.getFace())) {
            return source.getFace();
        }
        try {
            return api.getUpInfoByUid(source.getUid()).getFace();
        } catch (Exception e) {
            log.debug("获取 uid {} 的头像失败: {}", source.getUid(), e.getMessage());
            return null;
        }
    }

    /**
     * 为图片地址附加指定宽度的缩放参数
     * <p>
     * 下原图既慢又浪费：页头头像只有 100px，排行榜头像只有 32px，一场直播的榜单动辄数十人。
     * 地址里已带 @ 参数的不再追加
     */
    public static String atSize(String url, int size) {
        if (StringUtil.isBlank(url) || url.contains("@")) {
            return url;
        }
        return url + "@" + size + "w.webp";
    }

    /**
     * 金额格式化（分入参）：保留一位小数，整数金额省略小数位
     * <p>
     * 打赏事件里金额一律按「分」整数记账，显示这一刻才换回元，与下播报告的写法同一形
     */
    public static String yuanFromFen(long amountFen) {
        long rounded = Math.round(amountFen / 10.0);
        if (rounded % 10 == 0) {
            return String.valueOf(rounded / 10);
        }
        return String.valueOf(rounded / 10.0);
    }

    /**
     * 底部自定义标识图片：读盘、按高度缩放、画在版心左缘
     * <p>
     * 一个绘制器实例只读一次盘，读了失败也记为读过——路径写错时免得每张图
     * 都重试一遍并刷一条警告。各报告图（下播报告、打赏播报）各持一个实例。
     * <p>
     * 读哪个路径由调用方传入：下播报告用「报告自定义标识图片」这一项，
     * 动态图是另一份配置、另一套画法，不在此列。
     */
    public static final class Logo {
        private volatile boolean loaded;

        private BufferedImage image;

        /**
         * 绘制底部标识，未配置或读取失败时跳过
         */
        public void draw(CommonPainter painter, NovaBilibiliProperties properties) {
            BufferedImage logo = logo(properties);
            if (logo == null) {
                return;
            }

            int top = painter.getY();
            painter.drawImage(logo, new Point(MARGIN, top));
            painter.setPos(MARGIN, top + logo.getHeight() + 10);
        }

        /**
         * 读取并缓存标识图片
         *
         * @return 标识图片，未配置或读取失败时为 {@code null}
         */
        private BufferedImage logo(NovaBilibiliProperties properties) {
            if (loaded) {
                return image;
            }

            synchronized (this) {
                if (!loaded) {
                    String path = properties.getLive().getReportLogoPath();
                    if (StringUtil.isNotBlank(path)) {
                        try {
                            Path file = Path.of(path);
                            if (Files.isReadable(file)) {
                                image = ImageUtil.resizeByHeight(ImageIO.read(file.toFile()), LOGO_HEIGHT);
                            } else {
                                log.warn("报告的标识图片 {} 不存在或不可读, 已跳过绘制", path);
                            }
                        } catch (Exception e) {
                            log.warn("读取报告的标识图片 {} 失败, 已跳过绘制: {}", path, e.getMessage());
                        }
                    }
                    loaded = true;
                }
            }

            return image;
        }
    }
}
