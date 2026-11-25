package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.SettingsAppliedListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 设置页上改了直播数据保存间隔：直播数据与运行状态的定时存盘一起按新间隔重排
 * <p>
 * 两份定时存盘启动时按同一个间隔排好，此后不再看配置。重排不打断正在写的那一趟，
 * 也不让两趟同时写，见 {@link org.frostnova.nova.core.util.IntervalTask}。
 */
@Component
public class AutoSaveIntervalListener implements SettingsAppliedListener {
    /**
     * 直播数据保存间隔的配置项名
     */
    public static final String KEY = "novabot.core.live.auto-save-live-data-interval";

    private final ObjectProvider<DefaultLiveDataService> liveData;

    private final ObjectProvider<NovaStateStore> state;

    public AutoSaveIntervalListener(ObjectProvider<DefaultLiveDataService> liveData,
                                    ObjectProvider<NovaStateStore> state) {
        this.liveData = liveData;
        this.state = state;
    }

    @Override
    public Set<String> keys() {
        return Set.of(KEY);
    }

    @Override
    public void applied(Map<String, String> changes) {
        liveData.ifAvailable(DefaultLiveDataService::rescheduleAutoSave);
        state.ifAvailable(NovaStateStore::rescheduleAutoSave);
    }
}
