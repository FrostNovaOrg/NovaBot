package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * 「直播报告」命令
 * <p>
 * 拉取正在直播主播的实时数据报告，不必等到下播。
 * <p>
 * 刻意不伪造下播事件：那会把直播状态翻成已下播，随后备用轮询发现仍在播又会推一条假开播，
 * 还会把本场统计清零。本命令只读统计数据，完全不触碰状态机。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveReportCommand implements StarBotCommand {
    private final AbstractDataSource dataSource;

    private final LiveDataService liveDataService;

    private final BilibiliLiveReportPainter painter;

    @Autowired
    public BilibiliLiveReportCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                     BilibiliLiveReportPainter painter) {
        this.dataSource = dataSource;
        this.liveDataService = liveDataService;
        this.painter = painter;
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
    public CommandReply execute(CommandContext context) {
        List<PushUser> living = new ArrayList<>();
        for (PushUser user : reportSubscribers(context)) {
            if (liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), user.getUid()).orElse(false)) {
                living.add(user);
            }
        }

        if (living.isEmpty()) {
            return CommandReply.of("当前没有正在直播的主播");
        }

        // 同时有多位在播时只出第一位的报告：一次回复多张大图既慢又刷屏，
        // 需要看别人的可用参数指定（后续可扩展）
        PushUser streamer = living.get(0);
        log.info("会话 {} 请求 {} 的实时直播报告", context.getNum(), streamer.getUname());

        return painter.paint(LivePlatform.BILIBILI.getName(),
                        new LiveStreamerInfo(streamer.getUid(), streamer.getUname(), streamer.getRoomId(), streamer.getFace()))
                .map(CommandReply::image)
                .orElseGet(() -> CommandReply.of("报告绘制失败, 请查看日志"));
    }

    /**
     * 找出把下播报告推送到本会话的主播
     * <p>
     * 只服务于确实配置了报告推送的会话：没配过的群本就不关心这些数据。
     */
    private List<PushUser> reportSubscribers(CommandContext context) {
        List<PushUser> result = new ArrayList<>();
        for (PushUser user : dataSource.getUsers(LivePlatform.BILIBILI.getName())) {
            if (Boolean.FALSE.equals(user.getEnabled()) || user.getUid() == null) {
                continue;
            }

            boolean subscribed = user.getTargets().stream()
                    .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                    .filter(target -> context.getPlatform().equals(target.getPlatform()))
                    .filter(target -> context.getType() == target.getType())
                    .filter(target -> context.getNum().equals(target.getNum()))
                    .flatMap(target -> target.getMessages().stream())
                    .filter(message -> !Boolean.FALSE.equals(message.getEnabled()))
                    .map(PushMessage::getHandler)
                    .anyMatch(handler -> BilibiliLiveReportPushHandler.class.getName().equals(handler));

            if (subscribed) {
                result.add(user);
            }
        }
        return result;
    }

    @Override
    public String category() {
        return "数据查询";
    }
}
