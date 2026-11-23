package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.console.assistant.AssistantQueryService.AudienceIdentity;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantLocalStore;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 每问独立保存昵称映射，原始观众身份留在本机。 */
final class AssistantPrivacy {
    private final AssistantLocalStore local;
    private final String platform;
    private final Set<Long> excluded;
    private final Long streamerUid;
    private final Supplier<Map<Long, AudienceIdentity>> audience;
    private final Set<String> knownUids = new HashSet<>();
    private Map<Long, AudienceIdentity> audienceIdentities;
    private static final Pattern IDENTIFIER = Pattern.compile("[0-9]{5,}");
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, String> replacements = new LinkedHashMap<>();
    private static final Pattern ALIAS = Pattern.compile("观众-[0-9a-fA-F]{6}");
    private static final Pattern ALIAS_OR_IDENTIFIER = Pattern.compile(ALIAS.pattern() + "|" + IDENTIFIER.pattern());
    private Pattern replacementPattern;
    private final Set<String> dataTypes = new LinkedHashSet<>();
    private static final Set<String> IDENTITIES = Set.of("uid", "uname", "platform", "roomId", "userUid", "userName", "userSets");

    AssistantPrivacy(AssistantLocalStore local, String platform, Long streamerUid, List<Long> excluded,
                     Supplier<Map<Long, AudienceIdentity>> audience) {
        this.local = local;
        this.platform = platform;
        this.excluded = Set.copyOf(excluded);
        this.streamerUid = streamerUid;
        this.audience = audience;
        knownUids.add(streamerUid.toString());
        excluded.forEach(uid -> knownUids.add(uid.toString()));
        names.putAll(local.nicknames());
        names.forEach((alias, name) -> replacements.put(name, alias));
        excluded.forEach(uid -> {
            String alias = local.alias(platform, uid);
            replacements.put(alias, "[已隐藏]");
            String name = names.remove(alias);
            if (name != null) replacements.put(name, "[已隐藏]");
        });
    }

    JSONObject prepare(String tool, JSONObject raw, AssistantQueryContext context) {
        learn(raw);
        replacementPattern = null;
        JSONObject result = (JSONObject) copy(raw);
        if ("revenue".equals(tool) && context.amountVisible()) {
            JSONArray contributors = new JSONArray();
            var items = raw.getJSONArray("contributors");
            BigDecimal total = raw.getBigDecimal("totalAmount");
            if (items != null) for (Object item : items) {
                JSONObject row = (JSONObject) item;
                Long uid = row.getLong("uid");
                if (uid == null || excluded.contains(uid)) continue;
                BigDecimal amount = row.getBigDecimal("amount");
                contributors.add(JSONObject.of("alias", local.alias(platform, uid), "share",
                        total == null || total.signum() <= 0 || amount == null ? null : amount.divide(total, MathContext.DECIMAL64)));
            }
            result.put("contributors", items == null ? null : contributors);
            if (!contributors.isEmpty()) dataTypes.add("贡献者代号与占比 " + contributors.size() + " 位");
        }
        if (AssistantAggregateRequest.NAME.equals(tool)) {
            var columns = raw.getJSONArray("columns");
            int audienceColumn = columns == null ? -1 : columns.indexOf("audience");
            if (audienceColumn >= 0) {
                var rows = raw.getJSONArray("rows");
                var masked = result.getJSONArray("rows");
                for (int i = 0; i < rows.size(); i++) {
                    var cell = rows.getJSONArray(i).getJSONObject(audienceColumn);
                    masked.getJSONArray(i).set(audienceColumn, mask(cell.getString("alias")));
                }
                if (!rows.isEmpty()) dataTypes.add("观众代号 " + rows.size() + " 行");
            }
        }
        if (AssistantSqlRequest.NAME.equals(tool) && result.get("rows") instanceof JSONArray rows) {
            if (learnSqlAudience(rows.stream().map(JSONArray.class::cast).flatMap(Collection::stream))) dataTypes.add("观众代号");
        }
        dataTypes.add("汇总数字");
        return result;
    }

    boolean canAttribute(Long uid) { return uid != null && uid > 0 && !excluded.contains(uid); }

    LongFunction<String> aliases() { return local.aliases(platform); }

