package com.starlwr.bot.adapter.onebot.extension.napcat.aop;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import com.starlwr.bot.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 发送 @全体成员 次数不足时替换为群待办
 */
@Slf4j
@Aspect
@StarBotComponent
@ConditionalOnProperty(name = "starbot.adapter.onebot.extension.napcat.enable-backup-at-all", havingValue = "true", matchIfMissing = true)
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

        if (!holder.isNapcat(message.getPlatform())) {
            return joinPoint.proceed();
        }

        if (!PushTargetType.GROUP.equals(message.getType())) {
            return joinPoint.proceed();
        }

        if (!message.getContent().contains("{at=all}")) {
            return joinPoint.proceed();
        }

        OneBotSender sender = holder.getNapcat(message.getPlatform());

        JSONObject groupAtAllRemainParams = new JSONObject();
        groupAtAllRemainParams.put("group_id", message.getNum());
        JSONObject groupAtAllRemainResult = http.getGroupAtAllRemain(sender, groupAtAllRemainParams);
        if (Boolean.TRUE.equals(groupAtAllRemainResult.getBoolean("can_at_all"))) {
            return joinPoint.proceed();
        }

        log.info("NapCat 推送平台 {} @全体成员 次数不足, 将自动替换为群待办发送", sender.getName());

        message.setContent(message.getContent().replace("{at=all}", ""));

        JSONObject todoParams = new JSONObject();
        todoParams.put("group_id", message.getNum());

        if (StringUtil.isNotBlank(message.getContent())) {
            message.addOnSuccessCallback(() -> {
                todoParams.put("message_id", message.getId());
                http.setGroupTodo(sender, todoParams);
            });

            return joinPoint.proceed();
        } else {
            Message next = message.getNext();
            Message previous = message.getPrevious();
            if (next != null) {
                next.addOnSuccessCallback(() -> {
                    todoParams.put("message_id", next.getId());
                    http.setGroupTodo(sender, todoParams);
                });
            } else if (previous != null) {
                todoParams.put("message_id", previous.getId());
                http.setGroupTodo(sender, todoParams);
            } else {
                log.error("NapCat 推送平台 {} 顺序号为 {} 的消息为空且前后无消息可用, 无法设置群待办", sender.getName(), message.getSequence());
            }

            return null;
        }
    }
}
