# DEV-004：后端基础工程完善

## 1. 任务元信息

| 项目 | 内容 |
| --- | --- |
| 任务编号 | DEV-004 |
| 任务名称 | 后端基础工程完善 |
| 任务类型 | 后端工程基础能力 |
| 所属阶段 | 第一阶段——工程基座完善 |
| 执行者 | Codex Cloud |
| 基准分支 | `develop` |
| 执行分支 | `chore/004-backend-foundation` |
| PR 目标分支 | `develop` |
| 提交信息 | `feat(server): improve backend foundation` |
| 主要修改范围 | `devmate-server/` |

> 本文件是供 Codex Cloud 后续执行的任务说明，不代表 DEV-004 已经实施。开始执行前必须阅读仓库根目录的 `AGENTS.md`、`CONTRIBUTING.md`、相关设计文档和本任务全文。

## 2. 当前背景

DevMate 采用前后端分离的 monorepo 架构。DEV-002 已在 `devmate-server/` 中建立 Java 21 + Spring Boot 3 + Maven Wrapper 后端工程，并提供了以下最小能力：

- Spring Boot 启动类；
- `GET /api/health` 健康检查；
- 最小版泛型统一响应 `Result<T>`；
- 仅处理未预期异常的 `GlobalExceptionHandler`；
- 基础 `application.yml`、开发配置示例和 Logback 控制台配置；
- 应用上下文与健康接口测试；
- MyBatis-Plus、Spring Security、Redis、MySQL 驱动的依赖预留。

DEV-003 已完成前端工程初始化并合并到 `develop`。当前阶段仍不进入用户、项目、AI 等业务开发，而是在 DEV-002 基础上补齐后端通用规范，使后续业务模块能够复用一致的接口、异常、配置、API 文档和日志能力。

本任务应完善已有实现，不得重复创建平行的 `Result`、异常处理器、启动类或健康接口。必须保持现有健康接口兼容，除非本文件明确要求变更。

## 3. 前置条件与执行前检查

开始前必须：

1. 阅读根目录 `AGENTS.md`、`CONTRIBUTING.md`、`README.md`；
2. 阅读 `docs/README.md`、相关架构文档、DEV-002 与 DEV-003 任务文档；
3. 确认 DEV-003 已合并到远程 `develop`；
4. 检查当前分支、工作区状态和最近提交；
5. 更新远程引用，从最新 `develop` 创建 `chore/004-backend-foundation`；
6. 检查 `devmate-server/` 中现有 `Result`、异常处理、配置、日志、README 和测试；
7. 如存在无关未提交修改或范围冲突，停止并报告，不覆盖、不删除、不纳入提交。

禁止直接在 `main` 或 `develop` 上开发、提交或推送。

## 4. 任务目标

完成后，DevMate 后端应具备：

- 简单、泛型、可扩展的统一接口响应模型；
- 业务异常、参数异常和未处理异常的统一转换机制；
- 基础且稳定的通用错误码规范；
- `application.yml`、`application-dev.yml`、`application-prod.yml` 的 profile 配置边界；
- 不含真实凭据的数据源配置规范；
- 明确的 SQL/Flyway 目录约定，但不包含任何业务表；
- SpringDoc OpenAPI 生成的 OpenAPI JSON 与 Swagger UI；
- 适合本地开发和基础排错的日志配置；
- 覆盖上述基础行为的自动化测试；
- 与真实实现一致的后端 README。

## 5. 技术要求

### 5.1 运行与依赖约束

- 保持 Java 21；
- 保持当前 Spring Boot 3.x 版本，不在本任务升级 Spring Boot、MyBatis-Plus 或 Maven Wrapper；
- 继续使用 Maven Wrapper；
- 使用当前 Spring MVC 技术栈；
- 新增与当前 Spring Boot 版本兼容的稳定版 `springdoc-openapi-starter-webmvc-ui`；
- SpringDoc 版本应集中声明或由现有依赖管理机制管理，并在 PR 中记录实际版本与兼容性依据；
- 不使用 alpha、beta、RC 或 SNAPSHOT 依赖；
- 不引入 Lombok、额外 JSON 框架、复杂日志 SDK 或任务范围外的工具。

### 5.2 包结构原则

继续遵守仓库的模块化单体约定：业务代码未来按业务模块组织，通用能力仅放真正跨模块复用的内容。

本任务建议涉及：

