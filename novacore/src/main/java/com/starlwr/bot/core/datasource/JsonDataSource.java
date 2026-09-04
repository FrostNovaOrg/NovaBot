package com.starlwr.bot.core.datasource;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.DatasourceProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.exception.DataSourceException;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.util.CollectionUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JSON 数据源
 */
@Profile("json")
@Slf4j
@Service
@DataSource(name = "json")
public class JsonDataSource extends AbstractDataSource {
    /**
     * 推送用户的必填字段：键名与取值方式
     * <p>
     * 一份清单管两件事，不写两份——
     * {@link #parse} 按键名查「键在不在」，{@link #unfilledFields} 按取值方式查「填没填」。
     * 分开写两份的下场是：往解析器里加一个必填字段，完成度那一侧不会跟着加，
     * 于是新字段留空照样能一路带进内存，而两处判据都是绿的。
     * <p>
     * 用有序映射：字段列出来的次序就是提示语里的次序，与文件里的次序一致才好对着改。
     */
    private static final Map<String, Function<PushUser, Object>> USER_REQUIRED_FIELDS = new LinkedHashMap<>();

    /**
     * 推送目标的必填字段，说明同 {@link #USER_REQUIRED_FIELDS}
     */
    private static final Map<String, Function<PushTarget, Object>> TARGET_REQUIRED_FIELDS = new LinkedHashMap<>();

    /**
     * 推送消息的必填字段，说明同 {@link #USER_REQUIRED_FIELDS}
     */
    private static final Map<String, Function<PushMessage, Object>> MESSAGE_REQUIRED_FIELDS = new LinkedHashMap<>();

    static {
        USER_REQUIRED_FIELDS.put("uid", PushUser::getUid);
        USER_REQUIRED_FIELDS.put("platform", PushUser::getPlatform);

        TARGET_REQUIRED_FIELDS.put("platform", PushTarget::getPlatform);
        TARGET_REQUIRED_FIELDS.put("type", PushTarget::getType);
        TARGET_REQUIRED_FIELDS.put("num", PushTarget::getNum);

        MESSAGE_REQUIRED_FIELDS.put("handler", PushMessage::getHandler);
    }

    private final DatasourceProperties properties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> pendingTask;

    /**
     * 文件监听服务，停机时需主动关闭
     */
    private volatile WatchService watchService;

    private final AtomicLong lastTriggeredTime = new AtomicLong(0);

    private final long debounceDelayMillis = 1000L;

    @Autowired
    public JsonDataSource(ApplicationEventPublisher eventPublisher, DataSourceServiceRegistry dataSourceServiceRegistry, PushMessageInitializer messageInitializer, DatasourceProperties properties) {
        super(eventPublisher, dataSourceServiceRegistry, messageInitializer);
        this.properties = properties;
    }

    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, event, handler, params, enabled 字段
     */
    @Override
    public void load() {
        log.info("已选用 JSON 作为数据源");
        log.info("开始从 JSON 中初始化推送配置");

        String path = properties.getJsonPath();
        try {
            List<PushUser> users = parse(Files.readString(Path.of(path)));
            add(admit(users));
        } catch (NoSuchFileException e) {
            // 🔴 文件不在<b>不是错误，是还没配</b>。发行包不再带这个文件，它由控制台在加第一位主播时生成，
            // 于是「刚装好、还没加过主播」的实例必然走到这里。此前这一支抛异常，而异常是在
            // ApplicationReadyEvent 里抛出来的——进程当场死掉，使用者看到的是「装好了起不来」，
            // 而他还没有任何机会去配置。
            //
            // 🔴 但它也可能是「路径配错了」——那两件事在这一句 NoSuchFileException 上长得一样，
            // 而它们该做的事完全不同。不按「路径改没改过」去分成两支：那条分支只改得了日志的级别，
            // 分完之后没有任何判据量得到走的是哪一支，而一条量不到的分支迟早会走反。
            // 一行话把两种读法都说出来，判断留给看日志的人——他知道这个路径是不是自己配的。
            log.warn("没有找到推送配置文件 {}, 先按空的推送配置启动。"
                    + "刚装好的实例本来就没有它, 在控制台里加第一位主播时会自动生成; "
                    + "若这个路径是你自己配的, 请核对 starbot.core.datasource.json-path", path);
        } catch (Exception e) {
            throw new DataSourceException("读取数据源 JSON 文件异常", e);
        }

        log.info("成功从 JSON 中导入了 {} 个主播", this.users.size());

        eventPublisher.publishEvent(new StarBotDataSourceLoadCompleteEvent(new ArrayList<>(this.users)));

        if (properties.isJsonAutoReload()) {
            watchFileUpdate();
        }
    }

    /**
     * 监听 JSON 文件更新
     */
    private void watchFileUpdate() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            this.watchService = watchService;
            Path jsonPath = Paths.get(properties.getJsonPath()).toAbsolutePath();
            Path parentPath = jsonPath.getParent();
            parentPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

            executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            if (changed != null && changed.getFileName().equals(jsonPath.getFileName())) {
                                if (pendingTask != null && !pendingTask.isDone()) {
                                    pendingTask.cancel(false);
                                }

                                pendingTask = scheduler.schedule(() -> {
                                    Thread.currentThread().setName("json-watcher");
                                    long now = System.currentTimeMillis();
                                    long last = lastTriggeredTime.getAndSet(now);
                                    if (now - last >= debounceDelayMillis) {
                                        log.info("检测到数据源 JSON 文件已更新, 开始从 JSON 中重载推送配置");
                                        reload();
                                    }
                                }, debounceDelayMillis, TimeUnit.MILLISECONDS);
                            }
                        }

                        if (!key.reset()) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (ClosedWatchServiceException e) {
                        // 停机时会关闭 WatchService，阻塞中的 take() 随即抛出该异常。
                        // 这是正常终止而非故障，既不该记 ERROR，也必须跳出循环：
                        // 继续循环的话 take() 会立即再抛，变成边空转边刷 ERROR 直到进程退出
                        break;
                    } catch (Exception e) {
                        // 此处的异常来自事件处理而非 take()，属可恢复情况，记录后继续监听
                        log.error("监听数据源 JSON 文件更新异常", e);
                    }
                }
            });
        } catch (Exception e) {
            log.error("监听数据源 JSON 文件更新异常", e);
        }
    }

    /**
     * 重载数据源
     */
    private void reload() {
        String path = properties.getJsonPath();
        try {
            List<PushUser> addUsers = new ArrayList<>();
            List<PushUser> removeUsers = new ArrayList<>();
            List<PushUser> updateUsers = new ArrayList<>();

            // 先滤掉没填完的，再查重：未填完的条目 uid 同为空，彼此在 equals 眼里是「同一位主播」，
            // 放着不管会让「有两条还没填」被报成「配置里有重复的用户」——指向的方向是错的
            List<PushUser> users = admit(parse(Files.readString(Path.of(path))));
            if (new HashSet<>(users).size() != users.size()) {
                throw new DataSourceException("推送用户列表中存在重复的用户");
            }

            CollectionUtil.compareCollectionDiff(this.users, users, addUsers, removeUsers, updateUsers);

            add(addUsers);
            for (PushUser user : removeUsers) {
                remove(user);
            }
            update(updateUsers);
        } catch (Exception e) {
            log.error("重载数据源 JSON 文件异常", e);
        }
    }

    /**
     * 解析 JSON 数据
     * @param json JSON 数据
     * @return 解析出的 PushUser 列表
     */
    protected List<PushUser> parse(String json) {
        List<PushUser> users = new ArrayList<>();

        for (JSONObject userObject : JSON.parseArray(json).toList(JSONObject.class)) {
            for (String field : USER_REQUIRED_FIELDS.keySet()) {
                if (!userObject.containsKey(field)) {
                    throw new DataSourceException("数据源 JSON 文件格式错误, " + field + " 字段缺失");
                }
            }

            PushUser user = new PushUser();
            user.setUid(userObject.getLong("uid"));
            user.setPlatform(userObject.getString("platform"));
            if (!userObject.containsKey("enabled")) {
                user.setEnabled(true);
            } else {
                user.setEnabled(userObject.getBoolean("enabled"));
            }
            user.setTargets(new ArrayList<>());

            if (userObject.containsKey("targets")) {
                for (JSONObject targetObject : userObject.getJSONArray("targets").toList(JSONObject.class)) {
                    for (String field : TARGET_REQUIRED_FIELDS.keySet()) {
                        if (!targetObject.containsKey(field)) {
                            throw new DataSourceException("数据源 JSON 文件格式错误, 缺失 " + field + " 字段");
                        }
                    }

                    PushTarget target = new PushTarget();
                    target.setUser(user);
                    target.setPlatform(targetObject.getString("platform"));
                    // type 留空时不去按 code 反查：of 收的是 int，空值在这里会拆箱成空指针，
                    // 抛出的是一句与配置毫无关系的话。留成 null 交给完成度那一关去说清楚它没填
                    Integer type = targetObject.getInteger("type");
                    target.setType(type == null ? null : PushTargetType.of(type));
                    target.setNum(targetObject.getLong("num"));
                    if (!targetObject.containsKey("enabled")) {
                        target.setEnabled(true);
                    } else {
                        target.setEnabled(targetObject.getBoolean("enabled"));
                    }
                    target.setMessages(new ArrayList<>());
                    user.getTargets().add(target);

                    if (targetObject.containsKey("messages")) {
                        for (JSONObject messageObject : targetObject.getJSONArray("messages").toList(JSONObject.class)) {
                            for (String field : MESSAGE_REQUIRED_FIELDS.keySet()) {
                                if (!messageObject.containsKey(field)) {
                                    throw new DataSourceException("数据源 JSON 文件格式错误, " + field + " 字段缺失");
                                }
                            }

                            PushMessage message = new PushMessage();
                            message.setTarget(target);
                            message.setHandler(messageObject.getString("handler"));
                            JSONObject params = messageObject.getJSONObject("params");
                            if (params != null) {
                                message.setParams(params.toJSONString());
                            }
                            if (!messageObject.containsKey("enabled")) {
                                message.setEnabled(true);
                            } else {
                                message.setEnabled(messageObject.getBoolean("enabled"));
                            }
                            target.getMessages().add(message);
                        }
                    }
                }
            }

            users.add(user);
        }

        return users;
    }

    /**
     * 挑出填完了的推送用户，其余登记为「配置未完成」
     * <p>
     * 必填字段的<b>键在不在</b>由 {@link #parse} 拦，那一关拦的是写错了的文件，理当报错。
     * 这一关拦的是另一件事：键都在、值还空着——发行包里的示例正是这样发出去的，
     * 照着改的人在改完之前，手上那份配置必然处在这个状态。<b>那不是错误，是还没做完。</b>
     * <p>
     * 因此这里既不抛异常也不照单收下：抛异常会让机器人起不来，使用者看到的只是「没上线」；
     * 收下则会把半份配置一路带进内存，直到某个读它的地方才炸——
     * 控制台读运行状态时的那句「num is marked non-null but is null」就是这么来的。
     * <p>
     * 日志只写一行：未填完的条目往往是一批（照示例配了几位主播就有几条），
     * 逐条一行会把启动日志刷满，而每一行说的是同一件事。
     * @param users 解析出的推送用户
     * @return 其中填完了的那些
     */
    private List<PushUser> admit(List<PushUser> users) {
        List<PushUser> ready = new ArrayList<>();
        List<IncompleteEntry> incomplete = new ArrayList<>();

        for (int i = 0; i < users.size(); i++) {
            List<String> unfilled = unfilledFields(users.get(i));
            if (unfilled.isEmpty()) {
                ready.add(users.get(i));
            } else {
                incomplete.add(new IncompleteEntry(i + 1, unfilled));
            }
        }

        reportIncompleteEntries(incomplete);

        if (!incomplete.isEmpty()) {
            log.warn("数据源 JSON 文件中有 {} 条推送配置尚未填写完整, 已跳过: {}", incomplete.size(),
                    incomplete.stream().map(IncompleteEntry::describe).collect(Collectors.joining("; ")));
        }

        return ready;
    }

    /**
     * 找出一位推送用户身上还没填的必填字段
     * <p>
     * 空串与 null 一并算作没填：JSON 里留空的占位（{@code "uid": ""}）解出来正是 null，
     * 而手工编辑时写成 {@code " "} 的也是同一件事。
     * @param user 推送用户
     * @return 尚未填写的字段路径，全填好了时为空列表
     */
    private static List<String> unfilledFields(PushUser user) {
        List<String> unfilled = new ArrayList<>();

        USER_REQUIRED_FIELDS.forEach((name, value) -> {
            if (isBlank(value.apply(user))) {
                unfilled.add(name);
            }
        });

        List<PushTarget> targets = user.getTargets() == null ? List.of() : user.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            PushTarget target = targets.get(i);
            String targetPath = "targets[" + (i + 1) + "]";

            TARGET_REQUIRED_FIELDS.forEach((name, value) -> {
                if (isBlank(value.apply(target))) {
                    unfilled.add(targetPath + "." + name);
                }
            });

            List<PushMessage> messages = target.getMessages() == null ? List.of() : target.getMessages();
            for (int j = 0; j < messages.size(); j++) {
                PushMessage message = messages.get(j);
                String messagePath = targetPath + ".messages[" + (j + 1) + "]";

                MESSAGE_REQUIRED_FIELDS.forEach((name, value) -> {
                    if (isBlank(value.apply(message))) {
                        unfilled.add(messagePath + "." + name);
                    }
                });
            }
        }

        return unfilled;
    }

    /**
     * 判断一个字段值算不算「没填」
     * @param value 字段值
     * @return 是否没填
     */
    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    /**
     * 停止文件监听并关闭线程池
     * <p>
     * 这两个线程池此前既非 Spring 托管也无人关闭。必须主动关闭监听服务，
     * 才能让阻塞在 take() 上的线程退出——WatchService 的等待不响应中断，
     * 单靠 shutdownNow() 是叫不醒它的。
     */
    @PreDestroy
    public void shutdown() {
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.debug("关闭数据源文件监听失败: {}", e.getMessage());
            }
        }

        scheduler.shutdownNow();
        executor.shutdownNow();
    }
}
