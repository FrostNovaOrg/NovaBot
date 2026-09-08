package com.starlwr.bot.adapter.onebot.extension.napcat.aop;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 顺着体检问版本那一趟，认出哪几个推送平台的对面是 NapCat
 *
 * <h2>为什么搭在版本接口上</h2>
 * 认出对面是谁这件事，本身没有触发时机可言。而适配器本来就会去问一次版本，
 * 应答里的 {@code app_name} 正好写着对面是什么实现——于是搭一趟顺风车，
 * 既不必自己安排轮询，也不必要求使用者手工声明「这台是 NapCat」。
 *
 * <h2>只看不改</h2>
 * 环绕通知在这里<b>不加工返回值</b>：版本接口是适配器体检用的，
 * 一旦本扩展改动它的应答，体检那一侧的判断就建在了本扩展的实现细节上。
 */
@Slf4j
@Aspect
@NovaComponent
public class NapCatServiceDiscoveryAspect {
    /**
     * 对面自称的实现名里带这一段就算 NapCat
     * <p>
     * 按<b>子串</b>认而不是整串相等：各家分支会把自己的名字拼在前后
     * （如 {@code NapCat.Onebot}）。代价是大小写不同的写法认不出来，
     * 但那要等真出现这样一个实现名再说，现在就放宽等于放进一堆猜测
     */
    private static final String NAPCAT = "NapCat";

    private final NapcatServiceHolder holder;

    @Autowired
    public NapCatServiceDiscoveryAspect(NapcatServiceHolder holder) {
        this.holder = holder;
    }

    @Pointcut("execution(* com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter.getVersionInfo(..))")
    public void getVersionInfoMethod() {}

    @Around("getVersionInfoMethod()")
    public Object aroundSendMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        JSONObject versionInfo = (JSONObject) joinPoint.proceed();

        if (isNapcat(versionInfo)) {
            OneBotSender sender = (OneBotSender) joinPoint.getArgs()[0];
            log.info("发现 NapCat 服务: {}", sender.getName());
            holder.registerNapcat(sender);
        }

        return versionInfo;
    }

    /**
     * 这份版本应答是不是 NapCat 报出来的
     * <p>
     * 认不出就当作不是：这一层只负责「多认出一个能用扩展功能的平台」，
     * 认漏了只是少一项锦上添花的功能，认错了则会拿 NapCat 独有的接口去打别家实现
     */
    private boolean isNapcat(JSONObject versionInfo) {
        return versionInfo != null
                && versionInfo.containsKey("app_name")
                && versionInfo.getString("app_name").contains(NAPCAT);
    }
}
