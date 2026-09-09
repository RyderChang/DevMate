# DevMate Server

DevMate 的 Spring Boot 后端基础工程。当前实现统一响应、基础异常转换、健康检查、
OpenAPI 文档和 profile 配置边界，尚未实现任何业务模块。

## 前置要求

- JDK 21
- Maven 3.9.x（仓库包含 Maven Wrapper，无需全局安装 Maven）

## 构建、测试与启动

在仓库根目录执行：

```bash
./devmate-server/mvnw -f devmate-server/pom.xml test
./devmate-server/mvnw -f devmate-server/pom.xml clean package
./devmate-server/mvnw -f devmate-server/pom.xml spring-boot:run
```

也可以进入 `devmate-server/` 后运行对应的 `./mvnw` 命令。默认启动不依赖 MySQL、
Redis 或外部网络。

## Profiles 与环境变量

应用不会固定激活 profile。使用 `--spring.profiles.active=dev` 或环境变量
`SPRING_PROFILES_ACTIVE=dev` 启用本地开发配置；生产部署使用 `prod`。支持的配置入口为：

| 变量 | 用途 | dev 默认值 |
| --- | --- | --- |
| `SERVER_PORT` | HTTP 端口 | `8080` |
| `DB_URL` | MySQL JDBC URL | `jdbc:mysql://localhost:3306/devmate` |
| `DB_USERNAME` | 数据库用户名 | `devmate` |
| `DB_PASSWORD` | 数据库密码 | 空，仅作为本地配置入口 |

`prod` profile 不为数据库配置提供默认值，部署时必须由环境注入。数据源字段目前仅是后续
接入契约；`DataSourceAutoConfiguration` 仍被排除，因此任何 profile 都不会连接数据库。
开发环境只将 `com.devmate` 日志调为 `DEBUG`，默认与生产环境保持 `INFO`。

## API 与文档

启动后可访问：

- 健康接口：`GET http://localhost:8080/api/health`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- Swagger 重定向入口（依赖默认行为）：`http://localhost:8080/swagger-ui.html`

健康接口响应保持为：

```json
{"code":0,"message":"success","data":"DevMate server running"}
```

所有 API 响应使用 `code`、`message`、`data` 字段。成功应用码是 `0`；通用参数、业务和
内部错误码分别是 `1001`、`2001` 和 `5000`。HTTP 状态表达协议结果，应用错误码不等同于
HTTP 状态码。

SpringDoc 使用稳定版 `2.8.13`，与项目当前 Spring Boot `3.5.6` 的 Spring Boot 3 / Jakarta
Servlet 技术栈兼容。文档只描述当前真实存在的接口，未配置认证方案。

## 数据库迁移约定

未来 Flyway migration 统一放在 `src/main/resources/db/migration/`，命名为
`V<version>__<description>.sql`。本任务没有启用 Flyway、创建 migration SQL 或连接数据库。

## 临时限制

工程预留 Spring Security、MyBatis-Plus、Redis 和 MySQL Connector/J 依赖，但尚未实现这些
能力。`SecurityAutoConfiguration` 与 `DataSourceAutoConfiguration` 暂时在
`application.yml` 中排除，以保证基础工程在没有认证或数据库时可启动。后续认证任务将移除
Security 排除并建立正式鉴权；后续数据库任务将启用数据源和 Flyway。

当前没有用户、认证、数据库、Redis、AI 或其他业务功能，也不需要配置相关 Token、密钥或
外部服务。