```text
com.devmate
└── common
    ├── api
    │   ├── Result.java
    │   └── ErrorCode.java
    ├── config
    │   └── OpenApiConfig.java
    └── exception
        ├── BusinessException.java
        └── GlobalExceptionHandler.java
```

文件名可根据现有规范做等价调整，但不得创建第二套并行的公共响应或异常体系。没有真实用例的 `service`、`repository`、`domain`、`dto`、`vo` 空包不得创建。

## 6. 实现范围

### 6.1 统一响应模型

在现有 `com.devmate.common.api.Result<T>` 基础上完善，不要另建 `ApiResponse` 等重复类型。

JSON 字段保持：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

要求：

- `code` 为整数应用错误码；
- `message` 为稳定、可安全返回客户端的消息；
- `data` 为泛型数据，允许为空；
- 支持携带数据的成功响应；
- 支持无数据的成功响应；
- 支持基于错误码和消息的失败响应；
- 可提供少量清晰的静态工厂方法，避免 Controller 重复构造；
- 保持类型不可变，不暴露 setter；
- 不依赖 Lombok；
- 不绑定用户、项目、AI 等具体业务；
- 不加入分页、国际化、traceId、时间戳等尚未确认的字段。

兼容性要求：

- 保留 DEV-002 已确定的成功码 `0` 与成功消息 `success`；
- `GET /api/health` 的响应字段和值保持不变；
- HTTP 状态码表达协议结果，`Result.code` 表达应用结果，二者不能在代码中被当作同一个概念。

建立最小通用错误码定义，推荐放在 `common/api/ErrorCode.java`：

| 名称 | 建议 code | 默认 message | HTTP 状态 |
| --- | ---: | --- | --- |
| `SUCCESS` | `0` | `success` | 200 |
| `INVALID_PARAMETER` | `1001` | `Invalid request parameter` | 400 |
| `BUSINESS_ERROR` | `2001` | `Business request failed` | 400 |
| `INTERNAL_ERROR` | `5000` | `Internal server error` | 500 |

如现有实现或已接受文档中存在更明确的错误码约定，应复用该约定并在 PR 中说明偏差，不得保留散落的魔法数字。

### 6.2 全局异常处理

在现有 `GlobalExceptionHandler` 上扩展，并新增 `BusinessException`。

#### BusinessException

- 继承合适的非受检异常类型；
- 至少携带应用错误码和安全的客户端消息；
- 可支持明确的 HTTP 状态，但不得把 HTTP 状态直接当作应用错误码；
- 默认映射为 HTTP 400；
- 不包含数据库实体、HTTP 请求对象或具体业务模块依赖；
- 不输出或暴露堆栈、SQL、路径、密钥等敏感信息。

#### GlobalExceptionHandler

至少统一处理：

- `BusinessException`；
- `MethodArgumentNotValidException`，用于 `@Valid` 请求体校验；
- `ConstraintViolationException`，用于请求参数约束校验；
- 必要时处理同类的绑定或消息不可读异常，避免返回 Spring 默认错误结构；
- `Exception`，作为未预期异常兜底。

处理要求：

- 所有上述异常均返回 `Result` 结构；
- 参数异常返回 HTTP 400 与通用参数错误码；
- 业务异常返回其明确的 HTTP 状态、应用错误码和客户端消息；
- 未处理异常返回 HTTP 500 与通用内部错误码；
- 客户端响应不得包含异常类型、堆栈、SQL、服务器路径或内部实现细节；
- 参数错误消息应稳定、简洁；若组合字段错误，应有确定顺序，避免测试不稳定；
- 业务异常按可排查需要使用参数化日志，不记录无意义的完整堆栈；
- 未处理异常必须在服务端记录异常堆栈以及请求 method、URI；
- 不吞掉异常后返回 HTTP 200；
- 不处理 Spring Security、JWT 或权限异常。

### 6.3 Spring Boot 配置规范化

整理资源文件：

```text
src/main/resources/
├── application.yml
├── application-dev.yml
├── application-prod.yml
├── logback-spring.xml
└── db/
    └── migration/
        └── README.md
```

#### application.yml

- 保留应用名称 `devmate-server`；
- 保留默认端口 `8080`，允许 `SERVER_PORT` 覆盖；
- 不固定激活任何 profile；
- 保留 DEV-002 为未实现认证和数据库阶段设置的临时自动配置排除；
- 默认启动不得依赖 MySQL、Redis 或外部网络；
- Actuator 只暴露当前允许的 `health`、`info`；
- 不打开 debug、SQL 明文输出、完整错误堆栈或全部 Actuator 端点。

