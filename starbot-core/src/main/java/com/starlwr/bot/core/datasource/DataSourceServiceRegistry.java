package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.service.DataSourceServiceConfig;
import com.starlwr.bot.core.service.DataSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源服务注册表
 */
@Component
public class DataSourceServiceRegistry {
    private final Map<String, DataSourceService> serviceMap = new HashMap<>();

    @Autowired
    public DataSourceServiceRegistry(List<DataSourceService> services) {
        for (DataSourceService service : services) {
            DataSourceServiceConfig annotation = service.getClass().getAnnotation(DataSourceServiceConfig.class);
            if (annotation != null) {
                serviceMap.put(annotation.name(), service);
            }
        }
    }

    /**
     * 获取指定直播平台的数据源服务
     * @param platform 直播平台名称
     * @return 数据源服务
     */
    public Optional<DataSourceService> getDataSourceService(String platform) {
        return Optional.ofNullable(serviceMap.get(platform));
    }
}
