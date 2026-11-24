package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@NovaComponent
final class DaemonQqBotGatewayScheduler implements QqBotGatewayScheduler {
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "qqbot-gateway");
        thread.setDaemon(true);
        return thread;
    });

    DaemonQqBotGatewayScheduler() {
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public long nowMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    @Override
    public Future<?> schedule(Runnable task, long delayMillis) {
        return executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}
