package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.handler.PushHandlerSupport;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 下播打赏播报绘制器
 * <p>
 * 主播不在播时攒下的那一阵上舰与礼物，画成与下播报告同一套模板的一张图：
 * 同宽、同页头（头像、名字、副标题写这一阵发出的时刻）、同标识图与底部版权。
 * 正文逐人一段：观众名字，上舰的写开通或续费了哪一级，送礼的逐样写礼物名×数量。
 * <p>
 * 金额可见性是画图入参而不是图里自行判断的：同一阵推给主播私聊与推给大群
 * 是两次画图，该不该带金额相反，该说的事相同。不可见时一个金额都不画。
 */
@Slf4j
@NovaComponent
public class BilibiliOfflineRewardDigestPainter {
    /**
     * 初始画布高度，绘制过程中按需自动扩展
     */
    private static final int INITIAL_HEIGHT = 900;

    /**
     * 副标题里的时刻写法，与下播报告页头同一形
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 每人一段的段间距
     */
    private static final int PERSON_GAP = 18;

    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final NovaBilibiliProperties properties;

    /**
     * 底部标识图片只读一次盘，读过的结论这一阵与下一阵共用
     */
    private final ReportSharedStyle.Logo logoDrawer = new ReportSharedStyle.Logo();

    @Autowired
    public BilibiliOfflineRewardDigestPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                              NovaBilibiliProperties properties) {
        this.factory = factory;
        this.api = api;
        this.properties = properties;
    }

    /**
     * 画这一阵的播报图
     *
     * @param event 攒好的一阵
     * @param showRevenue 这个会话金额可不可见
     * @return 图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paint(BilibiliOfflineRewardDigestEvent event, boolean showRevenue) {
        try {
            CommonPainter painter = factory.create(ReportSharedStyle.WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(ReportSharedStyle.MARGIN, ReportSharedStyle.MARGIN);

            BufferedImage face = ReportSharedStyle.faceImage(api, event.getSource());
            ReportSharedStyle.drawSimpleHeader(painter, event.getSource(), face,
                    "打赏播报 · " + TIME_FORMATTER.format(Instant.ofEpochMilli(event.getTimestamp())));

            for (BilibiliOfflineRewardDigestEvent.Contribution person : event.getContributions()) {
                drawPerson(painter, person, showRevenue);
            }

            painter.movePos(0, 20);
            logoDrawer.draw(painter, properties);
            painter.drawCopyright(ReportSharedStyle.MARGIN);
            painter.movePos(0, 10);

            // 该调用同时把画布裁剪至实际内容高度并铺上背景，必须在全部内容绘制完毕后执行
            painter.createSolidRoundedRectangleBackground(Color.WHITE, ReportSharedStyle.CANVAS_RADIUS);

            return painter.base64();
        } catch (Exception e) {
            log.error("绘制 {} 的打赏播报图失败", event.getSource().getUname(), e);
            return Optional.empty();
        }
    }

    /**
     * 这一阵播报的<b>文字版</b>，画图失败时顶上
     * <p>
     * 与下播报告同一条退路：图画不出来时这一阵的感谢不能跟着消失。
     * 文字就是此前默认模板的那句感谢，原样保留——它经过一版真机使用，
     * 用词（不提「下播」、按人一段、金额进括号）是验证过的。
     * 金额可见性照画图那一路走：降级不是放宽口径的理由。
     *
     * @param event 攒好的一阵
     * @param showRevenue 这个会话金额可不可见
     * @return 文字版播报
     */
    public String textDigest(BilibiliOfflineRewardDigestEvent event, boolean showRevenue) {
        return "感谢 " + renderList(event, showRevenue) + "，"
                + PushHandlerSupport.resolveUname(api, event.getSource()) + " 都收到啦"
                + "\n\n（播报图片绘制失败，本条为文字版）";
    }

    /**
     * 把够格的人拼成一句感谢，供 {@code {list}} 占位符与文字退路共用
     * <p>
     * 一人一段，段内先写上舰、再写礼物；段与段之间用顿号。
     * 金额可见性只影响括号里的数字，谁做了什么两边都写。
     */
    public String renderList(BilibiliOfflineRewardDigestEvent event, boolean showRevenue) {
        return event.getContributions().stream()
                .map(person -> renderPerson(person, showRevenue))
                .collect(Collectors.joining("、"));
    }

    /**
     * 一个人拼成文字版的一段：名字打头，其后的事用逗号连起来。
     * 行内容与画图那一路同出 {@link #bodyLines}——同一阵的图与文字说的是同一句话
     */
    private String renderPerson(BilibiliOfflineRewardDigestEvent.Contribution person, boolean showRevenue) {
        List<String> lines = bodyLines(person, showRevenue);
        return lines.get(0) + " " + String.join("，", lines.subList(1, lines.size()));
    }

    /**
     * 画一个人的一段：名字一行，上舰与礼物各一行
     * <p>
     * 画进图里的每一行都出自 {@link #bodyLines}——金额可不可见这一问在那一处就有答案，
     * 不必等图画出来再从像素里找。
     */
    private void drawPerson(CommonPainter painter, BilibiliOfflineRewardDigestEvent.Contribution person,
                            boolean showRevenue) {
        List<String> lines = bodyLines(person, showRevenue);

        painter.drawTextWithStyle(List.of(new TextWithStyle(lines.get(0),
                CommonPainter.TEXT_FONT_SIZE, ReportSharedStyle.COLOR_NAME, Font.BOLD)));
        for (int i = 1; i < lines.size(); i++) {
            // 礼物多的观众一行画不下，按版心宽度折行
            painter.drawTextMultiLine(lines.get(i), ReportSharedStyle.COLOR_TEXT, ReportSharedStyle.MARGIN);
        }
        painter.movePos(0, PERSON_GAP);
    }

    /**
     * 一个人画进图里的几行字：首行名字，其后上舰一行、礼物一行
     * <p>
     * 金额可见时上舰行带「（¥x）」、礼物行带「（合计 ¥x）」；不可见时一个金额都没有。
     *
     * @param person 这一位
     * @param showRevenue 这个会话金额可不可见
     * @return 行的列表，至少一行
     */
    static List<String> bodyLines(BilibiliOfflineRewardDigestEvent.Contribution person, boolean showRevenue) {
        List<String> lines = new ArrayList<>();
        lines.add(StringUtil.isBlank(person.getUname()) ? String.valueOf(person.getUid()) : person.getUname());

        if (person.getGuardLevel() != null) {
            String text = guardVerb(person.getOperateType()) + guardLevelName(person.getGuardLevel());
            if (showRevenue && person.getGuardAmountFen() != null) {
                text += "（¥" + ReportSharedStyle.yuanFromFen(person.getGuardAmountFen()) + "）";
            }
            lines.add(text);
        }

        List<BilibiliOfflineRewardDigestEvent.GiftLine> gifts = person.getGifts();
        if (gifts != null && !gifts.isEmpty()) {
            String text = "送了 " + gifts.stream()
                    .map(line -> line.name() + "×" + line.count())
                    .collect(Collectors.joining("、"));
            if (showRevenue && person.getGiftAmountFen() != null) {
                text += "（合计 ¥" + ReportSharedStyle.yuanFromFen(person.getGiftAmountFen()) + "）";
            }
            lines.add(text);
        }

        return lines;
    }

    private static String guardLevelName(int level) {
        return switch (level) {
            case 1 -> "总督";
            case 2 -> "提督";
            case 3 -> "舰长";
            default -> "大航海";
        };
    }

    private static String guardVerb(GuardOperateType operateType) {
        return switch (operateType == null ? GuardOperateType.UNKNOWN : operateType) {
            case ACTIVATION -> "开通了";
            case RENEWAL -> "续费了";
            case UNKNOWN -> "上了";
        };
    }
}
