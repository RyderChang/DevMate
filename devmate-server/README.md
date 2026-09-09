# DevMate Server

DevMate 的后端基础工程，当前仅提供可独立启动的 Spring Boot bootstrap 能力和健康检查接口，尚未实现业务模块。

## 技术要求

- JDK 21
- Maven 3.9.x（仓库包含 Maven Wrapper，无需全局安装 Maven）

## 构建与测试

在仓库根目录执行：

```bash
./devmate-server/mvnw -f devmate-server/pom.xml clean package
./devmate-server/mvnw -f devmate-server/pom.xml clean test
```

也可以进入本目录后执行 `./mvnw clean package` 或 `./mvnw clean test`。

## 启动与健康检查

```bash
./devmate-server/mvnw -f devmate-server/pom.xml spring-boot:run
curl http://localhost:8080/api/health
```

预期响应：

```json
{"code":0,"message":"success","data":"DevMate server running"}
```

端口默认是 `8080`，可通过 `SERVER_PORT` 环境变量覆盖。

## 临时限制

工程已预留 Spring Security、MyBatis-Plus、Redis 和 MySQL Connector/J 依赖，但当前不连接或使用这些基础设施。为保证 bootstrap 在没有外部服务时仍可运行，`application.yml` 暂时排除了 `SecurityAutoConfiguration` 和 `DataSourceAutoConfiguration`。后续认证任务将移除 Security 排除并建立正式鉴权，数据库任务将移除 DataSource 排除并提供真实数据源配置。

当前不需要、也不应配置数据库、Redis 或 AI 密钥。用户、认证、数据库访问、Redis 和 AI 等业务能力均未实现。
