package org.frostnova.nova.bilibili.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Opponent;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 按需读取 PK 对手资料。同一场的订阅者共享正在进行的请求和返回的资料。 */
@Slf4j
@NovaComponent
public class BilibiliPkOpponentService {
    public record Profile(String nickname, Long fans, Integer guards, Integer onlineRankCount) {}
    private record Key(long pkId, long uid) {}

    private static final Profile EMPTY = new Profile(null, null, null, null);
    private final Cache<Key, CompletableFuture<Profile>> profiles = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(1)).build();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "bilibili-pk-opponent");
                thread.setDaemon(true);
                return thread;
            });
    private final BilibiliApiUtil api;

    public BilibiliPkOpponentService(BilibiliApiUtil api) {
        this.api = api;
    }

    /** 票数和名次随阶段变化，不参与对手的身份判断。缺失项在结果中为空。 */
    public CompletableFuture<Profile> lookup(Long pkId, Opponent opponent) {
        if (pkId == null || pkId <= 0 || opponent == null || opponent.uid() == null || opponent.uid() <= 0) {
            return CompletableFuture.completedFuture(EMPTY);
        }
        Key key = new Key(pkId, opponent.uid());
        return profiles.get(key, k -> {
            try {
                return CompletableFuture.supplyAsync(() -> fetch(k.uid(), opponent.roomId()), workers);
            } catch (RejectedExecutionException e) {
                return CompletableFuture.completedFuture(EMPTY);
            }
        }).copy();
    }

    private Profile fetch(long uid, Long roomId) {
        Up up = read(() -> api.getUpInfoByUid(uid));
        Long masterRoom = up == null ? null : up.getRoomId();
        Long room = roomId != null && roomId > 0 ? roomId : masterRoom;
        String nickname = up == null ? null : up.getUname();
        Long fans = up == null ? null : up.getFans();
        if (room == null || room <= 0) {
            return new Profile(nickname, fans, null, null);
        }
        Integer guards = read(() -> api.getGuardCount(room, uid).orElse(null));
        Integer online = read(() -> api.getOnlineRankCount(room, uid).orElse(null));
        return new Profile(nickname, fans, guards, online);
    }

    private static <T> T read(Supplier<T> request) {
        try {
            return request.get();
        } catch (RuntimeException e) {
            log.debug("读取 PK 对手资料失败", e);
            return null;
        }
    }

    @PreDestroy
    void close() {
        workers.shutdown();
    }
}
