package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.EventStreamProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置键改过名之后，控制台读侧对旧位置的兼容
 *
 * <h2>它治的是哪一种病</h2>
 * 配置键改名时，程序自己两套键都认得（见 {@code NovaEventStreamConfiguration#eventStreamProperties}），
 * 因此<b>功能上升级即无感</b>。控制台却不是这么读的：它把 {@code application.yml} 读成一张扁平的键值表，
 * 再拿现行键去表里取值——只写旧位置的既有部署，现行键在表里一个也取不到，
 * 界面于是显示元数据里的<b>默认值</b>，而程序正按旧位置的值在跑。
 * <p>
 * 这比功能坏掉更难查：界面上写着「已关闭」，事件流却在输出；使用者照着界面去排查，
 * 只会得出「这个开关不起作用」的结论。<b>界面读数骗人，比界面少一项更糟。</b>
 *
 * <h2>为什么是读侧别名，而不是首启改写配置文件</h2>
 * 另一条补法是启动时把旧键改写进现行位置。<b>不取</b>：那是替使用者改他自己的配置文件。
 * 配置文件是使用者的资料，不是程序的私有存档——他可能把它纳入了版本管理、可能在多台机器间同步、
 * 也可能只是想知道自己当初写了什么。程序单方面动它，还是在使用者根本没提出保存的时候动，
 * 代价远大于「界面上多一句提示」。
 *
 * <h2>写侧的规矩：只写现行键，旧键留着不删</h2>
 * 使用者在控制台保存时写入的是<b>现行键</b>（界面上的字段名取自元数据，元数据里只有现行键），
 * 于是保存一次就等于把这一项迁到了新位置，提示随之消失。
 * <p>
 * <b>旧位置那几行不删，也不改。</b>删是动使用者的资料，理由同上；而且删了并不能让配置更干净——
 * 真正读得懂那几行的是程序自己，它两套键都认，多留几行的代价只是启动时那条改名提醒。
 * 要清掉它们，该由使用者在「直接编辑配置文件」里自己动手。
 *
 * <h2>优先级</h2>
 * 必须与程序实际绑定时的优先级<b>逐项一致</b>，否则界面仍然骗人，只是换了个骗法：
 * 现行键写了就用现行键、没写才落回旧位置、两处都没写才是默认值。这里的「写没写」以
 * 配置文件里有没有这一行为准，与 {@code Binder} 分两趟绑时判断的是同一件事。
 */
public final class ConfigurationKeyAliases {
    /**
     * 核心自有的改名表：现行键（或前缀） → 旧键（或前缀）
     * <p>
     * 取自各配置类自己声明的常量，不在这里另抄一份字面量：抄一份就会与真正参与绑定的那一份漂开，
     * 而漂开之后界面与程序又会各说各话——正是这个类要治的那种病。
     * 插件侧的改名由 {@link ConfigurationKeyAliasContributor} 申报，不写在这里。
     */
    private static final Map<String, String> CORE_RENAMED = Map.of(
            EventStreamProperties.PREFIX, EventStreamProperties.LEGACY_PREFIX);

    private static final ConfigurationKeyAliases CORE = new ConfigurationKeyAliases(CORE_RENAMED);

    /**
     * 现行键（或前缀） → 旧键（或前缀）
     * <p>
     * 解析取最长匹配。值是完整键时，同前缀下未申报的邻键不会被吃掉；
     * 值是前缀时，该前缀下每一项都按现行前缀换算。
     */
    private final Map<String, String> renamed;

    private ConfigurationKeyAliases(Map<String, String> renamed) {
        this.renamed = Map.copyOf(renamed);
    }

    /**
     * 只含核心自有改名的表。
     * @return 核心表
     */
    public static ConfigurationKeyAliases core() {
        return CORE;
    }

    /**
     * 核心表与各贡献者申报的改名合并。无贡献者时即核心表。
     * <p>
     * 跨方重复现行键抛 {@link IllegalStateException}，文案与核心自己的表重复登记相同。
     * @param contributors 插件申报，顺序即合并顺序
     * @return 合并后的表
     */
    public static ConfigurationKeyAliases of(Collection<ConfigurationKeyAliasContributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return core();
        }

        Map<String, String> merged = new LinkedHashMap<>(CORE_RENAMED);
        for (ConfigurationKeyAliasContributor contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            Map<String, String> declared = contributor.renamed();
            if (declared == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : declared.entrySet()) {
                put(merged, entry.getKey(), entry.getValue());
            }
        }
        return new ConfigurationKeyAliases(merged);
    }

    private static void put(Map<String, String> renamed, String current, String legacy) {
        if (current == null || legacy == null) {
            return;
        }
        String previous = renamed.put(current, legacy);
        if (previous != null) {
            throw new IllegalStateException("配置键别名 " + current + " 被写了两次: "
                    + previous + " 与 " + legacy);
        }
    }

    /**
     * 把写在旧位置的键换算成它对应的现行键
     * <p>
     * 配置元数据里<b>只有现行键</b>，因此凡是拿名字去查元数据的地方——类型、说明、默认值——
     * 拿旧位置那一行的键一律查不到。而旧位置与现行位置<b>是同一项配置</b>，
     * 类型自然也是同一个，查之前先过一道这里即可。
     * <p>
     * 这件事必须放在本类：改过名的键有哪些只该有一处知道，散在各处抄一份，
     * 就会出现「某一处认得旧键、另一处不认得」的半截兼容。
     * @param name 配置项完整路径，可能写在旧位置
     * @return 对应的现行键；本来就是现行键、或与改名无关时原样返回
     */
    public String currentName(String name) {
        if (name == null) {
            return null;
        }

        String hitCurrent = null;
        String hitLegacy = null;
        int longest = -1;

        for (Map.Entry<String, String> entry : renamed.entrySet()) {
            String current = entry.getKey();
            String legacy = entry.getValue();
            if (legacy == null || current == null) {
                continue;
            }

            boolean exact = name.equals(legacy);
            boolean prefix = name.startsWith(legacy + ".");
            if (!exact && !prefix) {
                continue;
            }

            if (legacy.length() > longest) {
                longest = legacy.length();
                hitCurrent = current;
                hitLegacy = legacy;
            }
        }

        if (hitCurrent == null) {
            return name;
        }
        if (name.equals(hitLegacy)) {
            return hitCurrent;
        }
        return hitCurrent + "." + name.substring(hitLegacy.length() + 1);
    }

    /**
     * 把只写在旧位置的配置项按现行键补进读数
     * <p>
     * 就地修改传入的键值表：现行键已经在表里的项一概不动（现行键优先），
     * 只有现行键缺席、旧位置写了的项才补上。
     * @param values 配置文件里的键值表，就地补齐
     * @return 落回旧位置的项：现行键 → 它实际生效的那个旧键，界面据此标出来源
     */
    public Map<String, String> resolve(Map<String, String> values) {
        Map<String, String> fallbacks = new LinkedHashMap<>();

        // 先收齐再补，避免边遍历边往同一张表里写
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(values.entrySet());
        for (Map.Entry<String, String> entry : snapshot) {
            String current = currentName(entry.getKey());
            if (current == null || current.equals(entry.getKey())) {
                continue;
            }
            if (values.containsKey(current)) {
                // 现行键写了就以它为准，与绑定时的顺序一致
                continue;
            }

            values.put(current, entry.getValue());
            fallbacks.put(current, entry.getKey());
        }

        return fallbacks;
    }
}
