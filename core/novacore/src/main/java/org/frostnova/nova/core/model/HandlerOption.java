package org.frostnova.nova.core.model;

/**
 * 推送处理器的可配置项
 * <p>
 * 除消息模板之外，处理器往往还有别的参数——下播报告要决定展示哪些区块就是典型。
 * 这些参数原本只能在 {@code datasource.json} 的 {@code params} 里手写，
 * 而使用者根本无从知道有哪些键、取值范围是多少。
 * <p>
 * 处理器在 {@code options()} 里声明自己有哪些参数，配置界面据此**通用地**渲染出
 * 开关与数字框——界面不认识任何具体的参数名，因此第三方插件自带的参数同样能配。
 *
 * @param key 参数名，即 {@code params} 中的键
 * @param label 界面上的名称
 * @param description 一句话说明，可为空
 * @param type 取值类型，决定界面渲染成开关还是数字框
 * @param defaultValue 默认值，界面据此显示初始状态；使用者没改过的参数不会写进配置文件
 * @param min 数字型的下限，非数字型可为 null
 * @param max 数字型的上限，非数字型可为 null
 * @param revenueVisibility 这一项随会话的金额可见性怎么走，缺省为 {@link RevenueVisibility#UNRELATED}；
 *                          界面据此在当前设定下不出图的项上灰掉并写原因。第三方插件不标即无关
 * @param revenueNote 随金额的那句人话原因，给在灰掉的项旁边看；未标随金额的项可为 null
 */
public record HandlerOption(String key, String label, String description, Type type,
                            Object defaultValue, Integer min, Integer max,
                            RevenueVisibility revenueVisibility, String revenueNote) {
    /**
     * 旧构造：七个栏都在、随金额两栏取缺省
     * <p>
     * {@code options()} 是插件接口，第三方处理器按七栏构造的调用必须照旧能编：
     * 它们不认识金额可见性，等价于每一项都「无关」。
     */
    public HandlerOption(String key, String label, String description, Type type,
                         Object defaultValue, Integer min, Integer max) {
        this(key, label, description, type, defaultValue, min, max, RevenueVisibility.UNRELATED, null);
    }

    /**
     * 构造一个开关
     */
    public static HandlerOption bool(String key, String label, String description, boolean defaultValue) {
        return new HandlerOption(key, label, description, Type.BOOLEAN, defaultValue, null, null);
    }

    /**
     * 构造一个数字项
     */
    public static HandlerOption integer(String key, String label, String description,
                                        int defaultValue, int min, int max) {
        return new HandlerOption(key, label, description, Type.INTEGER, defaultValue, min, max);
    }

    /**
     * 参数的取值类型
     */
    public enum Type {
        /**
         * 布尔开关
         */
        BOOLEAN,

        /**
         * 整数，界面渲染为带上下限的数字框
         */
        INTEGER
    }

    /**
     * 这一项随会话的金额可见性怎么走
     * <p>
     * 同一份版式推给不同的会话，有的看得到金额、有的看不到：看不到的会话里有的区块
     * 整块不出（它每一行都是消费明细），有的只是换个不带钱的说法。处理器在这里声明
     * 每一项属于哪种，界面就能在「当前设定下不出图」的项上灰掉并写明原因——
     * 否则使用者把它调得再细，图上也不会有那一块，还以为是坏了。
     * <p>
     * 这只是**声明给界面看的说明**：区块到底出不出，仍由画图那一侧按会话的金额可见性决定。
     * 两边各写各的话，声明错一项，界面就灰错一项，而两头都不会报错——所以声明与画图
     * 的一致由报告插件那边的判据对着量，不在这里猜。
     */
    public enum RevenueVisibility {
        /**
         * 与金额可见性无关：两种会话里都照常出
         */
        UNRELATED,

        /**
         * 显示金额的会话里才出，隐藏金额的会话里整块不出
         */
        ONLY_WHEN_SHOWN,

        /**
         * 隐藏金额的会话里才出，显示金额的会话里不画（同一件事由别处承担）
         */
        ONLY_WHEN_HIDDEN,

        /**
         * 两种会话里都出，隐藏金额的那侧换成不带钱的说法
         */
        RESTYLED_WHEN_HIDDEN
    }
}
