package org.frostnova.nova.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动最前段的一道门。
 * <p>
 * 相位取最小，并且不注入任何别的组件：谁要是被这道门依赖，谁就会抢在门前面启动。
 * 端口、定时任务、就绪都排在这道门之后。
 * <p>
 * 候命开关关着时立刻放行，不等锁在不在手。开着时，锁还在别人手里就在这里等；
 * 等的时候若已收到停机信号，就地结束，不去接手。
 */
@Slf4j
@Component
class StandbyGate implements SmartLifecycle, BeanPostProcessor {

    private volatile boolean running;

    private boolean announced;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!announced && SingleInstanceLock.standbyEnabled()) {
            announced = true;
            log.info("候命对象已建立");
        }
        return bean;
    }

    @Override
    public void start() {
        long reached = System.nanoTime();
        long waited = 0;
        if (SingleInstanceLock.standbyEnabled() && !SingleInstanceLock.holding()) {
            // 先置上「正在等」，停机钩子这之后只做标记；下面这一句之后才开始轮询。
            SingleInstanceLock.beginWait();
            log.info("正在等这个目录里正在运行的那一份退出，再接手。");
            waited = SingleInstanceLock.awaitLock();
        }
        SingleInstanceLock.markPassed();
        long before = reached - SingleInstanceLock.startedAt();
        log.info("候命门已过：门前用了 {} 秒，等锁等了 {} 秒",
                SingleInstanceLock.seconds(before), SingleInstanceLock.seconds(waited));
        running = true;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        long passed = SingleInstanceLock.passedAt();
        long delta = passed == 0 ? 0 : System.nanoTime() - passed;
        log.info("已就绪：过门到就绪用了 {} 秒", SingleInstanceLock.seconds(delta));
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
