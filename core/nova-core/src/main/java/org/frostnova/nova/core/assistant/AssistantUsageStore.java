package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** 东八区用量与配置者确认；损坏时拒绝调用，不能悄悄清零额度。 */
public final class AssistantUsageStore {
    public record Acknowledgement(String actor, String at, String version) { }
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Path path;
    private final Clock clock;
    private String month;
    private String day;
    private BigDecimal spent = BigDecimal.ZERO;
    private Map<String, Integer> questions = new LinkedHashMap<>();
    private Map<String, Integer> qqQuestions = new LinkedHashMap<>();
    private Acknowledgement acknowledgement;
    private String problem;

    public AssistantUsageStore(Path directory, Clock clock) {
        this.path = directory.resolve("assistant-usage.json");
        this.clock = clock;
        if (Files.exists(path)) {
            try {
                AssistantFiles.restrict(path);
                JSONObject json = JSON.parseObject(Files.readString(path));
                if (json.getIntValue("version") != 1) throw storageError();
                month = YearMonth.parse(json.getString("month")).toString();
                day = LocalDate.parse(json.getString("day")).toString();
                spent = json.getBigDecimal("spentYuan");
                if (spent == null || spent.signum() < 0 || json.getJSONObject("questions") == null) throw storageError();
                readCounts(json.getJSONObject("questions"), questions);
                if (json.containsKey("qqQuestions")) readCounts(json.getJSONObject("qqQuestions"), qqQuestions);
                JSONObject accepted = json.getJSONObject("acknowledgement");
                if (accepted != null) acknowledgement = new Acknowledgement(accepted.getString("actor"), accepted.getString("at"), accepted.getString("version"));
            } catch (Exception error) {
                problem = "智能助手用量文件无法读取，请检查后再试";
            }
        }
        rollOver();
    }

    private static void readCounts(JSONObject object, Map<String, Integer> counts) {
        for (var entry : object.entrySet()) {
            int value = new BigDecimal(entry.getValue().toString()).intValueExact();
            if (value < 0) throw storageError();
            counts.put(entry.getKey(), value);
        }
    }

    public synchronized BigDecimal spentYuan() { rollOver(); return problem == null ? spent : null; }
    public synchronized Map<String, Integer> questionsToday() { rollOver(); return problem == null ? Map.copyOf(questions) : Map.of(); }
    public synchronized Acknowledgement acknowledgement() { return problem == null ? acknowledgement : null; }

    synchronized String problem() { return problem; }

    synchronized void requireReadable() {
        if (problem != null) throw new AssistantException("usage_unreadable", problem);
    }

    synchronized void checkBudget(AssistantProperties properties) {
        requireReadable();
        rollOver();
        if (spent.compareTo(properties.getMonthlyBudgetYuan()) >= 0) throw new AssistantException("monthly_budget", "本月智能助手额度已用完");
    }

    synchronized void checkQuestion(String account, AssistantLocalStore.QqSource qq, AssistantProperties properties) {
        requireReadable();
        rollOver();
        if (account == null || account.isBlank()) throw new AssistantException("提问账号不能为空");
        if (qq == null) {
            if (questions.getOrDefault(account, 0) >= properties.getDailyQuestionsPerAccount())
                throw new AssistantException("daily_limit", "今天的提问次数用完了");
        } else {
            if (qqQuestions.getOrDefault(qq.account(), 0) >= properties.getDailyQuestionsPerQqUser())
                throw new AssistantException("qq_user_limit", "你的聊天账号今天的提问次数用完了");
            if (qqQuestions.getOrDefault(qq.sessionKey(), 0) >= properties.getDailyQuestionsPerQqSession())
                throw new AssistantException("qq_session_limit", "这个会话今天的提问次数用完了");
        }
        checkBudget(properties);
    }

    synchronized void startQuestion(String account, AssistantProperties properties) {
        startQuestion(account, null, properties);
    }

    synchronized void startQuestion(String account, AssistantLocalStore.QqSource qq, AssistantProperties properties) {
        checkQuestion(account, qq, properties);
        if (qq != null) {
            Map<String, Integer> updated = new LinkedHashMap<>(qqQuestions);
            updated.merge(qq.account(), 1, Integer::sum);
            updated.merge(qq.sessionKey(), 1, Integer::sum);
            persist(spent, questions, updated, acknowledgement);
            qqQuestions = updated;
            return;
        }
        Map<String, Integer> updated = new LinkedHashMap<>(questions);
        updated.merge(account, 1, Integer::sum);
        persist(spent, updated, qqQuestions, acknowledgement);
        questions = updated;
    }

    synchronized void addCost(BigDecimal cost) {
        requireReadable();
        rollOver();
        if (cost == null || cost.signum() < 0) throw storageError();
        BigDecimal updated = spent.add(cost);
        persist(updated, questions, qqQuestions, acknowledgement);
        spent = updated;
    }

    synchronized void acknowledge(String actor, String version) {
        requireReadable();
        rollOver();
        if (actor == null || actor.isBlank()) throw new AssistantException("确认人不能为空");
        Acknowledgement updated = new Acknowledgement(actor, clock.instant().toString(), version);
        persist(spent, questions, qqQuestions, updated);
        acknowledgement = updated;
    }

    private void rollOver() {
        if (problem != null) return;
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        String currentMonth = YearMonth.from(today).toString();
        if (!currentMonth.equals(month)) { month = currentMonth; spent = BigDecimal.ZERO; }
        if (!today.toString().equals(day)) {
            day = today.toString();
            questions = new LinkedHashMap<>();
            qqQuestions = new LinkedHashMap<>();
        }
    }

    private void persist(BigDecimal cost, Map<String, Integer> counts, Map<String, Integer> qqCounts, Acknowledgement accepted) {
        try {
            AssistantFiles.write(path, JSONObject.of("version", 1, "month", month, "day", day,
                    "spentYuan", cost, "questions", counts, "qqQuestions", qqCounts, "acknowledgement", accepted).toJSONString());
        } catch (Exception error) { throw storageError(); }
    }

    private static AssistantException storageError() { return new AssistantException("usage_unreadable", "智能助手用量文件无法读写，请检查后再试"); }
}
