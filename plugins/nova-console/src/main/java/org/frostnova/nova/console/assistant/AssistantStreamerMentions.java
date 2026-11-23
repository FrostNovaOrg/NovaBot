package org.frostnova.nova.console.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

final class AssistantStreamerMentions {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern NON_HAN_EDGES = Pattern.compile("^[^\\p{IsHan}]+|[^\\p{IsHan}]+\\z");
    private static final Pattern QUESTION_PREFIX = Pattern.compile("^[\\p{IsWhite_Space}\\p{P}]+");
    private static final Set<String> QUERY_WORDS = Set.of(
            "最近", "近期", "上周", "本周", "这周", "上月", "本月", "这个", "今天", "昨天", "昨晚", "前天", "今晚",
            "请问", "帮我", "看看", "查查", "我想",
            "统计", "对比", "比较", "哪天", "哪场", "什么", "怎么", "多少", "每天", "每周", "过去",
            "一周", "两周", "三周", "四周",
            "流水", "收入", "直播", "弹幕", "观众", "粉丝", "礼物", "在线", "在線", "开播", "下播", "场次", "时间", "时长",
            "数据", "情况", "变化", "趋势", "平均", "高峰", "人数", "排行", "排名",
            "上个月", "这个月", "个数", "合计", "心动盲盒", "盲盒", "钟点", "各钟点平均在线", "平均在线", "送礼榜前十", "前十",
            "主播备忘", "备忘", "第一项", "目标", "目前", "完成", "收了", "播了", "多久", "几点", "几场", "怎么样");
    private static final Comparator<NameMatch<?>> NAME_MATCH_PRIORITY = Comparator
            .<NameMatch<?>>comparingInt(NameMatch::length).thenComparing(NameMatch::exact).reversed();

    private AssistantStreamerMentions() { }

    static <T extends Candidate> Mentions<T> namedIn(String question, List<T> candidates) {
        var prefix = QUESTION_PREFIX.matcher(question);
        int questionStart = prefix.lookingAt() ? prefix.end() : 0;
        var numbers = NUMBER.matcher(question).results().toList();
        var matches = new ArrayList<NameMatch<T>>();
        for (T item : candidates) {
            if (item.nickname() != null && !item.nickname().isBlank()) {
                addNameMatches(question, item.nickname(), item, true, matches);
                addShortNameMatches(question, questionStart, item, matches);
            }
            String uid = item.uid().toString();
            String roomId = item.roomId() == null ? null : item.roomId().toString();
            for (MatchResult number : numbers) {
                String digits = number.group();
                if (digits.equals(uid) || digits.equals(roomId)) {
                    matches.add(new NameMatch<>(number.start(), number.end(), digits.length(), true, item));
                }
            }
        }

        matches.sort(NAME_MATCH_PRIORITY);
        // 先留下最高优先级的命中。同级命中在同一处指向不同主播时，标记为有歧义。
        var covered = new NameMatch<?>[question.length()];
        var retained = new ArrayList<NameMatch<T>>();
        boolean ambiguous = false;
        for (var match : matches) {
            if (match.overlapsStrongerMatch(covered)) continue;
            for (int i = match.start(); i < match.end(); i++) {
                var previous = covered[i];
                if (previous != null && !previous.candidate().uid().equals(match.candidate().uid())) ambiguous = true;
                covered[i] = match;
            }
            retained.add(match);
        }
        var named = new LinkedHashMap<Long, T>();
        retained.sort(Comparator.comparingInt(NameMatch::start));
        retained.forEach(match -> named.putIfAbsent(match.candidate().uid(), match.candidate()));
        return new Mentions<>(List.copyOf(named.values()), ambiguous);
    }

    private static <T extends Candidate> void addNameMatches(String question, String name, T item, boolean exact,
                                                             List<NameMatch<T>> matches) {
        int length = name.codePointCount(0, name.length());
        for (int start = question.indexOf(name); start >= 0; start = question.indexOf(name, start + 1)) {
            matches.add(new NameMatch<>(start, start + name.length(), length, exact, item));
        }
    }

    private static <T extends Candidate> void addShortNameMatches(String question, int questionStart, T item,
                                                                  List<NameMatch<T>> matches) {
        String stem = NON_HAN_EDGES.matcher(item.nickname()).replaceAll("");
        int hanCount = 0;
        for (int end = 0; end < stem.length(); ) {
            int codePoint = stem.codePointAt(end);
            end += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) continue;
            hanCount++;
            if (hanCount < 2) continue;
            String prefix = stem.substring(0, end);
            if (prefix.equals(item.nickname()) || (hanCount == 2 && QUERY_WORDS.contains(prefix))) continue;
            if (hanCount == 2 && end < stem.length()) {
                if (question.startsWith(prefix, questionStart)) {
                    matches.add(new NameMatch<>(questionStart, questionStart + end,
                            prefix.codePointCount(0, end), false, item));
                }
            } else {
                addNameMatches(question, prefix, item, false, matches);
            }
        }
    }

    interface Candidate {
        Long uid();
        String nickname();
        Long roomId();
    }

    record Mentions<T>(List<T> streamers, boolean ambiguous) { }

    private record NameMatch<T extends Candidate>(int start, int end, int length, boolean exact, T candidate) {
        boolean overlapsStrongerMatch(NameMatch<?>[] covered) {
            for (int i = start; i < end; i++) {
                if (covered[i] != null && NAME_MATCH_PRIORITY.compare(covered[i], this) < 0) return true;
            }
            return false;
        }
    }
}
