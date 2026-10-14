package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 每次变更立即刷盘；落盘失败时不开始发送。 */
public final class WeeklyReportDeliveryStore {
    public enum Status { READY, QUIET, SENDING, SENT, FAILED, UNCERTAIN, NOT_SENT, EXPIRED }
    public record Delivery(String subscription, long dueAt, Status status, String reason, long updatedAt) {}
    private final Path path;
    private long lastSeen;
    private long period;
    private List<String> subscriptions = new ArrayList<>();
    private List<Delivery> records = new ArrayList<>();

    public WeeklyReportDeliveryStore(Path path) {
        this.path = path;
        try {
            if (!Files.exists(path)) return;
            var root = JSON.parseObject(Files.readString(path));
            lastSeen = root.getLongValue("lastSeen");
            period = root.getLongValue("period");
            subscriptions = root.getList("subscriptions", String.class);
            records = root.getList("records", Delivery.class);
            if (subscriptions == null || records == null) throw new IOException("周报记录缺少必要字段");
            records = new ArrayList<>(records);
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
        } catch (IOException e) {
            throw new UncheckedIOException("读取周报记录失败，停止自动发送", e);
        }
    }
    public synchronized long lastSeen() { return lastSeen; }
    public synchronized long period() { return period; }
    public synchronized List<String> subscriptions() { return List.copyOf(subscriptions); }
    public synchronized List<Delivery> records() { return List.copyOf(records); }

    public synchronized void enroll(long due, List<String> keys, Instant now) {
        for (String key : keys) {
            if (records.stream().noneMatch(r -> r.subscription().equals(key) && r.dueAt() == due))
                records.add(new Delivery(key, due, Status.READY, "", now.toEpochMilli()));
        }
        period = due;
        save();
    }
    public synchronized void update(Delivery previous, Status status, String reason, Instant now) {
        int i = records.indexOf(previous);
        if (i < 0) return;
        records.set(i, new Delivery(previous.subscription(), previous.dueAt(), status,
                reason == null ? "" : reason, now.toEpochMilli()));
        save();
    }
    public synchronized void observe(List<String> keys, Instant now) {
        subscriptions = new ArrayList<>(keys);
        lastSeen = now.toEpochMilli();
        long cutoff = now.atZone(ZoneId.of("Asia/Shanghai")).minusYears(1).toInstant().toEpochMilli();
        records.removeIf(r -> r.dueAt() < cutoff);
        save();
    }
    private void save() {
        var root = new JSONObject();
        root.put("lastSeen", lastSeen);
        root.put("period", period);
        root.put("subscriptions", subscriptions);
        root.put("records", records);
        try {
            if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
            DurableFiles.replace(path, root.toJSONString(), DurableFiles.OWNER_ONLY);
        } catch (IOException e) {
            throw new UncheckedIOException("保存周报记录失败，停止自动发送", e);
        }
    }
}
