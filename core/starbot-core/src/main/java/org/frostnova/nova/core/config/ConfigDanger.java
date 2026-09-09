package org.frostnova.nova.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这一项改到某个值之后，后果不小
 * <p>
 * 有那么几项配置，改错了不会报错、不会启动失败，只是<b>把一道门打开了</b>：留一条能绕过口令的
 * 后门、把接口暴露到网络、允许执行任意程序、让采集退化到只剩一成数据。它们的共同特征是
 * <b>改完之后一切看起来都很正常</b>，而代价要到很久以后才显形。
 * <p>
 * 标注在 {@code @ConfigurationProperties} 类的字段上，配置界面据此在那一行画一道警示边，
 * 并在改到危险的那一档时先把后果说给使用者听，点取消就退回原样。
 *
 * <h2>为什么由配置项自己声明，而不是界面上列一张表</h2>
 * 界面上那张表只能由核心来写，而核心<b>不该认识任何一个具体平台</b>——「匿名模式」是某个
 * 直播平台插件的配置项，把它的键名写进核心的界面文件，等于让核心的界面替一个可能没装的插件
 * 记着一件事。声明跟着字段走，插件带来的危险项就跟着插件一起来、一起走。
 * <p>
 * 同时它也解决了另一半：<b>「哪几项危险」与「危险在哪」必须是同一处回答的</b>。分成两处的话，
 * 加了一个危险项而忘了写后果，界面会弹一个空白的确认框出来。
 *
 * <h2>本注解为什么与 {@link ConfigLevel} 同处，而不是与 {@code ConfigEffect} 同处</h2>
 * {@code ConfigEffect} 在核心模块，是因为它要标到那边几节配置的字段上（网络、线程池、日志、
 * 直播、数据源）。危险项目前一个都不在那几节里——它们是「留后门」「开端口」「执行外部程序」
 * 这类事，全长在运行壳与各插件的配置上。<b>哪天核心那几节里出现了危险项，本注解要跟着搬过去</b>，
 * 否则核心就得反过来引用运行壳，边界尺当场会红。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigDanger {
    /**
     * 改成哪个值算危险
     * <p>
     * 与界面上读出来的值逐字比（两头的空白不算）。开关项写 {@code "true"}，
     * 取值型的写那个具体的值，例如监听地址的 {@code "0.0.0.0"}。
     * <p>
     * <b>危险的不是这一项本身，是它被改到某一档。</b>监听地址填 127.0.0.1 一点都不危险，
     * 填 0.0.0.0 才是；因此这里要的是值，不是一个「危险与否」的开关。
     * @return 危险值
     */
    String value();

    /**
     * 确认框的标题，写成一句问话
     * @return 标题
     */
    String title();

    /**
     * 后果，写清楚改了之后会怎样
     * <p>
     * 使用者要在这句话上作决定，因此它得说事实，不能只说「这么做有风险」。
     * @return 后果
     */
    String consequence();
}
