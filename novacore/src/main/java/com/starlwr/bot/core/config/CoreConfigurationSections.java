package com.starlwr.bot.core.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 本模块这几节配置的元数据出处
 * <p>
 * <b>本类不参与绑定，也不会成为 bean</b>：它没有 {@code @Component}／{@code @Configuration}，
 * 工程里也没有 {@code @ConfigurationPropertiesScan}，因此 Spring 不会注册它。
 * 它存在的唯一理由是让编译期的配置元数据处理器在<b>本模块</b>里跑一遍这几节。
 * <p>
 * 为什么非得在本模块跑：元数据里的「默认值」取自字段初始值，「说明」取自 Javadoc，
 * <b>两者都只存在于源码里</b>。这几节单独成件后待在本模块，而绑定根
 * {@code StarBotCoreProperties} 在运行壳那一侧——壳编译时看到的是本模块的
 * <b>class 文件</b>，读不到初始值也读不到 Javadoc。于是键名与类型照常生成，
 * 默认值与说明<b>整列变成 null</b>：程序照常读得到值，控制台的字段表却只剩下一排键名，
 * 而它不报任何错。判据见 {@code ConfigurationSurfaceBaselineTest} 第①格。
 * <p>
 * 与之配套，绑定根那一侧的这五个字段<b>不再标</b> {@code @NestedConfigurationProperty}：
 * 两侧都标，同一批键就会生成两份同名条目，合并时哪一份胜出取决于类路径顺序——
 * 一份带说明、一份是空的，赢家不定，等于把界面的字段表交给了运气。
 * <p>
 * 🔴 这几节自身<b>不许挂</b> {@code @ConfigurationProperties}：那会给同一批配置键
 * 开出第二个绑定入口。本类把注解收在一处，绑定入口仍然只有一个。
 */
@Getter
@ConfigurationProperties(prefix = "starbot.core")
class CoreConfigurationSections {
    @NestedConfigurationProperty
    private final NetworkThreadProperties networkThread = new NetworkThreadProperties();

    @NestedConfigurationProperty
    private final LogProperties log = new LogProperties();

    @NestedConfigurationProperty
    private final NetworkProperties network = new NetworkProperties();

    @NestedConfigurationProperty
    private final DatasourceProperties datasource = new DatasourceProperties();

    @NestedConfigurationProperty
    private final LiveProperties live = new LiveProperties();
}
