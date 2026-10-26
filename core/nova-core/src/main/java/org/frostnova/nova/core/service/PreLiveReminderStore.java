package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 每条订阅只保存提醒日期和状态；发送意图落盘成功后才允许入队。 */
public final class PreLiveReminderStore {
    public enum Status { SENDING, SENT, FAILED, UNCERTAIN, NOT_SENT }
    private final Path path;
    private final Map<String, Map<String, Status>> deliveries = new LinkedHashMap<>();
    private boolean unreadable;

    public PreLiveReminderStore(Path path) {
        this.path = path;
        try {
            if (!Files.exists(path)) {
                unreadable = Files.exists(marker());
                return;
            }
            restrict(path);
            var root = JSON.parseObject(Files.readString(path));
            if (root == null) throw new IOException("缺少提醒记录");
            for (String subscription : root.keySet()) {
                if (subscription.isBlank() || !(root.get(subscription) instanceof JSONObject dates))
                    throw new IOException("提醒记录格式不正确");
                var statuses = new LinkedHashMap<String, Status>();
                for (String date : dates.keySet()) {
                    LocalDate.parse(date);
                    var status = Status.valueOf(dates.getString(date));
                    statuses.put(date, status == Status.SENDING ? Status.UNCERTAIN : status);
                }
                deliveries.put(subscription, statuses);
            }
            Files.deleteIfExists(marker());
        } catch (IOException | RuntimeException failure) {
            unreadable = true;
            try { DurableFiles.replace(marker(), "UNREADABLE", DurableFiles.OWNER_ONLY); }
            catch (IOException | RuntimeException ignored) { /* 原记录保留，重启仍会停发。 */ }
        }
    }

    private Path marker() { return path.resolveSibling(path.getFileName() + ".unreadable"); }

    public synchronized boolean contains(String subscription, LocalDate date) {
        return unreadable || deliveries.getOrDefault(subscription, Map.of()).containsKey(date.toString());
    }

    public synchronized boolean reserve(String subscription, LocalDate date) {
        return reserve(subscription, date, date);
    }

    public synchronized boolean reserve(String subscription, LocalDate date, LocalDate dueDate) {
        if (contains(subscription, date) || contains(subscription, dueDate)) return false;
        var dates = deliveries.computeIfAbsent(subscription, k -> new LinkedHashMap<>());
        dates.put(date.toString(), Status.SENDING);
        dates.put(dueDate.toString(), Status.SENDING);
        return save();
    }

    public synchronized void result(String subscription, LocalDate date, Status status) {
        result(subscription, date, date, status);
    }

    public synchronized void result(String subscription, LocalDate date, LocalDate dueDate, Status status) {
        var dates = deliveries.get(subscription);
        if (dates == null || !dates.containsKey(date.toString()) || !dates.containsKey(dueDate.toString())) return;
        dates.put(date.toString(), status);
        dates.put(dueDate.toString(), status);
        save();
    }

    private boolean save() {
        if (unreadable) return false;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            restrict(path);
            DurableFiles.replace(path, JSON.toJSONString(deliveries), DurableFiles.OWNER_ONLY);
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static void restrict(Path file) throws IOException {
        if (Files.isRegularFile(file) && Files.getFileAttributeView(file,
                java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(file, DurableFiles.OWNER_ONLY);
    }
}
