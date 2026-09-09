package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.NovaBotPrefixes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页的分组
 * <p>
 * 此前设置页是按配置键的前缀自动分组的——{@code novabot.core.push} 一组、
 * {@code novabot.core.live} 一组，共二十余组。那是<b>照程序结构摆的</b>：
 * 使用者要办一件事（「让它别在半夜发消息」），得先猜这件事在代码里归哪个类管。
 * 这里改成按「要办的事」分组，常用六组在上，工程用的两组折到页底。
 *
 * <h2>为什么是闭集</h2>
 *
 * 组是写死的八个，映射由前缀表给出。<b>新加的配置键如果一条前缀也匹配不上，它就没有组</b>——
 * 接口上那一项的 {@code group} 为空，构建期那道判据当场点名。反过来做（匹配不上就丢进
 * 「其他」）看起来更稳妥，实际是给每一个新键签了张免检牌：它会静静地待在「其他」里，
 * 而没有任何东西会再提起这件事。
 *
 * <h2>为什么是最长前缀</h2>
 *
 * 一个键只落一组，这一点由<b>结构</b>保证而不是由判据保证：前缀表是 {@code 前缀 → 组}
 * 的映射（不是 {@code 组 → 前缀清单}），同一条前缀写两次在构造时就会抛，
 * 而取最长的那一条匹配则让「整段归 A、其中某一项归 B」表达得出来——
 * 报告用的那两张标识图片就长在采集那一段的前缀底下。
 */
public final class ConfigurationGroups {
    /**
     * 一个分组
     *
     * @param id 组标识，界面上的锚点与药丸用它
     * @param title 组名
     * @param description 组说明，写「这一组管什么」
     * @param advanced 是否折进页底的「高级 · 工程用」
     */
    public record Group(String id, String title, String description, boolean advanced) {
    }

    /**
     * 常用六组，顺序即界面上的顺序
     */
    public static final Group PUSH = new Group("push", "推送",
            "这一组管「什么时候发、发多少」。", false);

    public static final Group ALERT = new Group("alert", "告警",
            "出了事谁先知道。告警会出现在日志里，改这里不影响那条线怎么显示。", false);

    public static final Group COMMAND = new Group("cmd", "命令与权限",
            "群里谁能管命令。群主与群管理员天生能用启用命令与禁用命令，超级管理员是跨群都算数的那几个号。", false);

    public static final Group COLLECT = new Group("collect", "采集",
            "从直播平台那头拿数据的方式，以及数据存到哪。改这一组前先读一遍每项的说明。", false);

    public static final Group REPORT = new Group("report", "报告外观",
            "报告图与动态图上的东西。", false);

    public static final Group AUTH = new Group("auth", "登录与安全",
            "控制台自己怎么被访问。", false);

    /**
     * 高级两组，折在页底
     */
    public static final Group LOG_DEBUG = new Group("logdbg", "日志与调试",
            "出问题时才需要动这一组。", true);

    public static final Group SERVICE = new Group("svc", "服务",
            "这台机器怎么把控制台端出来，以及各处的连接、线程与超时。", true);

    /**
     * 八个组，顺序即界面顺序
     */
    private static final List<Group> ALL =
            List.of(PUSH, ALERT, COMMAND, COLLECT, REPORT, AUTH, LOG_DEBUG, SERVICE);

    /**
     * 核心自有前缀表。插件前缀由 {@link ConfigurationGroupContributor} 申报，不写在这里。
     */
    private static final ConfigurationGroups CORE = buildCore();

    /**
     * 配置键前缀 → 组
     * <p>
     * 取最长匹配。键 {@code k} 命中前缀 {@code p} 的条件是 {@code k} 等于 {@code p}，
     * 或以 {@code p.} 开头——不写后面这个点的话，{@code novabot.core.push} 会把
     * {@code novabot.core.pushover} 一并吃掉。
     */
    private final Map<String, Group> prefixes;

    private ConfigurationGroups(Map<String, Group> prefixes) {
        this.prefixes = prefixes;
    }

    /**
     * 只含核心自有前缀的分组表：{@code novabot.core.*}／{@code spring.*}。
     * @return 核心表
     */
    public static ConfigurationGroups core() {
        return CORE;
    }

