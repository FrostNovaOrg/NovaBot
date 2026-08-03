# 贡献指南

## 开始之前

本仓库是 [StarBot](https://github.com/Starlwr/StarBot) 的整理版本。**与上游共通的问题请优先反馈给上游**，
这样所有使用者都能受益。仅当问题出在本仓库的改动上时，再在此处提交。

## 开发环境

需要 JDK 17 或更高版本与 Maven 3.9 或更高版本。

```bash
./build.sh
```

`build-tools/starbot-plugin-processor` 需要先单独安装，`build.sh` 已处理。若要单独构建某个模块：

```bash
mvn -Pinstall -f starbot-bilibili/pom.xml test
```

注意 `-Pinstall`：`starbot-core` 的默认 profile 会做 Spring Boot 重打包，产出的 jar 无法作为依赖使用。

## 代码风格

跟随现有代码：

- 注释与日志使用中文，公开方法写 Javadoc 并标注 `@param` 与 `@return`
- 使用 Lombok 的 `@Getter` / `@Setter` / `@Slf4j`，不手写样板代码
- 解析外部数据（哔哩哔哩接口响应、直播间消息）时**一律做空值防护**。
  这些结构随版本频繁变动，一次拆箱空指针就会中断整个直播间的消息处理
- 注释说明「为什么」而不是「做了什么」。代码本身已经说明做了什么

## 测试

新增逻辑请附带测试，尤其是：

- 安全相关代码（鉴权、限流、凭据处理）
- 协议解析（数据包编解码、消息解析）
- 边界与异常路径（字段缺失、长度非法、解压失败）

不要编写依赖真实网络请求的测试。需要接口返回值时用固定的 JSON 样本。

```bash
mvn -Pinstall test
```

## 提交

- 提交信息使用中文，说明改动的意图
- 一个提交只做一件事
- 涉及安全的改动请在 PR 中明确说明影响面

## 许可证

提交即表示同意你的贡献以 AGPL-3.0 发布。
