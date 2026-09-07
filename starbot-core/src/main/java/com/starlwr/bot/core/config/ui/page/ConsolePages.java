package com.starlwr.bot.core.config.ui.page;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 控制台页面清单的整理与查找
 * <p>
 * 清单里的每一项都来自插件，也就是<b>本模块管不着的代码</b>：标识会原样成为 DOM 里的 id，
 * 脚本名会原样接在取资源的路径后面。因此登记这一步就是唯一的关口——
 * 放行之后再想补救，补的就是浏览器里那个奇怪的元素，和一次照着 {@code ../} 去翻文件的读取。
 * <p>
 * 同样因为它们来自插件，取值这几下<b>本身就可能抛</b>：那几个方法里可以是任何东西。
 * 一项出事只该少一个页签，不该让整条清单连同其余插件的页一起消失。
 */
@Slf4j
public final class ConsolePages {
    /**
     * 页面脚本在插件 jar 里的资源目录
     */
    public static final String SCRIPT_ROOT = "config-ui-pages/";

    /**
     * 核心自己那份界面资源所在的目录，与 {@code /assets/{name}} 出口取资源的前缀是同一个串
     */
    private static final String CORE_ASSET_ROOT = "config-ui/";

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

    /**
     * 控制台内置页的路由名
     * <p>
     * 顶级插件页的标识会原样成为地址 {@code #/<页标识>}，与这些名字撞车就会盖住内置页。
     * 只拦 {@link ConsolePageSlot#TOP}：设置页子页的地址是 {@code #/settings/<页标识>}，不占这一段。
     */
    private static final Set<String> BUILTIN_PAGE_IDS = Set.of(
            "home", "push", "streamers", "log", "links", "settings", "setup");

    /**
     * 向导内置步骤的键
     * <p>
     * 向导步骤插件页的标识会原样成为步骤表上的 key，与这些名字撞车就会盖住内置步骤。
     * 只拦 {@link ConsolePageSlot#SETUP_STEP}：其它落位不占这一段。
     */
    private static final Set<String> BUILTIN_SETUP_STEP_IDS = Set.of(
            "lock", "bot", "account", "streamer", "test");

    /**
     * 已通过登记的一项：注册项本身，加上登记时读到的标识与顺序值
     * <p>
     * 排序要用标识与顺序值，而<b>再问注册项一次就是再给它一次抛异常的机会</b>——
     * 那一下会发生在比较器里，掀掉的是整次排序。登记时读到什么就按什么排。
     * <p>
     * 留着注册项本身而不是只留这几个值：取页面脚本要用它自己的类加载器去翻它自己的 jar，
     * 换成核心这边造的替身，翻的就是核心的资源目录，插件页从此取不到。
     */
    private record Entry(ConsolePageProvider provider, String id, int order) {
    }

    private ConsolePages() {
    }

    /**
     * 向注册项要一个值，它抛了就当这一项没填
     * @param provider 注册项
     * @param field 要取的那一项
     * @param what 取的是什么，用于日志
     * @return 取到的值，取值过程抛异常时为空
     */
    private static <T> T read(ConsolePageProvider provider, Function<ConsolePageProvider, T> field, String what) {
        try {
            return field.apply(provider);
        } catch (RuntimeException | LinkageError e) {
            // 连类名都可能取不到（插件的类加载器已经出事了），因此这里也不敢直接调 provider 的任何东西
            log.warn("控制台页面读取{}失败, 已忽略该页: {}", what, e.toString());
            return null;
        }
    }

