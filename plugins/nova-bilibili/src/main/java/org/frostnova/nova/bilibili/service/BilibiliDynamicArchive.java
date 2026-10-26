package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.model.DynamicArchiveEntry;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 每位主播一个永久保留、仅追加的文本档案。 */
@Slf4j
@NovaComponent
public class BilibiliDynamicArchive {
    private final NovaCoreProperties properties;
    private final Map<Path, Set<String>> storedIds = new HashMap<>();

    public BilibiliDynamicArchive(NovaCoreProperties properties) {
        this.properties = properties;
    }

    /** 只有写入成功后才记住 id；失败不会阻断动态推送，下轮可以重试。 */
    public synchronized void append(long uid, Dynamic dynamic, long capturedAt) {
        if (dynamic == null || dynamic.getId() == null || dynamic.getId().isBlank()) return;
        try {
            Path file = path(uid);
            Set<String> ids = storedIds.get(file);
            if (ids == null) {
                ids = new HashSet<>();
                for (DynamicArchiveEntry row : readFile(file, uid)) ids.add(row.id());
                storedIds.put(file, ids);
            }
            if (ids.contains(dynamic.getId())) return;
            String line = JSON.toJSONString(DynamicArchiveEntry.from(uid, dynamic, capturedAt), JSONWriter.Feature.WriteNulls) + "\n";
            Files.createDirectories(file.getParent());
            JsonlFiles.separateTruncatedTail(file);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            ids.add(dynamic.getId());
        } catch (Exception e) {
            log.warn("动态归档写入失败, uid={}, id={}: {}", uid, dynamic.getId(), e.getMessage());
        }
    }

    /** 按主播及毫秒时间范围读取，结束时刻不含；发布时间未知时用抓取时刻。坏行跳过。 */
    public synchronized List<DynamicArchiveEntry> read(long uid, long fromInclusive, long toExclusive) {
        try {
            return readFile(path(uid), uid).stream().filter(row -> {
                long at = row.publishedAt() == null ? row.capturedAt() : row.publishedAt();
                return at >= fromInclusive && at < toExclusive;
            }).toList();
        } catch (Exception e) {
            log.warn("动态归档读取失败, uid={}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    private List<DynamicArchiveEntry> readFile(Path file, long uid) throws IOException {
        try (Stream<String> lines = JsonlFiles.lines(file)) {
            return lines.map(line -> parse(line, uid)).filter(java.util.Objects::nonNull).toList();
        } catch (NoSuchFileException ignored) {
            return List.of();
        }
    }

    private DynamicArchiveEntry parse(String line, long uid) {
        try {
            DynamicArchiveEntry row = JSON.parseObject(line, DynamicArchiveEntry.class);
            if (row == null || row.id() == null || row.id().isBlank() || row.uid() != uid
                    || !BilibiliPlatform.BILIBILI.id().equals(row.platform())) return null;
            return row;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Path path(long uid) {
        Path parent = Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath().getParent();
        return parent.resolve("dynamics").resolve(BilibiliPlatform.BILIBILI.id() + "-" + uid + ".jsonl");
    }
}
