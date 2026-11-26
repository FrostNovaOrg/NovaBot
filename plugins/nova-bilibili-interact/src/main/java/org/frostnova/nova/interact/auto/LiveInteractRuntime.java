package org.frostnova.nova.interact.auto;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliEmojiEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliFollowEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliFreeGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliGovernorEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliShareEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.event.datasource.change.NovaDataSourceRemoveEvent;
import org.frostnova.nova.core.event.live.base.NovaLiveGiftEvent;
import org.frostnova.nova.core.event.live.base.NovaLiveMessageEvent;
import org.frostnova.nova.core.event.live.common.MembershipEvent;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@NovaComponent
public class LiveInteractRuntime {
    private final AutoDanmakuQueue queue;
    private final InteractStore store;
    private final LiveDanmakuService sender;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "live-interact-send");
        thread.setDaemon(true);
        return thread;
    });

    public LiveInteractRuntime(AutoDanmakuQueue queue, InteractStore store, LiveDanmakuService sender) {
        this.queue = queue;
        this.store = store;
        this.sender = sender;
    }

    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        sender.rooms().forEach(queue::register);
        worker.scheduleWithFixedDelay(() -> {
            try {
                queue.tick();
            } catch (RuntimeException e) {
                log.warn("检查自动弹幕队列失败（{}）", e.getClass().getSimpleName());
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(() -> store.pruneHistory(Instant.now()), 0, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    @Order(-100)
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        if (worker.isShutdown()) return;
        queue.stop();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) log.warn("自动弹幕发送线程仍在等待平台响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EventListener
    public void streamerChanged(NovaDataSourceChangeEvent event) {
        PushUser user = event.getUser();
        if (user == null || user.getUid() == null || !BilibiliPlatform.BILIBILI.id().equals(user.getPlatform())) return;
        if (event instanceof NovaDataSourceRemoveEvent) {
            queue.forgetRoom(user.getUid(), "主播已移除");
        } else if (user.getRoomId() != null && user.getRoomId() > 0) {
            String name = user.getUname() == null || user.getUname().isBlank() ? "房间 " + user.getRoomId() : user.getUname();
            queue.register(new LiveDanmakuService.Room(user.getUid(), name, user.getRoomId()));
        } else {
            queue.forgetRoom(user.getUid(), "主播的直播间不可用");
        }
    }

    @EventListener
    public void liveOn(BilibiliLiveOnEvent event) {
        queue.liveOn(event.getSource().getUid(), event.isReconnect());
    }

    @EventListener
    public void liveOff(BilibiliLiveOffEvent event) {
        queue.liveOff(event.getSource().getUid());
    }

    @EventListener({BilibiliDanmuEvent.class, BilibiliEmojiEvent.class})
    public void danmaku(NovaLiveMessageEvent event) {
        queue.viewer(event);
    }

    @EventListener({BilibiliPaidGiftEvent.class, BilibiliRandomGiftEvent.class, BilibiliFreeGiftEvent.class})
    public void gift(NovaLiveGiftEvent event) {
        queue.thank(ThanksKind.GIFT, event, event.getSender());
    }

    @EventListener({BilibiliCaptainEvent.class, BilibiliCommanderEvent.class, BilibiliGovernorEvent.class})
    public void membership(MembershipEvent event) {
        queue.thank(ThanksKind.MEMBERSHIP, event, event.getSender());
    }

    @EventListener
    public void superChat(BilibiliSuperChatEvent event) {
        queue.thank(ThanksKind.SUPER_CHAT, event, event.getSender());
    }

    @EventListener
    public void follow(BilibiliFollowEvent event) {
        queue.thank(ThanksKind.FOLLOW, event, event.getSender());
    }

    @EventListener
    public void share(BilibiliShareEvent event) {
        queue.thank(ThanksKind.SHARE, event, event.getSender());
    }
}
