package com.starlwr.bot.core.repository;

import com.starlwr.bot.core.model.PushMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 推送消息 JPA 接口
 */
@Profile("mysql")
@Repository
public interface PushMessageRepository extends JpaRepository<PushMessage, Long> {
}
