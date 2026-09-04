package com.starlwr.bot.adapter.onebot.service;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.controller.OneBotController;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行期把 OneBot 连接接上
 * <p>
 * 连接信息此前只在进程启动时读一次：控制台上存下地址与 Token 之后，这个进程仍然什么都没连上，
 * 「挑推送目标」拿到的是空名单、「发一条试试」发不出去，而屏幕上没有任何东西解释为什么——
 * 只能让人先重启一次。本类把「按当前配置接上」做成一个可以随时调的动作，
 * <b>启动那条路走的也是它</b>。
 *
 * <h2>为什么要有这么一个统筹的地方</h2>
 * 接上一条连接不是一件事，是四件：推送接口要挂上去（否则核心投递不出去）、
 * HTTP 要体检一次并挂上定期检测、Websocket 要连、以及这些之前得先有一个
 * {@link OneBotSender} 存在。它们分散在三个类里，各自都只答自己那一段；
 * 谁来保证四件事按顺序发生、且只发生一次，得有个地方写下来。
 */
@Slf4j
@StarBotComponent
public class OneBotConnectionManager {
    /**
     * 这台机器上第一台机器人的平台名
     * <p>
     * 推送目标在 datasource.json 里按这个名字找回这台机器人，因此它一旦落盘就不该再变。
     * 与发行包里那份配置示例写的是同一个名字：照示例装的机器与从控制台配出来的机器，
     * 这一项必须一致，否则两台机器上同一份推送配置一台能用一台不能。
     */
    static final String FIRST_SENDER_NAME = "qq-onebot";

    /**
     * 这台机器上第一台机器人的推送接口路径
     */
    static final String FIRST_SENDER_API = "/send";

    /**
     * 这台机器上第一台机器人的消息发送间隔，单位: 毫秒
     * <p>
     * 同样取自发行包里那份配置示例。类字段上的默认值是 0（不间隔），那是给
     * 「配置文件里写了这条连接却没写这一项」的老部署用的；而一台全新的机器
     * 没有这段历史，给它一个连发不会被限流的值更合适。
     */
    static final int FIRST_SENDER_DELAY = 1000;

    private final OneBotAdapterPluginProperties properties;

    private final OneBotController controller;

    private final OneBotWebsocketService websocketService;

    private final OneBotHttpService httpService;

    private final OneBotConnectionState state;

    @Autowired
    public OneBotConnectionManager(OneBotAdapterPluginProperties properties, OneBotController controller,
                                   OneBotWebsocketService websocketService, OneBotHttpService httpService,
                                   OneBotConnectionState state) {
        this.properties = properties;
        this.controller = controller;
        this.websocketService = websocketService;
        this.httpService = httpService;
        this.state = state;
    }

    /**
     * 按传入的连接信息重建第一台机器人的连接
     * <p>
     * 整个方法上锁：使用者连按两下保存时，两次调用不能交错着去断彼此的连接。
     * @param address 地址，留空表示不改
     * @param httpPort HTTP 端口，非正数表示不改
     * @param websocketPort Websocket 端口，非正数表示不改
     * @param httpToken HTTP 访问令牌，留空表示不改
     * @param websocketToken Websocket 访问令牌，留空表示不改
     * @return 接上的结果，含配置文件里这条连接该有的全部字段
     */
    public synchronized BotConnectionTester.Applied apply(String address, int httpPort, int websocketPort,
                                                          String httpToken, String websocketToken) {
        OneBotSender sender = first();

        // 空白与非正数一律保持原值：界面有意不回填两个 Token，留空的语义是「这一项不改」
        if (StringUtil.isNotBlank(address)) {
            sender.setOneBotAddress(address.trim());
        }
        if (httpPort > 0) {
            sender.setOneBotHttpPort(httpPort);
        }
        if (websocketPort > 0) {
            sender.setOneBotWebsocketPort(websocketPort);
        }
        if (StringUtil.isNotBlank(httpToken)) {
            sender.setOneBotHttpToken(httpToken.trim());
        }
        if (StringUtil.isNotBlank(websocketToken)) {
            sender.setOneBotWebsocketToken(websocketToken.trim());
        }

        Map<String, String> configuration = configurationOf(sender);

        if (!controller.register(sender)) {
            return new BotConnectionTester.Applied(false,
                    "还缺 OneBot HTTP Token，这条连接接不上；填好之后再存一次", configuration);
        }

        // 顺序：先把 Websocket 接上，再体检 HTTP。两者互不依赖，但 Websocket 要连上
        // 才会有消息进来，而 HTTP 这一趟是同步的往返，先做它会让整个保存动作多等一个往返
        websocketService.start(sender);
        httpService.check(sender);

        return new BotConnectionTester.Applied(true, describe(sender.getName()), configuration);
    }

