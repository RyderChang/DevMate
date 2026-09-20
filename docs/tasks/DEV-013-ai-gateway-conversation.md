# DEV-013：AI Gateway 与项目对话后端

## 1. 任务元信息

| 项目         | 内容                                                          |
| ------------ | ------------------------------------------------------------- |
| 任务编号     | `DEV-013`                                                     |
| 任务名称     | AI Gateway 与项目对话后端                                     |
| 所属阶段     | 第二阶段——AI 对话                                             |
| 任务类型     | 后端业务能力、外部 AI 集成、数据库迁移、安全边界与自动化测试  |
| 状态         | 待实施；本任务书不代表功能已经完成                            |
| 前置任务     | DEV-012 已合并，第一阶段基线可重复验证                        |
| 目标分支     | `develop`                                                     |
| 建议实现分支 | `feature/dev-013-ai-gateway-conversation`                     |
| 预计主要目录 | `devmate-server/`、`docs/adr/`、相关 README                   |
| 明确不涉及   | 前端聊天页面、流式输出、RAG、工具调用、代码执行、多提供商切换 |

本任务书基于远端 `develop` 的 DEV-012 合并提交
`45fcae14bf8dcb4f182bdfa6b0340015985229eb` 编写。实际实施前必须重新获取最新
`develop` 并核对基线，不得把本文中的文件列表、版本或测试数量当作永久事实。

## 2. 背景与目标

第一阶段已经具备认证授权、按用户隔离的项目空间、前端项目 CRUD、MySQL/Flyway
基础设施与 Foundation CI。第二阶段需要让用户在自己的项目内发起最小 AI 对话，同时建立后续
RAG、代码审查和测试生成都能复用的模型调用边界。

DEV-013 的目标不是一次完成完整聊天产品，而是交付一个可测试、可审计且不绑定业务模块的后端纵向切片：

1. 建立提供商无关的 `AI Gateway` 应用接口；
2. 提供第一个 OpenAI Responses API 适配器；
3. 在 MySQL 保存项目对话、消息和 AI 调用元数据；
4. 提供创建、列表、详情、消息分页和同步发送消息 API；
5. 强制按当前用户与项目归属隔离所有对话资源；
6. 对输入、上下文、输出、超时和并发设置明确上限；
7. 记录模型、提示词版本、Token 用量、耗时和执行状态；
8. 确保 CI 和自动化测试不访问真实模型、不需要 API Key，也不产生付费调用。

完成本任务后只能声明“项目对话后端与首个模型适配器可用”。前端交互、流式体验、知识检索和生产级
配额治理仍未完成。

## 3. 当前基线与启动条件

### 3.1 当前已知基线

- 后端使用 Java 21、Spring Boot 3.5.6、Spring MVC、Spring Security、MyBatis-Plus、MySQL 8 和 Flyway；
- 当前最大 migration 为 `V4__create_projects_table.sql`；
- `ProjectService.requireOwnedActiveProject` 已提供项目所有权检查入口；
- 普通用户通过可信的 `CurrentUser.id()` 确定身份，不能从请求体接受 `ownerUserId`；
- API 使用 `Result<T>`、`PageResult<T>`、`ErrorCode`、`BusinessException` 和全局异常处理；
- 项目不存在、已删除或属于其他用户时统一返回 `404 PROJECT_NOT_FOUND`；
- 数据库和对外时间统一使用 UTC；
- 当前没有 AI SDK、模型密钥、对话表、AI Gateway 或模型调用代码；
- Foundation CI 会运行后端 `clean verify`，测试不得依赖公共网络或私人账户。

### 3.2 启动条件

1. 本任务书已经由项目所有者审核并合并；
2. 从最新远端 `develop` 创建独立实现分支；
3. 检查工作区、最新 migration、构建文件、现有错误码和测试基线；
4. 确认 OpenAI API 的账户、模型可用性和费用由运行环境所有者负责，仓库不承诺具体模型一定可用；
5. 本地真实模型冒烟测试只在所有者主动提供环境变量时执行，不能成为 CI 前置条件；
6. 若实施时官方 API 契约或仓库结构已经变化，先更新任务书或在 PR 中明确说明，不机械照搬。

## 4. 冻结的架构决策

### 4.1 模块边界

新增两个清晰边界：

