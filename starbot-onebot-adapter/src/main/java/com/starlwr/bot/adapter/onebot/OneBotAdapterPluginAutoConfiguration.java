package com.starlwr.bot.adapter.onebot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 本插件对 Spring 的自报：「我在这里，扫我」
 * <p>
 * <b>五个插件模块各有一份同形的自报类，写在这里的理由对五份都成立</b>，其余四份只指回本文件，
 * 不各写一遍——同一条规矩写两份，改的时候只会改一份。
 * <p>
 * <b>为什么需要它</b>：应用的组件扫描基包是核心的 {@code com.starlwr.bot.core}，
 * 各插件的类全在那之外，光把 jar 摆上类路径 Spring 也看不见它们。
 * 通道由同模块下的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 开出：Spring Boot 启动时用 {@code ClassLoader.getResources} 收齐类路径上所有同名文件，
 * 逐个把里面写的配置类装进容器；本类上的 {@code @ComponentScan} 再把本模块的组件扫进来。
 * <p>
 * 🔴 <b>当前版本这份自报是不生效的</b>，而且这正是它此刻该有的样子：启动参数是
 * {@code -Dloader.path=lib,plugins-lib}，{@code plugins} 目录不在其中，
 * 于是插件 jar 根本不在应用类路径上，上面那次 {@code getResources} 一份都收不到——
 * 实测：不带 {@code plugins} 时可见的插件自报文件数为 0，带上时为 5。
 * 插件今天仍由 {@code StarBotPluginLoader} 自己开一个类加载器装进容器。
 * <b>两条路同时开着会当场撞车</b>：同一个类被扫描器与加载器各注册一次，
 * 而 Spring Boot 默认不许覆盖 bean 定义，程序起不来（实测退码 1，
 * 报 {@code The bean '...' could not be registered ... overriding is disabled}）。
 * 所以「把 {@code plugins} 加进 {@code loader.path}」与「退休那台加载器」必须同一次做完；
 * 这五份文件只是先把该说的话说在这里，让那一次只剩翻开关。
 * <p>
 * <b>为什么要排除自身</b>：被 {@code @AutoConfiguration} 装进来的配置类以<b>全类名</b>作 bean 名，
 * 而组件扫描给同一个类起的是<b>短名</b>，两个名字互不相识；不排除，同一个配置类会进容器两次。
 * <p>
 * <b>为什么本模块还要多排除一段包名</b>：NapCat 扩展是<b>另一个模块</b>，
 * 它的包 {@code ...adapter.onebot.extension.napcat} 恰好落在本模块包名之下，
 * 而本类的扫描按包名走、连子包一起收。不排除，那个模块的组件会被本模块的自报扫走，
 * 它自己那份自报就成了摆设——装没装它、由谁把它装进来，两件事会对不上账。
 * 按 {@code extension} 这一段排除而不是点名某个模块：日后再挂第二个扩展，这一条照样管得住。
 */
@AutoConfiguration
@ComponentScan(
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = OneBotAdapterPluginAutoConfiguration.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.starlwr\\.bot\\.adapter\\.onebot\\.extension\\..*")})
public class OneBotAdapterPluginAutoConfiguration {
}