    /**
     * 取第一台机器人，还没有就建一台
     * <p>
     * 一台全新的机器上 senders 是空的：发行包不带 application.yml，第一次保存时
     * 按配置面渲染出来的那一份里，这个列表是<b>空表</b>。此时不建一条出来的话，
     * 「保存连接信息」这件事没有落点——改哪一条？
     * @return 第一台机器人
     */
    private OneBotSender first() {
        if (!properties.getSenders().isEmpty()) {
            return properties.getSenders().get(0);
        }

        OneBotSender sender = new OneBotSender();
        sender.setName(FIRST_SENDER_NAME);
        sender.setApi(FIRST_SENDER_API);
        sender.setWebsocket(true);
        sender.setDelay(FIRST_SENDER_DELAY);
        properties.getSenders().add(sender);

        log.info("这台机器上还没有配过机器人, 已按默认值建出推送平台 {}", FIRST_SENDER_NAME);
        return sender;
    }

    /**
     * 这条连接在配置文件里该长什么样
     * <p>
     * 给的是<b>整条元素</b>而不只是刚改的那几项：一台全新的机器上这条元素还不存在，
     * 只写地址与端口的话，文件里会落下一条没有名字的连接——而它在下次启动时
     * 让程序直接起不来（平台名为空即抛）。已经存在的元素上，没变的那几行写回去
     * 与原文逐字节相同，不会产生多余改动。
     * <p>
     * 推送接口 Token（{@code api-token}）<b>有意不在其中</b>：它留空的语义是
     * 「每次启动生成一把新的、只在本次运行有效」，把生成出来的那一把写进文件，
     * 等于替使用者把这个决定改成了「固定一把长期有效的」。
     * @param sender 推送平台信息
     * @return 元素内部的键到取值，顺序即写入顺序
     */
    private Map<String, String> configurationOf(OneBotSender sender) {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("name", sender.getName());
        configuration.put("api", sender.getApi());
        configuration.put("websocket", String.valueOf(sender.isWebsocket()));
        configuration.put("one-bot-address", sender.getOneBotAddress());
        configuration.put("one-bot-http-port", String.valueOf(sender.getOneBotHttpPort()));
        configuration.put("one-bot-websocket-port", String.valueOf(sender.getOneBotWebsocketPort()));
        configuration.put("one-bot-http-token", sender.getOneBotHttpToken());
        configuration.put("one-bot-websocket-token", sender.getOneBotWebsocketToken());
        configuration.put("delay", String.valueOf(sender.getDelay()));
        return configuration;
    }

    /**
     * 把这条连接此刻的状况写成一句话
     * <p>
     * 说的是<b>刚刚体检出来的现状</b>，不是「已保存」这类只描述动作的话：
     * 使用者点保存想知道的正是「现在通了没有」。
     * @param platformName 推送平台名
     * @return 一句话
     */
    private String describe(String platformName) {
        OneBotConnectionState.Entry entry = state.all().get(platformName);
        if (entry == null) {
            return "已接上，正在检测";
        }

        OneBotConnectionState.Status http = entry.getHttp();
        return http.kind() == OneBotConnectionState.Kind.OK
                ? http.detail()
                : "HTTP 这一路还不通：" + http.detail();
    }
}
