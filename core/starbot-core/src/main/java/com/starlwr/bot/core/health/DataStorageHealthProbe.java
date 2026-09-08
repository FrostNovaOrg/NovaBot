package com.starlwr.bot.core.health;

import com.starlwr.bot.core.service.TotalDataStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 数据存储健康探针
 * <p>
 * 「总数据」类查询要不要得到答案，取决于累计存储此刻的状态。这件事此前只在启动日志里说过一次，
 * 出问题时得翻日志才知道累计存储到底开没开——而「查不到累计数据」有<b>两种成因</b>，
 * 处理方式完全不同，因此这里分三档说：
 * <ul>
 *     <li><b>没配</b>：本场数据完整可用，只是没有累计能力。<b>这不是故障</b>，报 OK 并写清
 *     去哪儿配、配完不用重启——把一个正常的部署形态标红只会让人麻木</li>
 *     <li><b>配了、连得上</b>：报 OK，并写出连的是哪儿</li>
 *     <li><b>配了、连不上</b>：报降级。配了却连不上是<b>真出了事</b>，
 *     此时累计类查询会一律回「不可用」，而使用者只会看到菜单里少了两条</li>
 * </ul>
 */
@Component
public class DataStorageHealthProbe implements HealthProbe {
    private final TotalDataStorage storage;

    @Autowired
    public DataStorageHealthProbe(TotalDataStorage storage) {
        this.storage = storage;
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
        if (!storage.isConfigured()) {
            return HealthStatus.ok("只有本场数据；要跨场次的累计数据，"
                    + "就在设置页「采集」组里填 spring.data.redis.host，填完即时生效，不用重启");
        }

        String target = storage.describeTarget();
        if (storage.isAvailable()) {
            return HealthStatus.ok("本场数据 + 累计数据（Redis " + target + "）");
        }

        return HealthStatus.degraded("只有本场数据（累计存储连不上）",
                "Redis " + target + " 此刻连不上，「我的总数据」等累计查询已自动降级，"
                        + "群里的菜单也不再列那两条。确认它起着、地址与密码没写错即可，"
                        + "连回来会自己恢复，不用重启");
    }
}
