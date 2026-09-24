package org.frostnova.nova.bilibili.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 一次取回的大航海名单，带上首页报的总人数。
 * <p>
 * 总人数可能比这份名单长：后面的页没取到，或名单到了翻页上限。
 * 调用方仍把它当一份名单用。
 */
public final class GuardListFetch extends ArrayList<GuardMember> {
    private final int reportedTotal;

    public GuardListFetch(Collection<GuardMember> members, Integer reportedTotal) {
        super(members == null ? List.of() : members);
        this.reportedTotal = reportedTotal == null || reportedTotal <= 0 ? 0 : reportedTotal;
    }

    /**
     * 这份名单首页报的总人数。普通名单没有这个数时为空
     * @param members 取回的名单
     * @return 首页报的总人数，没有时为空
     */
    public static Optional<Integer> reportedTotal(List<GuardMember> members) {
        if (members instanceof GuardListFetch fetch && fetch.reportedTotal > 0) {
            return Optional.of(fetch.reportedTotal);
        }
        return Optional.empty();
    }
}
