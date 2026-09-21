# DevMate Server

DevMate 的 Java 21 / Spring Boot 3 后端。当前已接通 MySQL 8 数据源、HikariCP、Flyway、
MyBatis-Plus 基础能力，并提供统一响应、异常转换、健康检查、基于 JWT/RBAC 的用户认证，
以及按用户隔离的项目空间和项目对话 API。Flyway 负责创建认证、授权、项目、对话、消息与
AI 调用元数据表；前端已具备认证和项目 CRUD 页面。AI Gateway 当前提供默认关闭的 OpenAI
Responses 适配器，前端聊天、项目成员、GitHub 绑定、文件存储、Redis 业务和 RAG 尚未实现。

完整启动步骤见[本地开发指南](../docs/development/local-development.md)，
阶段验证状态见[验收记录](../docs/testing/foundation-acceptance.md)。

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
测试基类的 `DynamicPropertySource` 注入，不读取 dev/prod 数据库凭据；无需预装
MySQL，也不得用共享或生产数据库代替。Docker 不可用时测试会失败而不会静默跳过。

## Profiles 与数据库配置

应用不固定激活 profile。`dev` 用于本地开发，提供非敏感的本地 URL 和应用用户名默认值；
`prod` 用于生产，数据库 URL、用户名和密码都必须由部署环境注入。两个 profile 都要求显式
提供 `DB_PASSWORD`，仓库不保存密码。

| 环境变量                        | 用途                              | 公共/dev 默认值                            | prod 默认值 |
| ------------------------------- | --------------------------------- | ------------------------------------------ | ----------- |
| `SERVER_PORT`                   | HTTP 端口                         | `8080`                                     | `8080`      |
| `DB_URL`                        | MySQL JDBC URL                    | dev: `jdbc:mysql://localhost:3306/devmate` | 无，必填    |
| `DB_USERNAME`                   | 最小权限应用账户                  | dev: `devmate`                             | 无，必填    |
| `DB_PASSWORD`                   | 应用账户密码                      | 无，必填                                   | 无，必填    |
| `DB_POOL_MAX_SIZE`              | Hikari 最大连接数                 | `10`                                       | `20`        |
| `DB_POOL_MIN_IDLE`              | Hikari 最小空闲连接数             | `2`                                        | `2`         |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 获取连接超时（毫秒）              | `30000`                                    | `30000`     |
| `DB_POOL_IDLE_TIMEOUT_MS`       | 空闲连接超时（毫秒）              | `600000`                                   | `600000`    |
| `DB_POOL_MAX_LIFETIME_MS`       | 连接最大生命周期（毫秒）          | `1800000`                                  | `1800000`   |
| `JWT_SECRET`                    | JWT HMAC 签名密钥（至少 32 字节） | 无，必填                                   | 无，必填    |
| `JWT_EXPIRATION`                | JWT 有效期（ISO-8601 Duration）   | `PT2H`                                     | `PT2H`      |
| `AI_ENABLED`                    | 是否启用模型生成                  | `false`                                    | `false`     |
| `AI_PROVIDER`                   | AI 提供商（当前仅 `openai`）      | `openai`                                   | `openai`    |
| `OPENAI_BASE_URL`               | 服务端 OpenAI API 基础 URL        | `https://api.openai.com/v1`                | 同左        |
| `OPENAI_API_KEY`                | OpenAI API Key                    | 空；启用 AI 时必填                         | 空；必填    |
| `OPENAI_MODEL`                  | 经部署者确认的模型 ID             | 空；启用 AI 时必填                         | 空；必填    |
| `AI_CONNECT_TIMEOUT`            | 连接超时                          | `PT5S`                                     | `PT5S`      |
| `AI_READ_TIMEOUT`               | 读取超时，最大 `PT120S`           | `PT60S`                                    | `PT60S`     |
| `AI_MAX_OUTPUT_TOKENS`          | 单次最大输出 Token                | `1024`                                     | `1024`      |
| `AI_MAX_MESSAGE_CHARACTERS`     | 单条消息 Unicode 字符上限         | `8000`                                     | `8000`      |
| `AI_MAX_CONTEXT_MESSAGES`       | 历史消息数上限                    | `20`                                       | `20`        |
| `AI_MAX_CONTEXT_CHARACTERS`     | 历史消息字符上限                  | `24000`                                    | `24000`     |
| `AI_GENERATION_LEASE`           | 单对话生成租约                    | `PT2M`                                     | `PT2M`      |
| `AI_MAX_RESPONSE_BYTES`         | 可接受上游响应体上限              | `1048576`                                  | `1048576`   |