#### application-dev.yml

- 只包含本地开发 profile 的安全配置；
- 为端口、数据源 URL、用户名、密码等提供环境变量配置入口；
- 可为本地非敏感值提供清楚标注的默认值；密码和其他敏感字段不得提供真实值；
- `com.devmate` 可设置为 `DEBUG`，第三方框架不得默认开启冗长 DEBUG；
- 不提交个人机器路径或开发者专属配置。

#### application-prod.yml

- 所有数据库凭据和环境相关值均来自环境变量；
- 不提供生产密码、Token 或看似可用的硬编码凭据；
- 默认日志级别为 `INFO` 或更严格；
- 不开放额外 Actuator 端点；
- 不启用自动建表或 schema 自动修改；
- 不在本任务配置真实生产数据库连接。

#### 配置兼容性

- 本任务只建立配置规范，不实际启用数据库访问；
- DEV-002 中的 `DataSourceAutoConfiguration` 与 `SecurityAutoConfiguration` 临时排除应继续保留，并在 README 中说明未来分别由数据库和认证任务移除；
- profile 文件中的数据源字段当前仅作为后续接入契约，不能使默认、dev 或测试启动强制连接数据库；
- 现有 `application-dev.yml.example` 的有效说明应迁移到正式 profile 与 README；完成迁移后删除重复示例文件，避免两套配置来源；
- 不创建或提交 `.env`、真实证书、真实连接串和任何秘密信息。

### 6.4 OpenAPI 与 Swagger UI

集成 SpringDoc OpenAPI：

- 在 `pom.xml` 添加与当前 Spring Boot 兼容的 `springdoc-openapi-starter-webmvc-ui`；
- 添加最小 `OpenApiConfig`，仅配置项目标题、简介和当前 API 版本等非业务元数据；
- OpenAPI JSON 默认可通过 `/v3/api-docs` 访问；
- Swagger UI 默认可通过 `/swagger-ui/index.html` 访问；
- README 同时说明 `/swagger-ui.html` 可能作为重定向入口，但以实际验证通过的入口为准；
- 健康接口应出现在生成的 OpenAPI 文档中；
- 不为尚未存在的业务接口伪造文档；
- 不配置 JWT、OAuth2、API Key 或其他安全方案；
- 不自定义复杂 UI、不暴露敏感配置或内部管理信息。

若 SpringDoc 默认端点因现有配置不可访问，应做最小、可解释的调整，不得通过引入临时认证放行逻辑解决。

### 6.5 数据库基础准备

本任务只建立约定，不连接数据库、不编写 Mapper、不创建表。

- 数据源属性采用环境变量，命名在 README 中统一说明；
- SQL/Flyway 目录统一为 `src/main/resources/db/migration/`；
- 在该目录增加简短 `README.md`，说明未来迁移文件命名采用 `V<version>__<description>.sql`；
- 说明已合并或在共享环境执行的 migration 不得修改；
- 当前不得添加任何 migration SQL、初始化数据或业务表 DDL；
- 不添加 MyBatis Mapper、实体、Repository 或数据库 Service；
- 不启用 JPA/Hibernate DDL 自动生成；
- 不在本任务添加或启用 Flyway 依赖，Flyway 正式接入与首个 schema 迁移由后续数据库任务完成；
- 不以 H2 代替 MySQL 8 做兼容性结论。

### 6.6 日志规范

在现有 `logback-spring.xml` 和 profile 配置上做最小完善：

- 保留 Spring Boot 默认的 SLF4J + Logback 方案；
- 控制台日志至少包含时间、级别、线程、logger 和消息；
- 输出编码为 UTF-8；
- 默认和生产环境日志级别为 `INFO`；
- 开发环境仅将 `com.devmate` 调整为 `DEBUG`；
- 异常日志使用参数化写法；
- 不记录密码、Token、连接串、完整请求/响应正文或环境变量全集；
- 不配置开发者本机绝对路径；
- 不引入 ELK、Loki、SkyWalking、外部日志服务、JSON encoder、链路追踪 SDK 或复杂滚动策略；
- traceId 等链路能力不在本任务实现。

### 6.7 README 更新

更新 `devmate-server/README.md`，内容必须与本任务完成后的真实状态一致，至少包括：

