package com.starlwr.bot.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableRetry
@EnableCaching
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootApplication
public class StarBotCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(StarBotCoreApplication.class, args);
    }
}