    /** 查询结果仍用代号，昵称只写入本机的显示映射。 */
    boolean learnSqlAudience(Stream<?> cells) {
        var aliases = cells.filter(String.class::isInstance).map(String.class::cast)
                .flatMap(text -> ALIAS.matcher(text).results())
                .map(match -> match.group().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (aliases.isEmpty()) return false;
        aliases.removeIf(alias -> "[已隐藏]".equals(replacements.get(alias)));
        if (aliases.isEmpty()) return true;

        Map<Long, AudienceIdentity> identities;
        try {
            identities = loadAudience();
        } catch (AssistantException unavailable) {
            // 本地档案读不到时，沿用已有的昵称映射。
            return true;
        }
        if (identities.isEmpty()) return true;
        var encode = aliases();
        for (var entry : identities.entrySet()) {
            Long uid = entry.getKey();
            var identity = entry.getValue();
            if (!canAttribute(uid) || streamerUid.equals(uid) || identity.latestNickname() == null) continue;
            String alias = encode.apply(uid);
            if (!aliases.contains(alias)) continue;
            names.put(alias, identity.latestNickname());
            local.rememberNickname(alias, identity.latestNickname());
            identity.nicknames().forEach(nickname -> replacements.putIfAbsent(nickname, alias));
            aliases.remove(alias);
            if (aliases.isEmpty()) break;
        }
        replacementPattern = null;
        return true;
    }

    private void learn(Object value) {
        if (value instanceof JSONObject row) {
            if (row.get("uid") instanceof Number && row.get("uname") instanceof String name && !name.isEmpty() && !streamerUid.equals(row.getLong("uid"))) {
                Long uid = row.getLong("uid");
                String alias = excluded.contains(uid) ? "[已隐藏]" : local.alias(platform, uid);
                if (!excluded.contains(uid)) {
                    names.put(alias, name);
                    local.rememberNickname(alias, name);
                }
                replacements.put(name, alias);
                knownUids.add(uid.toString());
            }
            row.values().forEach(this::learn);
        } else if (value instanceof Collection<?> values) values.forEach(this::learn);
    }

    private Object copy(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject result = new JSONObject();
            object.forEach((key, child) -> {
                if (!IDENTITIES.contains(key) && !"amount".equals(key) && !"contributors".equals(key)
                        && !"text".equals(key) && !"words".equals(key)) result.put(key, copy(child));
            });
            return result;
        }
        if (value instanceof Collection<?> values) {
            JSONArray result = new JSONArray();
            values.forEach(child -> result.add(copy(child)));
            return result;
        }
        return value instanceof String text ? mask(text) : value;
    }

    /** 保留结构中的数值与键，只处理文字值，不能在序列化结果上替换昵称。 */
    Object maskValues(Object value) {
        if (value instanceof Map<?, ?> object) {
            JSONObject result = new JSONObject();
            object.forEach((key, child) -> result.put(key.toString(), maskValues(child)));
            return result;
        }
        if (value instanceof Collection<?> values) {
            JSONArray result = new JSONArray();
            values.forEach(child -> result.add(maskValues(child)));
            return result;
        }
        return value instanceof String text ? mask(text) : value;
    }

    void prepareMemos(List<String> texts) {
        // 首轮查数之前，也要从本机档案识别备忘里仅出现昵称的观众。
        loadAudience().forEach((uid, identity) -> {
            if (streamerUid.equals(uid) || excluded.contains(uid)) return;
            var mentioned = identity.nicknames().stream().filter(name -> !name.isEmpty()
                    && texts.stream().anyMatch(text -> text.contains(name))).toList();
            if (mentioned.isEmpty()) return;
            String alias = local.alias(platform, uid);
            for (String name : mentioned) {
                if (replacements.putIfAbsent(name, alias) == null) names.putIfAbsent(alias, name);
            }
        });
        replacementPattern = null;
        dataTypes.add("主播备忘");
    }

    private Map<Long, AudienceIdentity> loadAudience() {
        if (audienceIdentities == null) {
            audienceIdentities = audience.get();
            audienceIdentities.forEach((uid, identity) -> {
                if (!streamerUid.equals(uid)) knownUids.add(uid.toString());
                if (excluded.contains(uid)) identity.nicknames().forEach(name -> {
                    if (!name.isEmpty()) replacements.put(name, "[已隐藏]");
                });
            });
            replacementPattern = null;
        }
        return audienceIdentities;
    }

    String mask(String text) {
        if (text == null) return null;
        if (audienceIdentities == null && (!excluded.isEmpty() || IDENTIFIER.matcher(ALIAS.matcher(text).replaceAll("")).find()))
            loadAudience();
        if (replacementPattern == null) {
            String alternatives = replacements.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                    .map(name -> name.matches("[0-9]+") ? "(?<![0-9])" + Pattern.quote(name) + "(?![0-9])" : Pattern.quote(name))
                    .collect(Collectors.joining("|"));
            replacementPattern = Pattern.compile(alternatives.isEmpty() ? "(?!)" : alternatives);
        }
        StringBuilder result = new StringBuilder();
        Matcher aliases = ALIAS.matcher(text);
        int position = 0;
        while (aliases.find()) {
            result.append(maskPlain(text.substring(position, aliases.start())));
            result.append(replacements.getOrDefault(aliases.group(), aliases.group()));
            position = aliases.end();
        }
        return result.append(maskPlain(text.substring(position))).toString();
    }

    private String maskPlain(String text) {
        // 先匹配完整昵称，再判断连续数字；新生成的代号不参与编号替换。
        return ALIAS_OR_IDENTIFIER.matcher(maskNames(text)).replaceAll(match ->
                Matcher.quoteReplacement(knownUids.contains(match.group()) ? "[编号已隐藏]" : match.group()));
    }

    private String maskNames(String text) {
        // 一次匹配只替换原文字，生成的代号不再参与下一次昵称匹配。
        return replacementPattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(replacements.get(match.group())));
    }

    String display(String text) {
        for (var entry : names.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
        return text;
    }

    String displayChart(String text) {
        if ("[已隐藏]".equals(text)) return text;
        return ALIAS.matcher(mask(text)).replaceAll(match -> {
            String alias = match.group().toLowerCase(Locale.ROOT);
            if ("[已隐藏]".equals(replacements.get(alias))) return "[已隐藏]";
            String name = names.getOrDefault(alias, "[昵称未知]");
            return Matcher.quoteReplacement(ALIAS.matcher(name).replaceAll("[昵称未知]"));
        });
    }

    List<String> dataTypes() { return List.copyOf(dataTypes); }
}