- JDK 21 与 Maven Wrapper 前置要求；
- 测试、打包、启动命令；
- profile 的用途与激活方式；
- 配置所使用的环境变量名称；
- 默认启动不依赖外部基础设施；
- 数据库与 Security 自动配置仍被临时排除的原因及后续移除计划；
- `GET /api/health` 的调用与响应示例；
- Swagger UI 和 OpenAPI JSON 的访问地址；
- SQL/Flyway 目录约定；
- 当前未实现数据库、认证、Redis、AI 和其他业务功能。

不得把“配置占位”描述为数据库已经接入，也不得把依赖预留描述为功能已经实现。

## 7. 文件修改边界

### 7.1 允许修改

- `devmate-server/pom.xml`
- `devmate-server/README.md`
- `devmate-server/src/main/java/com/devmate/common/**`
- `devmate-server/src/main/resources/**`
- `devmate-server/src/test/java/com/devmate/**` 中与本任务基础能力直接相关的测试
- 现有健康接口及其测试，仅限保持统一响应和 OpenAPI 可验证所需的最小调整

### 7.2 允许新增

- `ErrorCode`
- `BusinessException`
- `OpenApiConfig`
- 异常处理、响应模型和 OpenAPI 的测试
- `application-dev.yml`
- `application-prod.yml`
- `src/main/resources/db/migration/README.md`
- 完成本任务必要的其他后端基础文件

### 7.3 允许删除

- `devmate-server/src/main/resources/application-dev.yml.example`，但仅限其有效内容已迁移至正式 profile 和 README 后删除。

### 7.4 禁止修改

- `devmate-web/`
- `main` 分支
- 与 DEV-004 无关的根目录和其他模块文件
- 已合并任务文档的历史内容

如实际完成任务必须修改上述边界外文件，先停止并请求项目负责人确认，不得自行扩大范围。

## 8. 禁止实现范围

DEV-004 禁止实现：

- 用户注册、用户登录或用户中心；
- JWT、Spring Security 认证逻辑或权限系统；
- 正式 SecurityFilterChain、内存用户或临时全放行安全配置；
- Redis 连接或业务逻辑；
- WebSocket；
- AI、RAG、向量数据库或模型接口；
- 项目管理、GitHub 仓库分析、代码审查或测试生成模块；
- 业务数据库表、完整数据库设计、Mapper、实体或数据库业务代码；
- 文件上传、对象存储或文件系统业务；
- 第三方服务接入；
- Docker、部署、CI/CD 或复杂可观测平台；
- 分页结构、国际化、traceId 等未经本任务确认的扩展；
- 任何前端修改；
- 任务范围外的依赖升级、重构或格式化。

## 9. 测试要求

新增或完善测试，至少覆盖：

### 9.1 Result 单元测试

- 携带数据的成功响应；
- 无数据成功响应；
- 失败响应；
- 字段值与 JSON 契约一致；
- 现有健康接口成功响应仍为 `code = 0`、`message = "success"`。

### 9.2 全局异常处理测试

- `BusinessException` 转换为预期 HTTP 状态和统一响应；
- `@Valid` 请求体校验失败返回 HTTP 400 与统一响应；
- 请求参数约束失败返回 HTTP 400 与统一响应；
- 未处理异常返回 HTTP 500 与通用安全消息；
- 异常响应不包含堆栈、异常类名或内部路径；
- 测试用 Controller 应放在测试源码中，不得为测试向生产代码加入伪业务接口。

### 9.3 OpenAPI 测试

- 应用上下文可加载 SpringDoc 配置；
- `/v3/api-docs` 返回 HTTP 200 和 JSON；
- OpenAPI 文档包含 `/api/health`；
- Swagger UI 入口可访问或按依赖默认行为正确重定向。

### 9.4 Profile 与启动验证

- 默认 profile 在没有数据库、Redis 和外部服务时正常加载；
- dev profile 在没有真实秘密和外部连接时正常加载；
- 配置测试不得访问公共网络或真实数据库；
- 不要求在缺少生产环境变量时启动 prod profile；
- 所有测试必须稳定、独立，不依赖执行顺序和开发者本机状态。

## 10. 验证方式

在 `devmate-server/` 目录执行并记录真实结果：

```bash
./mvnw test
./mvnw clean package
```

Windows 环境使用等价命令：

```powershell
mvnw.cmd test
mvnw.cmd clean package
```

完成构建后进行有限的启动验证：

