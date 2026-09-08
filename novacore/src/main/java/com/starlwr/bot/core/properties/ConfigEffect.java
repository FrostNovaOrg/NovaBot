package com.starlwr.bot.core.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置项的生效时机
 * <p>
 * 改完一项配置之后，它到底是当场就管用，还是要等下次启动？这件事此前没有任何地方记着，
 * 界面只能笼统地说一句「重启后生效」——于是<b>真正即时生效的那几项也被说成要重启</b>，
 * 使用者为了让「暂停推送」立刻起作用而去重启整个程序，反倒把正在采集的场次打断了。
 * <p>
 * 标注在 {@code @ConfigurationProperties} 类的字段上，由配置界面在运行期读取。
 * <p>
 * <b>没有默认值，这是有意的。</b>{@code ConfigLevel} 未标注时按「高级」处理，因为猜错的代价
 * 只是一项配置藏得深了些；生效时机猜错的代价则是使用者据此作出的判断本身是错的——
 * 说成即时生效而实际没生效，人会以为功能坏了；说成要重启而实际不必，人会白白重启一次。
 * 因此新增配置项必须显式回答这个问题，答不上来就让构建红着
 * （判据在 {@code ConfigurationConsistencyTest}）。
 * <p>
 * <b>标成 {@link Effect#IMMEDIATE} 不会让一项配置自动变得即时生效</b>：它只是个声明。
 * 真正让它当场管用的是保存时把新值写回运行中的配置对象那一步，而<b>能这么做的键是一份显式名单</b>
 * （见运行壳侧的 {@code RuntimeConfigurationApplier}）。声明与名单必须一致，
 * 两者对不上时同一条判据会红——否则界面会承诺一件没人去做的事。
 *
 * <h2>本注解为什么在核心模块里</h2>
 * 它要标到本模块这几节配置的字段上（网络、线程池、日志、直播、数据源）。放在运行壳那一侧的话，
 * 核心就得反过来引用壳，边界尺的第六、七格会当场红——那道边界比「两个注解摆在一起」更值钱。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigEffect {
    /**
     * 生效时机
     * @return 生效时机
     */
    Effect value();

    /**
     * 生效时机枚举
     */
    enum Effect {
        /**
         * 即时生效：保存之后当场管用，不必重启
         */
        IMMEDIATE,

        /**
         * 重启生效：保存只是写进配置文件，下次启动才读得到
         */
        RESTART
    }
}
