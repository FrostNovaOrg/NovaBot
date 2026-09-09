package org.frostnova.nova.adapter.onebot.extension.napcat.http;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.extension.napcat.annotation.NapcatApi;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;

/**
 * NapCat 在 OneBot 标准之外多出来的那几支接口
 * <p>
 * 这里只有声明没有实现：每一支都由 {@link NapcatHttpAdapterProxy} 按注解上的地址代打。
 * 因此<b>新增一支接口 ＝ 在这里加一个标了注解的方法</b>，不必动代理。
 * <p>
 * 两支的约定一致：回来的报文里 {@code retcode} 非 0 时抛
 * {@link org.frostnova.nova.adapter.onebot.exception.OneBotApiException}，
 * 否则只把 {@code data} 那一段交出来（对面没给 {@code data} 时是 {@code null}）。
 */
public interface NapcatHttpAdapter {
    /**
     * 问这个群今天还能不能 @全体成员
     * @param sender 推送平台信息，决定这次请求打给谁
     * @param params 请求参数，需要 {@code group_id}
     * @return {@code data} 段，其中 {@code can_at_all} 是还能不能 @
     */
    @NapcatApi(name = "获取 @全体成员 剩余次数", url = "/get_group_at_all_remain")
    JSONObject getGroupAtAllRemain(OneBotSender sender, JSONObject params);

    /**
     * 把一条已经发出去的消息设成群待办
     * <p>
     * 待办认的是消息 ID，所以只能等消息发出去之后再调，不能与发送同时进行。
     * @param sender 推送平台信息，决定这次请求打给谁
     * @param params 请求参数，需要 {@code group_id} 与 {@code message_id}
     * @return {@code data} 段
     */
    @NapcatApi(name = "设置群待办", url = "/set_group_todo")
    JSONObject setGroupTodo(OneBotSender sender, JSONObject params);
}
