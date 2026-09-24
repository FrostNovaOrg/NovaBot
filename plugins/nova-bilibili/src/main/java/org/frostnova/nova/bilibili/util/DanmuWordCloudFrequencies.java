package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.service.DefaultLiveDataService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按弹幕原文重算一场的词频，口径与直播中入表相同。
 * <p>
 * 只计文字弹幕，分词、单条上限、词汇量上限都走原来的那一套。
 * 名单里的用户整条跳过。原文按落盘顺序走，满了之后新词不收、旧词照加。
 */
public final class DanmuWordCloudFrequencies {
    private DanmuWordCloudFrequencies() {
    }

    /**
     * 把配置里的用户编号收成集合。空行与不是纯数字的行跳过。
     * @param raw 每行一个编号，可为 null
     * @return 要跳过的用户编号，没有时为空集
     */
    public static Set<Long> parseUids(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.strip();
            if (trimmed.isEmpty() || !digits(trimmed)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // 超长的一串数字对不上任何用户，跳过
            }
        }
        return ids;
    }

    /**
     * 从这一场的弹幕原文重算词频
     * @param platform 直播平台
     * @param streamerUid 主播 UID，词频记在这位主播名下
     * @param records 按落盘顺序的弹幕原文
     * @param exclude 不计的用户编号
     * @return 词语到次数，没有时为空表
     */
    public static Map<String, Integer> recount(String platform, long streamerUid,
                                                List<DanmuRecord> records, Set<Long> exclude) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        Set<Long> skip = exclude == null ? Set.of() : exclude;
        if (records != null) {
            for (DanmuRecord record : records) {
                if (record == null || record.type() != DanmuRecord.Type.DANMU) {
                    continue;
                }
                if (record.uid() != null && skip.contains(record.uid())) {
                    continue;
                }
                for (String word : DanmuWordUtil.extractWords(record.text())) {
                    data.incrementLiveWordFrequency(platform, streamerUid, word);
                }
            }
        }
        return data.getLiveWordFrequencies(platform, streamerUid);
    }

    private static boolean digits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
