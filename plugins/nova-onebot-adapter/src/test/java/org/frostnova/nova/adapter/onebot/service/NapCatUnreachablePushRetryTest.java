package org.frostnova.nova.adapter.onebot.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.dto.MessageDTO;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapterProxy;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NapCat 重启、掉线重登的那几秒，纯文字推送（开播通知、下播、命令回复）会不会丢。
 * <p>
 * 进程内直调把请求交给适配器。适配器若把「根本没连上」包成一条普通结果，
 * 核心看见结果非空就不再重试，通知就没了。请求已经送出、只是等回包超时的，
 * 不能重发：对端可能已经发进群，再发一次群里就是两条。
 */
@DisplayName("NapCat 连不上时的推送")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NapCatUnreachablePushRetryTest {
    private static final String PLATFORM = "qq-onebot";

    private static final String NOTICE = "主播开播了";

    private FakeOneBotHttpServer napcat;

    private HttpServer hang;

    private ExecutorService hangThreads;

    private ThreadPoolTaskExecutor executor;

    private int closedPort;

    @AfterEach
    void tearDown() {
        if (napcat != null) {
            napcat.close();
        }
        if (hang != null) {
            hang.stop(0);
        }
        if (hangThreads != null) {
            hangThreads.shutdownNow();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("NapCat 重启那几秒里发的开播通知：连接被拒两次、第三次成功，群里收到且只收到一次")
    void liveNoticeRefusedTwiceThenDeliveredOnce() throws Exception {
        Rig rig = open(Mode.REFUSE_TWICE);

        JSONObject result = rig.sender.sendNow(notice());

        assertAll(
                () -> assertEquals(0, result.getIntValue("code"),
                        "一次失败不该就定。结果 " + result + "；异常 " + rig.gate.chain()),
                () -> assertEquals(3, rig.gate.sends.get(),
                        "连接被拒应再试，第三次才成功；实际 " + rig.gate.sends.get()
                                + " 次；异常 " + rig.gate.chain()),
                () -> assertEquals(1, napcat.groupMessages().size(),
                        "群里应收到且只收到一次，实际 " + napcat.groupMessages().size())
        );
    }

    @Test
    @DisplayName("请求已送出、读超时：只发一次、不重发，日志写明送达不明")
    void readTimeoutSendsOnceAndLogsDeliveryUnknown() throws Exception {
        Rig rig = open(Mode.HANG);
        ListAppender<ILoggingEvent> appender = attachLog();
        try {
            JSONObject result = rig.sender.sendNow(notice());

            String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
            assertAll(
                    () -> assertEquals(1, rig.gate.sends.get(),
                            "读超时不该重发，实际 " + rig.gate.sends.get() + " 次；异常 " + rig.gate.chain()),
                    () -> assertTrue(logged.contains("送达不明"), "日志应写明送达不明: " + logged),
                    () -> assertNotEquals(0, result.getIntValue("code"), result.toJSONString())
            );
        } finally {
            detachLog(appender);
        }
    }

    @Test
    @DisplayName("NapCat 回了业务失败码：不重试")
    void businessFailureIsNotRetried() throws Exception {
        Rig rig = open(Mode.BUSINESS);
        napcat.failGroupMessageAt(1);

        JSONObject result = rig.sender.sendNow(notice());

        assertAll(
                () -> assertEquals(1, rig.gate.sends.get(),
                        "业务失败码不该重试，实际 " + rig.gate.sends.get() + " 次"),
                () -> assertNotEquals(0, result.getIntValue("code"), result.toJSONString()),
                () -> assertEquals(1, napcat.groupMessages().size(),
                        "失败那一次打出去了就该停，实际 " + napcat.groupMessages().size())
        );
    }

    private Rig open(Mode mode) throws IOException {
        napcat = new FakeOneBotHttpServer();
        closedPort = closedPort();
        if (mode == Mode.HANG) {
            startHang();
        }

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();

        HttpUtil http = new HttpUtil(executor, oneBotClient(), new LogProperties());
        OneBotConnectionState state = new OneBotConnectionState();
        InvocationHandler real = new OneBotHttpAdapterProxy(http, state);
        Gate gate = new Gate(mode, real, http, closedPort, hangUrl());
        OneBotHttpAdapter adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                gate);

        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        OneBotHttpService service = new OneBotHttpService(mock(TaskScheduler.class), executor, properties, adapter,
                new OneBotMessageConverter(), state);

        OneBotSender onebot = new OneBotSender();
        onebot.setName(PLATFORM);
        onebot.setOneBotAddress("127.0.0.1");
        onebot.setOneBotHttpPort(napcat.port());
        onebot.setOneBotHttpToken("token");
        service.register(onebot);

        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1/onebot/send");
        target.setDelay(0);
        target.setLocalDelivery((headers, params) -> service.send(toMessage(params)));

        return new Rig(coreSender(target), gate);
    }

    /**
     * 与运行时同一套客户端：JDK HttpClient、HTTP/1.1、连接与读取分开限时。
     * 读取限时收短，是为了让「等回包」这一格在秒级内结束。
     */
    private static RestTemplate oneBotClient() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(500));
        return new RestTemplate(factory);
    }

    private static int closedPort() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        int port = socket.getLocalPort();
        socket.close();
        return port;
    }

    private void startHang() throws IOException {
        hangThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "napcat-hang");
            thread.setDaemon(true);
            return thread;
        });
        hang = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        hang.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
            } catch (IOException ignored) {
                return;
            }
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        hang.setExecutor(hangThreads);
        hang.start();
    }

    private String hangUrl() {
        return hang == null ? "" : "http://127.0.0.1:" + hang.getAddress().getPort() + "/send_group_msg";
    }

    private NovaMessageSender coreSender(Sender target) {
        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        NovaCoreProperties properties = new NovaCoreProperties();
        HttpUtil unused = mock(HttpUtil.class);
        return new NovaMessageSender(unused, senderService, new PushActivityRecorder(TimelineWriter.NONE),
                new PushGate(properties), TimelineWriter.NONE, new AtAllQuotaService(properties), resolvers,
                new FirstPushTipService(new NovaStateStore(properties)));
    }

    private static Message notice() {
        return Message.create(PLATFORM, PushTargetType.GROUP, FakeOneBotHttpServer.GROUP_NUM, NOTICE).get(0);
    }

    /**
     * 进程内直调把核心的参数装成消息。字段与控制器上那一处相同：
     * 平台、正文、群号、顺序号、时间戳、目标类型。
     */
    private static MessageDTO toMessage(Map<String, Object> params) {
        MessageDTO message = new MessageDTO();
        message.setPlatform(params.get("platform") == null ? null : params.get("platform").toString());
        message.setContent(params.get("content") == null ? null : params.get("content").toString());
        message.setNum(number(params.get("num")));
        message.setSequence(number(params.get("sequence")));
        message.setCreateTime(number(params.get("create_time")));
        Long type = number(params.get("type"));
        message.setType(type == null ? PushTargetType.UNKNOWN : PushTargetType.of(type.intValue()));
        return message;
    }

    private static Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(value.toString().strip());
    }

    private static ListAppender<ILoggingEvent> attachLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLog(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        logger.detachAppender(appender);
    }

    private enum Mode {
        REFUSE_TWICE,
        HANG,
        BUSINESS
    }

    private record Rig(NovaMessageSender sender, Gate gate) {
    }

    /**
     * 前两次打到一个已经关掉的端口，抛出的就是这台机器上「连接被拒」的真实异常；
     * 第三次才落到假的 NapCat。读超时则打到一个收下请求但永不回包的端口。
     */
    private static final class Gate implements InvocationHandler {
        private final Mode mode;

        private final InvocationHandler real;

        private final HttpUtil http;

        private final int closedPort;

        private final String hangUrl;

        private final AtomicInteger sends = new AtomicInteger();

        private final AtomicReference<Throwable> lastTransport = new AtomicReference<>();

        private Gate(Mode mode, InvocationHandler real, HttpUtil http, int closedPort, String hangUrl) {
            this.mode = mode;
            this.real = real;
            this.http = http;
            this.closedPort = closedPort;
            this.hangUrl = hangUrl;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("sendGroupMsg".equals(method.getName())) {
                int n = sends.incrementAndGet();
                if (mode == Mode.HANG) {
                    miss(hangUrl, args[1]);
                } else if (mode == Mode.REFUSE_TWICE && n <= 2) {
                    miss("http://127.0.0.1:" + closedPort + "/send_group_msg", args[1]);
                }
            }
            return real.invoke(proxy, method, args);
        }

        private void miss(String url, Object params) {
            try {
                http.postJson(url, Map.of(), params);
            } catch (RuntimeException e) {
                lastTransport.set(e);
                throw e;
            }
            throw new IllegalStateException("对端不该给出响应: " + url);
        }

        private String chain() {
            Throwable error = lastTransport.get();
            if (error == null) {
                return "无";
            }
            StringBuilder text = new StringBuilder();
            Throwable current = error;
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (text.length() > 0) {
                    text.append(" <- ");
                }
                text.append(current.getClass().getName());
                if (current.getMessage() != null) {
                    text.append(": ").append(current.getMessage());
                }
                Throwable next = current.getCause();
                if (next == current) {
                    break;
                }
                current = next;
            }
            return text.toString();
        }
    }
}
