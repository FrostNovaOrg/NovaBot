package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * StarBot 监听外部事件触发事件处理
 * <p>
 * 静音时段与「已暂停推送」在这里就把整个事件挡下来，而不是让它一路展开成 N 条消息
 * 再逐条被发送器丢掉。两件事因此才做得到：
 * <ul>
 *   <li><b>时间线上只记一条</b>，且这一条说得出<b>是谁的通知、丢给了几个会话</b>——
 *       这两样只有在展开之前才知道。逐条记的话，一次开播会在时间线上刷出十几行，
 *       每行只说得出「群 12345」，而使用者想知道的是「刚才那场开播的通知没发出去」。</li>
 *   <li><b>不去调平台接口</b>。开播处理器要拉一次直播间标题与封面，静音期间那一趟纯属白跑。</li>
 * </ul>
 * ⚠️ <b>发送器那一道闸没有撤，两道闸问的也不是同一件事。</b>这一道只管走事件分发这条路的推送；
 * 命令回复、告警、首推提示都不经过这里，它们仍然由
 * {@link com.starlwr.bot.core.sender.StarBotMessageSender#send} 那一道拦下并各记一条。
 * 拆掉本类这一道的表现是<b>时间线从聚合的一条变回逐条、平台接口白跑一趟</b>，不是「静音失灵」，
 * 所以两道各有各的判据、可分别掰红。
 */
@Slf4j
@Component
public class StarBotHandlerListener {
    private final AbstractDataSource dataSource;

    private final PushGate pushGate;

    private final TimelineWriter timeline;

    @Autowired
    public StarBotHandlerListener(AbstractDataSource dataSource, PushGate pushGate, TimelineWriter timeline) {
        this.dataSource = dataSource;
        this.pushGate = pushGate;
        this.timeline = timeline;
    }

    /**
     * 调用事件处理器处理外部事件
     * @param event 事件
     */
    @Order(0)
    @EventListener
    public void onStarBotExternalBaseEvent(StarBotExternalBaseEvent event) {
        Optional<PushUser> optionalUser = dataSource.getUser(event.getPlatform(), event.getSource().getUid());
        if (optionalUser.isEmpty()) {
            return;
        }

        String eventClass = event.getClass().getName();

        PushUser user = optionalUser.get();
        if (!pushGate.allowed()) {
            recordDrop(event, user);
            return;
        }

        for (PushTarget target : user.getTargets()) {
            for (PushMessage message : target.getMessages()) {
                if (handles(event, message) && message.getHandlerInstance() instanceof NovaEventHandler handler) {
                    try {
                        handler.handle(event, message);
                    } catch (Exception e) {
                        log.error("事件处理器 {} 处理事件 {} 异常", handler.getClass().getName(), eventClass, e);
                    }
                }
            }
        }
    }

    /**
     * 这条推送配置认不认这个事件
     * <p>
     * 只有一份：分发按它挑出要处理的那几条，被拦下时也按它数「丢了几个会话」。
     * 各写一份的话，改了分发这一处而漏了计数那一处，<b>时间线上的目标数就与真会发出去的条数
     * 对不上，而对不上这件事没有任何现象</b>——那个数看起来永远像是对的。
     */
    private boolean handles(StarBotExternalBaseEvent event, PushMessage message) {
        return event.getClass().equals(message.getEventClass())
                && message.getHandlerInstance() instanceof NovaEventHandler;
    }

    /**
     * 在时间线上为这一次被拦下的推送记一条
     * <p>
     * <b>没有目标认领这个事件时什么也不记。</b>那种事件本来就不会推，为它记一条「丢弃」
     * 等于凭空造一条坏消息——而直播间里每分钟都有事件，那一条会把时间线淹掉。
     * 🔴 <b>「什么都没丢」与「丢了一堆」在一条不带数的记录上长得一样</b>，所以目标数要现算。
     */
    private void recordDrop(StarBotExternalBaseEvent event, PushUser user) {
        int targets = 0;
        for (PushTarget target : user.getTargets()) {
            for (PushMessage message : target.getMessages()) {
                if (handles(event, message)) {
                    targets++;
                    break;
                }
            }
        }

        if (targets == 0) {
            return;
        }

        PushGate.Block block = pushGate.blockedBy();
        String streamer = StringUtil.isBlank(event.getSource().getUname())
                ? String.valueOf(event.getSource().getUid())
                : event.getSource().getUname();

        log.info("{}, 已丢弃 {} 的一次推送（{} 个会话）", block.getDescription(), streamer, targets);

        // 认的是 blockedBy() 给的那一项而不是那句中文：文案是随时会改的东西，
        // 改一个字判据就静默失效，失效方向还是「从此全归成同一类」
        timeline.record(TimelineEvent.of(block.timelineType(), TimelineEvent.Level.WARN)
                .streamer(streamer)
                .text(block.getDescription() + "，丢弃了" + streamer + "的一次推送（" + targets + " 个会话）")
                .detail("targets", String.valueOf(targets))
                .detail("platform", event.getPlatform())
                .detail("event", event.getClass().getSimpleName())
                .build());
    }
}
