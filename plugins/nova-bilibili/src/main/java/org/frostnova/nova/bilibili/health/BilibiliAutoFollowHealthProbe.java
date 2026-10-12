package org.frostnova.nova.bilibili.health;

import org.frostnova.nova.bilibili.service.BilibiliDynamicService;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/** 自动关注健康探针，只读取动态服务的内存状态，不发起网络请求。 */
@NovaComponent
public class BilibiliAutoFollowHealthProbe implements HealthProbe {
    private final BilibiliDynamicService dynamicService;

    @Autowired
    public BilibiliAutoFollowHealthProbe(BilibiliDynamicService dynamicService) {
        this.dynamicService = dynamicService;
    }

    @Override
    public String name() {
        return "哔哩哔哩自动关注";
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Scope scope() {
        return Scope.PLATFORM;
    }

    @Override
    public HealthStatus check() {
        Map<String, String> members = new LinkedHashMap<>();
        dynamicService.getFollowRejections().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> members.put(entry.getKey().toString(),
                        entry.getValue().displayName() + "：" + entry.getValue().message()));
        if (members.isEmpty()) {
            return HealthStatus.ok("正常");
        }
        return new HealthStatus(HealthStatus.Level.DEGRADED,
                "有 " + members.size() + " 位主播关注不了：" + String.join("；", members.values()),
                "这是对方或 B 站那边的设置，NovaBot 每天会自动再试；能做的只有换一个能关注他的账号登录，或者请主播调整设置。",
                "", members, "账号关注不了这几位，他们的动态推不出来，直播推送不受影响");
    }
}
