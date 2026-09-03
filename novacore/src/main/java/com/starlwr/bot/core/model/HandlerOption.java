package com.starlwr.bot.core.model;

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
 */
public record HandlerOption(String key, String label, String description, Type type,
                            Object defaultValue, Integer min, Integer max) {
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
}
