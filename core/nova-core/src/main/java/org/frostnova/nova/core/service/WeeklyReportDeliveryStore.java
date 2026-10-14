package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 每次变更立即刷盘；落盘失败时不开始发送。 */
@Slf4j
public final class WeeklyReportDeliveryStore {
    public enum Status { READY, QUIET, SENDING, SENT, FAILED, UNCERTAIN, NOT_SENT, EXPIRED }
    public record Delivery(String subscription, long dueAt, Status status, String reason, long updatedAt) {}
    private final Path path;
    private long lastSeen;
    private long period;
    private List<String> subscriptions = new ArrayList<>();
    private List<Delivery> records = new ArrayList<>();
    private String readWarning = "";
    private String writeWarning = "";
    private Path backup;
    private boolean quarantinePending;

    public WeeklyReportDeliveryStore(Path path) {
        this.path = path;
        if (!Files.exists(path)) return;
        try {
            var root = JSON.parseObject(Files.readString(path));
            if (root == null) throw new IOException("周报记录为空");
            long loadedLastSeen = number(root, "lastSeen");
            long loadedPeriod = number(root, "period");
            if (!(root.get("subscriptions") instanceof JSONArray keys)
                    || !(root.get("records") instanceof JSONArray entries))
                throw new IOException("周报记录缺少必要数组");
            var loadedSubscriptions = new ArrayList<String>();
            for (var key : keys) {
                if (!(key instanceof String value) || value.isBlank()) throw new IOException("周报订阅格式不正确");
                loadedSubscriptions.add(value);
            }
            var loadedRecords = new ArrayList<Delivery>();
            for (var entry : entries) {
                if (!(entry instanceof JSONObject r)) throw new IOException("周报记录格式不正确");
                var status = Status.valueOf(string(r, "status"));
                loadedRecords.add(new Delivery(string(r, "subscription"), number(r, "dueAt"), status,
                        string(r, "reason"), number(r, "updatedAt")));
            }
            lastSeen = loadedLastSeen;
            period = loadedPeriod;
            subscriptions = loadedSubscriptions;
            records = loadedRecords;
        } catch (IOException | RuntimeException e) {
            backup = path.resolveSibling(path.getFileName() + ".corrupt-" + Instant.now().toEpochMilli()
                    + "-" + java.util.UUID.randomUUID());
            quarantinePending = true;
            log.error("周报记录文件损坏，将另存为 {}，从空记录重新开始", backup, e);
            quarantine();
            return;
        }
        boolean recovered = false;
        for (int i = 0; i < records.size(); i++) {
            var r = records.get(i);
            if (r.status() == Status.SENDING) {
                records.set(i, new Delivery(r.subscription(), r.dueAt(), Status.UNCERTAIN,
                        "发送途中程序停止，未自动重发", r.updatedAt()));
                recovered = true;
            }
        }
        if (recovered) save();
    }
    private static long number(JSONObject object, String key) throws IOException {
        if (!(object.get(key) instanceof Number value) || value.longValue() < 0
                || value.doubleValue() != value.longValue()) throw new IOException("周报记录字段不正确：" + key);
        return value.longValue();
    }
    private static String string(JSONObject object, String key) throws IOException {
        if (!(object.get(key) instanceof String value) || (!key.equals("reason") && value.isBlank()))
            throw new IOException("周报记录字段不正确：" + key);
        return value;
    }
    private boolean quarantine() {
        try {
            Files.move(path, backup);
            quarantinePending = false;
            readWarning = "周报记录文件损坏，已另存为 " + backup.getFileName() + "，已重新开始记录";
            return true;
        } catch (IOException | RuntimeException e) {
            writeWarning = "损坏文件无法另存：" + e.getMessage();
            log.error("损坏的周报记录无法另存为 {}，自动发送已暂停", backup, e);
            return false;
        }
    }
    public synchronized long lastSeen() { return lastSeen; }
    public synchronized long period() { return period; }
    public synchronized List<String> subscriptions() { return List.copyOf(subscriptions); }
    public synchronized List<Delivery> records() { return List.copyOf(records); }
    public synchronized String warning() {
        return writeWarning.isEmpty() ? readWarning : "周报记录无法保存，自动发送已暂停：" + writeWarning;
    }
    public synchronized boolean ensureSaved() { return writeWarning.isEmpty() || save(); }

    public synchronized boolean enroll(long due, List<String> keys, Instant now) {
        for (String key : keys) {
            if (records.stream().noneMatch(r -> r.subscription().equals(key) && r.dueAt() == due))
                records.add(new Delivery(key, due, Status.READY, "", now.toEpochMilli()));
        }
        period = due;
        boolean saved = save();
        if (saved && !keys.isEmpty()) readWarning = "";
        return saved;
    }
    public synchronized boolean update(Delivery previous, Status status, String reason, Instant now) {
        int i = records.indexOf(previous);
        if (i < 0) return false;
        records.set(i, new Delivery(previous.subscription(), previous.dueAt(), status,
                reason == null ? "" : reason, now.toEpochMilli()));
        boolean saved = save();
        // 未成功记录发送意图时尚未入队；恢复保存后仍可处理这条待发记录。
        if (!saved && status == Status.SENDING) records.set(i, previous);
        if (saved) readWarning = "";
        return saved;
    }
    public synchronized void observe(List<String> keys, Instant now) {
        subscriptions = new ArrayList<>(keys);
        lastSeen = now.toEpochMilli();
        long cutoff = now.atZone(ZoneId.of("Asia/Shanghai")).minusYears(1).toInstant().toEpochMilli();
        records.removeIf(r -> r.dueAt() < cutoff);
        save();
    }
    private boolean save() {
        if (quarantinePending && !quarantine()) return false;
        try {
            var root = new JSONObject();
            root.put("lastSeen", lastSeen);
            root.put("period", period);
            root.put("subscriptions", subscriptions);
            root.put("records", records);
            if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
            DurableFiles.replace(path, root.toJSONString(), DurableFiles.OWNER_ONLY);
            writeWarning = "";
            return true;
        } catch (IOException | RuntimeException e) {
            writeWarning = e instanceof AccessDeniedException ? "没有写入权限（" + path + "）"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("周报记录无法保存到 {}，自动发送已暂停", path, e);
            return false;
        }
    }
}
