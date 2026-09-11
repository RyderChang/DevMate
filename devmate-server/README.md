# DevMate Server

DevMate 的 Java 21 / Spring Boot 3 后端。当前已接通 MySQL 8 数据源、HikariCP、Flyway、
MyBatis-Plus 基础能力，并提供统一响应、异常转换、健康检查及基于 JWT 的用户认证。Flyway
负责创建 `users` 表；尚未实现用户中心、Redis 或 AI 功能。

## 前置要求

- JDK 21；
- Docker（运行基于 Testcontainers 的 MySQL 8 集成测试）；
- 本地启动时可访问的 MySQL 8 实例；
- Maven 3.9.x，或直接使用仓库内的 Maven Wrapper。

## 构建与测试

在仓库根目录执行：

```bash
./devmate-server/mvnw -f devmate-server/pom.xml test
./devmate-server/mvnw -f devmate-server/pom.xml clean package
./devmate-server/mvnw -f devmate-server/pom.xml dependency:tree
```

数据库集成测试固定使用 `mysql:8.4.6`，创建临时空库并由 Flyway 迁移。测试连接信息由
Spring Boot Testcontainers service connection 注入，不读取 dev/prod 数据库凭据；无需预装
MySQL，也不得用共享或生产数据库代替。Docker 不可用时测试会失败而不会静默跳过。

## Profiles 与数据库配置

应用不固定激活 profile。`dev` 用于本地开发，提供非敏感的本地 URL 和应用用户名默认值；
`prod` 用于生产，数据库 URL、用户名和密码都必须由部署环境注入。两个 profile 都要求显式
提供 `DB_PASSWORD`，仓库不保存密码。

| 环境变量 | 用途 | 公共/dev 默认值 | prod 默认值 |
| --- | --- | --- | --- |
| `SERVER_PORT` | HTTP 端口 | `8080` | `8080` |
| `DB_URL` | MySQL JDBC URL | dev: `jdbc:mysql://localhost:3306/devmate` | 无，必填 |
| `DB_USERNAME` | 最小权限应用账户 | dev: `devmate` | 无，必填 |
| `DB_PASSWORD` | 应用账户密码 | 无，必填 | 无，必填 |
| `DB_POOL_MAX_SIZE` | Hikari 最大连接数 | `10` | `20` |
| `DB_POOL_MIN_IDLE` | Hikari 最小空闲连接数 | `2` | `2` |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 获取连接超时（毫秒） | `30000` | `30000` |
| `DB_POOL_IDLE_TIMEOUT_MS` | 空闲连接超时（毫秒） | `600000` | `600000` |
| `DB_POOL_MAX_LIFETIME_MS` | 连接最大生命周期（毫秒） | `1800000` | `1800000` |
| `JWT_SECRET` | JWT HMAC 签名密钥（至少 32 字节） | 无，必填 | 无，必填 |
| `JWT_EXPIRATION` | JWT 有效期（ISO-8601 Duration） | `PT2H` | `PT2H` |

### 准备本地数据库并启动

使用 MySQL 8 管理账户创建 UTF-8 数据库和独立应用账户。以下标识符是本地示例；请在 MySQL
客户端中交互式设置强密码，不要把密码写进脚本或 Git：

```sql
CREATE DATABASE devmate CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'devmate'@'localhost' IDENTIFIED BY 'replace-interactively';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP
    ON devmate.* TO 'devmate'@'localhost';
```

然后仅在当前 shell 注入密码并启动 dev profile：

```bash
export DB_PASSWORD='<local-password>'
export JWT_SECRET='<random-secret-at-least-32-bytes>'
SPRING_PROFILES_ACTIVE=dev ./devmate-server/mvnw \
  -f devmate-server/pom.xml spring-boot:run
```

如 MySQL 不在默认地址，同时设置 `DB_URL` 和 `DB_USERNAME`。JDBC 会话通过 Connector/J 与
应用的 UTC 时间基线协作；MySQL 服务端也应配置为 UTC。不要使用 `root` 运行应用。

## Flyway 与 MyBatis-Plus

Flyway 在启动时使用应用的同一数据源，按 `classpath:db/migration` 中的版本顺序迁移并验证
checksum。迁移或校验失败会阻止启动。`clean`、自动 baseline 和乱序执行均被禁用，也没有
`schema.sql`、`data.sql` 或 ORM 自动建表。

迁移命名为 `V<version>__<description>.sql`。已合并或在共享环境执行的文件不可修改、删除或
重排；修正必须新增更高版本，并在 PR 中说明前向修复和回滚方案。`V1__baseline.sql` 仅执行
无副作用探测，不创建业务表。详细规则见 `src/main/resources/db/migration/README.md`。

MyBatis-Plus 使用同一数据源，开启 snake_case 到 camelCase 映射，默认不输出 SQL。用户主键
由 MySQL 自增生成；`SELECT 1` 探针 Mapper 只存在于测试源码。

## API 与当前限制

启动后可访问：

- `GET http://localhost:8080/health`（匿名）；
- `POST http://localhost:8080/auth/register`（匿名）；
- `POST http://localhost:8080/auth/login`（匿名）；
- `GET http://localhost:8080/auth/me`（需要 `Authorization: Bearer <token>`）。

健康接口仍返回：

```json
{"code":200,"message":"success","data":{"status":"UP"}}
```

除注册、登录和健康检查外，Spring Security 默认要求 JWT 认证，OpenAPI 与 Swagger UI 也不在
白名单中。JWT 密钥只从 `JWT_SECRET` 注入，不提供仓库内明文默认值。当前不应配置 Redis、AI、
GitHub 或其他外部服务凭据。

DEV-005 执行环境访问 Maven Central 时仍收到 HTTP 403，因此 DEV-004 遗留的依赖解析债务尚未
在该环境消除，测试、打包和依赖树也无法在该环境完成。合并前应在可访问 Maven Central 且
Docker 可用的本地或 CI 环境依次执行本 README 的三条 Maven Wrapper 命令。
