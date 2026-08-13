package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import com.starlwr.bot.core.util.SecureToken;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件流只读口令的签发、吊销与校验
 *
 * <h2>为什么不复用配置界面那把令牌</h2>
 *
 * 那把令牌同时是<b>控制台管理员凭据</b>：持有它即可进配置界面改配置。
 * 复用有两处代价——面板机器上的副本一旦泄漏，<b>事件流与控制台一起失守</b>；
 * 而换面板口令只能靠换控制台口令，<b>所有控制台使用者被迫重新登录</b>，
 * 吊销一个人的代价被放大到全体。
 * <p>
 * 🔒 <b>边界铁律：控制台令牌不得进入、也不得经过面板宿主机器。</b>
 * 铁律一破，这条路线就退化成「把管理员口令抄一份给别人」。
 * {@link #verify} <b>只认本服务签发的口令</b>，
 * 绝不能为了「省事」加一句「口令表里没有就再比一次控制台令牌」——
 * 那样铁律当场破掉，而功能测试会全绿，因为面板照样连得上。
 *
 * <h2>存储形态</h2>
 *
 * 与场次归档同形态：追加式 JSON 行文件，一行一把。<b>只存哈希不存明文</b>。
 * <p>
 * <b>吊销不删行，只标 {@code revokedAt}</b>：删掉之后「这把口令曾经存在过」这件事就没了，
 * 事后追查「当时是谁在连」会查不到。与归档只增不改同源。
 */
@Slf4j
@Service
public class EventStreamTokenService {
    private static final String FILE_NAME = "event-stream-tokens.jsonl";

    /**
     * 连续鉴权失败计数。<b>只用于让失败看得见，不做自动封禁</b>——
     * IP 白名单已裁不做，这里用<b>可见性代替对抗</b>。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private final Path file;

    @Autowired
    public EventStreamTokenService(StarBotCoreProperties properties) {
        this.file = Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath()
                .getParent().resolve(FILE_NAME);
    }

    /**
     * 签发一把新的只读口令
     * <p>
     * ⚠️ <b>返回的明文只在这一刻存在。</b> 库里只留哈希，所以此后<b>不可能再取回</b>——
     * 调用方必须把它当场交给使用者。
     * <b>能再取回来的明文，等于明文落盘</b>，那正是这里只存哈希要避免的事。
     * @param label 签给谁，用于日后定向吊销
     * @return 口令明文，<b>仅此一次</b>
     */
    public synchronized String issue(@NonNull String label) {
        String token = SecureToken.generate();
        EventStreamToken record = new EventStreamToken(hash(token), label, System.currentTimeMillis(), 0);
        append(record);
        log.info("已签发一把事件流只读口令: {} (指纹 {})", label, SecureToken.fingerprint(token));
        return token;
    }

    /**
     * 吊销一把口令
     * <p>
     * 按指纹定位，<b>标记而不删除</b>。已经吊销的再吊销一次不算失败——
     * 重复操作应当是幂等的，否则使用者会因为「点了两次」而以为出了问题。
     * <p>
     * ⚠️ <b>吊销对已经建立的连接不自动生效。</b> 这一条与校验放在哪一侧无关，
     * 两种机制下都成立：吊销之后必须确认旧连接已断，否则「已吊销」只对新连接成立。
     * @param fingerprint 口令指纹（列表里展示的那个）
     * @return 是否找到了这把口令
     */
    public synchronized boolean revoke(@NonNull String fingerprint) {
        List<EventStreamToken> all = list();
        boolean found = false;
        List<EventStreamToken> updated = new ArrayList<>(all.size());
        for (EventStreamToken token : all) {
            if (fingerprintOf(token).equals(fingerprint) && token.active()) {
                updated.add(new EventStreamToken(token.hash(), token.label(),
                        token.issuedAt(), System.currentTimeMillis()));
                found = true;
            } else {
                updated.add(token);
            }
        }

        if (found) {
            rewrite(updated);
            log.warn("已吊销一把事件流只读口令: 指纹 {} —— ⚠️ 已建立的连接不会自动断开, 请确认对方已掉线",
                    fingerprint);
        }
        return found;
    }

    /**
     * 校验结论
     * <p>
     * 取值与事件输出协议 {@code auth_failed.reason} 的线上取值一一对应，
     * <b>字符串写在这里而不是散在调用侧</b>——散着写迟早会有一处拼错，
     * 而客户端只会把认不出的 reason 当成「未知错误」，那正是这一位要消灭的东西。
     */
    public enum Verdict {
        /** 放行 */
        OK(null),

        /** 从没被签发过，或压根没出示 */
        BAD_TOKEN("bad_token"),

        /**
         * 曾经有效，已被吊销
         * <p>
         * ⚠️ 与 {@link #BAD_TOKEN} <b>必须分开</b>：只测「乱填一串被拒」证明不了吊销真的生效。
         */
        REVOKED("revoked"),

        /**
         * 已过期
         * <p>
         * 🔴 <b>当前永远不会返回这一项</b>：口令首版不带有效期
         * （{@code expiresAt} 恒为 {@code null}，见裁决 #99 二），到期靠控制台吊销。
         * <p>
         * 它留在这里是因为<b>契约有三值</b>（VRDash《设置界面设计稿-88》§五②）。
         * 日后真加了有效期，<b>必须回这一项而不是 {@link #BAD_TOKEN}</b>——
         * 「过期了」和「这把口令根本不存在」对用户是完全不同的两件事，
         * 前者该提示重取，后者该提示查证来源。
         */
        EXPIRED("expired");

        private final String wire;

        Verdict(String wire) {
            this.wire = wire;
        }

        /**
         * 协议里 {@code auth_failed.reason} 该写的值，{@link #OK} 返回 {@code null}
         */
        public String wire() {
            return wire;
        }
    }

    /**
     * 校验来访口令并给出结论
     * <p>
     * <b>只认本服务签发且未吊销的口令。</b>
     * ⚠️ 「无效」与「已吊销」是两种不同的失败：前者从没被签发过，后者<b>曾经有效</b>。
     * 两者都拒，但要分得开——客户端据此告诉用户该重取还是该查来源。
     * @param presented 来访者出示的口令
     * @return 校验结论
     */
    public Verdict check(String presented) {
        if (presented == null || presented.isBlank()) {
            recordFailure("未出示口令", null);
            return Verdict.BAD_TOKEN;
        }

        String presentedHash = hash(presented);
        for (EventStreamToken token : list()) {
            if (MessageDigest.isEqual(token.hash().getBytes(StandardCharsets.UTF_8),
                    presentedHash.getBytes(StandardCharsets.UTF_8))) {
                if (!token.active()) {
                    recordFailure("口令已吊销", presented);
                    return Verdict.REVOKED;
                }
                consecutiveFailures.set(0);
                return Verdict.OK;
            }
        }

        recordFailure("口令无效", presented);
        return Verdict.BAD_TOKEN;
    }

    /**
     * 校验来访口令
     * @param presented 来访者出示的口令
     * @return 是否放行
     */
    public boolean verify(String presented) {
        return check(presented) == Verdict.OK;
    }

    /**
     * 记一次鉴权失败
     * <p>
     * ⚠️ <b>绝不把来访者出示的口令原文写进日志。</b> 那等于把攻击者猜的串
     * 与（万一是手滑打错的）真口令一起落盘——日志本身就成了一个凭据文件。
     * 只记<b>指纹</b>：它足以判断「是不是同一个人在反复试」，却复原不出原文。
     * <p>
     * 不做自动封禁，只让失败<b>看得见</b>。连续失败的计数写进同一行，
     * 是为了让「有人在试」与「某人手滑了一次」在日志里长得不一样。
     */
    private void recordFailure(String reason, String presented) {
        int times = consecutiveFailures.incrementAndGet();
        log.warn("事件流鉴权失败({}), 连续第 {} 次, 出示口令指纹 {}",
                reason, times, presented == null ? "none" : SecureToken.fingerprint(presented));
    }

    /**
     * 列出全部口令记录，含已吊销的
     * <p>
     * 已吊销的也要列出来：它们是<b>审计事实</b>，「这把口令曾经存在过、何时被撤」
     * 正是事后追查要看的东西。
     */
    public synchronized List<EventStreamToken> list() {
        if (!Files.exists(file)) {
            return List.of();
        }

        List<EventStreamToken> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JSONObject json = JSON.parseObject(line);
                    result.add(new EventStreamToken(
                            json.getString("hash"),
                            json.getString("label"),
                            json.getLongValue("issuedAt"),
                            json.getLongValue("revokedAt")));
                } catch (Exception e) {
                    // 一行坏掉不该让整份口令表读不出来——那会让所有面板一起连不上
                    log.warn("跳过口令表中无法解析的一行: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("读取事件流口令表失败", e);
        }
        return result;
    }

    /**
     * 口令的展示指纹，控制台列表与吊销都用它定位
     */
    public String fingerprintOf(@NonNull EventStreamToken token) {
        return token.hash().substring(0, Math.min(8, token.hash().length()));
    }

    private void append(EventStreamToken token) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(token) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("写入事件流口令表失败", e);
        }
    }

    private void rewrite(List<EventStreamToken> tokens) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder content = new StringBuilder();
            for (EventStreamToken token : tokens) {
                content.append(JSON.toJSONString(token)).append(System.lineSeparator());
            }
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("更新事件流口令表失败", e);
        }
    }

    /**
     * 口令哈希。SHA-256 足够——这里防的是「文件泄漏后口令被直接读走」，
     * 而口令是 32 字节随机生成的，不存在字典攻击的入口
     */
    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算口令哈希", e);
        }
    }
}
