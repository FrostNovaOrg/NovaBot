package com.starlwr.bot.adapter.onebot.extension.napcat.aop;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import com.starlwr.bot.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.MessagePlaceholders;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * @全体成员 次数不足时改用群待办顶上
 *
 * <h2>为什么值得做</h2>
 * 群里每天能 @全体成员 的次数是有限的，用完之后开播通知就淹在聊天记录里。
 * NapCat 多给了一支「设置群待办」的接口——待办会顶在群名片上，
 * 是这份额度用完之后<b>唯一还能让人一眼看见</b>的位置。
 *
 * <h2>为什么必须先发再挂</h2>
 * 待办认的是消息 ID，而消息 ID 要等消息真发出去才有。所以这里不能一口气做完：
 * 先把 {@code {at=all}} 摘掉照常发，再<b>登记一个发送成功回调</b>，等 ID 到手了才去挂待办。
 *
 * <h2>摘完什么都不剩的那一条</h2>
 * 模板写成「{@code {at=all}}{@code {next}}正文」时，占位符独占一条消息，摘完是空的。
 * 空消息不能发，于是这一条整条不发（<b>不调用 proceed</b>），
 * 待办改挂到相邻的那一条上——后一条还没发，只能登记回调；前一条已经发过，可以当场挂。
 */
@Slf4j
@Aspect
@StarBotComponent
@ConditionalOnProperty(name = "novabot.adapter.onebot.extension.napcat.enable-backup-at-all", havingValue = "true", matchIfMissing = true)
public class BackupAtAllAspect {
    private final NapcatServiceHolder holder;

    private final NapcatHttpAdapter http;

    @Autowired
    public BackupAtAllAspect(NapcatServiceHolder holder, NapcatHttpAdapter http) {
        this.holder = holder;
        this.http = http;
    }

    @Pointcut("execution(* com.starlwr.bot.core.sender.StarBotMessageSender.send(..))")
    public void sendMethod() {}

    @Around("sendMethod()")
    public Object aroundSendMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Message message = (Message) joinPoint.getArgs()[0];

        // 三道放行判据从便宜到贵排：前两条只看消息自己，第三条要打一次网络请求。
        // 顺序反过来的话，每一条私聊推送都会白问一次 NapCat
        if (!holder.isNapcat(message.getPlatform())
                || !PushTargetType.GROUP.equals(message.getType())
                || !message.getContent().contains(MessagePlaceholders.AT_ALL)) {
            return joinPoint.proceed();
        }

        OneBotSender sender = holder.getNapcat(message.getPlatform());
        if (canAtAll(sender, message.getNum())) {
            return joinPoint.proceed();
        }

        log.info("NapCat 推送平台 {} @全体成员 次数不足, 将自动替换为群待办发送", sender.getName());
        message.setContent(message.getContent().replace(MessagePlaceholders.AT_ALL, ""));

        JSONObject todoParams = new JSONObject();
        todoParams.put("group_id", message.getNum());

        if (StringUtil.isNotBlank(message.getContent())) {
            setTodoAfterSent(message, sender, todoParams);
            return joinPoint.proceed();
        }

        hangTodoOnNeighbour(message, sender, todoParams);
        return null;
    }

    /**
     * 问 NapCat 这个群现在还能不能 @全体成员
     * <p>
     * 答不上来（应答里没有 {@code can_at_all}）时按<b>不能</b>算：方向是刻意的。
     * 猜「能」的那次会照发 {@code {at=all}}，真发不出去就什么补救都没有了；
     * 猜「不能」的代价只是多挂一条群待办
     */
    private boolean canAtAll(OneBotSender sender, Long groupNum) {
        JSONObject params = new JSONObject();
        params.put("group_id", groupNum);
        return Boolean.TRUE.equals(http.getGroupAtAllRemain(sender, params).getBoolean("can_at_all"));
    }

    /**
     * 等这条消息发出去、拿到 ID 之后，把它设成群待办
     */
    private void setTodoAfterSent(Message message, OneBotSender sender, JSONObject todoParams) {
        message.addOnSuccessCallback(() -> {
            todoParams.put("message_id", message.getId());
            http.setGroupTodo(sender, todoParams);
        });
    }

    /**
     * 本条摘完是空的、发不出去，把待办挂到相邻的那一条上
     * <p>
     * 先看后一条：它与本条同属一次推送、内容更相关。没有后一条才退而求其次用前一条，
     * 那一条已经发过，当场就能挂
     */
    private void hangTodoOnNeighbour(Message message, OneBotSender sender, JSONObject todoParams) {
        Message next = message.getNext();
        if (next != null) {
            setTodoAfterSent(next, sender, todoParams);
            return;
        }

        Message previous = message.getPrevious();
        if (previous != null) {
            todoParams.put("message_id", previous.getId());
            http.setGroupTodo(sender, todoParams);
            return;
        }

        log.error("NapCat 推送平台 {} 顺序号为 {} 的消息为空且前后无消息可用, 无法设置群待办", sender.getName(), message.getSequence());
    }
}