1. 使用默认 profile 启动应用；
2. 确认日志中应用启动成功且没有尝试连接 MySQL、Redis 或第三方服务；
3. 请求 `GET /api/health`，确认 HTTP 200 与既有统一响应；
4. 请求 `/v3/api-docs`，确认 HTTP 200 且包含健康接口；
5. 访问 `/swagger-ui/index.html`，确认页面可用；
6. 正常终止应用进程。

提交前还必须确认：

```bash
git diff --check
git status --short
```

确认未提交 `target/`、日志文件、IDE 文件、真实配置、凭据或其他生成产物。

若因环境、权限或依赖下载失败而无法执行某项验证，必须在 PR 中列出未执行项、原因和风险，不得声称验证通过。

## 11. Acceptance Criteria

- [ ] 基于最新 `develop` 的 `chore/004-backend-foundation` 分支完成开发。
- [ ] 未直接修改或提交到 `main`、`develop`。
- [ ] 在现有 `Result<T>` 上完成统一响应能力，未创建重复响应类型。
- [ ] `Result<T>` 支持有数据成功、无数据成功和失败响应。
- [ ] 保留成功码 `0`、消息 `success` 和现有健康接口响应契约。
- [ ] 通用错误码集中定义，代码中没有散落的相关魔法数字。
- [ ] `BusinessException` 已实现且不依赖具体业务模块。
- [ ] `GlobalExceptionHandler` 统一处理业务异常、参数异常和未处理异常。
- [ ] 参数错误返回 HTTP 400，未处理异常返回 HTTP 500，不以成功状态掩盖错误。
- [ ] 所有异常响应使用统一 `Result`，且不泄露敏感内部信息。
- [ ] `application.yml`、`application-dev.yml`、`application-prod.yml` 职责清晰。
- [ ] 未固定激活 profile，默认和 dev 启动不依赖外部服务。
- [ ] 配置文件与 Git 历史中不存在真实密码、Token、密钥或生产连接串。
- [ ] 数据库与 Security 自动配置的临时排除及后续计划已在 README 说明。
- [ ] SpringDoc OpenAPI 已集成，`/v3/api-docs` 可访问且包含健康接口。
- [ ] Swagger UI 可通过已记录的地址访问。
- [ ] 数据库迁移目录约定已建立，但未添加业务 SQL、业务表或 Flyway 运行依赖。
- [ ] 默认、开发和生产日志级别符合要求，未引入复杂日志平台。
- [ ] 后端 README 与实际能力、命令和限制一致。
- [ ] Result、异常处理、OpenAPI 和启动行为具备相应自动化测试。
- [ ] `./mvnw test` 通过。
- [ ] `./mvnw clean package` 通过。
- [ ] 默认启动、健康接口、OpenAPI JSON 和 Swagger UI 验证通过。
- [ ] 未修改 `devmate-web/`，未实现禁止范围内的功能。
- [ ] 未提交构建产物、日志、IDE 配置、个人环境文件或秘密信息。
- [ ] 使用规定提交信息并创建目标为 `develop` 的 PR。
- [ ] 未自动合并 PR，等待项目负责人审核。

## 12. Git 流程

### 12.1 创建分支

从最新远程 `develop` 创建：

```text
chore/004-backend-foundation
```

如该分支已经存在且不能确认属于本任务，不得覆盖、强制推送或改写历史，应停止并报告。

### 12.2 提交信息

完成实现和全部可执行验证后提交：

```text
feat(server): improve backend foundation
```

一个提交应保持单一任务范围。不得使用 `--no-verify` 绕过检查。

### 12.3 Pull Request

- 源分支：`chore/004-backend-foundation`
- 目标分支：`develop`
- 建议标题：`feat(server): improve backend foundation`
- 不得创建指向 `main` 的 PR；
- 不得自动合并，等待项目负责人审核。

PR 描述至少包含：

- 背景与目标；
- 实际新增、修改和删除的文件；
- 统一响应与错误码约定；
- SpringDoc 和 profile 配置说明；
- 实际执行的测试、构建和启动验证结果；
- 风险、兼容性、回滚方式和未完成事项；
- 明确声明未实现任何业务功能。

## 13. Codex Cloud 完成后的输出要求

执行结束后向项目负责人提供：

1. 完成内容与未完成内容；
2. 新增、修改、删除文件清单；
3. 关键设计决策及兼容性说明；
4. 实际执行的验证命令和逐项结果；
5. 未执行项、已知风险或与任务文档的偏差；
6. 分支名称与 Commit SHA；
7. Pull Request 链接。

在项目负责人确认并合并前，不得进入下一项开发任务。
