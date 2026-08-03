package com.starlwr.bot.adapter.onebot.health;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Optional;

/**
 * OneBot 连接测试
 * <p>
 * 用一次真实的接口调用验证连接信息，并按失败类型给出针对性建议：
 * 「连不上」「Token 不对」「端口填错」的修复方式完全不同，笼统报「连接失败」帮不上忙。
 */
@Slf4j
@StarBotComponent
public class OneBotConnectionTester implements BotConnectionTester {
    private final OneBotHttpAdapter http;

    private final OneBotAdapterPluginProperties properties;

    @Autowired
    public OneBotConnectionTester(OneBotHttpAdapter http, OneBotAdapterPluginProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String adapter() {
        return "onebot";
    }

    @Override
    public Optional<Connection> current() {
        return properties.getSenders().stream()
                .findFirst()
                .map(sender -> new Connection(sender.getOneBotAddress(),
                        sender.getOneBotHttpPort(), sender.getOneBotWebsocketPort()));
    }

    @Override
    public Result test(String address, int httpPort, String httpToken) {
        OneBotSender sender = new OneBotSender();
        sender.setName("__connection_test__");
        sender.setOneBotAddress(address);
        sender.setOneBotHttpPort(httpPort);
        sender.setOneBotHttpToken(httpToken);

        try {
            JSONObject version = http.getVersionInfo(sender, new JSONObject());
            JSONObject login = http.getLoginInfo(sender, new JSONObject());

            return Result.ok("连接正常，实现版本 v" + version.getString("app_version")
                    + "，登录账号 " + login.getString("nickname") + "(" + login.getLong("user_id") + ")");
        } catch (HttpClientErrorException.Forbidden e) {
            return Result.failed("Token 校验未通过",
                    "HTTP Token 与 OneBot 实现中配置的不一致，请核对两侧的 token 是否完全相同");
        } catch (HttpClientErrorException.NotFound e) {
            // 端口通了但路径不对，多半是把 Websocket 端口填成了 HTTP 端口
            return Result.failed("端口可连接，但接口不存在",
                    "该端口很可能不是 OneBot 的 HTTP 服务端口，请确认填的不是 Websocket 端口");
        } catch (ResourceAccessException e) {
            return Result.failed("无法建立连接",
                    "请确认 NapCat 等 OneBot 实现已启动，且地址与 HTTP 端口填写正确");
        } catch (Exception e) {
            log.debug("测试 OneBot 连接失败: {}", e.getMessage());
            return Result.failed("连接测试失败：" + e.getMessage(),
                    "请检查地址、端口与 Token 配置，并确认 OneBot 实现处于运行状态");
        }
    }
}
