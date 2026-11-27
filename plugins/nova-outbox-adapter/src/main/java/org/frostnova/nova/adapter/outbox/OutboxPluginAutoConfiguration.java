package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationGroupContributor;
import org.frostnova.nova.core.config.ui.ConfigurationGroups;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@EnableConfigurationProperties(OutboxSettings.class)
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = OutboxPluginAutoConfiguration.class))
public class OutboxPluginAutoConfiguration implements ConfigurationGroupContributor {
    public static final String PLATFORM = "outbox";

    @Override
    public Map<String, ConfigurationGroups.Group> prefixes() {
        return Map.of(OutboxSettings.PREFIX, ConfigurationGroups.PUSH);
    }

    @Bean
    OutboxDatabase outboxDatabase(NovaCoreProperties properties) {
        Path liveData = Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath();
        return new OutboxDatabase(liveData.resolveSibling("outbox"));
    }

    /** 图片网址用核心那一件判（设置页里放行的网段同样生效）；核心那一件缺席时退到不放行任何内网网段 */
    @Bean
    OutboxService outboxService(OutboxDatabase database, OutboxSettings settings, TimelineWriter timeline,
                                ObjectProvider<MessageImageAddressPolicy> imageAddresses) {
        return new OutboxService(database, settings, Clock.systemDefaultZone(), timeline,
                imageAddresses.getIfAvailable(MessageImageAddressPolicy::new));
    }
}