```text
conversation module
        ↓ depends on
ai gateway interface
        ↑ implemented by
OpenAI Responses adapter
```

- `conversation` 模块负责项目归属、对话、消息、上下文选择、提示词版本和调用编排；
- `ai` 模块公开提供商无关的请求、响应和异常类型；
- OpenAI DTO、HTTP 字段和错误体只能存在于 AI 基础设施适配器内；
- `conversation` 不得直接依赖 OpenAI 类型、URL 或认证头；
- AI 适配器不得直接访问 conversation Mapper；
- 不得跨模块直接调用 Mapper，应通过公开的应用服务边界协作；
- 本任务不拆分 Worker 或微服务，继续遵守 ADR-0001 的模块化单体决策。

实现 PR 应新增 ADR，记录“提供商无关 Gateway、首个 OpenAI Responses 适配器、应用侧持有会话状态”这一长期决策。

### 4.2 首个提供商与传输方式

首个适配器调用 OpenAI `POST /v1/responses`。使用 Spring Framework 已提供的同步 HTTP 客户端能力和
Jackson 映射，不为本任务引入 Spring AI、LangChain 或另一套 Web 框架。若确需官方 Java SDK，必须先在
PR 中说明必要性、固定版本、传递依赖和许可证影响，不能无说明新增。

采用 Responses API 的依据：OpenAI 官方当前建议新的文本生成应用优先使用 Responses API，而不是旧的
Chat Completions API；官方也明确说明 `output` 可能包含多个不同类型的条目，不能假设文本恒定存在于
`output[0].content[0].text`。实现必须遍历并验证响应内容，而不是依赖脆弱的固定下标。

参考：

