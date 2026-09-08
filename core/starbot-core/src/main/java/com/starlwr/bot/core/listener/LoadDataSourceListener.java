package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 应用就绪后加载数据源
 */
@Slf4j
@Component
public class LoadDataSourceListener {
    private final AbstractDataSource dataSource;

    @Autowired
    public LoadDataSourceListener(AbstractDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 加载数据源
     */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        dataSource.load();
    }
}
