# DevMate Server

DevMate 的 Java 21 / Spring Boot 3 后端。当前已接通 MySQL 8 数据源、HikariCP、Flyway、
MyBatis-Plus 基础能力，并提供统一响应、异常转换、健康检查、基于 JWT/RBAC 的用户认证，
以及按用户隔离的项目空间、项目对话和知识文档元数据 API。Flyway 负责创建认证、授权、项目、对话、消息与
AI 调用元数据表；前端已具备认证、项目 CRUD 与同步对话页面。AI Gateway 当前提供默认关闭的 OpenAI
Responses 与 DeepSeek 官方 Chat Completions 适配器；knowledge 已接入私有 MinIO 原文件存储。
项目成员、GitHub 绑定、Redis 业务和 RAG 尚未实现。

完整启动步骤见[本地开发指南](../docs/development/local-development.md)，
阶段验证状态见[第一阶段验收记录](../docs/testing/foundation-acceptance.md)和
[第二阶段对话验收记录](../docs/testing/ai-conversation-acceptance.md)。本地模型 Stub 不需要真实模型账户。

## 前置要求

- JDK 21；
- Docker（运行基于 Testcontainers 的 MySQL 8 集成测试）；
- 本地启动时可访问的 MySQL 8 实例；
- Maven 3.9.x，或直接使用仓库内的 Maven Wrapper。

## 构建与测试

在仓库根目录执行：

首次运行完整测试须先按[存储核验指南](../docs/development/storage-preflight.md)构建固定 MinIO 测试镜像。
Foundation Backend CI 从锁定源码构建镜像，再执行完整 `clean verify` 并检查必需验收报告。

```bash
./devmate-server/mvnw -f devmate-server/pom.xml test
./devmate-server/mvnw -f devmate-server/pom.xml clean package
./devmate-server/mvnw -f devmate-server/pom.xml dependency:tree
```

数据库集成测试固定使用 digest 锁定的 `mysql:8.4.6`，创建临时空库并由 Flyway 迁移。测试连接信息由
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
| `AI_PROVIDER`                   | `openai` 或 `deepseek`            | `openai`                                   | `openai`    |
| `OPENAI_BASE_URL`               | 服务端 OpenAI API 基础 URL        | `https://api.openai.com/v1`                | 同左        |
| `OPENAI_API_KEY`                | OpenAI API Key                    | 空；启用 openai 时必填                     | 同左        |
| `OPENAI_MODEL`                  | 经部署者确认的 OpenAI 模型 ID     | 空；启用 openai 时必填                     | 同左        |
| `DEEPSEEK_BASE_URL`             | 官方 HTTPS 基础 URL               | `https://api.deepseek.com`                 | 同左        |
| `DEEPSEEK_API_KEY`              | DeepSeek API Key                  | 空；启用 deepseek 时必填                   | 同左        |
| `DEEPSEEK_MODEL`                | 经部署者确认的 DeepSeek 模型 ID   | 空；启用 deepseek 时必填                   | 同左        |
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
GitHub 或其他未实现服务的凭据。AI 仅在部署者显式设置 `AI_ENABLED=true` 并注入所选提供商密钥
和模型时启用；默认关闭状态不要求这些值。
国内试用可设置 `AI_PROVIDER=deepseek`、`DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL=deepseek-flash`，
无需 OpenAI 凭据。既有 `AI_PROVIDER=openai` 配置保留；默认 provider 仍为 openai，AI 默认关闭。
DeepSeek 地址默认 `https://api.deepseek.com`，只接受官方 HTTPS 空路径或 `/v1`；
完整安全注入与真实冒烟步骤见[本地开发指南](../docs/development/local-development.md)。

进入 MVC trace filter 的 HTTP 请求由服务端生成 UUID 格式的 traceId，通过 `X-Trace-Id`
响应头返回，并用于关联该请求产生的 AI 调用审计日志。认证过滤器在此之前拒绝的 401 响应
不保证携带该头；调用方不能假定所有错误响应均包含 traceId。客户端不能指定或覆盖服务端 traceId。

项目对话接口均需要有效 Bearer Token 和 `user` authority：

- `POST /projects/{projectId}/conversations`：创建对话；
- `GET /projects/{projectId}/conversations`：固定排序分页；
- `GET /projects/{projectId}/conversations/{conversationId}`：查询对话；
- `GET /projects/{projectId}/conversations/{conversationId}/messages`：按序分页查询消息；
- `POST /projects/{projectId}/conversations/{conversationId}/messages`：同步生成非流式回复。

