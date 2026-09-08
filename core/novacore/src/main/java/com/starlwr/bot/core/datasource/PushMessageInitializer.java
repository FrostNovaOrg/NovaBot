package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.model.PushMessage;

/**
 * 推送消息补全器
 * <p>
 * 数据源从配置里读出来的推送消息只有「用哪个处理器」「参数是什么」这些<b>文本</b>；
 * 真正要投递时还得挂上处理器实例、事件类型与解析好的参数对象。这一步依赖推送侧
 * 有哪些处理器已注册，<b>数据源本身既不知道也不该知道</b>——它只知道读到了一串类名。
 * <p>
 * 所以这里留一个挂钩：数据源在加载与更新推送用户时逐条调用它，由推送侧实现并装配。
 * 补不全（例如配置里写的处理器根本不存在）时返回 {@code false}，数据源会把这条消息丢掉，
 * 与补全器返回 {@code true} 但什么都没挂上相比，前者<b>丢得明白</b>：
 * 丢弃的判断权在这里，而不是散落在数据源内部对某个字段是否为空的猜测里。
 * <p>
 * 未装配补全器时（例如空数据源）数据源不做补全，也不丢弃任何消息。
 */
public interface PushMessageInitializer {
    /**
     * 补全一条推送消息
     * @param message 推送消息
     * @return 是否补全成功；返回 false 时该条消息会被丢弃
     */
    boolean initialize(PushMessage message);
}
