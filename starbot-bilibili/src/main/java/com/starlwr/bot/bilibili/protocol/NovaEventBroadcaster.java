package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.event.live.common.ConnectedEvent;
import com.starlwr.bot.core.event.live.common.DisconnectedEvent;
import com.starlwr.bot.core.event.live.common.LikeUpdateEvent;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.event.live.common.OnlineRankCountUpdateEvent;
import com.starlwr.bot.core.event.live.common.RoomInfoChangeEvent;
import com.starlwr.bot.core.event.live.common.WatchedUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 把 NovaBot 的直播事件送进事件输出协议
 * <p>
 * 用户级事件交给 {@link NovaEventMapper} 直接转换；房间级的三种消息没有一一对应的事件，
 * 需要在这里攒状态：
 * <ul>
 *   <li>{@code live_state} 的标题不在开播消息里，只能记住最近一次房间信息变更给的那个</li>
 *   <li>{@code room_stat} 的三个数分别来自三种事件，要合并成一条并按协议限流到最快 5 秒一条</li>
 * </ul>
 *
 * <h2>关闭时的开销</h2>
 * 事件输出默认关闭，此时本类在每个事件上只做一次布尔判断就返回，
 * 既不映射也不序列化——用不到这个能力的部署不该为它付任何代价。
 */
@Slf4j
@StarBotComponent
public class NovaEventBroadcaster {
    /**
     * {@code room_stat} 的最快推送间隔。与协议的 ROOM_STAT_MIN_INTERVAL_MS 对齐
     */
    private static final Duration ROOM_STAT_INTERVAL = Duration.ofSeconds(5);

    private final NovaEventStream stream;

    private final boolean enabled;

    /**
     * 每个房间的状态。房间数是个位数，用 ConcurrentHashMap 足够
     */
    private final Map<Long, RoomState> rooms = new ConcurrentHashMap<>();

    /**
     * 单个房间的状态
     */
    private static class RoomState {
        private volatile String title = "";

        private volatile boolean live = false;

        private volatile Long startTs;

        private volatile Integer watched;

        private volatile Integer online;

        private volatile Integer likeTotal;

        /**
         * 距上次推送以来这三个数有没有变过。没变就不推——协议的限流是上限而非节拍
         */
        private volatile boolean statDirty = false;
    }

    @Autowired
    public NovaEventBroadcaster(StarBotBilibiliProperties properties, NovaEventStream stream,
                                @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.stream = stream;
        this.enabled = properties.getEventStream().isEnabled();

        if (enabled) {
            scheduler.scheduleAtFixedRate(this::flushRoomStats, ROOM_STAT_INTERVAL);
        }
    }

