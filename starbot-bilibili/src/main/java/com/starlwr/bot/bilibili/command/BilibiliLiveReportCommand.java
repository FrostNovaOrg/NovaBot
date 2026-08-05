package com.starlwr.bot.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
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

    private final RevenueVisibilityService revenueVisibility;

    @Autowired
    public BilibiliLiveReportCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                     BilibiliLiveReportPainter painter, RevenueVisibilityService revenueVisibility) {
        this.dataSource = dataSource;
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
    public CommandReply execute(CommandContext context) {
        List<Subscription> living = new ArrayList<>();
        for (Subscription subscription : reportSubscribers(context)) {
            if (liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), subscription.user().getUid()).orElse(false)) {
                living.add(subscription);
            }
        }

        if (living.isEmpty()) {
            return CommandReply.of("当前没有正在直播的主播");
        }

        // 同时有多位在播时只出第一位的报告：一次回复多张大图既慢又刷屏，
        // 需要看别人的可用参数指定（后续可扩展）
        PushUser streamer = living.get(0).user();
        log.info("会话 {} 请求 {} 的实时直播报告", context.getNum(), streamer.getUname());

        // 用该会话自己配的版式，而不是一套默认值：本命令此前走的是全默认，
        // 于是界面上给这个群配的版式在这条路径上完全不生效——关掉的区块照样画出来
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(living.get(0).params(),
                revenueVisibility.isVisible(context.getPlatform(), context.getType(), context.getNum()));

        return painter.paint(LivePlatform.BILIBILI.getName(),
                        new LiveStreamerInfo(streamer.getUid(), streamer.getUname(), streamer.getRoomId(), streamer.getFace()),
                        options)
                .map(CommandReply::image)
                .orElseGet(() -> CommandReply.of("报告绘制失败, 请查看日志"));
    }

    /**
     * 找出把下播报告推送到本会话的主播，连同该条推送配置的版式参数
     * <p>
     * 只服务于确实配置了报告推送的会话：没配过的群本就不关心这些数据。
     */
    private List<Subscription> reportSubscribers(CommandContext context) {
        List<Subscription> result = new ArrayList<>();
        for (PushUser user : dataSource.getUsers(LivePlatform.BILIBILI.getName())) {
            if (Boolean.FALSE.equals(user.getEnabled()) || user.getUid() == null) {
                continue;
            }

            user.getTargets().stream()
                    .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                    .filter(target -> context.getPlatform().equals(target.getPlatform()))
                    .filter(target -> context.getType() == target.getType())
                    .filter(target -> context.getNum().equals(target.getNum()))
                    .flatMap(target -> target.getMessages().stream())
                    .filter(message -> !Boolean.FALSE.equals(message.getEnabled()))
                    .filter(message -> BilibiliLiveReportPushHandler.class.getName().equals(message.getHandler()))
                    .findFirst()
                    .ifPresent(message -> result.add(new Subscription(user, message.getParamsJsonObject())));
        }
        return result;
    }

    /**
     * 一位主播与它推给本会话的报告配置
     *
     * @param user 主播
     * @param params 该条推送的版式参数
     */
    private record Subscription(PushUser user, JSONObject params) {
    }

    @Override
    public String category() {
        return "数据查询";
    }
}
