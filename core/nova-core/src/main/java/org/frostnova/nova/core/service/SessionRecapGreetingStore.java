package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.DurableFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 招呼与认领只保存通道标识、时间和状态；发送意图先落盘，结果不明时不重发。 */
@Slf4j
@Service
public class SessionRecapGreetingStore {
    public enum Status { SENDING, SENT, UNCERTAIN }
    public record Greeting(String key, long sentAt, Status status, boolean consumed) {}
    private final Path path;
    private Map<String, Greeting> records = new LinkedHashMap<>();
    private boolean unreadable;

    @Autowired
    public SessionRecapGreetingStore(NovaCoreProperties properties) {
        this(Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath()
                .resolveSibling("session-recap-greetings.json"));
    }

    public SessionRecapGreetingStore(Path path) {
        this.path = path;
        try {
            String content;
            try { content = Files.readString(path); }
            catch (NoSuchFileException absent) { return; }
            restrict();
            var root = JSON.parseObject(content);
            if (root == null || !(root.get("records") instanceof JSONArray entries))
                throw new IOException("招呼记录缺少必要字段");
            for (var item : entries) {
                if (!(item instanceof JSONObject entry) || !(entry.get("key") instanceof String key) || key.isBlank()
                        || !(entry.get("sentAt") instanceof Number at) || at.longValue() < 0
                        || at.doubleValue() != at.longValue() || !(entry.get("consumed") instanceof Boolean consumed)
                        || !(entry.get("status") instanceof String status)) throw new IOException("招呼记录格式不正确");
                if (records.putIfAbsent(key, new Greeting(key, at.longValue(), Status.valueOf(status), consumed)) != null)
                    throw new IOException("招呼记录重复");
            }
        } catch (IOException | RuntimeException failure) {
            unreadable = true;
            log.warn("小结招呼记录读不出，恢复记录并重启前暂停招呼和数字选档");
        }
    }

    public synchronized boolean contains(String key) { return unreadable || records.containsKey(key); }

    public synchronized boolean begin(String key, Instant now) throws IOException {
        if (contains(key)) return false;
        replace(key, new Greeting(key, now.toEpochMilli(), Status.SENDING, false));
        return true;
    }

    public synchronized void complete(String key, Status status, Instant now) throws IOException {
        var old = records.get(key);
        if (old == null) return;
        replace(key, new Greeting(key, status == Status.SENT ? now.toEpochMilli() : old.sentAt(), status, old.consumed()));
    }

    /** 已确认没有进入发送队列或没有送达，留到下个合适时刻重试。 */
    public synchronized void retry(String key) throws IOException { replace(key, null); }

    public synchronized boolean pending(String key, Instant now) {
        var record = records.get(key);
        return !unreadable && record != null && !record.consumed()
                && !now.isBefore(Instant.ofEpochMilli(record.sentAt()))
                && now.isBefore(Instant.ofEpochMilli(record.sentAt()).plus(Duration.ofHours(24)));
    }

    public synchronized boolean consume(String key, Instant now) throws IOException {
        if (!pending(key, now)) return false;
        var old = records.get(key);
        replace(key, new Greeting(key, old.sentAt(), old.status(), true));
        return true;
    }

    private void replace(String key, Greeting value) throws IOException {
        if (unreadable) throw new IOException("招呼记录读不出");
        var next = new LinkedHashMap<>(records);
        if (value == null) next.remove(key); else next.put(key, value);
        Files.createDirectories(path.toAbsolutePath().getParent());
        restrict();
        DurableFiles.replace(path, JSONObject.of("records", next.values()).toJSONString(), DurableFiles.OWNER_ONLY);
        restrict();
        records = next;
    }

    private void restrict() throws IOException {
        if (Files.isRegularFile(path) && Files.getFileAttributeView(path,
                java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(path, DurableFiles.OWNER_ONLY);
    }
}
