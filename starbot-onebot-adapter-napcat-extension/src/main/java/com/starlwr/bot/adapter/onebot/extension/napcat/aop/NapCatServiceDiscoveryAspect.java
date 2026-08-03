package com.starlwr.bot.adapter.onebot.extension.napcat.aop;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * NapCat 服务发现
 */
@Slf4j
@Aspect
@StarBotComponent
public class NapCatServiceDiscoveryAspect {
    private final NapcatServiceHolder holder;

    @Autowired
    public NapCatServiceDiscoveryAspect(NapcatServiceHolder holder) {
        this.holder = holder;
    }

    @Pointcut("execution(* com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter.getVersionInfo(..))")
    public void getVersionInfoMethod() {}

    @Around("getVersionInfoMethod()")
    public Object aroundSendMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        JSONObject result = (JSONObject) joinPoint.proceed();

        if (result == null) {
            return null;
        }

        if (!result.containsKey("app_name")) {
            return result;
        }

        if (!result.getString("app_name").contains("NapCat")) {
            return result;
        }

        OneBotSender sender = (OneBotSender) joinPoint.getArgs()[0];
        log.info("发现 NapCat 服务: {}", sender.getName());
        holder.registerNapcat(sender);

        return result;
    }
}
