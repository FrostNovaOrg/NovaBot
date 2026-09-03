package com.starlwr.bot.core.config.ui.page;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制台页面清单的整理与查找
 * <p>
 * 清单里的每一项都来自插件，也就是<b>本模块管不着的代码</b>：标识会原样成为 DOM 里的 id，
 * 脚本名会原样接在取资源的路径后面。因此登记这一步就是唯一的关口——
 * 放行之后再想补救，补的就是浏览器里那个奇怪的元素，和一次照着 {@code ../} 去翻文件的读取。
 */
@Slf4j
public final class ConsolePages {
    /**
     * 页面脚本在插件 jar 里的资源目录
     */
    public static final String SCRIPT_ROOT = "config-ui-pages/";

    /**
     * 合规的页签标识：小写字母打头的小写字母、数字与连字符，最长 32 位
     */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /**
     * 合规的脚本文件名
     * <p>
     * 与配置界面静态资源那份校验同形：只接受字母数字、下划线与连字符，扩展名只认 {@code .js}。
     * <b>不含点号序列也不含路径分隔符</b>，因此 {@code ../} 这类穿越根本匹配不上。
     */
    private static final Pattern SCRIPT = Pattern.compile("[A-Za-z0-9_-]+\\.js");

    private ConsolePages() {
    }

    /**
     * 整理注册清单
     * <p>
     * 不合规的直接丢掉并留一行日志：登记不上的页签在界面上根本不出现，
     * 不写日志的话，插件作者只会看到「我的页没了」而无从知道是哪一项填错。
     * @param providers 原始注册项
     * @return 按顺序值排好、标识不重复的可用注册项，不可修改
     */
    public static List<ConsolePageProvider> valid(List<ConsolePageProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        List<ConsolePageProvider> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ConsolePageProvider provider : providers) {
            if (provider == null) {
                continue;
            }

            String id = provider.id();
            if (id == null || !ID.matcher(id).matches()) {
                log.warn("控制台页面 {} 的标识不合规, 已忽略: {}", provider.getClass().getName(), id);
                continue;
            }

            String displayName = provider.displayName();
            if (displayName == null || displayName.isBlank()) {
                log.warn("控制台页面 {} 没有显示名称, 已忽略", id);
                continue;
            }

            String script = provider.script();
            if (script == null || !SCRIPT.matcher(script).matches()) {
                log.warn("控制台页面 {} 的脚本名不合规, 已忽略: {}", id, script);
                continue;
            }

            if (!seen.add(id)) {
                log.warn("控制台页面标识 {} 重复, 后一个已忽略: {}", id, provider.getClass().getName());
                continue;
            }

            kept.add(provider);
        }

        kept.sort(Comparator.comparingInt(ConsolePageProvider::order).thenComparing(ConsolePageProvider::id));
        return List.copyOf(kept);
    }

    /**
     * 按脚本文件名查找注册项
     * <p>
     * 只在<b>整理过的清单</b>里找：没登记上的那些，名字对得再准也取不到东西。
     * 这条路正是页面脚本的出口，白名单在这里，不在调用方。
     * @param providers 原始注册项
     * @param script 脚本文件名
     * @return 注册项，没有对应项时为空
     */
    public static Optional<ConsolePageProvider> byScript(List<ConsolePageProvider> providers, String script) {
        if (script == null) {
            return Optional.empty();
        }

        return valid(providers).stream()
                .filter(provider -> provider.script().equals(script))
                .findFirst();
    }
}
