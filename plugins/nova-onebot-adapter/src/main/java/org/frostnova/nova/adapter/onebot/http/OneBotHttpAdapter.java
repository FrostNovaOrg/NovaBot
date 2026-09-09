package org.frostnova.nova.adapter.onebot.http;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.annotation.OneBotApi;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;

/**
 * NovaBot OneBot HTTP 服务接口
 * <p>
 * <b>返回类型不是装饰</b>：代理按方法声明的返回类型去取 {@code data}。
 * 列表类接口的 {@code data} 是数组，写成 {@link JSONObject} 会<b>安静地拿到 null</b>
 * 而不是报错，详见 {@code OneBotHttpAdapterProxy}。
 */
public interface OneBotHttpAdapter {
    @OneBotApi(name = "获取版本信息", url = "/get_version_info")
    JSONObject getVersionInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取登录信息", url = "/get_login_info")
    JSONObject getLoginInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取状态信息", url = "/get_status")
    JSONObject getStatus(OneBotSender sender, JSONObject params);

    // 名单类三支标 latency = false：见 @OneBotApi#latency()。OneBotTargetDirectory 每轮
    // 要打「群数加三」次这类请求，是它们把「推送变慢」的耗时窗整轮冲掉的
    @OneBotApi(name = "获取群成员信息", url = "/get_group_member_info", latency = false)
    JSONObject getGroupMemberInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取群列表", url = "/get_group_list", latency = false)
    JSONArray getGroupList(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取好友列表", url = "/get_friend_list", latency = false)
    JSONArray getFriendList(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "发送私聊消息", url = "/send_private_msg")
    JSONObject sendPrivateMsg(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "发送群聊消息", url = "/send_group_msg")
    JSONObject sendGroupMsg(OneBotSender sender, JSONObject params);
}
