package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantMemoStore;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 主播页智能助手；主播范围与写请求检查由控制台权限层统一执行。 */
@Slf4j
@RestController
@NovaComponent
@RequestMapping("/config/api/streamers/{platform}/{uid}/assistant")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AssistantAskController {
    private static final int COMPARISON_LIMIT = 5;
    private final AssistantAskService service;
    private final AssistantMemoStore memos;
    private final AbstractDataSource dataSource;
    private final StreamerDirectory directory;
    private final StreamerNames names;
    private final LiveSessionArchive archive;

    public AssistantAskController(AssistantAskService service, AssistantLocalStore local, AbstractDataSource dataSource,
                                  StreamerDirectory directory, StreamerNames names, LiveSessionArchive archive) {
        this.service = service;
        this.memos = local.memos();
        this.dataSource = dataSource;
        this.directory = directory;
        this.names = names;
        this.archive = archive;
    }

    @GetMapping("/state")
    public AssistantClient.State state(@PathVariable String platform, @PathVariable Long uid, HttpServletRequest request) {
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
        return service.state(platform, uid);
    }

    @PostMapping("/ask")
    public JSONObject ask(@PathVariable String platform, @PathVariable Long uid, HttpServletRequest request,
                          HttpServletResponse response) {
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
        JSONObject body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw new AssistantException("提问内容过长");
            body = JSON.parseObject(bytes);
        } catch (AssistantException error) { throw error; }
        catch (Exception error) { throw new AssistantException("提问格式不正确"); }
        List<Long> streamers = selectStreamers(platform, uid, account, body);
        var progress = new QueueResponse(response, "1".equals(request.getHeader("X-Assistant-Progress")));
        JSONObject result;
        try {
            result = service.ask(platform, streamers, account.id(), body, AssistantQueryContext.CONSOLE, progress);
            result.put("streamers", streamers.stream().map(String::valueOf).toList());
            AssistantAskService.logQuestion(log, ConfigUiAccess.actor(), platform, uid, result);
            if (account.role() != ConfigUiAccount.Role.ADMIN) {
                result.remove("costYuan");
                result.remove("remaining");
            }
        } catch (AssistantException error) {
            if (!response.isCommitted()) throw error;
            result = error(error).getBody();
        }
        if (!response.isCommitted()) return result;
        progress.finish(result);
        return null;
    }

    /** 排队时才开始分段写回。普通 JSON 调用方只收到合法的前导空白和最终结果。 */
    private record QueueResponse(HttpServletResponse response, boolean showProgress) implements AssistantQuestionQueue.Progress {
        @Override public void queued(int ahead) {
            response.setContentType(showProgress ? "application/x-ndjson" : "application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            write(showProgress ? JSONObject.of("ahead", ahead) : null);
        }

        @Override public void heartbeat() { write(null); }

        @Override public void started() { write(showProgress ? JSONObject.of("started", true) : null); }

        void finish(JSONObject result) { write(showProgress ? JSONObject.of("result", result) : result); }

        private void write(JSONObject frame) {
            try {
                var output = response.getOutputStream();
                if (frame != null) output.write(JSON.toJSONBytes(frame));
                output.write('\n');
                response.flushBuffer();
            } catch (IOException disconnected) {
                throw new UncheckedIOException(disconnected);
            }
        }
    }

    private List<Long> selectStreamers(String platform, Long uid, ConfigUiAccount account, JSONObject body) {
        if (body == null || !(body.get("question") instanceof String question) || question.isBlank()
                || question.codePointCount(0, question.length()) > 300) return List.of(uid);

        var candidates = new LinkedHashSet<Long>();
        candidates.add(uid);
        directory.entries().stream().filter(entry -> platform.equals(entry.platform()))
                .map(StreamerDirectory.Entry::uid).forEach(candidates::add);
        dataSource.getUsers(platform).stream().map(PushUser::getUid).forEach(candidates::add);
        if (account.role() == ConfigUiAccount.Role.ADMIN) candidates.addAll(archive.streamerUids(platform));
        else account.streamers().stream().map(ConfigUiStreamerId::parse).filter(Objects::nonNull)
                .filter(id -> platform.equals(id.platform())).map(ConfigUiStreamerId::uid).forEach(candidates::add);

        var mentions = AssistantStreamerMentions.namedIn(question, candidates.stream()
                .filter(candidate -> candidate != null && candidate > 0 && ConfigUiAccess.visible(platform, candidate))
                .map(candidate -> candidateName(platform, candidate)).toList());
        if (mentions.ambiguous()) return List.of(uid);

        var selected = new LinkedHashSet<Long>();
        selected.add(uid);
        mentions.streamers().forEach(candidate -> selected.add(candidate.uid()));
        if (selected.size() == 1 && body.get("streamers") instanceof List<?> previous) {
            for (Object value : previous) {
                Long previousUid = ConfigUiStreamerId.positiveDecimal(value);
                if (previousUid != null && ConfigUiAccess.visible(platform, previousUid)) selected.add(previousUid);
            }
        }
        if (selected.size() > COMPARISON_LIMIT) throw new AssistantException("一次最多比 5 位主播，请少点几位。");
        return List.copyOf(selected);
    }

    private StreamerCandidate candidateName(String platform, Long uid) {
        var loaded = dataSource.getUser(platform, uid).orElse(null);
        return new StreamerCandidate(uid, names.uname(platform, uid, loaded == null ? null : loaded.getUname()),
                loaded == null ? null : loaded.getRoomId());
    }

    private record StreamerCandidate(Long uid, String nickname, Long roomId) implements AssistantStreamerMentions.Candidate { }

    @GetMapping("/memos")
    public ResponseEntity<JSONObject> memos(@PathVariable String platform, @PathVariable Long uid) {
        return memoResponse(memos.list(platform, uid));
    }

    @PostMapping("/memos")
    public ResponseEntity<JSONObject> addMemo(@PathVariable String platform, @PathVariable Long uid, HttpServletRequest request) {
        MemoInput input = memoInput(request);
        return memoResponse(memos.add(platform, uid, input.text(), input.expiresOn()));
    }

    @PutMapping("/memos/{id}")
    public ResponseEntity<JSONObject> updateMemo(@PathVariable String platform, @PathVariable Long uid,
                                                  @PathVariable String id, HttpServletRequest request) {
        MemoInput input = memoInput(request);
        return memoResponse(memos.update(platform, uid, id, input.text(), input.expiresOn()));
    }

    @DeleteMapping("/memos/{id}")
    public ResponseEntity<JSONObject> deleteMemo(@PathVariable String platform, @PathVariable Long uid, @PathVariable String id) {
        return memoResponse(memos.delete(platform, uid, id));
    }

    @DeleteMapping("/memos")
    public ResponseEntity<JSONObject> resetUnreadableMemos(@PathVariable String platform, @PathVariable Long uid) {
        return memoResponse(memos.resetUnreadable(platform, uid));
    }

    private ResponseEntity<JSONObject> memoResponse(List<AssistantMemoStore.Memo> items) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(JSONObject.of(
                "success", true, "today", memos.today().toString(), "items", items.stream()
                        .map(memo -> JSONObject.of("id", memo.id(), "text", memo.text(), "createdOn", memo.createdOn().toString(),
                                "expiresOn", memo.expiresOn() == null ? null : memo.expiresOn().toString()))
                        .toList()));
    }

    private record MemoInput(String text, LocalDate expiresOn) { }

    private static MemoInput memoInput(HttpServletRequest request) {
        JSONObject body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new AssistantException("备忘内容须为 1–60 字");
            body = JSON.parseObject(bytes);
        } catch (AssistantException error) { throw error; }
        catch (Exception error) { throw new AssistantException("备忘格式不正确"); }
        if (body == null || !(body.get("text") instanceof String text))
            throw new AssistantException("备忘内容须为 1–60 字");
        Object raw = body.get("expiresOn");
        if (raw == null) return new MemoInput(text, null);
        if (raw instanceof String date && date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !date.startsWith("0000")) {
            try { return new MemoInput(text, LocalDate.parse(date)); }
            catch (DateTimeParseException ignored) { }
        }
        throw new AssistantException("截止日格式不正确");
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<JSONObject> error(AssistantException error) {
        HttpStatus status = switch (error.reason()) {
            case "memo_missing" -> HttpStatus.NOT_FOUND;
            case AssistantMemoStore.UNREADABLE, "memo_unavailable" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "memo_readable" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(JSONObject.of("success", false, "reason", error.reason(), "message", error.getMessage()));
    }
}
