package org.frostnova.nova.console.controller;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.analytics.SessionRecapRenderer;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.*;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AudienceLedgerService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.LiveSessionArchive.ArchivedSession;
import org.frostnova.nova.core.service.SessionRecapService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 只保存渲染后的短文字，计算用的观众数据在一次生成结束后释放。 */
@NovaComponent
@Slf4j
public class RecapPreviewService {
    private final LiveSessionArchive archive;
    private final AudienceLedgerService ledger;
    private final SessionRecapService recaps;
    private final AssistantProperties properties;
    private final ReentrantLock generation = new ReentrantLock(true);
    private final Map<StreamerKey, CompletableFuture<Versions>> running = new ConcurrentHashMap<>();
    // 只在 generation 锁内读写。限制缓存大小，浏览很多主播也不会无限增长。
    private final Map<StreamerKey, Cached> cache = new LinkedHashMap<>(16, .75f, true);

    public RecapPreviewService(LiveSessionArchive archive, AudienceLedgerService ledger,
                              SessionRecapService recaps, AssistantProperties properties) {
        this.archive = archive; this.ledger = ledger; this.recaps = recaps; this.properties = properties;
    }

    public record Streamer(String platform, long uid, String uname, int sessionCount, long latestStart) {}
    public record Streamers(boolean success, List<Streamer> streamers, String message) {}
    public record Text(String message, List<String> notes) {}
    public record Card(long startTime, long endTime, long durationSeconds, Text level1, Text level2) {}
    public record Preview(boolean success, List<Card> sessions, String message) {}
    private record StreamerKey(String platform, long uid) {}
    private record Snapshot(List<ArchivedSession> sessions, Set<Long> excluded) {}
    private record Versions(Preview masked, Preview original) {}
    private record Cached(Snapshot snapshot, Versions versions) {}

    public Streamers streamers() {
        try {
            var grouped = new LinkedHashMap<StreamerKey, Streamer>();
            for (var session : archive.sealedSessions()) {
                var key = new StreamerKey(session.platform(), session.uid());
                var previous = grouped.get(key);
                String name = session.uname();
                if (name == null || name.isBlank()) name = previous == null ? "未记录昵称" : previous.uname();
                grouped.put(key, new Streamer(key.platform(), key.uid(), name,
                        previous == null ? 1 : previous.sessionCount() + 1, session.startTime()));
            }
            return new Streamers(true, grouped.values().stream()
                    .sorted(Comparator.comparingLong(Streamer::latestStart).reversed()
                            .thenComparing(Streamer::platform).thenComparingLong(Streamer::uid)).toList(), null);
        } catch (Exception failure) {
            log.warn("读取小结样稿的已封存场次失败", failure);
            return new Streamers(false, List.of(), "读取已封存场次没成功，请稍后再试。");
        }
    }

    public Preview preview(String platform, long uid, boolean showNicknames) {
        if (platform == null || !platform.matches("[A-Za-z0-9_-]{1,32}") || uid <= 0)
            return failed("平台或主播编号不对，请重新选择主播。");
        var key = new StreamerKey(platform, uid);
        var task = new CompletableFuture<Versions>();
        var existing = running.putIfAbsent(key, task);
        if (existing == null) {
            try {
                generation.lock();
                try {
                    task.complete(generate(key));
                } finally {
                    generation.unlock();
                }
            } catch (Throwable failure) {
                task.complete(failedVersions("生成样稿没成功，请稍后再试。"));
                log.warn("生成小结样稿失败，平台 {}，主播 {}", platform, uid, failure);
            } finally {
                running.remove(key, task);
            }
        } else task = existing;
        Versions versions = task.join();
        return showNicknames ? versions.original() : versions.masked();
    }

    private Versions generate(StreamerKey key) {
        Snapshot snapshot;
        try {
            snapshot = new Snapshot(archive.sealedSessions().stream()
                    .filter(s -> key.platform().equals(s.platform()) && key.uid() == s.uid()).toList(),
                    Set.copyOf(properties.getExcludedUids()));
        } catch (Exception failure) {
            log.warn("读取小结样稿的已封存场次失败，平台 {}，主播 {}", key.platform(), key.uid(), failure);
            return failedVersions("读取已封存场次没成功，请稍后再试。");
        }
        var cached = cache.get(key);
        if (cached != null && cached.snapshot().equals(snapshot)) return cached.versions();
        if (snapshot.sessions().isEmpty()) return failedVersions("这位主播还没有已封存场次。");
        try {
            ledger.updateStreamer(key.platform(), key.uid());
        } catch (Exception failure) {
            log.warn("补齐小结样稿的观众账本失败，平台 {}，主播 {}", key.platform(), key.uid(), failure);
            return failedVersions("补齐观众账本没成功，请稍后再试。");
        }
        try {
            var selected = snapshot.sessions().subList(Math.max(0, snapshot.sessions().size() - 5), snapshot.sessions().size());
            var results = recaps.calculateBatch(key.platform(), key.uid(), selected.stream().map(ArchivedSession::startTime).toList());
            var masked = new ArrayList<Card>();
            var original = new ArrayList<Card>();
            var history = new ArrayList<History>();
            for (int i = 0; i < selected.size(); i++) {
                var session = selected.get(i);
                var result = results.get(i);
                var first = SessionRecapRenderer.render(result, new Options(1, true, Nicknames.MASKED, snapshot.excluded()), history);
                var second = SessionRecapRenderer.render(result, new Options(2, true, Nicknames.MASKED, snapshot.excluded()), history);
                masked.add(card(session, first, second));
                original.add(card(session,
                        SessionRecapRenderer.render(result, new Options(1, true, Nicknames.ORIGINAL, snapshot.excluded()), history),
                        SessionRecapRenderer.render(result, new Options(2, true, Nicknames.ORIGINAL, snapshot.excluded()), history)));
                history.add(second.history());
            }
            // 生成途中拒绝名单变动时提示重试，不能把旧昵称版本留在缓存里。
            if (!snapshot.excluded().equals(Set.copyOf(properties.getExcludedUids())))
                return failedVersions("拒绝名单刚有变化，请稍后再试。");
            var versions = new Versions(new Preview(true, List.copyOf(masked), null), new Preview(true, List.copyOf(original), null));
            cache.put(key, new Cached(snapshot, versions));
            if (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
            return versions;
        } catch (Exception failure) {
            log.warn("计算或整理小结样稿失败，平台 {}，主播 {}", key.platform(), key.uid(), failure);
            return failedVersions("计算或整理小结没成功，请稍后再试。");
        }
    }

    private static Card card(ArchivedSession session, Draft first, Draft second) {
        return new Card(session.startTime(), session.endTime(), session.durationSeconds(), text(first), text(second));
    }
    private static Text text(Draft draft) {
        return new Text(draft.message(), draft.notes().stream().map(Line::text).toList());
    }
    private static Preview failed(String message) { return new Preview(false, List.of(), message); }
    private static Versions failedVersions(String message) { var result = failed(message); return new Versions(result, result); }
}
