package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.model.GuardListFetch;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 每场下播留下当时的大航海名单，不论这场有没有配下播报告。
 * <p>
 * 名单要翻很多页。已经留下的就不再取；还没有的放到后台去取，
 * 下播当时别的事不用等它。各场共用这一处后台，同时取的路数有限，
 * 排不上的这场先不留。停机时正在取的直接停下，不再补。
 * 配了下播报告的，画报告时就会留下，这里看到文件就不再取。
 */
@Slf4j
@NovaComponent
public class BilibiliGuardRosterKeeper {
    private final BilibiliApiUtil api;

    private final LiveDetailArchive archive;

    private final LiveDataService liveData;

    /**
     * 同时在取的路数。下播常常好几间一起到，两路够用，再多就排队
     */
    private static final int FETCH_LANES = 2;

    /**
     * 排队等候的场数。再多的这场先不留，免得后台越积越多
     */
    private static final int FETCH_WAITING = 8;

    private final Executor fetch;

    /**
     * 自己这处后台。测试传入的不关
     */
    private final ExecutorService ownedPool;

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @Autowired
    public BilibiliGuardRosterKeeper(BilibiliApiUtil api, LiveDetailArchive archive, LiveDataService liveData) {
        this.api = api;
        this.archive = archive;
        this.liveData = liveData;
        ExecutorService pool = rosterPool();
        this.fetch = pool;
        this.ownedPool = pool;
    }

    BilibiliGuardRosterKeeper(BilibiliApiUtil api, LiveDetailArchive archive, LiveDataService liveData,
                              Executor fetch) {
        this.api = api;
        this.archive = archive;
        this.liveData = liveData;
        this.fetch = fetch;
        this.ownedPool = null;
    }

    private static ExecutorService rosterPool() {
        return new ThreadPoolExecutor(FETCH_LANES, FETCH_LANES, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(FETCH_WAITING),
                runnable -> {
                    Thread thread = new Thread(runnable, "guard-roster");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 停机时正在取的和还排着队的都放弃，不再补
     */
    @PreDestroy
    void stopFetching() {
        if (ownedPool != null) {
            ownedPool.shutdownNow();
        }
    }

    /**
     * 下播之后留下这一场的名单。这份已经在目录里就不再向平台要。
     * @param event 下播事件
     */
    @Order(1000)
    @EventListener
    public void onLiveOff(BilibiliLiveOffEvent event) {
        try {
            schedule(event);
        } catch (RuntimeException e) {
            log.debug("下播时没能留下大航海名单: {}", e.getMessage());
        }
    }

    private void schedule(BilibiliLiveOffEvent event) {
        if (event == null || event.getSource() == null) {
            return;
        }
        Long uid = event.getSource().getUid();
        Long roomId = event.getSource().getRoomId();
        if (uid == null || roomId == null || event.getPlatform() == null) {
            return;
        }
        Optional<Long> start = liveData.getLiveStartTime(event.getPlatform(), uid);
        if (start == null || start.isEmpty()) {
            return;
        }
        if (archive.readText(event.getPlatform(), uid, start.get(), GuardRosterFile.NAME).isPresent()) {
            return;
        }
        String key = event.getPlatform() + ":" + uid + ":" + start.get();
        if (!pending.add(key)) {
            return;
        }
        long started = start.get();
        try {
            fetch.execute(() -> {
                try {
                    keep(event, uid, roomId, started);
                } catch (RuntimeException e) {
                    log.debug("下播时没能留下大航海名单: {}", e.getMessage());
                } finally {
                    pending.remove(key);
                }
            });
        } catch (RejectedExecutionException rejected) {
            pending.remove(key);
            log.warn("下播名单这次排不上，这场先不留");
        }
    }

    private void keep(BilibiliLiveOffEvent event, long uid, long roomId, long start) {
        if (archive.readText(event.getPlatform(), uid, start, GuardRosterFile.NAME).isPresent()) {
            return;
        }
        Optional<List<GuardMember>> fetched = api.getGuardList(roomId, uid);
        if (fetched == null || fetched.isEmpty() || fetched.get().isEmpty()) {
            return;
        }
        List<GuardMember> members = fetched.get();
        int total = GuardListFetch.reportedTotal(members).orElse(members.size());
        archive.writeOnce(event.getPlatform(), uid, start, GuardRosterFile.NAME,
                GuardRosterFile.toJson(System.currentTimeMillis(), total, members));
    }
}
