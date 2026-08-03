package com.starlwr.bot.core.plugin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * StarBot 插件加载器
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StarBotPluginLoader implements EnvironmentAware, ResourceLoaderAware, BeanDefinitionRegistryPostProcessor {
    private Environment environment;

    private ResourceLoader resourceLoader;

    private Object evaluator;

    private Method shouldSkipMethod;

    private ClassLoader pluginClassLoader;

    private final List<StarBotPlugin> plugins = new ArrayList<>();

    @Getter
    private final Map<StarBotPluginMeta, List<Dependency>> needDownloadDependencies = new HashMap<>();

    private final Map<String, Class<?>> componentClasses = new HashMap<>();

    private final Pattern jarPattern = Pattern.compile("^(.+)-([\\d.]+[\\w.-]*)\\.jar$");

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(@NonNull ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
        List<File> pluginJars = scanJarFiles("plugins");

        List<File> libs = new ArrayList<>();
        libs.addAll(scanJarFiles("lib"));
        libs.addAll(scanJarFiles("plugins-lib"));

        Set<String> existsDependencies = new HashSet<>();
        existsDependencies.add("starbot-core");
        for (File lib : libs) {
            Matcher matcher = jarPattern.matcher(lib.getName());
            if (matcher.matches()) {
                existsDependencies.add(matcher.group(1));
            } else {
                log.error("无法解析的依赖: {}", lib.getName());
            }
        }

        log.info("开始注册 StarBot 插件");

        URL[] jarUrls = pluginJars.stream().map(jar -> {
            try {
                return jar.toURI().toURL();
            } catch (Exception e) {
                log.error("插件 {} 路径转换异常", jar.getName(), e);
                return null;
            }
        }).filter(Objects::nonNull).toArray(URL[]::new);
        pluginClassLoader = new URLClassLoader(jarUrls, getClass().getClassLoader());

        for (File jar : pluginJars) {
            try {
                try (JarFile jarFile = new JarFile(jar)) {
                    List<Dependency> missingDependencies = new ArrayList<>();

                    StarBotPlugin plugin = new StarBotPlugin();
                    plugin.setJarFile(jar);

                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();

                        if ("plugin.json".equals(entry.getName())) {
                            try (InputStream input = jarFile.getInputStream(entry)) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                plugin.setMeta(JSON.parseObject(json, StarBotPluginMeta.class));
                            }
                        } else if ("dependency.json".equals(entry.getName())) {
                            try (InputStream input = jarFile.getInputStream(entry)) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                JSONObject dependencyInfo = JSON.parseObject(json);
                                plugin.setDependencies(dependencyInfo.getList("dependencies", Dependency.class));
                                plugin.setPluginDependencies(dependencyInfo.getList("plugins", Dependency.class));
                                missingDependencies.addAll(plugin.getDependencies().stream().filter(dependency -> !existsDependencies.contains(dependency.getArtifactId())).toList());
                            }
                        } else if (entry.getName().endsWith(".class")) {
                            if (!entry.getName().contains("META-INF") && !entry.getName().contains("module-info") && !entry.getName().contains("package-info") && !entry.getName().contains("$")) {
                                String className = entry.getName().replace('/', '.').replace(".class", "");
                                plugin.getComponentClassNames().add(className);
                            }
                        }
                    }

                    if (plugin.getMeta() != null) {
                        needDownloadDependencies.put(plugin.getMeta(), missingDependencies);
                        plugins.add(plugin);

                        StarBotPluginMeta meta = plugin.getMeta();
                        log.info("已注册插件 {} v{} --{}: {}", meta.getName(), meta.getVersion(), meta.getAuthor(), meta.getDescription());
                    }
                }
            } catch (Exception e) {
                log.error("插件 {} 注册异常", jar.getName(), e);
            }
        }

        sortPlugins();

        if (needDownloadDependencies.values().stream().allMatch(List::isEmpty)) {
            log.info("开始加载 StarBot 插件");

            for (String beanDefinitionName : registry.getBeanDefinitionNames()) {
                componentClasses.put(beanDefinitionName, getClassByBeanDefinition(registry.getBeanDefinition(beanDefinitionName)));
            }

            for (StarBotPlugin plugin : plugins) {
                for (String className : plugin.getComponentClassNames()) {
                    try {
                        Class<?> clazz = Class.forName(className, false, pluginClassLoader);

                        if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()) && clazz.isAnnotationPresent(StarBotComponent.class)) {
                            log.debug("加载 StarBot 组件: {} - {}", plugin.getJarFile().getName(), clazz.getName());
                            plugin.getComponentClasses().add(clazz);
                        }
                    } catch (Exception e) {
                        log.error("加载 StarBot 组件 {} - {} 异常", plugin.getJarFile().getName(), className, e);
                    }
                }

                if (!plugin.getComponentClasses().isEmpty()) {
                    for (Class<?> clazz : plugin.getComponentClasses()) {
                        AnnotatedGenericBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(clazz);
                        String beanDefinitionName = new AnnotationBeanNameGenerator().generateBeanName(beanDefinition, registry);
                        AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDefinition);

                        AnnotatedTypeMetadata metadata = beanDefinition.getMetadata();

                        if (metadata.isAnnotated(RemoveBeanDefinition.class.getName())) {
                            Map<String, Object> attributes = metadata.getAnnotationAttributes(RemoveBeanDefinition.class.getName());
                            if (attributes != null) {
                                String[] names = (String[]) attributes.get("name");
                                for (String name : names) {
                                    if (registry.containsBeanDefinition(name)) {
                                        registry.removeBeanDefinition(name);
                                        log.debug("StarBot 组件: {} - {} 根据名称移除类定义: {}", plugin.getJarFile().getName(), clazz.getName(), name);
                                    }
                                }
                                Class<?>[] types = (Class<?>[]) attributes.get("type");
                                for (Class<?> type : types) {
                                    for (String name: new HashSet<>(componentClasses.keySet())) {
                                        if (type.isAssignableFrom(componentClasses.get(name)) && registry.containsBeanDefinition(name)) {
                                            registry.removeBeanDefinition(name);
                                            componentClasses.remove(name);
                                            log.debug("StarBot 组件: {} - {} 根据类型 {} 移除类定义: {}", plugin.getJarFile().getName(), clazz.getName(), type.getName(), name);
                                        }
                                    }
                                }
                            }
                        }

                        componentClasses.put(beanDefinitionName, clazz);

                        if (metadata.isAnnotated(Profile.class.getName())) {
                            Map<String, Object> attributes = metadata.getAnnotationAttributes(Profile.class.getName());
                            if (attributes != null) {
                                String[] profiles = (String[]) attributes.get("value");
                                if (!environment.acceptsProfiles(Profiles.of(profiles))) {
                                    log.debug("StarBot 组件: {} - {} 不匹配 @Profile 条件: {}, 不注册至 Spring 容器中", plugin.getJarFile().getName(), clazz.getName(), Arrays.toString(profiles));
                                    continue;
                                }
                            }
                        }

                        if (shouldSkip(registry, beanDefinition)) {
                            log.debug("StarBot 组件: {} - {} 不满足 @Condition 条件, 不注册至 Spring 容器中", plugin.getJarFile().getName(), clazz.getName());
                            continue;
                        }

                        if (metadata.isAnnotated(Scope.class.getName())) {
                            Map<String, Object> attributes = metadata.getAnnotationAttributes(Scope.class.getName());
                            if (attributes != null) {
                                String scope = (String) attributes.get("value");
                                if ("prototype".equals(scope)) {
                                    beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
                                }
                            }
                        }

                        BeanDefinitionReaderUtils.registerBeanDefinition(new BeanDefinitionHolder(beanDefinition, beanDefinitionName), registry);
                    }

                    StarBotPluginMeta meta = plugin.getMeta();
                    log.info("已加载插件 {} v{} --{}: {}", meta.getName(), meta.getVersion(), meta.getAuthor(), meta.getDescription());
                } else {
                    log.warn("插件 {} v{} --{}: {} 没有可加载的组件, 将跳过加载", plugin.getMeta().getName(), plugin.getMeta().getVersion(), plugin.getMeta().getAuthor(), plugin.getMeta().getDescription());
                }
            }
        } else {
            return;
        }

        AnnotationConfigUtils.registerAnnotationConfigProcessors(registry);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(registry);

        plugins.removeIf(plugin -> plugin.getComponentClasses().isEmpty());
        if (plugins.isEmpty()) {
            log.info("没有需要加载的 StarBot 插件");
        } else {
            log.info("成功加载了 {} 个 StarBot 插件", plugins.size());
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) {
        StarBotClassLoader starBotClassLoader = new StarBotClassLoader(pluginClassLoader, beanFactory.getBeanClassLoader());
        beanFactory.setBeanClassLoader(starBotClassLoader);
        Thread.currentThread().setContextClassLoader(starBotClassLoader);
        BeanDefinitionRegistryPostProcessor.super.postProcessBeanFactory(beanFactory);
    }

    /**
     * 扫描目录中的依赖列表
     * @param path 目录
     * @return 依赖列表
     */
    private List<File> scanJarFiles(String path) {
        try {
            File pluginDir = new File(path);
            if (pluginDir.exists() && pluginDir.isDirectory()) {
                File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    return Arrays.asList(jars);
                }
            }
        } catch (Exception e) {
            log.error("扫描依赖包列表异常", e);
        }

        return new ArrayList<>();
    }

    /**
     * 插件列表拓扑排序
     */
    public void sortPlugins() {
        // 移除重复插件
        Map<String, StarBotPlugin> latestMap = new HashMap<>();

        for (StarBotPlugin plugin : plugins) {
            String pluginId = plugin.getId();
            if (latestMap.containsKey(pluginId)) {
                log.error("检测到存在重复插件: {}, 仅保留较新版本", pluginId);
            }

            latestMap.put(pluginId, plugin);
        }

        plugins.clear();
        plugins.addAll(latestMap.values());

        if (plugins.isEmpty()) {
            return;
        }

        // 移除缺失前置依赖插件的插件
        boolean changed;
        do {
            changed = false;
            Set<String> existsPluginIds = plugins.stream().map(StarBotPlugin::getId).collect(Collectors.toSet());

            Iterator<StarBotPlugin> it = plugins.iterator();
            while (it.hasNext()) {
                StarBotPlugin plugin = it.next();

                for (Dependency dependency: plugin.getPluginDependencies()) {
                    if (!existsPluginIds.contains(dependency.getId())) {
                        log.error("未安装插件 {} 的前置依赖插件 {}, 无法加载该插件", plugin.getId(), dependency.getId());
                        it.remove();
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);

        if (plugins.isEmpty()) {
            return;
        }

        // 构建依赖图
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();

        for (StarBotPlugin plugin: plugins) {
            String pluginId = plugin.getId();
            graph.put(pluginId, new ArrayList<>());
            indegree.put(pluginId, 0);
        }

        for (StarBotPlugin plugin : plugins) {
            String pluginId = plugin.getId();

            for (Dependency dependency : plugin.getPluginDependencies()) {
                graph.get(dependency.getId()).add(pluginId);
                indegree.put(pluginId, indegree.get(pluginId) + 1);
            }
        }

        // 拓扑排序
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<String> sortedIds = new ArrayList<>();

        while (!queue.isEmpty()) {
            String id = queue.poll();
            sortedIds.add(id);

            for (String next : graph.get(id)) {
                indegree.put(next, indegree.get(next) - 1);
                if (indegree.get(next) == 0) {
                    queue.offer(next);
                }
            }
        }

        // 循环依赖检测
        if (sortedIds.size() != plugins.size()) {
            List<List<String>> cycles = findAllCycles(graph);

            log.error("插件间存在循环依赖, 无法加载以下插件:\n{}", cycles.stream().map(this::getCycleGraph).collect(Collectors.joining("\n")));

            Set<String> nodesInCycles = cycles.stream().flatMap(Collection::stream).collect(Collectors.toSet());
            plugins.removeIf(plugin -> nodesInCycles.contains(plugin.getId()));

            sortPlugins();
            return;
        }

        // 排序原插件列表
        Map<String, StarBotPlugin> finalMap = plugins.stream().collect(Collectors.toMap(StarBotPlugin::getId, plugin -> plugin));

        plugins.clear();
        for (String name : sortedIds) {
            StarBotPlugin plugin = finalMap.get(name);
            if (plugin != null) {
                plugins.add(plugin);
            }
        }
    }

    /**
     * 查找所有循环依赖链
     * @param graph 依赖图
     * @return 循环依赖链列表
     */
    private List<List<String>> findAllCycles(Map<String, List<String>> graph) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();

        for (String node : graph.keySet()) {
            dfsCycle(node, graph, visited, stack, new ArrayList<>(), cycles);
        }

        return cycles;
    }

    /**
     * DFS 查找循环依赖
     * @param node 当前节点
     * @param graph 依赖图
     * @param visited 已访问节点
     * @param stack 当前递归栈
     * @param path 当前路径
     * @param cycles 循环依赖列表
     */
    private void dfsCycle(String node, Map<String, List<String>> graph, Set<String> visited, Set<String> stack, List<String> path, List<List<String>> cycles) {
        if (stack.contains(node)) {
            int idx = path.indexOf(node);
            if (idx != -1) {
                List<String> cycle = path.subList(idx, path.size());
                cycles.add(new ArrayList<>(cycle));
            }
            return;
        }

        if (visited.contains(node)) {
            return;
        }

        visited.add(node);
        stack.add(node);
        path.add(node);

        for (String next : graph.get(node)) {
            dfsCycle(next, graph, visited, stack, path, cycles);
        }

        stack.remove(node);
        path.remove(path.size() - 1);
    }

    /**
     * 生成循环依赖图
     * @param cycle 循环依赖节点列表
     * @return 循环依赖图
     */
    private String getCycleGraph(List<String> cycle) {
        StringBuilder message = new StringBuilder();
        boolean singleNode = cycle.size() == 1;

        for(int i = 0; i < cycle.size(); ++i) {
            String node = cycle.get(i);
            if (i == 0) {
                message.append(String.format(singleNode ? "┌──->──┐%n" : "┌─────┐%n"));
            } else {
                message.append(String.format("%s     ↓%n", "↑"));
            }
            message.append(String.format("%s  %s%n", "|", node));
        }

        message.append(String.format(singleNode ? "└──<-──┘%n" : "└─────┘%n"));
        return message.toString();
    }

    /**
     * 判断类是否满足 @Condition 条件
     * @param registry BeanDefinitionRegistry 实例
     * @param beanDefinition 类定义
     * @return 类是否满足 @Condition 条件
     */
    private boolean shouldSkip(BeanDefinitionRegistry registry, AnnotatedBeanDefinition beanDefinition) {
        if (evaluator == null || shouldSkipMethod == null) {
            try {
                Class<?> clazz = Class.forName("org.springframework.context.annotation.ConditionEvaluator", false, getClass().getClassLoader());

                Constructor<?> constructor = clazz.getDeclaredConstructor(BeanDefinitionRegistry.class, Environment.class, ResourceLoader.class);
                constructor.setAccessible(true);

                evaluator = constructor.newInstance(
                        registry,
                        environment,
                        resourceLoader
                );

                shouldSkipMethod = clazz.getDeclaredMethod("shouldSkip", AnnotatedTypeMetadata.class, ConfigurationCondition.ConfigurationPhase.class);
                shouldSkipMethod.setAccessible(true);
            } catch (Exception e) {
                throw new RuntimeException("调用 ConditionEvaluator.shouldSkip 失败", e);
            }
        }

        try {
            return (boolean) shouldSkipMethod.invoke(evaluator, beanDefinition.getMetadata(), ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        } catch (Exception e) {
            throw new RuntimeException("调用 ConditionEvaluator.shouldSkip 失败", e);
        }
    }

    /**
     * 根据 BeanDefinition 获取 Class
     * @param beanDefinition 类定义
     * @return Class 实例
     */
    private Class<?> getClassByBeanDefinition(BeanDefinition beanDefinition) {
        Class<?> clazz = beanDefinition.getResolvableType().resolve();
        if (clazz != null) {
            return clazz;
        }

        if (beanDefinition.getBeanClassName() != null) {
            try {
                clazz = Class.forName(beanDefinition.getBeanClassName(), false, getClass().getClassLoader());
            } catch (Exception ignored) {
            }

            if (clazz != null) {
                return clazz;
            }
        }

        Object source = beanDefinition.getSource();
        if (source instanceof StandardMethodMetadata metadata) {
            return metadata.getIntrospectedMethod().getReturnType();
        } else if (source instanceof MethodMetadata metadata) {
            try {
                return Class.forName(metadata.getReturnTypeName(), false, getClass().getClassLoader());
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
