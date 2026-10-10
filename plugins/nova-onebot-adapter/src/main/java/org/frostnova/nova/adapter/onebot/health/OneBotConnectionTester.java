package org.frostnova.nova.adapter.onebot.health;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.adapter.onebot.service.OneBotConnectionManager;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.plugin.NovaComponent;
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
 * 测通并存下来之后当场把连接接上，那一步交给 {@link OneBotConnectionManager}。
 */
@Slf4j
@NovaComponent
public class OneBotConnectionTester implements BotConnectionTester {
    private final OneBotHttpAdapter http;

    private final OneBotAdapterPluginProperties properties;

    private final OneBotConnectionManager connections;

    @Autowired
    public OneBotConnectionTester(OneBotHttpAdapter http, OneBotAdapterPluginProperties properties,
                                  OneBotConnectionManager connections) {
        this.http = http;
        this.properties = properties;
        this.connections = connections;
    }

    @Override
    public String adapter() {
        return "onebot";
    }

    @Override
    public Applied apply(String address, int httpPort, int websocketPort, String httpToken, String websocketToken) {
        return connections.apply(address, httpPort, websocketPort, httpToken, websocketToken);
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
        // 用保留名，让耗时统计等按平台聚合的指标能把这次临时调用排除掉：
        // 测试连接常常正是指向一个填错的地址，混进真实平台的统计会把它带歪
        sender.setName(OneBotConnectionState.RESERVED_TEST_SENDER);
        sender.setOneBotAddress(address);
        sender.setOneBotHttpPort(httpPort);
        sender.setOneBotHttpToken(httpToken);

        // HTTP Token 留空与保存一侧同义，即「保持原值」：地址与已存那一台相同时用已存的去测，
        // 测的正是存下去会用的那一份。地址不同则照空的测，已存的凭据不发到没确认过的机器上
        OneBotSender saved = properties.getSenders().stream().findFirst().orElse(null);
        boolean savedTokenWithheld = false;
        if (saved != null && StringUtil.isBlank(httpToken)) {
            if (sameAddress(address, saved.getOneBotAddress())) {
                sender.setOneBotHttpToken(saved.getOneBotHttpToken());
            } else {
                savedTokenWithheld = StringUtil.isNotBlank(saved.getOneBotHttpToken());
            }
        }

        // 头值里放不下的字符会让 HTTP 客户端在建请求时抛异常，消息原样带着整段头值，
        // 在这里先拦下，Token 一个字也不进回包和日志
        String token = sender.getOneBotHttpToken();
        if (!fitsInHeader(token)) {
            log.debug("测试 OneBot 连接未发出：HTTP Token 里有请求头放不下的字符");
            return Result.failed("HTTP Token 里有请求头放不下的字符（例如换行、中文或全角字符）",
                    "请检查配置文件里的 HTTP Token，或在页面上重新填写后再测");
        }

        try {
            JSONObject version = http.getVersionInfo(sender, new JSONObject());
            JSONObject login = http.getLoginInfo(sender, new JSONObject());

            return Result.ok("连接正常，实现版本 v" + version.getString("app_version")
                    + "，登录账号 " + login.getString("nickname") + "(" + login.getLong("user_id") + ")");
        } catch (HttpClientErrorException.Forbidden e) {
            if (savedTokenWithheld) {
                return Result.failed("Token 校验未通过",
                        "地址换了，留空的 Token 不会沿用已存的，请把 HTTP Token 填上再测");
            }
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
            // 异常消息可能带着请求头，回包只给类别，日志里先遮盖这次发出去的 Token
            log.debug("测试 OneBot 连接失败: {}", masked(e.getMessage(), token));
            return Result.failed("连接测试失败：" + e.getClass().getSimpleName(),
                    "请检查地址、端口与 Token 配置，并确认 OneBot 实现处于运行状态");
        }
    }

    /**
     * 与 JDK HttpClient 的头值校验同一套：只收制表符、空格、可见 ASCII（0x21-0x7E）与 0x80-0xFF；
     * 其余控制字符、DEL 与 0xFF 以上的字一律不收
     */
    private static boolean fitsInHeader(String token) {
        if (token == null) {
            return true;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean fits = c == '\t' || c == ' ' || (c >= 0x21 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF);
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    private static String masked(String message, String token) {
        if (message == null || token == null || token.isEmpty()) {
            return message;
        }
        return message.replace(token, "******");
    }

    private static boolean sameAddress(String tested, String saved) {
        return tested != null && saved != null && tested.trim().equals(saved.trim());
    }
}
