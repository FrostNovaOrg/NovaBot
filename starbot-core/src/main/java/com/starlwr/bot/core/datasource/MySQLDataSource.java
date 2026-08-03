package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.repository.PushUserRepository;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 数据源
 */
@Profile("mysql")
@Slf4j
@Service
@DataSource(name = "mysql")
public class MySQLDataSource extends AbstractDataSource {
    private final PushUserRepository pushUserRepository;

    @Autowired
    public MySQLDataSource(ApplicationEventPublisher eventPublisher, DataSourceServiceRegistry dataSourceServiceRegistry, StarBotEventHandlerService handlerService, PushUserRepository pushUserRepository) {
        super(eventPublisher, dataSourceServiceRegistry, handlerService);
        this.pushUserRepository = pushUserRepository;
    }

    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, handler, params, enabled 字段
     */
    @Override
    public void load() {
        log.info("已选用 MySQL 作为数据源");
        log.info("开始从 MySQL 中初始化推送配置");

        List<PushUser> dbUsers = pushUserRepository.findAll();

        List<PushUser> users = dbUsers.stream()
                .map(user -> {
                    PushUser copyUser = new PushUser();
                    BeanUtils.copyProperties(user, copyUser);
                    return copyUser;
                })
                .collect(Collectors.toList());

        add(users);

        log.info("成功从 MySQL 中导入了 {} 个主播", this.users.size());

        eventPublisher.publishEvent(new StarBotDataSourceLoadCompleteEvent(new ArrayList<>(this.users)));
    }
}