生成请求必须提供 UUID `clientRequestId`。同一 ID 不会重复调用模型；同一对话同一时间只允许一个
生成请求。模型调用在数据库事务之外执行，成功和失败都会记录安全的调用状态。两种协议均显式使用
`stream=false`，不启用工具或提供商托管会话；OpenAI 请求使用 `store=false`，DeepSeek 请求使用
`thinking.type=disabled` 和 `max_tokens`，仅返回完整可见文本，不保存隐藏推理。
AI 默认关闭时，读取和管理对话仍可用，
生成接口返回 `503 AI_SERVICE_DISABLED`。

完整测试需要 Docker，以便 Testcontainers 在隔离的 MySQL 8.4.6 空库上执行 V1 至 V6 migration、
Mapper、认证授权和项目 API 集成测试。合并前应在 Docker 可用的环境执行本 README 的 Maven
Wrapper 命令。

## 知识文档接入

提供 `POST/GET /projects/{projectId}/documents` 和 `GET/DELETE /projects/{projectId}/documents/{documentId}`。
完整字段、权限、错误和 UUID 重放语义见[文档 API](../docs/api/knowledge-documents.md)。仅接受 UTF-8 txt/md，
单文件 5 MiB、请求 6 MiB、项目 100 份/100 MiB。`STORED` 只代表原文件完成；无解析、向量、文档页面或下载 URL。

knowledge 默认关闭，无存储凭据也能启动。启用时由环境先准备私有 bucket，并给应用最小的对象 PUT/GET/DELETE
及 bucket HEAD 权限（用于区分对象缺失和 bucket 故障）；应用不创建 bucket 或更改策略。
禁止日志记录文件名、正文、bucket/key、凭据或 SDK 诊断；不要在运行环境启用 SQL/SDK wire 调试日志。

| 环境变量                                                                                    | 默认值与用途                                                                 |
| ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `KNOWLEDGE_ENABLED`                                                                         | `false`；启用上传与远端恢复                                                  |
| `KNOWLEDGE_ENDPOINT`                                                                        | 空；启用时必填，生产 profile 强制 HTTPS                                      |
| `KNOWLEDGE_ALLOW_LOCAL_HTTP`                                                                | `false`；仅隔离本地环境可显式启用                                            |
| `KNOWLEDGE_REGION`                                                                          | `us-east-1`                                                                  |
| `KNOWLEDGE_BUCKET`                                                                          | 空；环境创建的私有 bucket                                                    |
| `KNOWLEDGE_ACCESS_KEY` / `KNOWLEDGE_SECRET_KEY`                                             | 空；服务端环境注入，不输出、不入库                                           |
| `KNOWLEDGE_MAX_FILE_BYTES` / `KNOWLEDGE_MAX_REQUEST_BYTES`                                  | `5242880` / `6291456`，只允许在硬上限内收紧                                  |
| `KNOWLEDGE_MAX_DOCUMENTS` / `KNOWLEDGE_MAX_PROJECT_BYTES`                                   | `100` / `104857600`，含所有未清理预留                                        |
| `KNOWLEDGE_TEMP_MAX_BYTES` / `KNOWLEDGE_TEMP_DIRECTORY`                                     | `268435456` / JVM 临时目录下 `devmate-knowledge`；私有实例目录与共享持久预算 |
| `KNOWLEDGE_MAX_CONCURRENT_UPLOADS`                                                          | `4`，实例上限，解析 multipart 前领取                                         |
| `KNOWLEDGE_CONNECT_TIMEOUT` / `KNOWLEDGE_READ_TIMEOUT` / `KNOWLEDGE_OPERATION_TIMEOUT`      | `PT2S` / `PT10S` / `PT30S`；每个 SDK 操作最多一次尝试                        |
| `KNOWLEDGE_OPERATION_LEASE`                                                                 | `PT2M`；必须比总操作超时至少多 30 秒                                         |
| `KNOWLEDGE_SCAN_INTERVAL` / `KNOWLEDGE_SCAN_BATCH_SIZE` / `KNOWLEDGE_MAX_AUTOMATIC_RETRIES` | `PT60S` / `50` / `5`，退避 1/2/4/8/16 分钟                                   |

配置修改通过重启生效。关闭功能仍允许授权元数据查询、删除标记和临时文件清理，暂停对象清理；重新启用后扫描
持久记录。未知写入结果持续占容量，不能凭租约过期或一次 HEAD 缺失清账。人工重试、备份与回滚见开发指南。
生产适配器采用锁定 AWS SDK 2.55.6 同步 S3/URLConnection，仅声明已测试的 MinIO 组合，未验证其他服务商。
