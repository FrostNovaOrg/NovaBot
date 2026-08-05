package com.starlwr.bot.adapter.onebot.http;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.annotation.OneBotApi;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;

/**
 * StarBot OneBot HTTP 服务接口
 */
public interface OneBotHttpAdapter {
    @OneBotApi(name = "获取版本信息", url = "/get_version_info")
    JSONObject getVersionInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取登录信息", url = "/get_login_info")
    JSONObject getLoginInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取状态信息", url = "/get_status")
    JSONObject getStatus(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "获取群成员信息", url = "/get_group_member_info")
    JSONObject getGroupMemberInfo(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "发送私聊消息", url = "/send_private_msg")
    JSONObject sendPrivateMsg(OneBotSender sender, JSONObject params);

    @OneBotApi(name = "发送群聊消息", url = "/send_group_msg")
    JSONObject sendGroupMsg(OneBotSender sender, JSONObject params);
}
