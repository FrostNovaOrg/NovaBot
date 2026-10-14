package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.model.LiveStreamerInfo;

/** 周报图片的扩展点，输入已按金额可见性处理的汇总内容。 */
public interface WeeklyReportRenderer {
    String platform();
    byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data);
}