### 准备本地数据库并启动

使用 MySQL 8 管理账户创建 UTF-8 数据库和独立应用账户。以下标识符是本地示例；请在 MySQL
客户端中交互式设置强密码，不要把密码写进脚本或 Git：

```sql
CREATE DATABASE devmate CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'devmate'@'localhost' IDENTIFIED BY 'replace-interactively';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
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

项目接口均需要有效 Bearer Token 和 `user` authority：

- `POST /projects`：创建当前用户的项目；
- `GET /projects?page=1&pageSize=20`：分页查询当前用户的未删除项目；
- `GET /projects/{projectId}`：查询当前用户的项目；
- `PUT /projects/{projectId}`：完整更新项目名称和描述；
- `DELETE /projects/{projectId}`：软删除项目。

列表页码范围为 `1..10000`，页大小范围为 `1..100`，默认页大小为 `20`，并固定按
`update_time DESC, id DESC` 排序。详情、更新和删除始终同时校验项目 ID、当前用户 ID 和
未删除状态；不存在、已删除或属于其他用户的项目统一返回 `404 PROJECT_NOT_FOUND`。管理员
默认不能绕过所有权检查。删除会同时记录 UTC 删除时间，本任务不提供恢复接口。

健康接口仍返回：

```json
{ "code": 200, "message": "success", "data": { "status": "UP" } }
```

除注册、登录和健康检查外，Spring Security 默认要求 JWT 认证，OpenAPI 与 Swagger UI 也不在
白名单中。JWT 密钥只从 `JWT_SECRET` 注入，不提供仓库内明文默认值。当前不应配置 Redis、
GitHub 或其他未实现服务的凭据。AI 仅在部署者显式设置 `AI_ENABLED=true` 并注入 OpenAI Key
和模型时启用；默认关闭状态不读取或要求这些值。

服务端为每个 HTTP 请求生成 UUID 格式的 traceId，通过 `X-Trace-Id` 响应头返回，并用于关联
该请求产生的 AI 调用审计日志。客户端不能指定或覆盖服务端 traceId。

项目对话接口均需要有效 Bearer Token 和 `user` authority：

- `POST /projects/{projectId}/conversations`：创建对话；
- `GET /projects/{projectId}/conversations`：固定排序分页；
- `GET /projects/{projectId}/conversations/{conversationId}`：查询对话；
- `GET /projects/{projectId}/conversations/{conversationId}/messages`：按序分页查询消息；
- `POST /projects/{projectId}/conversations/{conversationId}/messages`：同步生成非流式回复。

生成请求必须提供 UUID `clientRequestId`。同一 ID 不会重复调用模型；同一对话同一时间只允许一个
生成请求。模型调用在数据库事务之外执行，成功和失败都会记录安全的调用状态。请求显式使用
`store=false`、`stream=false`，不启用工具或提供商托管会话。AI 默认关闭时，读取和管理对话仍可用，
生成接口返回 `503 AI_SERVICE_DISABLED`。

完整测试需要 Docker，以便 Testcontainers 在隔离的 MySQL 8.4.6 空库上执行 V1 至 V5 migration、
Mapper、认证授权和项目 API 集成测试。合并前应在 Docker 可用的环境执行本 README 的 Maven
Wrapper 命令。
