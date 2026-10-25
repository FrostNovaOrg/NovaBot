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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.function.UnaryOperator;

import static org.frostnova.nova.core.service.StreamerProfile.*;

/** 每位主播独立落盘。读错状态另留标记，坏件移走和程序重启后仍禁止自动投递。 */
@Slf4j
@Service
public class StreamerProfileStore {
    private final Path directory;

    @Autowired
    public StreamerProfileStore(NovaCoreProperties properties) {
        this(Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath().resolveSibling("streamer-profiles"));
    }
    public StreamerProfileStore(Path directory) { this.directory = directory; }

    public Path path(String platform, long uid) {
        if (platform == null || platform.isBlank() || uid <= 0) throw new IllegalArgumentException("主播编号不正确");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(platform.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded + "-" + uid + ".json");
    }
    private Path marker(Path path) { return path.resolveSibling(path.getFileName() + ".unreadable"); }

    public synchronized StreamerProfile read(String platform, long uid) {
        Path path = path(platform, uid);
        Status status;
        String content;
        try {
            try {
                Files.readAttributes(marker(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                status = Status.UNREADABLE;
            } catch (NoSuchFileException absent) {
                status = Status.READY;
            }
            try {
                content = Files.readString(path);
            } catch (NoSuchFileException absent) {
                return StreamerProfile.empty(status);
            }
        } catch (CharacterCodingException failure) {
            return unreadable(path);
        } catch (IOException | RuntimeException failure) {
            log.warn("主播档案暂时读不出：{}，本次停止自动投递，下次重试", path);
            return StreamerProfile.empty(Status.TEMPORARILY_UNREADABLE);
        }
        try {
            var root = JSON.parseObject(content);
            if (root == null) throw new IOException("档案为空");
            long tier = number(root, "tier", false);
            if (tier != 1 && tier != 2) throw new IOException("档位不正确");
            if (!(root.get("paused") instanceof Boolean paused)
                    || !(root.get("concerns") instanceof JSONArray concerns)
                    || !(root.get("exclusions") instanceof JSONArray exclusions)) throw new IOException("档案缺少必要字段");
            var loadedConcerns = new ArrayList<Concern>();
            var loadedExclusions = new ArrayList<Exclusion>();
            var uniqueText = new HashSet<String>();
            var uniquePatterns = new HashSet<Pattern>();
            for (var item : concerns) {
                if (!(item instanceof JSONObject entry) || !(entry.get("text") instanceof String text)
                        || text.isBlank() || text.codePointCount(0, text.length()) > 30 || !uniqueText.add(text))
                    throw new IOException("在意的条目不正确");
                loadedConcerns.add(new Concern(text, number(entry, "setAt", false)));
            }
            for (var item : exclusions) {
                if (!(item instanceof JSONObject entry) || !(entry.get("type") instanceof String type))
                    throw new IOException("别提的条目不正确");
                Pattern pattern = Pattern.valueOf(type);
                if (!uniquePatterns.add(pattern)) throw new IOException("别提的条目重复");
                loadedExclusions.add(new Exclusion(pattern, number(entry, "setAt", false)));
            }
            return new StreamerProfile((int) tier, number(root, "tierSetAt", true), loadedConcerns,
                    loadedExclusions, paused, number(root, "pauseSetAt", true), status);
        } catch (IOException | RuntimeException failure) {
            return unreadable(path);
        }
    }
    private StreamerProfile unreadable(Path path) {
        // 先记状态再移原件；状态写不成时保留原件，下一次读取仍会报错。
        log.warn("主播档案读不出：{}，使用空档案并停止自动投递；主播可在本人私聊说「恢复」，或在控制台点击「档案已核对，恢复」", path);
        try {
            Files.createDirectories(directory);
            DurableFiles.replace(marker(path), "UNREADABLE", DurableFiles.OWNER_ONLY);
            restrict(marker(path));
            restrict(path);
            Path backup = DurableFiles.quarantine(path);
            restrict(backup);
        } catch (IOException | RuntimeException backupFailure) {
            log.warn("主播档案坏件无法另存：{}", path);
        }
        return StreamerProfile.empty(Status.UNREADABLE);
    }
    private static Long number(JSONObject object, String key, boolean nullable) throws IOException {
        if (nullable && object.containsKey(key) && object.get(key) == null) return null;
        if (!(object.get(key) instanceof Number n) || n.longValue() < 0 || n.doubleValue() != n.longValue())
            throw new IOException("档案时间或档位不正确");
        return n.longValue();
    }
    private static void restrict(Path path) throws IOException {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(path, DurableFiles.OWNER_ONLY);
    }

    /** 成功落盘后才向调用者返回；保留读错状态，设置命令不会悄悄恢复自动投递。 */
    public synchronized StreamerProfile update(String platform, long uid, UnaryOperator<StreamerProfile> change) throws IOException {
        return update(platform, uid, change, false);
    }

    /** 主播明确恢复；坏件状态下从空档案重新开始，正常档案保留其他设置。 */
    public synchronized StreamerProfile resume(String platform, long uid, long at) throws IOException {
        return update(platform, uid, p -> new StreamerProfile(p.tier(), p.tierSetAt(), p.concerns(),
                p.exclusions(), false, at, p.status()), true);
    }

    /** 控制台核对坏件后重置；重复提交不会清空已恢复的正常档案。 */
    public synchronized StreamerProfile recover(String platform, long uid) throws IOException {
        return update(platform, uid, UnaryOperator.identity(), true);
    }

    private StreamerProfile update(String platform, long uid, UnaryOperator<StreamerProfile> change,
                                   boolean recover) throws IOException {
        StreamerProfile before = read(platform, uid);
        if (before.status() == Status.TEMPORARILY_UNREADABLE) throw new IOException("档案暂时读不出");
        StreamerProfile base = recover && before.status() == Status.UNREADABLE
                ? StreamerProfile.empty(Status.READY) : before;
        StreamerProfile next = change.apply(base);
        if (next.status() != base.status()) throw new IllegalArgumentException("不能通过设置清除档案读错状态");
        if (next.equals(before)) return next;
        Path path = path(platform, uid);
        Files.createDirectories(directory);
        if (before.status() == Status.UNREADABLE && !Files.exists(marker(path)))
            throw new IOException("档案读错状态尚未保存");
        var root = new JSONObject();
        root.put("tier", next.tier()); root.put("tierSetAt", next.tierSetAt());
        root.put("concerns", next.concerns()); root.put("exclusions", next.exclusions());
        root.put("paused", next.paused()); root.put("pauseSetAt", next.pauseSetAt());
        // 空时间也写出，避免与缺字段的坏件混淆。
        String content = JSON.toJSONString(root, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
        if (Files.exists(path)) restrict(path);
        DurableFiles.replace(path, content, DurableFiles.OWNER_ONLY);
        restrict(path);
        // 先把恢复后的档案写好，再清状态；任何一步失败都不能回报恢复成功。
        if (recover) Files.deleteIfExists(marker(path));
        return next;
    }
}
