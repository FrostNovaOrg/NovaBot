package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 动态图片绘制器
 * <p>
 * 将一条动态渲染为图片。动态接口返回的结构随类型差异极大且字段常有变动，因此绘制过程中
 * 所有取值均做空值防护：任一模块缺失只会导致该部分不被绘制，不会中断整张图片的生成。
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicPainter {
    /**
     * 图片总宽度
     */
    private static final int WIDTH = 900;

    /**
     * 初始画布高度，绘制过程中按需自动扩展
     * <p>
     * 画布是 TYPE_INT_ARGB 位图，每像素 4 字节：直接按「足够高」分配（例如 100000）会一次性
     * 占用 360 MB，在小内存 VPS 上必然耗尽内存。此处取够用的初始值，由绘图器按需扩展。
     */
    private static final int INITIAL_HEIGHT = 1200;

    /**
     * 画布圆角半径
     */
    private static final int CANVAS_RADIUS = 25;

    /**
     * 内容区左右留白
     */
    private static final int MARGIN = 35;

    /**
     * 头像尺寸
     */
    private static final int AVATAR_SIZE = 100;

    /**
     * 转发动态的原动态背景色
     */
    private static final Color ORIGIN_BACKGROUND = new Color(244, 245, 247);

    private static final Color COLOR_NAME = new Color(251, 114, 153);

    private static final Color COLOR_TIP = new Color(153, 162, 170);

    private static final Color COLOR_TEXT = new Color(51, 51, 51);

    /**
     * 底部 logo 的绘制高度
     */
    private static final int LOGO_HEIGHT = 45;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final StarBotCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final StarBotBilibiliProperties properties;

    /**
     * 缓存的 logo 图片
     */
    private volatile BufferedImage logo;

    /**
     * logo 是否已尝试加载，读取失败时也置为 true，避免每条动态都重复尝试
     */
    private volatile boolean logoLoaded;

    @Autowired
    public BilibiliDynamicPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api, StarBotBilibiliProperties properties) {
        this.factory = factory;
        this.api = api;
        this.properties = properties;
    }

    /**
     * 绘制动态图片
     * @param dynamic 动态
     * @return 图片的 Base64 编码，绘制失败时返回空
     */
    public Optional<String> paint(Dynamic dynamic) {
        try {
            CommonPainter painter = factory.create(WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(MARGIN, MARGIN);

            drawHeader(painter, dynamic);
            drawContent(painter, dynamic, WIDTH - MARGIN * 2);

            if (dynamic.isForward() && dynamic.getOrigin() != null) {
                drawOrigin(painter, dynamic.getOrigin());
            }

            painter.movePos(0, 20);

            if (properties.getDynamic().isDrawLogo()) {
                drawLogo(painter);
            }

            // 绘制底部版权信息，AGPL 要求保留对上游的署名
            painter.drawCopyright(MARGIN);
            painter.movePos(0, 10);

            // 该调用同时把画布裁剪至实际内容高度并铺上背景，必须在全部内容绘制完毕后执行
            painter.createSolidRoundedRectangleBackground(Color.WHITE, CANVAS_RADIUS);

            return properties.getDynamic().isAutoSaveImage()
                    ? painter.saveAndGetBase64(imagePath(dynamic))
                    : painter.base64();
        } catch (Exception e) {
            log.error("绘制动态 {} 的图片失败", dynamic.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * 绘制头部：头像、昵称与发布时间
     */
    private void drawHeader(CommonPainter painter, Dynamic dynamic) {
        int textX = MARGIN + AVATAR_SIZE + 25;
        int top = painter.getY();

        dynamic.getAuthorFace()
                .flatMap(url -> api.getBilibiliImage(atSize(url, AVATAR_SIZE)))
                .map(face -> ImageUtil.maskToCircle(ImageUtil.resize(face, AVATAR_SIZE, AVATAR_SIZE)))
                .ifPresent(face -> painter.drawImage(face, new Point(MARGIN, top)));

        painter.drawSection(dynamic.getAuthorName().orElse("未知用户"), COLOR_NAME, new Point(textX, top + 8));
        painter.drawTip(dynamic.getPublishTime().map(TIME_FORMATTER::format).orElse(""), COLOR_TIP, new Point(textX, top + 58));

        painter.setPos(MARGIN, top + AVATAR_SIZE + 20);
    }

    /**
     * 绘制动态正文
     * @param contentWidth 内容可用宽度
     */
    private void drawContent(CommonPainter painter, Dynamic dynamic, int contentWidth) {
        JSONObject modules = dynamic.getModules();
        if (modules == null) {
            return;
        }

        // drawTextMultiLine 的最后一个参数是距画布右边缘的留白，由内容宽度换算
        int marginRight = painter.getImage().getWidth() - painter.getX() - contentWidth;

        String text = extractText(modules);
        if (StringUtil.isNotBlank(text)) {
            painter.drawTextMultiLine(text, COLOR_TEXT, Math.max(0, marginRight));
            painter.movePos(0, 10);
        }

        drawMajor(painter, modules.getJSONObject("module_dynamic"), contentWidth);
    }

    /**
     * 提取动态的文字内容
     */
    private String extractText(JSONObject modules) {
        JSONObject moduleDynamic = modules.getJSONObject("module_dynamic");
        if (moduleDynamic == null) {
            return "";
        }

        JSONObject desc = moduleDynamic.getJSONObject("desc");
        if (desc != null && StringUtil.isNotBlank(desc.getString("text"))) {
            return desc.getString("text");
        }

        // 视频、专栏等类型的文字位于 major 内部
        JSONObject major = moduleDynamic.getJSONObject("major");
        if (major == null) {
            return "";
        }

        for (String key : new String[]{"archive", "article", "opus", "live_rcmd"}) {
            JSONObject node = major.getJSONObject(key);
            if (node == null) {
                continue;
            }

            String title = node.getString("title");
            if (StringUtil.isNotBlank(title)) {
                return title;
            }
        }

        return "";
    }

    /**
     * 绘制动态的主体内容：图片、视频封面或专栏封面
     */
    private void drawMajor(CommonPainter painter, JSONObject moduleDynamic, int contentWidth) {
        if (moduleDynamic == null) {
            return;
        }

        JSONObject major = moduleDynamic.getJSONObject("major");
        if (major == null) {
            return;
        }

        String type = major.getString("type");
        if (type == null) {
            return;
        }

        switch (type) {
            case "MAJOR_TYPE_DRAW" -> drawPictures(painter, major.getJSONObject("draw"), contentWidth);
            case "MAJOR_TYPE_ARCHIVE" -> drawCover(painter, major.getJSONObject("archive"), "cover", contentWidth);
            case "MAJOR_TYPE_ARTICLE" -> drawArticleCover(painter, major.getJSONObject("article"), contentWidth);
            case "MAJOR_TYPE_LIVE_RCMD" -> drawCover(painter, major.getJSONObject("live_rcmd"), "cover", contentWidth);
            case "MAJOR_TYPE_OPUS" -> drawOpus(painter, major.getJSONObject("opus"), contentWidth);
            default -> log.debug("未处理的动态主体类型: {}", type);
        }
    }

    /**
     * 绘制图片动态中的所有图片
     */
    private void drawPictures(CommonPainter painter, JSONObject draw, int contentWidth) {
        if (draw == null) {
            return;
        }

        JSONArray items = draw.getJSONArray("items");
        if (items == null || items.isEmpty()) {
            return;
        }

        List<String> urls = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && StringUtil.isNotBlank(item.getString("src"))) {
                urls.add(atSize(item.getString("src"), contentWidth));
            }
        }

        // 单图铺满内容宽度，多图按九宫格排布
        if (urls.size() == 1) {
            api.getBilibiliImage(urls.get(0))
                    .map(image -> ImageUtil.resizeByWidth(image, contentWidth))
                    .ifPresent(image -> drawWithRoundedCorner(painter, image));
            return;
        }

        int columns = urls.size() == 2 || urls.size() == 4 ? 2 : 3;
        int gap = 10;
        int cell = (contentWidth - gap * (columns - 1)) / columns;
        int rows = (urls.size() + columns - 1) / columns;

        List<Optional<BufferedImage>> images = api.asyncGetBilibiliImages(urls).join();

        // 基准坐标只取一次：drawImage 指定坐标时不会推进游标，若在循环中读取 getY 会与行偏移重复累加
        int startX = painter.getX();
        int startY = painter.getY();

        // 画布按需扩展，绘制前先确保高度足够容纳整个网格
        painter.expandHeightIfNeeded(startY + rows * (cell + gap));

        for (int i = 0; i < images.size(); i++) {
            int x = startX + (i % columns) * (cell + gap);
            int y = startY + (i / columns) * (cell + gap);

            images.get(i)
                    .map(image -> ImageUtil.maskToRoundedRectangle(ImageUtil.resize(image, cell, cell), 10))
                    .ifPresent(image -> painter.drawImage(image, new Point(x, y)));
        }

        painter.setPos(startX, startY + rows * (cell + gap));
    }

    /**
     * 绘制视频或直播封面
     */
    private void drawCover(CommonPainter painter, JSONObject node, String key, int contentWidth) {
        if (node == null) {
            return;
        }

        String cover = node.getString(key);
        if (StringUtil.isBlank(cover)) {
            return;
        }

        api.getBilibiliImage(atSize(cover, contentWidth))
                .map(image -> ImageUtil.resizeByWidth(image, contentWidth))
                .ifPresent(image -> drawWithRoundedCorner(painter, image));
    }

    /**
     * 绘制专栏封面，专栏的封面位于数组中
     */
    private void drawArticleCover(CommonPainter painter, JSONObject article, int contentWidth) {
        if (article == null) {
            return;
        }

        JSONArray covers = article.getJSONArray("covers");
        if (covers == null || covers.isEmpty()) {
            return;
        }

        api.getBilibiliImage(atSize(covers.getString(0), contentWidth))
                .map(image -> ImageUtil.resizeByWidth(image, contentWidth))
                .ifPresent(image -> drawWithRoundedCorner(painter, image));
    }

    /**
     * 绘制图文动态
     */
    private void drawOpus(CommonPainter painter, JSONObject opus, int contentWidth) {
        if (opus == null) {
            return;
        }

        JSONArray pics = opus.getJSONArray("pics");
        if (pics == null || pics.isEmpty()) {
            return;
        }

        for (int i = 0; i < pics.size(); i++) {
            JSONObject pic = pics.getJSONObject(i);
            if (pic == null || StringUtil.isBlank(pic.getString("url"))) {
                continue;
            }

            api.getBilibiliImage(atSize(pic.getString("url"), contentWidth))
                    .map(image -> ImageUtil.resizeByWidth(image, contentWidth))
                    .ifPresent(image -> {
                        drawWithRoundedCorner(painter, image);
                        painter.movePos(0, 10);
                    });
        }
    }

    /**
     * 绘制被转发的原动态，以浅色背景区分
     * <p>
     * 原动态先画在独立画布上，再由 createSolidRoundedRectangleBackground 一步完成
     * 「裁剪至内容高度」与「铺上圆角背景」，最后整体贴回主画布。直接在主画布上先画内容
     * 再补背景会把内容盖住——Java2D 无法在已有内容之下作画。
     */
    private void drawOrigin(CommonPainter painter, Dynamic origin) {
        int boxWidth = WIDTH - MARGIN * 2;
        int padding = 20;

        CommonPainter box = factory.create(boxWidth, INITIAL_HEIGHT, true);
        box.setPos(padding, padding);
        box.drawText("@" + origin.getAuthorName().orElse("未知用户"), COLOR_TIP);
        box.movePos(0, 8);
        drawContent(box, origin, boxWidth - padding * 2);
        box.movePos(0, padding);
        box.createSolidRoundedRectangleBackground(ORIGIN_BACKGROUND, 12);

        BufferedImage image = box.getBufferedImage();

        painter.movePos(0, 15);
        painter.drawImage(image, new Point(MARGIN, painter.getY()));
        painter.setPos(MARGIN, painter.getY() + image.getHeight() + 10);
    }

    /**
     * 绘制带圆角的图片并下移绘制位置
     */
    private void drawWithRoundedCorner(CommonPainter painter, BufferedImage image) {
        painter.drawImage(ImageUtil.maskToRoundedRectangle(image, 10));
    }

    /**
     * 绘制底部 logo
     * <p>
     * logo 图片只在首次绘制时从类路径读取一次并缓存，避免每条动态都重复解码。
     */
    private void drawLogo(CommonPainter painter) {
        BufferedImage logo = logo();
        if (logo == null) {
            return;
        }

        int top = painter.getY();
        painter.drawImage(logo, new Point(MARGIN, top));
        painter.setPos(MARGIN, top + logo.getHeight() + 10);
    }

    /**
     * 读取并缓存 logo 图片
     * @return logo 图片，读取失败时返回 null
     */
    private BufferedImage logo() {
        if (logoLoaded) {
            return logo;
        }

        synchronized (this) {
            if (!logoLoaded) {
                try (InputStream stream = getClass().getResourceAsStream("/logo.png")) {
                    if (stream != null) {
                        logo = ImageUtil.resizeByHeight(ImageIO.read(stream), LOGO_HEIGHT);
                    } else {
                        log.warn("类路径下未找到 logo.png, 已跳过 logo 绘制");
                    }
                } catch (Exception e) {
                    log.warn("读取 logo.png 失败, 已跳过 logo 绘制: {}", e.getMessage());
                }
                logoLoaded = true;
            }
        }

        return logo;
    }

    /**
     * 为图片地址附加缩放参数
     * <p>
     * 哔哩哔哩的图床支持通过 @{width}w 参数按需返回缩放后的图片，直接取原图会浪费带宽，
     * 在动态含多张大图时尤其明显。
     * @param url 图片地址
     * @param width 期望宽度
     * @return 附加了缩放参数的地址
     */
    private String atSize(String url, int width) {
        if (StringUtil.isBlank(url) || url.contains("@")) {
            return url;
        }

        return url + "@" + width + "w.webp";
    }

    /**
     * 构造动态图片的保存路径
     */
    private String imagePath(Dynamic dynamic) {
        return "images/dynamic/" + dynamic.getId() + "_" + Instant.now().getEpochSecond() + ".png";
    }
}
