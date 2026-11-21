package org.frostnova.nova.console.assistant;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 只保留最近查过的一位主播的首次留档索引，逐条原文与昵称不进入缓存。 */
final class AssistantAudienceHistory {
    private Snapshot cached;

    record Key(String platform, long uid, Long liveStart, Path sessionsFile) {}

    interface Loader {
        void load(Snapshot snapshot) throws IOException;
    }

    synchronized Snapshot load(Key key, Loader loader) throws IOException {
        if (cached != null && cached.key.equals(key) && cached.unchanged()) return cached;
        cached = null;
        Snapshot snapshot = new Snapshot(key);
        snapshot.watch(key.sessionsFile());
        loader.load(snapshot);
        snapshot.checkUnchanged();
        cached = snapshot;
        return snapshot;
    }

    static final class Snapshot {
        private final Key key;
        private final Map<Path, FileStamp> files = new HashMap<>();
        private long earliest;
        private long[] uids = new long[16];
        private long[] times = new long[16];
        private int size;
        private int invalidLines;

        private Snapshot(Key key) { this.key = key; }

        void watch(Path file) throws IOException { files.put(file, stamp(file)); }

        void checkUnchanged() throws IOException {
            if (!unchanged()) throw new AssistantAggregateRequest.Invalid("归档正在变化，请稍后重试");
        }

        private boolean unchanged() throws IOException {
            for (var entry : files.entrySet()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (!Objects.equals(entry.getValue(), stamp(entry.getKey()))) return false;
            }
            return true;
        }

        long earliest() { return earliest; }

        int invalidLines() { return invalidLines; }

        void skipInvalidLine() { invalidLines++; }

        void observe(long at) {
            if (earliest == 0 || at < earliest) earliest = at;
        }

        long firstSeen(long uid) { return times[slot(uids, uid)]; }

        void observe(long uid, long at) {
            observe(at);
            int index = slot(uids, uid);
            if (uids[index] == uid) {
                times[index] = Math.min(times[index], at);
                return;
            }
            if (size == uids.length / 2) {
                grow();
                index = slot(uids, uid);
            }
            uids[index] = uid;
            times[index] = at;
            size++;
        }

        // 每次插入都受查询扫描上限约束；用原生数组保存编号和时刻，避免逐人保留装箱对象。
        private void grow() {
            var nextUids = new long[uids.length * 2];
            var nextTimes = new long[nextUids.length];
            for (int i = 0; i < uids.length; i++) {
                if (uids[i] == 0) continue;
                int index = slot(nextUids, uids[i]);
                nextUids[index] = uids[i];
                nextTimes[index] = times[i];
            }
            uids = nextUids;
            times = nextTimes;
        }
    }

    static int slot(long[] keys, long uid) {
        long mixed = uid * 0x9e3779b97f4a7c15L;
        int mask = keys.length - 1;
        int index = (int) (mixed ^ (mixed >>> 32)) & mask;
        while (keys[index] != 0 && keys[index] != uid) index = (index + 1) & mask;
        return index;
    }

    private static FileStamp stamp(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private record FileStamp(long size, FileTime modified, Object fileKey) {}
}
