package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.command.BilibiliStreamerCommand;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.report.painter.ReportPalettes;
import org.frostnova.nova.report.service.BilibiliLiveReportRedrawer;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveReportDeliveryService;
import org.frostnova.nova.core.service.LiveSessionDetailArchiver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「直播报告」命令
 * <p>
 * 在播时查看开播至今的数据，下播后查看上一场的整场报告。
 * 可以选择本会话已配置推送的主播，包含未启用报告推送的主播。
 * <p>
 * 刻意不伪造下播事件：那会把直播状态翻成已下播，随后备用轮询发现仍在播又会推一条假开播，
 * 还会把本场统计清零。本命令只读统计数据，完全不触碰状态机。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveReportCommand extends BilibiliStreamerCommand {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();
    private static final String DRAWING_FAILED = "报告绘制失败, 请查看日志";

    private final LiveDataService liveDataService;

    private final BilibiliLiveReportPainter painter;

    private final LiveReportDeliveryService reports;

    private final LiveSessionDetailArchiver archiver;

    private final BilibiliLiveReportRedrawer redrawer;

    /**
     * 各通道的报告图配色。回的图按发问的这个会话所在的通道画
     */
    private ReportPalettes palettes = ReportPalettes.factory();

    @Autowired
    public void setPalettes(ReportPalettes palettes) {
        this.palettes = palettes;
    }

    @Autowired
    public BilibiliLiveReportCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                     LiveDataService liveDataService, BilibiliLiveReportPainter painter,
                                     LiveReportDeliveryService reports, LiveSessionDetailArchiver archiver,
                                     BilibiliLiveReportRedrawer redrawer) {
        super(dataSource, choice);
        this.liveDataService = liveDataService;
        this.painter = painter;
        this.reports = reports;
        this.archiver = archiver;
        this.redrawer = redrawer;
    }

    @Override
    public String name() {
        return "直播报告";
    }

    @Override
    public String description() {
        return "看直播报告：直播中看到此刻，下播后看上一场";
    }

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    protected boolean autoSelectSoleLiveStreamer(CommandContext context) {
        return !context.isGroup();
    }

    @Override
    public CommandReply execute(CommandContext context) {
        Resolved resolved = resolve(context, context.arg(0));
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        PushTarget channel = context.getChannel();
        if (liveDataService.getLiveStatus(PLATFORM, streamer.getUid()).orElse(false)) {
            log.info("会话 {} 请求 {} 的实时直播报告", context.getNum(), streamer.getUname());
            var source = new LiveStreamerInfo(streamer.getUid(), unameOf(streamer), streamer.getRoomId(), streamer.getFace());
            return withNotice(resolved, painter.paint(PLATFORM, source, redrawer.commandOptions(channel),
                            palettes.forChannel(channel).palette())
                    .map(CommandReply::image)
                    .orElseGet(() -> CommandReply.of(DRAWING_FAILED)));
        }

        if (archiver.isArchivingLiveOff(PLATFORM, streamer.getUid())) {
            return CommandReply.of(nameOf(streamer) + " 刚下播，直播报告还在生成，稍后再发");
        }
        var detail = reports.latest(PLATFORM, streamer.getUid());
        if (detail.isEmpty()) {
            return CommandReply.of(nameOf(streamer) + " 还没有已结束的直播，暂时没有直播报告");
        }
        return withNotice(resolved, redrawer.channelContent(detail.get(), channel, redrawer.commandOptions(channel))
                .filter(content -> !content.isBlank())
                .map(CommandReply::of)
                .orElseGet(() -> CommandReply.of(DRAWING_FAILED)));
    }

    @Override
    public boolean groupOnly() {
        // 已配置推送的好友会话也可以查询直播报告。
        return false;
    }

    @Override
    public String category() {
        return "数据查询";
    }
}
