package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.analytics.LiveGiftTotal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 收到的礼物怎么分行
 * <p>
 * 单价从高到低，同价个数从多到少。按单价分四档，一档一行起头，不与别的档排在同一行。
 * 最多列出 30 种，剩下的收成末尾一行。
 */
public final class ReceivedGiftLayout {
    /**
     * 一张报告里最多列出的礼物种数
     */
    public static final int MAX_KINDS = 30;

    private ReceivedGiftLayout() {
    }

    /**
     * 排版结果里的一行：要么是一排礼物，要么是末尾的「另有」
     */
    public sealed interface Line permits GiftRow, OverflowLine {
    }

    /**
     * 同一档的一排礼物
     *
     * @param iconSize 这一档的图标边长，像素
     * @param columns 这一档每行最多几种
     * @param gifts 这一排实际放的礼物，个数不超过 {@code columns}
     */
    public record GiftRow(int iconSize, int columns, List<LiveGiftTotal> gifts) implements Line {
    }

    /**
     * 超过 {@link #MAX_KINDS} 种时的末行
     *
     * @param text 写在报告上的那一句
     * @param kinds 没画出来的种数
     * @param count 没画出来的礼物个数合计
     */
    public record OverflowLine(String text, int kinds, int count) implements Line {
    }

    /**
     * 把本场礼物排成行。没有礼物时为空，调用方整段不画。
     *
     * @param gifts 本场累计，顺序无所谓
     * @return 从上到下的行
     */
    public static List<Line> layout(List<LiveGiftTotal> gifts) {
        if (gifts == null || gifts.isEmpty()) {
            return List.of();
        }

        List<LiveGiftTotal> sorted = new ArrayList<>(gifts);
        sorted.sort(Comparator.comparingDouble(LiveGiftTotal::price).reversed()
                .thenComparing(Comparator.comparingInt(LiveGiftTotal::count).reversed())
                .thenComparing(gift -> gift.name() == null ? "" : gift.name())
                .thenComparing(gift -> gift.id() == null ? Long.MIN_VALUE : gift.id()));

        List<LiveGiftTotal> shown = sorted.size() > MAX_KINDS ? sorted.subList(0, MAX_KINDS) : sorted;
        List<LiveGiftTotal> rest = sorted.size() > MAX_KINDS ? sorted.subList(MAX_KINDS, sorted.size()) : List.of();

        List<Line> lines = new ArrayList<>();
        int index = 0;
        while (index < shown.size()) {
            Tier tier = tierOf(shown.get(index).price());
            List<LiveGiftTotal> row = new ArrayList<>();
            while (index < shown.size() && row.size() < tier.columns && tierOf(shown.get(index).price()) == tier) {
                row.add(shown.get(index));
                index++;
            }
            lines.add(new GiftRow(tier.iconSize, tier.columns, List.copyOf(row)));
        }

        if (!rest.isEmpty()) {
            int count = 0;
            for (LiveGiftTotal gift : rest) {
                count += gift.count();
            }
            lines.add(new OverflowLine("另有 " + rest.size() + " 种礼物，共 " + count + " 个", rest.size(), count));
        }
        return lines;
    }

    private static Tier tierOf(double price) {
        if (price >= 100) {
            return Tier.TOP;
        }
        if (price >= 10) {
            return Tier.HIGH;
        }
        if (price >= 1) {
            return Tier.MID;
        }
        return Tier.LOW;
    }

    private enum Tier {
        TOP(3, 96),
        HIGH(4, 72),
        MID(5, 60),
        LOW(6, 48);

        private final int columns;

        private final int iconSize;

        Tier(int columns, int iconSize) {
            this.columns = columns;
            this.iconSize = iconSize;
        }
    }
}
