package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 主播备忘单独存放，只有控制台写入。 */
public final class AssistantMemoStore {
    public static final String UNREADABLE = "memo_unreadable";
    private static final int MAX_ITEMS = 10;
    private static final int MAX_TEXT = 60;
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Shanghai");
    private final Path directory;
    private final Clock clock;

    public record Memo(String id, String text, LocalDate createdOn, LocalDate expiresOn) {
        public boolean isActiveOn(LocalDate day) {
            return expiresOn == null || !expiresOn.isBefore(day);
        }
    }

    AssistantMemoStore(Path directory, Clock clock) {
        this.directory = directory.resolve("assistant-memos");
        this.clock = clock;
    }

    public LocalDate today() {
        return clock.instant().atZone(DATE_ZONE).toLocalDate();
    }

    public synchronized List<Memo> list(String platform, Long uid) {
        return List.copyOf(read(file(platform, uid)));
    }

    public List<Memo> active(String platform, Long uid) {
        var items = list(platform, uid);
        LocalDate today = today();
        return items.stream().filter(memo -> memo.isActiveOn(today)).toList();
    }

    public synchronized List<Memo> add(String platform, Long uid, String text, LocalDate expiresOn) {
        Path file = file(platform, uid);
        var items = read(file);
        if (items.size() >= MAX_ITEMS) throw new AssistantException("每位主播最多 10 条备忘");
        items.add(new Memo(UUID.randomUUID().toString(), validatedText(text), today(), expiresOn));
        return write(file, items);
    }

    public synchronized List<Memo> update(String platform, Long uid, String id, String text, LocalDate expiresOn) {
        Path file = file(platform, uid);
        var items = read(file);
        int index = indexOf(items, id);
        items.set(index, new Memo(id, validatedText(text), items.get(index).createdOn(), expiresOn));
        return write(file, items);
    }

    public synchronized List<Memo> delete(String platform, Long uid, String id) {
        Path file = file(platform, uid);
        var items = read(file);
        items.remove(indexOf(items, id));
        return write(file, items);
    }

    public synchronized List<Memo> resetUnreadable(String platform, Long uid) {
        Path file = file(platform, uid);
        List<Memo> items;
        try {
            items = read(file);
        } catch (AssistantException error) {
            if (!UNREADABLE.equals(error.reason())) throw error;
            return write(file, List.of());
        }
        if (!items.isEmpty()) throw new AssistantException("memo_readable", "备忘已经可以读取，请重新加载");
        return List.of();
    }

    private Path file(String platform, Long uid) {
        if (platform == null || !platform.matches("[A-Za-z0-9_-]{1,32}") || uid == null || uid <= 0)
            throw new AssistantException("主播标识无效");
        return directory.resolve(platform + "-" + uid + ".json");
    }

    private static ArrayList<Memo> read(Path file) {
        try {
            AssistantFiles.restrict(file);
            var items = JSON.parseArray(Files.readString(file), Memo.class);
            if (items == null || items.size() > MAX_ITEMS || items.stream().anyMatch(memo ->
                    memo == null || memo.id() == null || memo.createdOn() == null || !validText(memo.text())))
                throw new IOException("备忘格式错误");
            return new ArrayList<>(items);
        } catch (NoSuchFileException ignored) {
            return new ArrayList<>();
        } catch (IOException | JSONException | DateTimeException error) {
            throw new AssistantException(UNREADABLE, "主播备忘读不出来，请检查本地文件");
        }
    }

    private static List<Memo> write(Path file, List<Memo> items) {
        try {
            if (items.isEmpty()) {
                Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
                Files.deleteIfExists(file);
            } else {
                AssistantFiles.write(file, JSON.toJSONString(items));
            }
            return List.copyOf(items);
        } catch (IOException error) {
            throw new AssistantException("memo_unavailable", "无法保存主播备忘，请检查本地文件");
        }
    }

    private static int indexOf(List<Memo> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) return i;
        }
        throw new AssistantException("memo_missing", "没有这条备忘");
    }

    private static String validatedText(String text) {
        if (text != null) text = text.strip();
        if (!validText(text)) throw new AssistantException("备忘内容须为 1–60 字");
        return text;
    }

    private static boolean validText(String text) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= MAX_TEXT;
    }
}
