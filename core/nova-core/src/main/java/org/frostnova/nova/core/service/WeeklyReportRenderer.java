package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.model.LiveStreamerInfo;

/** 周报图片的扩展点，输入已按金额可见性处理的汇总内容。 */
public interface WeeklyReportRenderer {
    String platform();
    byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data);

    /**
     * 按指定配色画
     * @param palette 报告图配色的预设 id，见 {@link ReportPaletteSettings}
     */
    default byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data, String palette) {
        return render(streamer, data);
    }

    /**
     * 这个 id 实际按哪一套画
     * <p>
     * 认不出的 id 落到哪一套由画图这一侧答。两个 id 答出同一个值，画出来就是同一张图。
     * @param palette 报告图配色的预设 id
     * @return 实际画图用的那一套的 id
     */
    default String paletteId(String palette) {
        return palette;
    }
}
