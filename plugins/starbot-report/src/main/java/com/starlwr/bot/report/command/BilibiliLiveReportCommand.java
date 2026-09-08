package com.starlwr.bot.report.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.bilibili.command.BilibiliStreamerCommand;
import com.starlwr.bot.report.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.report.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「直播报告」命令
 * <p>
 * 拉取正在直播主播的实时数据报告，不必等到下播。
 * 挑主播的规则与「直播间数据」相同：这里配了的主播即可，不要求这里还配过报告推送。
 * <p>
 * 刻意不伪造下播事件：那会把直播状态翻成已下播，随后备用轮询发现仍在播又会推一条假开播，
 * 还会把本场统计清零。本命令只读统计数据，完全不触碰状态机。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveReportCommand extends BilibiliStreamerCommand {
    private final LiveDataService liveDataService;

    private final BilibiliLiveReportPainter painter;

    private final RevenueVisibilityService revenueVisibility;

    @Autowired
    public BilibiliLiveReportCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                     LiveDataService liveDataService,
                                     BilibiliLiveReportPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, choice);
        this.liveDataService = liveDataService;
        this.painter = painter;
        this.revenueVisibility = revenueVisibility;
    }

    @Override
    public String name() {
        return "直播报告";
    }

    @Override
    public String description() {
        return "查看正在直播主播的实时数据报告";
    }

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        Resolved resolved = resolve(context, context.arg(0));
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        if (!liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), streamer.getUid()).orElse(false)) {
            return CommandReply.of(nameOf(streamer) + " 现在没在直播");
        }

        log.info("会话 {} 请求 {} 的实时直播报告", context.getNum(), streamer.getUname());

        boolean visible = revenueVisibility.isVisible(context.getPlatform(), context.getType(), context.getNum());
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(reportParams(context, streamer), visible);

        return painter.paint(BilibiliPlatform.BILIBILI.id(),
                        new LiveStreamerInfo(streamer.getUid(), streamer.getUname(), streamer.getRoomId(), streamer.getFace()),
                        options)
                .map(CommandReply::image)
                .orElseGet(() -> CommandReply.of("报告绘制失败, 请查看日志"));
    }

    /**
     * 该主播推到本会话、且开着的报告推送参数；没有这条推送时为空，走默认版式
     */
    private JSONObject reportParams(CommandContext context, PushUser streamer) {
        return streamer.getTargets().stream()
                .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                .filter(target -> context.getPlatform().equals(target.getPlatform()))
                .filter(target -> context.getType() == target.getType())
                .filter(target -> context.getNum().equals(target.getNum()))
                .flatMap(target -> target.getMessages().stream())
                .filter(message -> !Boolean.FALSE.equals(message.getEnabled()))
                // 比解析出来的类名而不是配置里那一串：老配置写的是本处理器搬包之前的全类名，
                // 按字面比认不出来，于是这条命令悄悄改用默认版式，看起来像是推送里的版式没生效
                .filter(message -> BilibiliLiveReportPushHandler.class.getName().equals(message.handlerClassName()))
                .findFirst()
                .map(PushMessage::getParamsJsonObject)
                .orElse(null);
    }

    @Override
    public boolean groupOnly() {
        // 与其余数据查询命令同一档：查的是主播的数据，已配推送的好友会话里一样该答得上来
        return false;
    }

    @Override
    public String category() {
        return "数据查询";
    }
}