    /**
     * 核心表与各贡献者申报的前缀合并。无贡献者时即核心表。
     * <p>
     * 跨方重复前缀抛 {@link IllegalStateException}，文案与核心自己的前缀表重复登记相同。
     * @param contributors 插件申报，顺序即合并顺序
     * @return 合并后的表
     */
    public static ConfigurationGroups of(Collection<ConfigurationGroupContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return core();
        }

        Map<String, Group> merged = new LinkedHashMap<>(core().prefixes);
        for (ConfigurationGroupContributor contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            Map<String, Group> declared = contributor.prefixes();
            if (declared == null) {
                continue;
            }
            for (Map.Entry<String, Group> entry : declared.entrySet()) {
                map(merged, entry.getKey(), entry.getValue());
            }
        }
        return new ConfigurationGroups(merged);
    }

    private static ConfigurationGroups buildCore() {
        Map<String, Group> map = new LinkedHashMap<>();
        // ---- 推送 ----
        map(map, NovaBotPrefixes.CORE + ".push", PUSH);
        map(map, NovaBotPrefixes.CORE + ".sender", PUSH);

        // ---- 告警 ----
        map(map, NovaBotPrefixes.CORE + ".alert", ALERT);
        map(map, NovaBotPrefixes.CORE + ".mail", ALERT);
        map(map, "spring.mail", ALERT);

        // ---- 命令与权限 ----
        map(map, NovaBotPrefixes.CORE + ".command", COMMAND);

        // ---- 采集 ----
        map(map, NovaBotPrefixes.CORE + ".live", COLLECT);
        // 累计存储答的是「跨场次的数据存到哪」，与「本场数据怎么采」是同一件事的两头
        map(map, "spring.data.redis", COLLECT);

        // ---- 报告外观 ----
        map(map, NovaBotPrefixes.CORE + ".paint", REPORT);

        // ---- 登录与安全 ----
        map(map, NovaBotPrefixes.CORE + ".config-ui", AUTH);

        // ---- 日志与调试 ----
        map(map, NovaBotPrefixes.CORE + ".log", LOG_DEBUG);
        map(map, NovaBotPrefixes.CORE + ".timeline", LOG_DEBUG);

        // ---- 服务 ----
        map(map, "server", SERVICE);
        map(map, NovaBotPrefixes.EVENT_STREAM, SERVICE);
        map(map, NovaBotPrefixes.CORE + ".exec", SERVICE);
        map(map, NovaBotPrefixes.CORE + ".network", SERVICE);
        map(map, NovaBotPrefixes.CORE + ".network-thread", SERVICE);
        map(map, NovaBotPrefixes.CORE + ".datasource", SERVICE);
        return new ConfigurationGroups(map);
    }

    private static void map(Map<String, Group> prefixes, String prefix, Group group) {
        Group previous = prefixes.put(prefix, group);
        if (previous != null) {
            throw new IllegalStateException("配置分组前缀 " + prefix + " 被写了两次: "
                    + previous.id() + " 与 " + group.id());
        }
    }

    /**
     * 八个组，按界面顺序
     * @return 组清单
     */
    public static List<Group> all() {
        return ALL;
    }

    /**
     * 分组表里登记的全部前缀
     * <p>
     * 供构建期那道判据反查：<b>指不到任何现存配置键的前缀是死条目</b>，
     * 它多半是某个键改名或删掉之后留下的，而留着它只会让下一个人以为那一段已经归好了组。
     * @return 前缀，登记顺序
     */
    public List<String> prefixes() {
        return List.copyOf(prefixes.keySet());
    }

    /**
     * 这个配置键归哪一组
     * @param name 配置键完整路径
     * @return 组，一条前缀也匹配不上时为 {@code null}
     */
    public Group groupOf(String name) {
        if (name == null) {
            return null;
        }

        Group hit = null;
        int longest = -1;

        for (Map.Entry<String, Group> entry : prefixes.entrySet()) {
            String prefix = entry.getKey();
            if (!name.equals(prefix) && !name.startsWith(prefix + ".")) {
                continue;
            }

            if (prefix.length() > longest) {
                longest = prefix.length();
                hit = entry.getValue();
            }
        }

        return hit;
    }

    /**
     * 组在界面上的序号，从 0 起
     * @param group 组
     * @return 序号，不是本表里的组时为 -1
     */
    public static int orderOf(Group group) {
        return ALL.indexOf(group);
    }
}
