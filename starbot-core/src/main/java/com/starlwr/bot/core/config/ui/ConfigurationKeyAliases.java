package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.EventStreamProperties;

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
     * 改过名的配置键前缀：现行前缀 → 旧前缀
     * <p>
     * 取自各配置类自己声明的常量，不在这里另抄一份字面量：抄一份就会与真正参与绑定的那一份漂开，
     * 而漂开之后界面与程序又会各说各话——正是这个类要治的那种病。
     */
    private static final Map<String, String> RENAMED_PREFIXES = Map.of(
            EventStreamProperties.PREFIX, EventStreamProperties.LEGACY_PREFIX);

    private ConfigurationKeyAliases() {
    }

    /**
     * 把只写在旧位置的配置项按现行键补进读数
     * <p>
     * 就地修改传入的键值表：现行键已经在表里的项一概不动（现行键优先），
     * 只有现行键缺席、旧位置写了的项才补上。
     * @param values 配置文件里的键值表，就地补齐
     * @return 落回旧位置的项：现行键 → 它实际生效的那个旧键，界面据此标出来源
     */
    public static Map<String, String> resolve(Map<String, String> values) {
        Map<String, String> fallbacks = new LinkedHashMap<>();

        RENAMED_PREFIXES.forEach((current, legacy) -> {
            String prefix = legacy + ".";

            // 先收齐再补，避免边遍历边往同一张表里写
            List<Map.Entry<String, String>> legacyEntries = values.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .toList();

            for (Map.Entry<String, String> entry : legacyEntries) {
                String name = current + "." + entry.getKey().substring(prefix.length());
                if (values.containsKey(name)) {
                    // 现行键写了就以它为准，与绑定时的顺序一致
                    continue;
                }

                values.put(name, entry.getValue());
                fallbacks.put(name, entry.getKey());
            }
        });

        return fallbacks;
    }
}