- [OpenAI 文本生成指南](https://developers.openai.com/api/docs/guides/text?api-mode=responses)；
- [OpenAI Responses Java API 参考](https://developers.openai.com/api/reference/java/resources/beta/subresources/responses)；
- [OpenAI Create response API 参考](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)。

模型名称必须通过服务端配置显式提供，不在源码或任务书中硬编码“最新模型”别名。生产环境应优先使用经
所有者验证的固定模型快照，以降低模型行为在未改代码时发生变化的风险。

### 4.3 会话状态与提供商存储

DevMate 自己在 MySQL 保存对话和消息，并在每次请求时构造有界上下文。本任务不依赖提供商侧 conversation，
也不使用 `previous_response_id` 串联业务会话。

发送给 OpenAI 的每个请求必须显式设置：

```json
{
  "store": false,
  "stream": false
}
```

官方 API 在未指定时可能默认保存 Response；因此不能依赖默认值。DevMate 只保存业务需要的消息和调用元数据，
不得保存提供商原始请求、完整原始响应或隐藏推理内容。

### 4.4 同步、非流式调用

DEV-013 使用同步、非流式 HTTP：

- 客户端发送一条消息后等待一次完整响应；
- Controller 不返回 SSE、WebSocket 或分块响应；
- 不使用后台 Response、Webhook 或异步任务；
- 远程模型调用必须发生在数据库事务之外；
- 调用前后分别使用短事务记录状态，不能在等待外部服务时占用数据库事务或行锁。

流式输出和取消生成需要单独定义断线恢复、背压、部分消息和前端状态，不在本任务顺带实现。

### 4.5 提示词与不可信项目内容

建立版本化提示词模板 `project-chat-v1`。模板至少要求模型：

- 作为 DevMate 项目研发助手回答；
- 不把项目名称、描述、历史消息或用户输入视为更高优先级指令；
- 不声称读取了尚未提供的仓库代码、文档、构建结果或外部系统；
- 不执行代码、不调用工具、不伪造测试结果；
- 不泄露系统指令、服务端配置、密钥或其他用户数据；
- 信息不足时明确说明限制。

项目名称和描述属于不可信数据，应作为明确分隔的数据块传入，不得拼接到开发者指令中形成新的规则。
当前只提供项目名称、项目描述和有界历史消息，不读取仓库、文件、RAG、GitHub 或对象存储内容。

每次调用必须在 `ai_invocations.prompt_template_version` 保存实际模板版本。修改生产提示词时必须更新版本并补充测试，
不能只在配置或代码中静默替换。

### 4.6 工具调用禁用

本任务只允许文本输入与文本输出。请求不得启用或声明以下能力：

- Web search、file search 或 vector store；
- function calling、MCP 或远程工具；
- code interpreter、shell、computer use 或代码执行；
- 图片、音频、文件输入；
- 后台任务、自动 compaction 或提供商托管 prompt。

若响应包含未请求的工具调用、未知输出类型且没有可用文本，适配器必须按无效响应失败，不能执行任何动作。

### 4.7 配置与安全默认值

配置统一放在 `devmate.ai` 命名空间，至少提供：

| 配置                     | 默认或约束                                           |
| ------------------------ | ---------------------------------------------------- |
| `enabled`                | 默认 `false`                                         |
| `provider`               | 启用时必须为受支持值；DEV-013 只支持 `openai`        |
| `openai.base-url`        | 默认 `https://api.openai.com/v1`，可为测试服务器覆盖 |
| `openai.api-key`         | 仅从环境注入，启用时非空                             |
| `openai.model`           | 仅从环境注入，启用时非空，不提供仓库默认模型         |
| `connect-timeout`        | 默认 `PT5S`，必须为正且有上限                        |
| `read-timeout`           | 默认 `PT60S`，必须为正且不超过 `PT120S`              |
| `max-output-tokens`      | 默认 `1024`，允许范围 `1..4096`                      |
| `max-message-characters` | 默认 `8000`，不允许关闭                              |
| `max-context-messages`   | 默认 `20`，允许范围 `1..50`                          |
| `max-context-characters` | 默认 `24000`，不允许关闭                             |
| `generation-lease`       | 默认 `PT2M`，必须大于读取超时                        |

仓库配置只能使用类似 `${OPENAI_API_KEY:}` 的空占位符，不得包含真实或看似真实的密钥。AI 默认关闭时，应用和
全部自动测试必须可以在没有模型配置的情况下启动。AI 显式启用但密钥、模型或限额非法时应启动失败，不能延迟到
首次用户请求才暴露错误配置。

不得把 API Key 返回前端、写入数据库、异常、Actuator、OpenAPI 示例或日志。`base-url` 只能由服务端部署配置，
不能由用户请求覆盖，避免 SSRF。

### 4.8 上下文、Token 与费用边界

- 单条用户消息去除首尾空白后必须非空且不超过 8000 个 Unicode 字符；
- 每次只选择当前对话最近的成功 USER/ASSISTANT 消息；
- 历史最多 20 条且总字符数最多 24000，超限时确定性地从最旧消息开始裁剪；
- 当前用户消息始终保留，若其自身超过限制直接拒绝；
- 每次调用设置 `max_output_tokens`，默认 1024；
- 保存提供商返回的 input、output 和 total Token 数；字段缺失时保存 `NULL`，不得猜测；
- DEV-013 不根据易变化的公开价格计算金额，也不实现每日/月度余额或组织配额；
- 不自动重试模型请求，避免超时结果不明确时产生重复计费；用户重试必须使用新的请求 ID。

本任务的“预算控制”仅表示单请求输入/输出和并发有硬上限，并保留可审计用量。账户级费用告警和用户配额属于
后续治理任务。

### 4.9 并发与幂等

同一对话同一时间只允许一个生成请求。不能只使用 JVM 内存锁，因为未来多实例运行时会失效。

发送消息请求必须包含由客户端生成的 UUID `clientRequestId`，并在数据库建立
`(conversation_id, client_request_id)` 唯一约束：

- 首次请求：原子取得该对话的生成租约，保存 USER 消息和 `PENDING` invocation；
- 相同 ID 已成功：返回已经保存的同一结果，不再次调用模型；
- 相同 ID 正在处理：返回 `409 AI_REQUEST_IN_PROGRESS`；
- 相同 ID 已失败：返回已记录的安全失败结果，不再次调用模型；如需重试必须使用新 ID；
- 另一个请求占有未过期租约：返回 `409 AI_REQUEST_IN_PROGRESS`；
- 租约超过配置时间：在短事务中把遗留 `PENDING` 调用标记为失败后再允许新请求取得租约。

成功、失败和超时路径都必须释放租约。数据库更新应使用状态条件或版本条件，避免后完成的旧请求覆盖新状态。

## 5. 数据模型与 migration

新增且仅新增一个 Flyway migration，例如：

```text
V5__create_conversations_and_ai_invocations.sql
```

不得修改 V1 至 V4。migration 必须可在空 MySQL 8 数据库按顺序执行，并包含明确的非空、检查、唯一、外键和索引。

### 5.1 `conversations`

至少包含：

| 字段                    | 约束与用途                                       |
| ----------------------- | ------------------------------------------------ |
| `id`                    | `BIGINT` 自增主键                                |
| `project_id`            | 所属项目，不可变                                 |
| `owner_user_id`         | 来自可信当前用户，不接受客户端输入               |
| `title`                 | `VARCHAR(200)`；创建时可选，空值使用固定默认标题 |
| `generation_state`      | `IDLE` 或 `GENERATING`                           |
| `generation_started_at` | 当前租约开始时间，空闲时为 `NULL`                |
| `create_time`           | UTC 创建时间                                     |
| `update_time`           | UTC 更新时间，用于列表排序                       |

对话查询必须同时带入 `owner_user_id` 和 `project_id`。应使用数据库约束或可验证的组合外键确保对话 owner 与项目 owner
一致，不能只依赖 Controller 先查一次项目后按裸 `conversation_id` 操作。

本任务不提供删除和恢复 API，因此不提前添加无行为支撑的软删除字段。未来定义保留策略时通过新 migration 增加。

### 5.2 `conversation_messages`

至少包含：

| 字段              | 约束与用途                                 |
| ----------------- | ------------------------------------------ |
| `id`              | `BIGINT` 自增主键                          |
| `conversation_id` | 对话外键                                   |
| `sequence_no`     | 对话内严格递增序号，和对话 ID 组成唯一约束 |
| `role`            | 仅 `USER`、`ASSISTANT`                     |
| `content`         | `TEXT`，不得为空                           |
| `create_time`     | UTC 创建时间                               |

开发者提示词不作为可见消息保存；其版本记录在 invocation 中。消息分页固定按 `sequence_no ASC, id ASC`，不允许
客户端传任意排序表达式。

### 5.3 `ai_invocations`

至少包含：

| 字段                      | 约束与用途                                 |
| ------------------------- | ------------------------------------------ |
| `id`                      | `BIGINT` 自增主键                          |
| `conversation_id`         | 对话外键                                   |
| `client_request_id`       | UUID 文本，与对话组成唯一约束              |
| `user_message_id`         | 本次 USER 消息                             |
| `assistant_message_id`    | 成功后关联 ASSISTANT 消息，失败时为 `NULL` |
| `provider`                | 实际提供商，例如 `openai`                  |
| `model`                   | 实际发送的模型配置值                       |
| `prompt_template_version` | 固定记录实际模板版本                       |
| `provider_request_id`     | 提供商响应 ID；不存在时为 `NULL`           |
| `status`                  | `PENDING`、`SUCCEEDED` 或 `FAILED`         |
| `error_code`              | 安全的内部错误类别，不保存提供商正文       |
| `input_tokens`            | 提供商报告的输入 Token，可空               |
| `output_tokens`           | 提供商报告的输出 Token，可空               |
| `total_tokens`            | 提供商报告的总 Token，可空                 |
| `duration_ms`             | 非负耗时，可空                             |
| `started_at`              | UTC 开始时间                               |
| `completed_at`            | 结束状态时间，PENDING 时为空               |

不增加用于保存完整 prompt、原始请求 JSON、原始响应 JSON、隐藏推理内容或 API Key 的字段。

## 6. API 契约

所有接口：

- 位于 `/projects/{projectId}/conversations` 下；
- 需要有效 Bearer Token 和 `user` authority；
- 使用可信 `CurrentUser.id()`；
- 使用现有 `Result<T>` 和 `PageResult<T>`；
- 对项目不存在、已删除、非所有者项目、对话不存在或对话不属于该项目统一返回 404，不泄露资源存在性。

### 6.1 创建对话

```http
POST /projects/{projectId}/conversations
```

请求体：

```json
{
  "title": "Architecture discussion"
}
```

`title` 可省略或为空；规范化后最大 200 个字符。空标题使用固定的产品默认标题，不额外调用模型生成标题。

响应至少包含 `id`、`projectId`、`title`、`generationState`、`createdAt`、`updatedAt`。

### 6.2 对话列表

```http
GET /projects/{projectId}/conversations?page=1&pageSize=20
```

- `page` 范围 `1..10000`；
- `pageSize` 范围 `1..100`；
- 排序固定为 `update_time DESC, id DESC`；
- 只返回当前用户在指定活动项目中的对话。

### 6.3 对话详情

```http
GET /projects/{projectId}/conversations/{conversationId}
```

返回对话基本信息，不在详情响应中无界嵌入全部消息。

### 6.4 消息列表

```http
GET /projects/{projectId}/conversations/{conversationId}/messages?page=1&pageSize=50
```

- 默认 `pageSize=50`，最大 100；
- 固定按 `sequence_no ASC, id ASC`；
- 返回 USER/ASSISTANT、内容、序号和 UTC 时间；
- 不返回开发者提示词、提供商原始响应、错误正文或隐藏推理。

### 6.5 发送消息并生成回复

```http
POST /projects/{projectId}/conversations/{conversationId}/messages
```

请求体：

```json
{
  "clientRequestId": "5abf96bb-58d8-4f18-97f6-70d877cf6257",
  "content": "Explain the current project boundaries."
}
```

成功响应至少包含：

- `conversationId`；
- 保存后的 USER 消息；
- 保存后的 ASSISTANT 消息；
- invocation 摘要：`status`、`provider`、`model`、Token 用量、耗时和完成时间。

不得把 API Key、完整 prompt、开发者指令或提供商原始 JSON 返回客户端。

## 7. 调用流程与事务边界

发送消息按以下顺序实现：

```text
认证用户
  → 校验活动项目与对话归属
  → 短事务：幂等检查、取得生成租约、保存 USER 消息和 PENDING invocation
  → 事务外：选择有界历史、构造 project-chat-v1、调用 AI Gateway
  → 短事务：保存 ASSISTANT 消息、完成 invocation、释放租约
  → 返回已持久化结果
```

失败路径必须在短事务中把 invocation 标记为 `FAILED` 并释放租约。不得通过一个 `@Transactional` 方法包围完整远程调用。
如果进程在远程调用期间终止，后续请求通过租约过期规则恢复，不永久锁死对话。

对话的 `update_time` 在成功保存新消息时更新；失败是否更新时间必须在实现中保持一致并由测试固定，不得因数据库
自动更新时间产生不可预测的列表顺序。

## 8. OpenAI 适配器要求

请求至少包含：

- 配置的 `model`；
- `store=false`；
- `stream=false`；
- `max_output_tokens`；
- `project-chat-v1` 的 developer/instructions 内容；
- 明确角色的有界历史与当前 USER 消息；
- 不包含任何 tools、file、image、audio 或后台参数。

响应解析必须：

1. 接受完整 HTTP 成功响应后验证顶层状态；
2. 遍历全部 `output` 项和内容项；
3. 按顺序聚合可见 `output_text`；
4. 对合法 refusal 保存可展示的拒绝文本，而不是伪造普通回答；
5. 没有可用文本、状态 incomplete/failed、JSON 畸形或字段类型错误时安全失败；
6. 读取 response ID 和实际 usage；
7. 忽略未知非执行型元数据，但绝不执行工具调用；
8. 限制可接受响应体大小，防止异常上游响应耗尽内存。

上游认证失败、限流、超时、网络失败、5xx 和无效响应应映射为稳定的内部异常类别。客户端消息必须安全、可操作，
不能透传提供商响应体、请求 ID 之外的内部诊断或账户信息。

## 9. 错误码与日志

在现有 `ErrorCode` 上做最小增量，至少区分：

| 场景                         | HTTP 语义                      |
| ---------------------------- | ------------------------------ |
| 对话不存在、越权或项目不匹配 | 404 `CONVERSATION_NOT_FOUND`   |
| AI 未启用                    | 503 `AI_SERVICE_DISABLED`      |
| 同一对话已有生成             | 409 `AI_REQUEST_IN_PROGRESS`   |
| 上游限流                     | 503 `AI_PROVIDER_RATE_LIMITED` |
| 上游超时                     | 504 `AI_PROVIDER_TIMEOUT`      |
| 网络或上游不可用             | 503 `AI_PROVIDER_UNAVAILABLE`  |
| 上游响应无法安全解析         | 502 `AI_RESPONSE_INVALID`      |

参数校验继续使用现有 `INVALID_PARAMETER`。不得把项目越权改成 403。

允许记录：traceId、内部 invocation ID、provider、model、状态、Token 数、耗时和安全错误类别。

禁止记录：API Key、Authorization 头、完整用户消息、完整助手消息、完整 prompt、项目描述、原始请求/响应、上游错误正文、
数据库连接串或环境变量全集。日志参数必须使用参数化写法。

## 10. 测试要求

### 10.1 AI Gateway 与适配器单元测试

- 请求显式包含 `store=false`、`stream=false` 和输出上限；
- 请求没有工具、文件、图像、后台任务或提供商 conversation；
- 模型和密钥只来自配置；
- 多个 output/content 文本按顺序聚合，不使用固定数组下标；
- refusal、空输出、incomplete、畸形 JSON、超大响应和未知工具输出安全处理；
- usage 和 provider request ID 正确映射；
- 401/403、429、超时、网络异常与 5xx 映射为稳定错误；
- 测试使用本地 Stub/Mock HTTP，不访问公共网络。

### 10.2 Conversation Service 单元测试

- 创建、列表、详情和消息分页正常；
- 项目和对话所有权始终带入当前用户 ID；
- 项目不存在、已删除、非所有者和对话错配均为统一 404；
- 空白、超长消息和非法 UUID 被拒绝；
- 上下文按消息数与字符数确定性裁剪；
- prompt 将项目字段和历史当作不可信数据；
- 成功保存 USER、ASSISTANT 和 SUCCEEDED invocation；
- 超时、限流、无效响应等失败保存 FAILED invocation 并释放租约；
- 相同 `clientRequestId` 不重复调用或重复保存消息；
- 并发请求、未过期租约和过期租约恢复行为正确；
- 远程调用不在数据库事务内执行。

### 10.3 Controller/API 测试

- 无 Token 为 401，缺少 `user` authority 为 403；
- 所有 API 使用统一响应结构与参数校验；
- 列表分页上下限和固定排序生效；
- 他人项目与他人对话不能通过路径 ID 访问；
- AI 关闭时只阻止生成接口，不破坏对话读取和既有项目 API；
- 上游失败不泄露敏感响应。

### 10.4 MySQL/Testcontainers 集成测试

- V1 至 V5 可从空库顺序迁移；
- 表、外键、检查约束、唯一约束和索引符合任务书；
- `(conversation_id, client_request_id)` 幂等约束真实生效；
- owner/project 关系不能写入不一致数据；
- 消息序号唯一且分页顺序稳定；
- 既有认证、RBAC 和项目测试继续通过。

CI 不得读取 `OPENAI_API_KEY`，不得调用真实 OpenAI API，也不得把网络失败当作跳过或成功。

## 11. 非目标

DEV-013 不实现：

- Vue 聊天页面、前端状态管理或路由；
- SSE、WebSocket、流式 token、取消生成或断线恢复；
- RAG、Embedding、Qdrant、文档上传或代码切片；
- GitHub 仓库绑定、代码同步或自动读取仓库；
- function calling、MCP、Web search、file search、computer use 或代码执行；
- 多模型路由、自动 fallback、负载均衡或多提供商 UI；
- Redis 限流、全局队列、异步 Worker 或分布式任务；
- 提供商托管 conversation、prompt 或 Response 存储；
- 自动标题生成、对话重命名、删除、恢复或导出；
- 每日/月度用户配额、金额结算、账单同步或管理后台；
- 独立内容审核服务或完整安全策略中心；
- 在服务端执行用户代码或让模型直接修改仓库。

发现这些需求时记录为后续任务，不扩大 DEV-013。

## 12. 验收标准

- [ ] 从最新 `develop` 创建独立实现分支，未直接在共享分支提交。
- [ ] 新增 ADR，记录 Gateway 边界、OpenAI Responses 首个适配器和应用侧状态决策。
- [ ] 业务模块只依赖提供商无关的 AI Gateway 接口。
- [ ] V5 migration 在空 MySQL 8 数据库顺序执行通过，V1 至 V4 未修改。
- [ ] 对话、消息和 invocation 具有必要约束、索引与所有权隔离。
- [ ] 创建、列表、详情、消息分页和同步生成 API 契约完整。
- [ ] 项目、对话不存在或越权使用一致的 404 语义。
- [ ] 模型调用位于数据库事务之外，成功和失败都能完成状态并释放租约。
- [ ] `clientRequestId` 幂等和数据库生成租约阻止重复调用。
- [ ] 请求显式使用 `store=false`、`stream=false`，且未启用任何工具。
- [ ] 输入、上下文、输出 token、超时、响应体和并发均有硬上限。
- [ ] 提示词版本化，项目内容按不可信数据处理。
- [ ] provider、model、模板版本、Token、耗时与状态已记录，但未保存原始请求/响应或隐藏推理。
- [ ] 默认 AI 关闭时应用和测试无需密钥即可启动；启用时非法配置快速失败。
- [ ] 自动测试使用 Stub/Mock，CI 没有真实模型调用和付费依赖。
- [ ] 日志、响应、配置、截图和 Git 历史中没有密钥或完整对话内容。
- [ ] 既有后端测试与 Foundation Backend CI 继续通过。
- [ ] README、OpenAPI、ADR 和 migration 说明与实际行为同步。
- [ ] 未开始前端聊天、流式输出、RAG 或其他后续能力。

## 13. 验证命令

在仓库根目录、JDK 21 和 Docker 可用环境执行：

```bash
docker info
./devmate-server/mvnw -B -f devmate-server/pom.xml clean verify
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check origin/develop...HEAD
git status --short
```

Windows PowerShell 使用 `./devmate-server/mvnw.cmd`。如实现新增了专用验证脚本，应在 PR 中列出真实执行命令和结果。
真实 OpenAI 冒烟测试不是合并门禁；若所有者选择执行，只报告脱敏结果，不记录请求正文、响应正文或密钥。

## 14. 预计文件影响范围

具体名称可按最新代码调整，但职责不得混淆：

```text
devmate-server/
  src/main/java/com/devmate/ai/
    application or gateway types
    config/
    infrastructure/openai/
  src/main/java/com/devmate/conversation/
    controller/
    dto/
    entity/
    mapper/
    service/
    vo/
  src/main/resources/
    application.yml
    application-dev.yml
    application-prod.yml
    db/migration/V5__create_conversations_and_ai_invocations.sql
  src/test/java/com/devmate/ai/
  src/test/java/com/devmate/conversation/
docs/
  adr/0002-*.md
  adr/README.md
  README.md
devmate-server/README.md
README.md（仅在当前状态确实变化时）
```

不得为“补齐结构”创建空包，也不得借本任务重组现有认证或项目模块。

## 15. 实施顺序

1. 更新基线并核对任务书、构建文件、migration 与现有测试；
2. 编写 ADR，冻结 Gateway、状态持有与首个适配器决策；
3. 新增 V5 migration 及 MySQL 约束测试；
4. 实现 AI Gateway 接口、配置校验和 OpenAI 适配器单元测试；
5. 实现 conversation 数据访问、所有权过滤和分页；
6. 实现提示词构造、上下文裁剪、幂等与生成租约；
7. 实现 Controller、DTO/VO 与安全错误映射；
8. 补齐 Service、Controller、适配器和集成测试；
9. 更新 OpenAPI、README、ADR 索引和 migration 说明；
10. 执行完整后端验证、文档检查和 `git diff --check`；
11. 推送 PR，核对 PR 最新 head 的 Foundation Backend；
12. 等待所有者审核，不自动合并，不进入 DEV-014。

## 16. PR、回滚与交付

建议提交：

```text
feat(ai): add project conversation gateway
```

PR 目标为 `develop`，必须说明：

- 背景、范围和非目标；
- Gateway 与 conversation 的模块边界；
- 数据表、API、幂等和事务设计；
- 实际验证命令、测试数量与 CI 链接；
- 是否执行真实模型冒烟测试；未执行时明确写“未执行”；
- 配置项及密钥注入方式，但不得包含真实值；
- 已知限制、风险和回滚方式；
- 未完成的前端、流式、RAG 和配额能力。

回滚时撤销代码和文档提交。V5 如果已经在共享环境执行，禁止修改或删除 migration；应通过新的前向 migration
处理数据库结构，是否保留历史对话数据必须由所有者确认。不得通过关闭安全校验、清空共享数据或修改旧 migration 回滚。

最终交付应报告分支、commit SHA、PR、修改文件、验证结果、风险和人工配置步骤。建议下一任务 DEV-014 规划前端项目
对话页面及同步交互；是否引入流式输出应在后续独立任务中重新评估。