    /**
     * 这个脚本名是不是与核心自带的界面资源撞了
     * <p>
     * 用核心自己这个类的类加载器去问：插件在独立的类加载器里，这一问看不见插件 jar 里的东西，
     * 问到的只会是核心那一份。
     * @param script 插件申报的脚本文件名，已通过合规校验，不含路径分隔符
     * @return 核心的 {@code config-ui/} 下有同名文件时为真
     */
    private static boolean collidesWithCoreAsset(String script) {
        return ConsolePages.class.getClassLoader().getResource(CORE_ASSET_ROOT + script) != null;
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

        List<Entry> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ConsolePageProvider provider : providers) {
            if (provider == null) {
                continue;
            }

            String id = read(provider, ConsolePageProvider::id, "标识");
            if (id == null || !ID.matcher(id).matches()) {
                log.warn("控制台页面 {} 的标识不合规, 已忽略: {}", provider.getClass().getName(), id);
                continue;
            }

            String displayName = read(provider, ConsolePageProvider::displayName, "显示名称");
            if (displayName == null || displayName.isBlank()) {
                log.warn("控制台页面 {} 没有显示名称, 已忽略", id);
                continue;
            }

            String script = read(provider, ConsolePageProvider::script, "脚本名");
            if (script == null || !SCRIPT.matcher(script).matches()) {
                log.warn("控制台页面 {} 的脚本名不合规, 已忽略: {}", id, script);
                continue;
            }

            // 🔴 撞名一律拒绝，不静默偏向任何一方。
            //    /assets/{name} 那条出口是「核心的 config-ui/ 里有就用核心的，没有才回落到插件」，
            //    因此同一个文件名底下会有两份内容，而**取到的是哪一份，请求方一点都看不出来**：
            //    状态码 200、长度正常，只是内容出自另一处。2026-09-03 实测过这一形的一次真事——
            //    某界面文件已从核心源码树删除改由插件提供，上一次构建留在 target/classes 里的旧文件
            //    照旧进包，于是服务端一直回那份源码里根本不存在的旧页。
            //
            //    反过来「插件优先」也不成：核心的 core.js 一类是每张页面都 import 的公共文件，
            //    让插件顶掉它，坏的是全部页面而不只是这一页——而且同样是静默的。
            //    两个方向都是「悄悄选一方」，与病同形。
            //
            //    所以这一页不予登记：页签不出现，日志里点名说清是谁、撞的是哪个名字，两份文件都留在原处。
            //    插件作者换个名字即可，而换名字这件事只有在他知道撞了名之后才做得到。
            if (collidesWithCoreAsset(script)) {
                log.warn("控制台页面 {} 申报的脚本名 {} 与配置界面自带的资源同名, 已忽略该页, 请改用其他文件名: {}",
                        id, script, provider.getClass().getName());
                continue;
            }

            Integer order = read(provider, ConsolePageProvider::order, "顺序值");
            if (order == null) {
                continue;
            }

            // 落位与上面几项一样是「向插件要一个值」，同样可能抛。取不到就不登记，
            // 而不是替它兜一个缺省——兜了的话，一个已经出事的插件会安静地长在某一页上，
            // 而那一页此刻正是它自己申报不出来的那一页
            ConsolePageSlot slot = read(provider, ConsolePageProvider::slot, "落位");
            if (slot == null) {
                log.warn("控制台页面 {} 的落位取不到, 已忽略", id);
                continue;
            }

            if (slot == ConsolePageSlot.TOP && BUILTIN_PAGE_IDS.contains(id)) {
                log.warn("控制台页面标识 {} 与内置页同名, 已忽略: {}", id, provider.getClass().getName());
                continue;
            }

            if (slot == ConsolePageSlot.SETUP_STEP && BUILTIN_SETUP_STEP_IDS.contains(id)) {
                log.warn("控制台页面标识 {} 与向导内置步骤重名, 已忽略: {}", id, provider.getClass().getName());
                continue;
            }

            if (!seen.add(id)) {
                log.warn("控制台页面标识 {} 重复, 后一个已忽略: {}", id, provider.getClass().getName());
                continue;
            }

            kept.add(new Entry(provider, id, order));
        }

        kept.sort(Comparator.comparingInt(Entry::order).thenComparing(Entry::id));
        return kept.stream().map(Entry::provider).toList();
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
                .filter(provider -> script.equals(read(provider, ConsolePageProvider::script, "脚本名")))
                .findFirst();
    }
}
