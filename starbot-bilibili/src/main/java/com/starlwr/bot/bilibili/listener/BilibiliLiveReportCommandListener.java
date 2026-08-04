package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「直播报告」消息命令
 * <p>
 * 在群里发送「直播报告」即可拉取当前正在直播主播的实时数据报告，
 * 不必等到下播。仅响应**配置了下播报告推送的群**，其他会话一律不理会；
 * 同一群有冷却时间，防止刷屏。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveReportCommandListener {
    /**
     * 命令文本
     */
    private static final String COMMAND = "直播报告";

    /**
     * 同一群的触发冷却时间
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private final AbstractDataSource dataSource;

    private final LiveDataService liveDataService;

    private final BilibiliLiveReportPainter painter;

    private final StarBotMessageSender sender;

    /**
     * 各群最近一次触发时间
     */
    private final Map<String, Instant> lastTriggered = new ConcurrentHashMap<>();

    @Autowired
    public BilibiliLiveReportCommandListener(AbstractDataSource dataSource, LiveDataService liveDataService,
                                             BilibiliLiveReportPainter painter, StarBotMessageSender sender) {
        this.dataSource = dataSource;
        this.liveDataService = liveDataService;
        this.painter = painter;
        this.sender = sender;
    }

    @EventListener(StarBotRemoteMessageEvent.class)
    public void onRemoteMessage(StarBotRemoteMessageEvent event) {
        if (!"group".equals(event.getMessageType()) || event.getNum() == null
                || event.getText() == null || !COMMAND.equals(event.getText().trim())) {
            return;
        }

        List<PushUser> subscribers = findReportSubscribers(event.getPlatform(), event.getNum());
        if (subscribers.isEmpty()) {
            // 该群未配置下播报告推送，不属于本命令的服务范围，保持沉默
            return;
        }

        String cooldownKey = event.getPlatform() + ":" + event.getNum();
        Instant last = lastTriggered.get(cooldownKey);
        if (last != null && Instant.now().isBefore(last.plus(COOLDOWN))) {
            log.info("群 {} 的直播报告命令处于冷却期, 已忽略", event.getNum());
            return;
        }
        lastTriggered.put(cooldownKey, Instant.now());

        List<PushUser> living = subscribers.stream()
                .filter(user -> liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), user.getUid()).orElse(false))
                .toList();

        if (living.isEmpty()) {
            reply(event, "当前没有正在直播的主播");
            return;
        }

        for (PushUser user : living) {
            log.info("群 {} 请求 {} 的实时直播报告", event.getNum(), user.getUname());
            painter.paint(LivePlatform.BILIBILI.getName(), new LiveStreamerInfo(user.getUid(), user.getUname(), user.getRoomId(), user.getFace()))
                    .ifPresentOrElse(
                            base64 -> reply(event, "{image_base64=" + base64 + "}"),
                            () -> reply(event, "报告绘制失败, 请查看日志"));
        }
    }

    /**
     * 找出把下播报告推送到指定群的哔哩哔哩推送用户
     */
    private List<PushUser> findReportSubscribers(String platform, Long num) {
        List<PushUser> result = new ArrayList<>();
        for (PushUser user : dataSource.getUsers(LivePlatform.BILIBILI.getName())) {
            if (Boolean.FALSE.equals(user.getEnabled()) || user.getUid() == null) {
                continue;
            }

            boolean subscribed = user.getTargets().stream()
                    .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                    .filter(target -> platform.equals(target.getPlatform()))
                    .filter(target -> PushTargetType.GROUP == target.getType())
                    .filter(target -> num.equals(target.getNum()))
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

    /**
     * 向命令来源群发送回复
     */
    private void reply(StarBotRemoteMessageEvent event, String content) {
        if (StringUtil.isBlank(content)) {
            return;
        }
        Message.create(event.getPlatform(), PushTargetType.GROUP, event.getNum(), content).forEach(sender::send);
    }
}
