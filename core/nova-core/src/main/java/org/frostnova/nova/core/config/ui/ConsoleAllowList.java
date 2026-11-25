package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.IpMatcher;

import java.util.List;
import java.util.function.Supplier;

/**
 * 控制台来源 IP 名单此刻的匹配器
 * <p>
 * 名单每个请求现读：设置页上保存之后，从下一个请求起就按新名单放行。
 * 名单没变时用上一次建好的匹配器，不每个请求都重新解析一遍。
 */
final class ConsoleAllowList implements Supplier<IpMatcher> {
    private final NovaCoreProperties.ConfigUi configUi;

    private volatile Built built;

    /**
     * 照哪份名单建的、建出来的匹配器
     */
    private record Built(List<String> source, IpMatcher matcher) {
    }

    ConsoleAllowList(NovaCoreProperties.ConfigUi configUi) {
        this.configUi = configUi;
    }

    @Override
    public IpMatcher get() {
        List<String> current = configUi.getAllowIps();
        Built last = built;
        if (last == null || !last.source().equals(current)) {
            List<String> source = current == null ? List.of() : List.copyOf(current);
            last = new Built(source, new IpMatcher(source));
            built = last;
        }
        return last.matcher();
    }
}
