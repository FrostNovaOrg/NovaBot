package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.History;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.SpokenPattern;
import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 发送意图先落盘；文件损坏后暂停，恢复有效记录文件后可重新启动。 */
public final class SessionRecapDeliveryStore {
    public enum Status { READY, QUIET, SENDING, SENT, FAILED, UNCERTAIN, NOT_SENT, EXPIRED }
    public record Delivery(String subscription, long startTime, long dueAt, Status status,
                           String reason, long updatedAt, History history) {}
    private final Path path;
    private final Map<String, Long> subscriptions = new LinkedHashMap<>();
    private final List<Delivery> records = new ArrayList<>();
    private boolean unreadable;
    private String warning = "";

    public SessionRecapDeliveryStore(Path path) {
        this.path = path;
        if (!Files.exists(path)) {
            unreadable = Files.exists(marker());
            if (unreadable) warning = "小结发送记录损坏，恢复记录文件前暂停自动发送";
            return;
        }
        try {
            restrict(path);
            var root = JSON.parseObject(Files.readString(path));
            if (root == null || !(root.get("subscriptions") instanceof JSONObject enrolled)
                    || !(root.get("records") instanceof JSONArray entries)) throw new IOException("记录缺少必要字段");
            var loaded = new ArrayList<Delivery>();
            for (var entry : entries) {
                if (!(entry instanceof JSONObject r)) throw new IOException("记录格式不正确");
                long start = number(r, "startTime"), due = number(r, "dueAt");
                if (!(r.get("history") instanceof JSONObject h) || number(h, "session") != start
                        || !(h.get("patterns") instanceof JSONArray patterns)) throw new IOException("规律记录格式不正确");
                var spoken = new ArrayList<SpokenPattern>();
                for (var pattern : patterns) {
                    if (!(pattern instanceof JSONObject item) || !(item.get("values") instanceof JSONObject values))
                        throw new IOException("规律格式不正确");
                    String key = string(item, "key");
                    StreamerProfile.Pattern.valueOf(key);
                    spoken.add(new SpokenPattern(key, new LinkedHashMap<>(values)));
                }
                var status = Status.valueOf(string(r, "status"));
                String reason = string(r, "reason");
                if (loaded.stream().anyMatch(d -> d.subscription().equals(string(r, "subscription")) && d.startTime() == start))
                    throw new IOException("场次记录重复");
                loaded.add(new Delivery(string(r, "subscription"), start, due,
                        status == Status.SENDING ? Status.UNCERTAIN : status,
                        status == Status.SENDING ? "发送途中程序停止，未自动重发" : reason,
                        number(r, "updatedAt"), new History(start, spoken)));
            }
            for (String key : enrolled.keySet()) subscriptions.put(key, number(enrolled, key));
            records.addAll(loaded);
            Files.deleteIfExists(marker());
            if (entries.stream().anyMatch(e -> "SENDING".equals(((JSONObject)e).getString("status")))) save();
        } catch (IOException | RuntimeException failure) {
            unreadable = true;
            warning = "小结发送记录损坏，恢复记录文件前暂停自动发送";
            try {
                restrict(path);
                DurableFiles.replace(marker(), "UNREADABLE", DurableFiles.OWNER_ONLY);
                restrict(marker());
                restrict(DurableFiles.quarantine(path));
            } catch (IOException | RuntimeException ignored) {
                warning = "小结发送记录损坏且无法另存，自动发送已暂停";
            }
        }
    }
    Path greetingPath() { return path.resolveSibling("session-recap-greetings.json"); }
    private static void restrict(Path file) throws IOException {
        if (Files.isRegularFile(file) && Files.getFileAttributeView(file,
                java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(file, DurableFiles.OWNER_ONLY);
    }
    private Path marker() { return path.resolveSibling(path.getFileName() + ".unreadable"); }
    private static long number(JSONObject r, String key) throws IOException {
        if (!(r.get(key) instanceof Number n) || n.longValue() < 0 || n.doubleValue() != n.longValue())
            throw new IOException("记录时间不正确");
        return n.longValue();
    }
    private static String string(JSONObject r, String key) {
        if (!(r.get(key) instanceof String s) || (!key.equals("reason") && s.isBlank()))
            throw new IllegalArgumentException("记录字段不正确");
        return s;
    }
    public synchronized List<Delivery> records() { return List.copyOf(records); }
    public synchronized String warning() { return warning; }
    public synchronized boolean ensureSaved() { return !unreadable && (warning.isEmpty() || save()); }
    public synchronized boolean observe(Set<String> keys, Instant now) {
        subscriptions.keySet().retainAll(keys);
        keys.forEach(k -> subscriptions.putIfAbsent(k, now.toEpochMilli()));
        long cutoff = now.minus(Duration.ofDays(90)).toEpochMilli();
        records.removeIf(r -> r.dueAt() < cutoff);
        return save();
    }
    public synchronized long subscribedAt(String key) { return subscriptions.getOrDefault(key, Long.MAX_VALUE); }
    public synchronized boolean enroll(String key, long start, long due, Instant now) {
        int index = -1;
        for (int i = 0; i < records.size(); i++) {
            var r = records.get(i);
            if (r.subscription().equals(key) && r.startTime() == start) { index = i; break; }
        }
        if (index < 0) records.add(new Delivery(key, start, due, Status.READY, "", now.toEpochMilli(), new History(start, List.of())));
        else {
            var old = records.get(index);
            if (old.dueAt() != due && (old.status() == Status.READY || old.status() == Status.QUIET
                    || old.status() == Status.EXPIRED || old.status() == Status.NOT_SENT))
                records.set(index, new Delivery(key, start, due, Status.READY, "", now.toEpochMilli(), new History(start, List.of())));
        }
        return save();
    }
    public synchronized boolean update(Delivery old, Status status, String reason, Instant now, History history) {
        int index = records.indexOf(old);
        if (index < 0) return false;
        records.set(index, new Delivery(old.subscription(), old.startTime(), old.dueAt(), status,
                reason == null ? "" : reason, now.toEpochMilli(), history));
        boolean saved = save();
        if (!saved && status == Status.SENDING) records.set(index, old);
        return saved;
    }
    public synchronized boolean update(Delivery old, Status status, String reason, Instant now) {
        return update(old, status, reason, now, old.history());
    }
    private boolean save() {
        if (unreadable) return false;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            restrict(path);
            DurableFiles.replace(path, JSONObject.of("subscriptions", subscriptions, "records", records).toJSONString(),
                    DurableFiles.OWNER_ONLY);
            restrict(path);
            warning = "";
            return true;
        } catch (IOException | RuntimeException failure) {
            warning = "小结发送记录无法保存，自动发送已暂停";
            return false;
        }
    }
}
