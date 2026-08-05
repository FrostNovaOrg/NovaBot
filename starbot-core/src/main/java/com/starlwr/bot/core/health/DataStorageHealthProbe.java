package com.starlwr.bot.core.health;

import com.starlwr.bot.core.service.LiveDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 数据存储健康探针
 * <p>
 * 「总数据」类查询要不要得到答案，取决于当前跑的是哪一种 {@link LiveDataService} 实现。
 * 这件事此前只在启动日志里说过一次，出问题时得翻日志才知道累计存储到底开没开——
 * 而「查不到累计数据」既可能是没配 Redis，也可能是 Redis 挂了，两者的处理方式完全不同。
 * <p>
 * 未配置外部存储**不是故障**：本场数据完整可用，只是没有累计能力。因此这里报 OK
 * 并说明现状，而不是报警——把一个正常的部署形态标红只会让人麻木。
 */
@Component
public class DataStorageHealthProbe implements HealthProbe {
    private final LiveDataService liveDataService;

    @Autowired
    public DataStorageHealthProbe(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;
    }

    @Override
    public String name() {
        return "数据存储";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public HealthStatus check() {
        String implementation = liveDataService.getClass().getSimpleName();

        if (liveDataService.supportsTotalData()) {
            return HealthStatus.ok("本场数据 + 累计数据（" + implementation + "）");
        }

        return HealthStatus.ok("仅本场数据（" + implementation + "）；"
                + "「我的总数据」等累计查询需配置 spring.data.redis.host 后才可用");
    }
}