    /**
     * 转发一条事件
     * <p>
     * 监听基类即可覆盖全部直播事件，逐个类型注册只会在新增事件类型时漏掉。
     * @param event 事件
     */
    @EventListener(StarBotBaseLiveEvent.class)
    public void onEvent(StarBotBaseLiveEvent event) {
        if (!enabled) {
            return;
        }

        try {
            dispatch(event);
        } catch (Exception e) {
            // 事件输出是旁路能力，出问题绝不能影响推送与统计这些主线
            log.error("转发事件 {} 至事件输出时异常", event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 主播被移出数据源
     * <p>
     * <b>这是唯一能推出 {@code disconnected} 的地方。</b> 主动摘除房间时连接器走的是
     * {@code close()} 那条路，它不会发 {@link DisconnectedEvent}——发了的话，
     * 使用者在界面上删掉一位主播就会收到一条「连接断开」的告警推送。
     * <p>
     * 但下游必须知道这件事：房间没了却收不到任何状态变化，面板上那一路就会
     * <b>永远停在「已连接」</b>，看着像还在采集，其实一条都不会再来。
     * @param event 数据源移除事件
     */
    @EventListener(StarBotDataSourceRemoveEvent.class)
    public void onRemoved(StarBotDataSourceRemoveEvent event) {
        if (!enabled) {
            return;
        }

        try {
            PushUser user = event.getUser();
            if (user == null || user.getRoomId() == null
                    || !LivePlatform.BILIBILI.getName().equals(user.getPlatform())) {
                return;
            }

            rooms.remove(user.getRoomId());
            publish(NovaEventMapper.sourceState(user.getRoomId(), event.getTimestamp(), "disconnected"), null);
        } catch (Exception e) {
            log.error("转发数据源移除事件至事件输出时异常", e);
        }
    }

    private void dispatch(StarBotBaseLiveEvent event) {
        Long room = roomOf(event);
        if (room == null) {
            return;
        }

        if (event instanceof LiveOnEvent e) {
            RoomState state = state(room);
            state.live = true;
            state.startTs = e.getTimestamp();
            publish(NovaEventMapper.liveState(room, e.getTimestamp(), true, state.title, state.startTs), event);
            return;
        }
        if (event instanceof LiveOffEvent e) {
            RoomState state = state(room);
            state.live = false;
            state.startTs = null;
            publish(NovaEventMapper.liveState(room, e.getTimestamp(), false, state.title, null), event);
            return;
        }
        if (event instanceof RoomInfoChangeEvent e) {
            RoomState state = state(room);
            String title = e.getTitle() == null ? "" : e.getTitle();
            // 平台改标题时无条件下发，改前改后一样也照发。这里自行判重，
            // 否则主播点开设置又原样保存就会白推一条
            if (title.equals(state.title)) {
                return;
            }
            state.title = title;
            publish(NovaEventMapper.liveState(room, e.getTimestamp(), state.live, title, state.startTs), event);
            return;
        }

        if (event instanceof ConnectedEvent e) {
            publish(NovaEventMapper.sourceState(room, e.getTimestamp(), "connected"), event);
            return;
        }
        if (event instanceof DisconnectedEvent e) {
            // 只要房间还在监听列表里我们就会一直重连，所以这里是 reconnecting 而不是 disconnected。
            // disconnected 走 onRemoved()——那才是「这个房间不会再有数据了」
            publish(NovaEventMapper.sourceState(room, e.getTimestamp(), "reconnecting"), event);
            return;
        }

        if (event instanceof WatchedUpdateEvent e) {
            markStat(room, s -> s.watched = e.getCount(), e.getCount());
            return;
        }
        if (event instanceof OnlineRankCountUpdateEvent e) {
            markStat(room, s -> s.online = e.getCount(), e.getCount());
            return;
        }
        if (event instanceof LikeUpdateEvent e) {
            markStat(room, s -> s.likeTotal = e.getCount(), e.getCount());
            return;
        }

        JSONObject envelope = NovaEventMapper.map(event);
        if (envelope != null) {
            publish(envelope, event);
        }
    }

    /**
     * 记下一项房间统计
     * @param room 房间号
     * @param setter 写入动作
     * @param value 新值，为空时不记——空表示平台这次没给，不是「变成了 0」
     */
    private void markStat(Long room, Consumer<RoomState> setter, Integer value) {
        if (value == null) {
            return;
        }
        RoomState state = state(room);
        setter.accept(state);
        state.statDirty = true;
    }

    /**
     * 把攒下的房间统计推出去
     * <p>
     * 定时器按 5 秒跑，但只推有变化的房间。协议说的「最快 5 秒一条」是上限不是节拍，
     * 一个没人看的房间不该每 5 秒发一条一模一样的数。
     */
    private void flushRoomStats() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, RoomState> entry : rooms.entrySet()) {
                RoomState state = entry.getValue();
                if (!state.statDirty) {
                    continue;
                }
                state.statDirty = false;

                JSONObject envelope = NovaEventMapper.roomStat(entry.getKey(), now, state.watched, state.online, state.likeTotal);
                if (envelope != null) {
                    publish(envelope, null);
                }
            }
        } catch (Exception e) {
            log.error("推送房间统计至事件输出时异常", e);
        }
    }

    /**
     * 编号并分发
     * @param envelope 信封
     * @param event 事件，用于附带原始报文；房间统计这类由我们自己合成的消息没有对应事件，传 {@code null}
     */
    private void publish(JSONObject envelope, StarBotBaseLiveEvent event) {
        // rawJson 是协议之外的附加字段。序列化推迟到这里做: 事件输出关闭时一次都不做，
        // 开启时也只做一次
        JSONObject raw = event == null ? null : event.getRawMessage();
        envelope.put("rawJson", raw == null ? null : raw.toJSONString());
        stream.publish(envelope);
    }

    private RoomState state(Long room) {
        return rooms.computeIfAbsent(room, key -> new RoomState());
    }

    private Long roomOf(StarBotBaseLiveEvent event) {
        LiveStreamerInfo source = event.getSource();
        return source == null ? null : source.getRoomId();
    }
}
