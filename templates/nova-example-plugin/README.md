<div align="center">

<img src="https://raw.githubusercontent.com/FrostNovaOrg/NovaBot/main/docs/assets/logo.svg" alt="NovaBot" height="56">

**<h2>NovaBot 示例插件</h2>**
</div>

## 目录

- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [开发说明](#开发说明)
- [依赖管理](#依赖管理)
- [依赖来源](#依赖来源)
- [构建与部署](#构建与部署)

## 快速开始

1. 克隆本示例项目作为模板创建新项目
2. 修改 `pom.xml` 中的项目信息, 该部分信息会作为插件元数据, 构建时生成到插件描述文件 `plugin.json` 中, 随插件 JAR 一同打包 (groupId, artifactId, version, name, description, url, developers 等)
3. 开发你的插件功能, 开发时可正常使用绝大多数 Spring 注解 (可参考 `NovaExampleStartEventListener.java`、`NovaExampleDanmuEventListener.java` 和 `NovaExampleMeowAdder.java` 示例)
4. 先按[依赖来源](#依赖来源)把本仓构件装进本地仓，再构建: `mvn clean package`
5. 将 `target` 中生成的 JAR 文件放入 NovaBot 的 `plugins` 目录

## 项目结构

NovaBot 插件使用标准的 Maven 项目结构：

```
nova-example-plugin/
├── pom.xml                                                # Maven 项目配置文件
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/                               # 插件代码包
│   │   │       ├── NovaExampleStartEventListener.java  # 示例功能: 启动事件监听器
│   │   │       ├── NovaExampleDanmuEventListener.java  # 示例功能: 弹幕监听器
│   │   │       ├── NovaExampleMeowAdder.java              # 示例功能: 推送消息修改器
│   │   │       └── NovaExamplePluginAutoConfiguration.java  # 自报类: 让 Spring Boot 装载本插件
│   │   ├── resources/
│   │   │   └── META-INF/spring/                           # 自报文件所在目录
│   └── test/                                              # 测试代码目录
└── target/                                                # 构建输出目录
    └── nova-example-plugin-1.0.0.jar                   # 构建后的插件 JAR 文件
```

## 开发说明

### 插件信息

每个 NovaBot 插件都需要在 `pom.xml` 文件中定义其元数据, 构建时它们会被生成到插件描述文件 `plugin.json` 中, 随插件 JAR 一同打包。元数据不再被输出到日志中, 装载本身也不单独打一行日志; 插件有没有被装上, 由插件自己的输出判读——本示例插件装上后, 日志里会出现 `NovaExampleStartEventListener` 打的这一行, 没有它就是没装上：

> 2025-11-22 01:58:39.062  INFO 64528 --- [                main] c.example.NovaExampleStartEventListener  : 主程序已启动完毕，示例插件已开始监听弹幕事件

```xml
<!-- 组名，通常使用反向域名，必填，与 artifactId 共同构成插件唯一标识 -->
<groupId>com.example</groupId>
<!-- 插件 ID，必填，与 groupId 共同构成插件唯一标识 -->
<artifactId>nova-example-plugin</artifactId>
<!-- 插件版本，必填，会写入插件描述文件 plugin.json -->
<version>1.0.0</version>
<!-- 插件名称，必填，会写入插件描述文件 plugin.json -->
<name>NovaExamplePlugin</name>
<!-- 插件描述，必填，会写入插件描述文件 plugin.json -->
<description>Example Plugin For NovaBot</description>
<!-- 插件主页 URL -->
<url>https://www.example.com</url>

<!-- 插件作者信息 -->
<developers>
    <developer>
        <!-- 插件作者 ID，必填，可与名称保持一致 -->
        <id>Author</id>
        <!-- 插件作者名称，必填，会写入插件描述文件 plugin.json -->
        <name>Author</name>
        <!-- 插件作者邮箱 -->
        <email>example@example.com</email>
    </developer>
</developers>
```

### 打包配置

> **注意**: 请不要随意修改 `pom.xml` 的 `<build>` 构建配置部分，否则可能导致插件无法被 NovaBot 加载

### 注意事项

>- 开发插件时, 可以使用绝大多数的 Spring 注解, 例如使用 `@Controller` 创建 API 接口, 使用 `@EventListener` 监听事件等
>- 需要注册至 Spring 容器或使用 Spring 机制 (例如事件机制) 的类, 需要在类上使用 `@NovaComponent` 注解, 该注解会将类注册为 NovaBot 组件, 并被 NovaBot 扫描并注册至 Spring 容器中  
>- 使用了 `@NovaComponent` 注解的类, 类名不可以与 NovaBot 本体或其他插件中的类名重复, 请命名时尽量避免过于简单或过于通用的命名
>- NovaBot 内部大量使用了 Spring 的事件机制, 插件可以通过创建事件监听器来处理这些事件, 常用事件类型请参考本仓库 [架构说明](https://github.com/FrostNovaOrg/NovaBot/blob/main/docs/architecture.md)

### 本仓库提供的扩展点

除了监听事件，插件还可以实现下列接口来接入核心的能力。实现类同样用 `@NovaComponent` 注册，
核心用 `ObjectProvider` 取——**没有实现时是空流而不是启动失败**，所以插件装不装都不影响核心启动。
本版起旧名 `@StarBotComponent`、`StarBotEventHandler`、`StarBotCommand` 已删除，须改用上表新名并改 import 重新编译。

| 接口 | 用途 |
|---|---|
| `NovaEventHandler` | 推送处理器，可被配置在 `datasource.json` 里 |
| `NovaCommand` | 群内聊天命令，自动出现在 `菜单` 里 |
| `HealthProbe` | 往总览页的健康自检里加一项 |
| `AlertChannel` | 新的告警投递通道 |
| `AccountLoginProvider` | 在配置界面里完成某个平台的登录 |
| `BotConnectionTester` | 推送平台的连通性测试 |
| `AtAllPermissionResolver` | 回答「机器人在这个会话能否 @全体成员」 |
| `LiveMetricCatalog` | 声明直播指标的中文名与**能否累加**，供运营统计使用 |

完整的事件类型清单、类加载规则与并发约定见本仓库的
[架构说明](https://github.com/FrostNovaOrg/NovaBot/blob/main/docs/architecture.md)——它比上游文档更贴近这里的实现。

## 依赖管理

NovaBot 插件使用 Maven 进行依赖管理, 插件可以依赖其他第三方库, 依赖清单会在构建时生成到插件描述文件 `dependency.json` 中, 随插件 JAR 一同打包

### 核心依赖

每个 NovaBot 插件必须直接依赖核心（artifactId `nova-core`）或通过依赖其他插件的方式间接依赖核心, 建议开发时使用最新版本：

```xml
<dependency>
    <groupId>org.frostnova.nova</groupId>
    <artifactId>nova-core</artifactId>
    <version>5.4.0</version>
</dependency>
```

### 添加第三方依赖

插件可以按需添加其他第三方依赖，例如：

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
    <optional>true</optional>
</dependency>
```

## 依赖来源

本仓未发布到远程仓。独立构建本模板前，先在根仓跑根构建脚本把 `nova-core`、`novacore` 与 `nova-plugin-processor` 装进本机本地仓，再到本目录执行 `mvn package`。

## 构建与部署

### 构建插件

使用 Maven 构建 NovaBot 插件：

```bash
mvn clean package
```

构建成功后，插件 JAR 文件将生成在 `target` 目录中，文件名格式为 `{artifactId}-{version}.jar`，例如 `nova-example-plugin-1.0.0.jar`。

### 部署插件

将生成的 JAR 文件复制到 NovaBot 的插件目录中：

1. 找到 NovaBot 的插件目录（`plugins` 文件夹）
2. 将插件 JAR 文件复制到该目录
3. 启动 NovaBot
