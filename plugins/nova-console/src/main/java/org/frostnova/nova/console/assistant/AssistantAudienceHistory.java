package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveSessionArchive;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.frostnova.nova.console.assistant.AssistantAudienceIndex.*;

/** 每位主播独立保存首次出现统计日。历史建档与窗口内的明细查数各有自己的额度。 */
final class AssistantAudienceHistory {
    static final String EVENTS = "events.jsonl";
    static final String DANMU = "danmu.jsonl";
    static final int MAX_SESSION_CHARS = 1_048_576;
    static final long BUILD_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int BATCH_VIEWERS = 160_000;
    private final ReentrantLock buildLock = new ReentrantLock();

    record Key(String platform, long uid, long liveStart, Path sessionsFile) {
        Path indexFile() {
            return sessionsFile.toAbsolutePath().resolveSibling("assistant-audience-history")
                    .resolve(platform + "-" + uid + ".index");
        }
    }

    interface SessionReader {
        void read(LiveSession session, Changes changes);
    }

    record Result(View view, long scannedRecords, int completedSessions, int totalSessions) implements AutoCloseable {
        @Override public void close() throws IOException {
            if (view != null) view.close();
        }
    }

    Result load(Key key, LiveSessionArchive archive, BiFunction<Long, String, Path> file,
                IntPredicate pause, SessionReader reader) throws IOException {
        boolean pausedAtStart = pause.test(0);
        Path index = key.indexFile();
        View ready = ready(index, key, file);
        if (ready != null) return finished(ready, 0);
        // 读已经建好的主播无需等待另一位主播的建档。
        if (!buildLock.tryLock()) throw new AssistantAggregateRequest.Invalid("观众首次出现记录还在建立，请稍后再问");
        try (var build = new Build(index, key, file)) {
            if (build.ready()) return finished(build.takeView(), 0);
            var catalog = catalog(key, archive);
            build.select(catalog);
            try {
                visit(key, archive, row -> {
                    long start = row.startTime();
                    if (build.done.containsKey(start)) return;
                    if (pausedAtStart || pause.test(build.completedHere)) throw new Paused();
                    LiveSession session = row.read(true);
                    if (session == null) return;
                    try {
                        build.read(session, catalog.get(start), reader);
                        if (build.changes.size >= BATCH_VIEWERS) build.save(false);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
            } catch (Paused ignored) {
                build.save(false);
                return new Result(null, build.scanned(), build.done.size(), catalog.size());
            }
            // 通过时间和身份筛选、但完整解析失败的场次沿用归档的坏行处理。
            for (var entry : catalog.entrySet()) {
                if (!build.done.containsKey(entry.getKey()))
                    build.done.put(entry.getKey(), stamp(file, entry.getKey(), entry.getValue()));
            }
            build.save(true);
            View view = build.takeView();
            return finished(view, build.scanned());
        } finally {
            buildLock.unlock();
        }
    }

    private static Result finished(View view, long scanned) {
        int count = view.progress.sessions().size();
        return new Result(view, scanned, count, count);
    }

    private static View ready(Path index, Key key, BiFunction<Long, String, Path> file) throws IOException {
        View view = AssistantAudienceIndex.open(index);
        if (view == null) return null;
        boolean keep = false;
        try {
            keep = matches(view, key, FileStamp.read(key.sessionsFile), file);
            return keep ? view : null;
        } finally {
            if (!keep) view.close();
        }
    }

    private static boolean matches(View view, Key key, FileStamp source, BiFunction<Long, String, Path> file)
            throws IOException {
        return view != null && view.progress.complete() && view.progress.liveStart() == key.liveStart
                && Objects.equals(view.progress.source(), source) && detailsUnchanged(view.progress.sessions(), file);
    }

    static void checkUnchanged(Key key, BiFunction<Long, String, Path> file, View view) throws IOException {
        if (!matches(view, key, FileStamp.read(key.sessionsFile), file)) throw archiveChanged();
    }

    private static AssistantAggregateRequest.Invalid archiveChanged() {
        return new AssistantAggregateRequest.Invalid("归档正在变化，请稍后重试");
    }

    private static boolean detailsUnchanged(Map<Long, SessionStamp> sessions, BiFunction<Long, String, Path> file)
            throws IOException {
        for (var entry : sessions.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            var expected = entry.getValue();
            if (!expected.equals(stamp(file, entry.getKey(), expected.summary()))) return false;
        }
        return true;
    }

    private static SessionStamp stamp(BiFunction<Long, String, Path> file, long start, String summary) throws IOException {
        return new SessionStamp(summary, FileStamp.read(file.apply(start, EVENTS)), FileStamp.read(file.apply(start, DANMU)));
    }

    private static Map<Long, String> catalog(Key key, LiveSessionArchive archive) throws IOException {
        Map<Long, String> catalog = new HashMap<>();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        visit(key, archive, row -> {
            var fingerprint = fingerprint(digest, row.line());
            catalog.merge(row.startTime(), fingerprint, (a, b) -> fingerprint(digest, a + b));
        });
        return catalog;
    }

    private static String fingerprint(MessageDigest digest, String text) {
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void visit(Key key, LiveSessionArchive archive,
                              Consumer<LiveSessionArchive.ArchiveRow> visit) throws IOException {
        archive.forEachOverlappingRow(key.platform, key.uid, 0, Long.MAX_VALUE, MAX_SESSION_CHARS, row -> {
            long start = row.startTime();
            if (start > 0 && row.endTime() >= start && start != key.liveStart) visit.accept(row);
        });
    }

    private static final class Paused extends RuntimeException {
        private Paused() { super(null, null, false, false); }
    }

    private static final class Build implements AutoCloseable {
        private final Path path;
        private final Key key;
        private final FileStamp source;
        private final BiFunction<Long, String, Path> file;
        private final Map<Long, SessionStamp> done = new HashMap<>();
        private View base;
        private Changes changes = new Changes();
        private int completedHere;
        private long scanned;
        private int earliestDay = NO_DAY;
        private long invalidLines;

        Build(Path path, Key key, BiFunction<Long, String, Path> file) throws IOException {
            this.path = path;
            this.key = key;
            this.file = file;
            source = FileStamp.read(key.sessionsFile);
            base = AssistantAudienceIndex.open(path);
        }

        boolean ready() throws IOException { return matches(base, key, source, file); }

        void select(Map<Long, String> catalog) throws IOException {
            if (base == null) return;
            var previous = base.progress;
            boolean summariesUnchanged = previous.sessions().entrySet().stream().allMatch(entry ->
                    entry.getValue().summary().equals(catalog.get(entry.getKey())));
            if (!summariesUnchanged || !detailsUnchanged(previous.sessions(), file)) {
                close();
                base = null;
                return;
            }
            done.putAll(previous.sessions());
            earliestDay = previous.earliestDay();
            invalidLines = previous.invalidLines();
        }

        void read(LiveSession session, String summary, SessionReader reader) throws IOException {
            long start = session.startTime();
            SessionStamp before = stamp(file, start, summary);
            reader.read(session, changes);
            if (!before.equals(stamp(file, start, summary))) throw archiveChanged();
            done.put(start, before);
            completedHere++;
        }

        void save(boolean complete) throws IOException {
            if (!Objects.equals(source, FileStamp.read(key.sessionsFile)) || !detailsUnchanged(done, file))
                throw archiveChanged();
            scanned += changes.scanned;
            earliestDay = Math.min(earliestDay, changes.earliestDay);
            invalidLines += changes.invalidLines;
            var progress = new Progress(source, key.liveStart, complete, earliestDay, invalidLines, done);
            AssistantAudienceIndex.save(path, progress, base, changes.sortedUids(), changes::firstDay);
            close();
            changes = new Changes();
            base = AssistantAudienceIndex.open(path);
            if (base == null) throw new IOException("无法读回观众首次出现记录");
        }

        long scanned() { return scanned; }

        View takeView() {
            var view = base;
            base = null;
            return view;
        }

        @Override public void close() throws IOException {
            if (base != null) base.close();
        }
    }

    /** 批内取最早统计日，落盘时与已完成的场次合并。 */
    static final class Changes {
        private int earliestDay = NO_DAY;
        private long dayStart, nextDayStart;
        private int day;
        private long[] uids = new long[16];
        private int[] days = new int[16];
        private int size;
        private long invalidLines;
        private long scanned;

        void count(long records) { scanned += records; }
        void skipInvalidLine() { invalidLines++; }

        int observe(long at) {
            if (at < dayStart || at >= nextDayStart) {
                var date = StatisticsDay.dateOf(Instant.ofEpochMilli(at));
                dayStart = StatisticsDay.startOf(date).toEpochMilli();
                nextDayStart = StatisticsDay.startOf(date.plusDays(1)).toEpochMilli();
                day = Math.toIntExact(date.toEpochDay());
            }
            earliestDay = Math.min(earliestDay, day);
            return day;
        }

        void observe(long uid, long at) {
            int first = observe(at);
            int index = slot(uids, uid);
            if (uids[index] == uid) {
                days[index] = Math.min(days[index], first);
                return;
            }
            if (size == uids.length / 2) {
                grow();
                index = slot(uids, uid);
            }
            uids[index] = uid;
            days[index] = first;
            size++;
        }

        private int firstDay(long uid) { return days[slot(uids, uid)]; }

        private long[] sortedUids() {
            long[] sorted = new long[size];
            int i = 0;
            for (long uid : uids) if (uid != 0) sorted[i++] = uid;
            Arrays.sort(sorted);
            return sorted;
        }

        private void grow() {
            var nextUids = new long[uids.length * 2];
            var nextDays = new int[nextUids.length];
            for (int i = 0; i < uids.length; i++) {
                if (uids[i] == 0) continue;
                int index = slot(nextUids, uids[i]);
                nextUids[index] = uids[i];
                nextDays[index] = days[i];
            }
            uids = nextUids;
            days = nextDays;
        }
    }

    static int slot(long[] keys, long uid) {
        long mixed = uid * 0x9e3779b97f4a7c15L;
        int mask = keys.length - 1;
        int index = (int) (mixed ^ (mixed >>> 32)) & mask;
        while (keys[index] != 0 && keys[index] != uid) index = (index + 1) & mask;
        return index;
    }
}
