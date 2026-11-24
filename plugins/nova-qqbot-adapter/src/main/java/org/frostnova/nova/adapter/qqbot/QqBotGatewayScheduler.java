package org.frostnova.nova.adapter.qqbot;

import java.util.concurrent.Future;

interface QqBotGatewayScheduler {
    long nowMillis();

    Future<?> schedule(Runnable task, long delayMillis);

    void shutdown();
}
