package com.starlwr.bot.adapter.onebot.extension.napcat.http;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.annotation.NapcatApi;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;

/**
 * StarBot Napcat HTTP 扩展服务接口
 */
public interface NapcatHttpAdapter {
    @NapcatApi(name = "获取 @全体成员 剩余次数", url = "/get_group_at_all_remain")
    JSONObject getGroupAtAllRemain(OneBotSender sender, JSONObject params);

    @NapcatApi(name = "设置群待办", url = "/set_group_todo")
    JSONObject setGroupTodo(OneBotSender sender, JSONObject params);
}
