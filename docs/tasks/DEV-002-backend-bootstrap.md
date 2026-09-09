# DEV-002：后端工程初始化

> 任务类型：Backend Bootstrap
>
> 所属阶段：第一阶段——工程基座与项目空间
>
> 执行者：Codex Cloud
>
> 目标分支：`chore/002-backend-bootstrap`
>
> PR 目标：`develop`
>
> 本文档是执行任务，不代表功能已经实现

## 1. 任务背景

DevMate 是一个面向软件研发流程的 AI 助手平台，采用 Vue 前端与 Spring Boot 后端分离的 monorepo。后端首版采用模块化单体，在同一应用中保持清晰的业务模块边界，未来只有在真实吞吐或隔离需求出现时才拆分 Worker。

DEV-001 已完成仓库治理与文档基线。DEV-002 只负责在 `devmate-server/` 中建立可编译、可测试、可启动的 Java 21 + Spring Boot 3 后端基础工程，为后续本地依赖、认证、项目管理和 AI 能力提供稳定入口。

本任务不实现任何用户、项目、数据库、Redis、GitHub、AI、RAG 或 WebSocket 业务。

## 2. 前置条件与执行前检查

开始前必须：

1. 阅读根目录 `AGENTS.md`、`CONTRIBUTING.md`、`README.md`；
2. 阅读 `docs/README.md`、相关架构/ADR 文档和本任务文件；
3. 确认 `DEV-001` 已合并到远程 `develop`；
4. 执行 `git status`、`git branch --show-current`、`git log --oneline -5`；
5. 更新远程引用，并从最新 `develop` 创建 `chore/002-backend-bootstrap`；
6. 检查 `devmate-server/` 当前内容，保留并更新 DEV-001 创建的说明文件；
7. 如有与本任务无关的未提交修改，停止并报告，不覆盖、不删除、不提交这些修改。

禁止直接在 `main` 或 `develop` 上开发、提交或推送。

## 3. 任务目标

完成后必须满足：

- `devmate-server/` 是独立 Maven 工程；
- 使用 Java 21 和稳定的 Spring Boot 3.x；
- 无 MySQL、Redis、Qdrant、MinIO、AI Key 等外部依赖时即可完成测试并启动；
- `GET /api/health` 返回统一响应结构；
- 具备最小的全局异常处理与日志配置；
- 依赖已为后续 MyBatis-Plus、Spring Security、Redis 和 MySQL 任务预留，但本任务不实现相关业务；
- 自动化测试验证应用上下文和健康接口；
- 不改动 `devmate-web/`、`deploy/` 或其他后续阶段内容。

## 4. 技术要求

### 4.1 运行与构建

- Java 21；
- Maven；
- Spring Boot 3.x 的稳定正式版本；
- Maven Compiler `release` 明确设为 21；
- 项目编码为 UTF-8；
- 打包类型为 `jar`；
- 不使用 SNAPSHOT、Milestone 或 Release Candidate 版本；
- 不使用 Spring Boot 4；
- 依赖版本由 Spring Boot BOM 管理时，不重复声明版本；
- MyBatis-Plus 等 BOM 未管理的依赖应只在一个位置集中声明版本。

若仓库没有锁定具体补丁版本，选择当前构建环境能够正常解析的稳定 Spring Boot 3.x，并在 PR 中说明实际版本和选择理由。不要顺带升级任务范围外的工具或依赖。

### 4.2 Maven 依赖

`pom.xml` 仅加入本任务和近期基础设施需要的依赖：

必需基础依赖：

- `spring-boot-starter-web`；
- `spring-boot-starter-validation`；
- `spring-boot-starter-actuator`；
- `spring-boot-starter-test`（test scope）。

预留依赖：

- `mybatis-plus-spring-boot3-starter`；
- `spring-boot-starter-security`；
- `spring-boot-starter-data-redis`；
- MySQL Connector/J（runtime scope）。

约束：

- 不添加 JWT、OpenAI、Spring AI、Qdrant、MinIO、WebSocket、Flyway、Testcontainers 或前端依赖；
- 不添加 Lombok，基础类型使用清晰的 Java 实现；
- 不添加当前代码没有使用、且不属于上述预留清单的依赖；
- 生成并提交 Maven Wrapper，确保没有全局 Maven 时仍可复现构建；
- Wrapper 使用稳定 Maven 3.9.x，不提交本地 Maven 缓存或构建产物。

### 4.3 暂时禁用尚未进入开发阶段的自动配置

由于本任务要求预留 Spring Security、MyBatis-Plus 和 MySQL 依赖，但尚未配置认证与数据库：

- 暂时显式排除 `SecurityAutoConfiguration`，保证健康接口不会被默认登录页或 401 拦截；
- 暂时显式排除 `DataSourceAutoConfiguration`，保证无数据库连接信息时应用仍可启动；
- MyBatis-Plus 自动配置如因缺少 DataSource 未生效，无需创建假数据源或 Mapper；若实际选定版本仍导致启动失败，可最小化排除对应自动配置，并在 PR 说明；
- 不创建“全部放行”的正式 SecurityFilterChain，不添加内存用户，也不写假数据库配置；
- 在 `devmate-server/README.md` 的“临时限制”中记录这些排除项将分别由后续数据库/认证任务移除。

此处只为保持 bootstrap 可运行，不代表生产安全方案。

## 5. 目录与包结构

基础目录：

```text
devmate-server/
├── .mvn/wrapper/
├── mvnw
├── mvnw.cmd
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/devmate/
    │   │   ├── DevMateApplication.java
    │   │   ├── bootstrap/health/controller/
    │   │   │   └── HealthController.java
    │   │   └── common/
    │   │       ├── api/
    │   │       │   └── Result.java
    │   │       └── exception/
    │   │           └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml.example
    │       └── logback-spring.xml
    └── test/
        └── java/com/devmate/
            ├── DevMateApplicationTests.java
            └── bootstrap/health/controller/
                └── HealthControllerTest.java
```

说明：

- `controller`、`service/application`、`repository/infrastructure`、`domain`、`dto`、`vo` 应在未来业务模块内部按需创建；
- 本任务没有 Service、Repository、Domain、DTO 或 VO 的真实用例，因此不要创建空包、空接口、`.gitkeep` 或无意义 `package-info.java`；
- 通用返回与异常放在 `common`，健康探针放在明确的 `bootstrap.health` 模块；
- 若 Maven Wrapper 的标准生成结果包含必要文件，可按官方结构保留，不得加入不相关脚本。

## 6. 实现内容

### 6.1 Spring Boot 工程初始化

- Maven 坐标建议：
  - `groupId`: `com.devmate`
  - `artifactId`: `devmate-server`
  - `name`: `devmate-server`
- 基础包名：`com.devmate`；
- 创建唯一启动类 `DevMateApplication`；
- 启动类保持最小化，不放业务方法、测试数据或外部客户端；
- 应用默认端口为 `8080`，允许通过环境变量覆盖；
- 应用名称设置为 `devmate-server`。

### 6.2 Maven 配置

- 配置 Spring Boot Maven Plugin；
- 配置 Java 21、UTF-8 和标准测试执行；
- 依赖 scope 正确，MySQL 驱动不得进入 test-only；
- `mvn clean test` 必须能在仓库根目录下通过 `mvn -f devmate-server/pom.xml clean test` 执行；
- `./devmate-server/mvnw -f devmate-server/pom.xml clean test` 或在子目录执行 Wrapper 也应可用。

### 6.3 统一返回结构 `Result<T>`

实现最小、可复用的泛型统一响应，JSON 字段固定为：

- `code`：整数业务码；
- `message`：字符串；
- `data`：泛型数据，可为空。

至少提供成功构造方式，使健康接口返回：

```json
{
  "code": 0,
  "message": "success",
  "data": "DevMate server running"
}
```

约束：

- 不加入分页结构、traceId、国际化或复杂错误码体系，这些属于后续通用基座任务；
- 字段不可变，避免公开 setter；
- 不依赖 Lombok；
- 不把 HTTP 状态码与业务码混为同一概念。

### 6.4 全局异常处理骨架

- 创建 `@RestControllerAdvice`；
- 至少处理未预期异常，返回 HTTP 500 和统一 `Result` 错误结构；
- 向客户端返回稳定、非敏感的错误消息，不返回异常类名、堆栈、SQL 或内部路径；
- 服务端使用参数化日志记录异常与请求上下文；
- 不创建业务异常层级、详细错误码目录或认证异常处理；
- 不吞掉异常后返回成功状态；
- 保持实现最小，后续 DEV-004 再完善校验异常、业务异常、traceId 和完整错误码。

### 6.5 健康检查接口

创建：

```http
GET /api/health
```

要求：

- HTTP 状态码为 `200`；
- `Content-Type` 为 JSON；
- 返回内容必须与本任务给出的 JSON 在字段和值上完全一致；
- 不查询数据库、Redis、Qdrant、MinIO 或外部网络；
- Controller 只构造统一成功响应，不包含其他业务逻辑；
- 本任务可同时保留 Actuator 默认健康能力，但不得公开除 `health`、`info` 外的敏感管理端点。

### 6.6 配置文件

`application.yml`：

- 配置应用名、默认端口和必要的管理端点；
- 使用环境变量加安全默认值处理非敏感配置；
- 不固定激活 `dev` profile；
- 不包含数据库、Redis、GitHub、AI 或对象存储凭据；
- 按第 4.3 节处理临时自动配置排除；
- 禁止开启 SQL 输出、debug、详细错误堆栈或全量 Actuator 暴露。

`application-dev.yml.example`：

- 仅展示可复制的本地开发配置结构；
- 不会被 Spring Boot 自动加载；
- 不包含真实连接串、用户名、密码、Token 或看似真实的假密钥；
- 本任务没有数据库环境，因此不要伪造可连接的数据源配置。

### 6.7 基础日志

创建 `logback-spring.xml`：

- 控制台输出时间、级别、线程、logger 和消息；
- 使用 UTF-8；
- 默认日志级别为 `INFO`；
- 不配置本地绝对路径；
- 不在本任务引入文件滚动、JSON encoder、链路追踪 SDK 或外部日志平台；
- 日志中不得输出密钥、环境变量全集或完整请求/响应正文。

### 6.8 后端 README

将 `devmate-server/README.md` 从“尚未初始化”的占位说明更新为真实状态，至少包含：

- 技术要求：JDK 21；
- Maven/Wrapper 构建命令；
- 测试命令；
- 启动命令；
- 健康接口调用示例；
- 当前仅有 bootstrap 能力；
- Security 与 DataSource 自动配置暂时排除的原因和后续移除计划；
- 当前不需要也不应配置数据库、Redis 或 AI 密钥。

不要把规划中的业务功能描述为已经实现。

## 7. 测试要求

必须创建并通过以下测试：

### 7.1 应用上下文测试

- 使用 Spring Boot Test；
- 验证应用上下文在没有数据库、Redis 和外部服务时可以加载；
- 测试不得访问公共网络或真实服务。

### 7.2 健康接口测试

- 使用 MockMvc 或等价的 Spring MVC 测试；
- 验证 `GET /api/health` 返回 HTTP 200；
- 验证 JSON `code` 为 `0`；
- 验证 JSON `message` 为 `success`；
- 验证 JSON `data` 为 `DevMate server running`；
- 验证响应类型兼容 JSON；
- 测试不依赖端口、执行顺序或开发者本机配置。

### 7.3 异常处理验证

至少通过聚焦测试或最小测试控制器验证：

- 未预期异常返回 HTTP 500；
- 响应仍为统一 `Result`；
- 响应不泄露堆栈和内部异常细节。

测试专用 Controller 必须放在测试源码中，不能为了测试向生产代码添加会抛异常的接口。

## 8. 明确禁止

本任务禁止：

- 用户、角色、注册、登录、密码、JWT、刷新令牌；
- 正式 Spring Security 认证/授权逻辑、SecurityFilterChain 或测试用户；
- 数据库表、Flyway migration、实体、MyBatis Mapper、Repository 实现；
- 数据源连接、数据库初始化、SQL 脚本或自动建表；
- Redis 配置类、缓存、限流、消息队列或业务代码；
- OpenAI/Spring AI/其他模型接口、Prompt、Embedding、RAG、Qdrant 客户端；
- GitHub OAuth、仓库同步或 Webhook；
- WebSocket、异步任务、文件上传、MinIO；
- Dockerfile、Docker Compose、Nginx、部署或 CI workflow；
- 前端工程初始化或修改 `devmate-web/`；
- 完整分页、traceId、复杂错误码和完整异常体系；
- 修改 DEV-001、既有 ADR 或与本任务无关的仓库规范；
- 添加真实密钥、真实连接串、测试账号或私有数据；
- 自动合并 PR 或继续执行 DEV-003。

## 9. 预计文件变更

### 9.1 修改

- `devmate-server/README.md`

### 9.2 创建

- `devmate-server/pom.xml`
- `devmate-server/mvnw`
- `devmate-server/mvnw.cmd`
- `devmate-server/.mvn/wrapper/maven-wrapper.properties`
- Maven Wrapper 标准运行所必需的其他文件（仅当所选官方 Wrapper 版本确实需要）
- `devmate-server/src/main/java/com/devmate/DevMateApplication.java`
- `devmate-server/src/main/java/com/devmate/bootstrap/health/controller/HealthController.java`
- `devmate-server/src/main/java/com/devmate/common/api/Result.java`
- `devmate-server/src/main/java/com/devmate/common/exception/GlobalExceptionHandler.java`
- `devmate-server/src/main/resources/application.yml`
- `devmate-server/src/main/resources/application-dev.yml.example`
- `devmate-server/src/main/resources/logback-spring.xml`
- `devmate-server/src/test/java/com/devmate/DevMateApplicationTests.java`
- `devmate-server/src/test/java/com/devmate/bootstrap/health/controller/HealthControllerTest.java`
- 异常处理聚焦测试所需的测试源码（不得增加生产测试接口）

除非为了满足 Maven Wrapper 标准结构或测试的最小需要，不得扩大文件清单。发现现有结构不同时，以 `AGENTS.md` 和已接受 ADR 为准，并在 PR 中解释偏差。

## 10. 验证步骤

完成实现后执行并记录实际结果：

```bash
java -version
mvn -version
mvn -f devmate-server/pom.xml clean test
git diff --check
git status --short
git diff --stat develop...HEAD
```

如环境没有全局 Maven，可改用 Maven Wrapper：

```bash
./devmate-server/mvnw -f devmate-server/pom.xml clean test
```

必须明确报告使用了哪条命令，不得声称未执行的命令已经通过。

随后进行有时间上限的启动冒烟验证：

1. 启动应用；
2. 等待日志明确显示应用启动完成；
3. 请求 `GET http://localhost:8080/api/health`；
4. 验证 HTTP 200 和完整 JSON；
5. 主动终止应用进程；
6. 检查日志没有数据库、Redis、安全登录页或外部服务连接失败。

启动验证必须设置合理超时并确保进程被清理，不能让后台 Java 进程留在执行环境中。

同时检查：

- Maven 依赖树不存在明显的重复 starter 或非预期依赖；
- Git 变更中没有 `target/`、IDE 文件、本地配置或密钥；
- 所有新增文本文件使用 UTF-8 和仓库约定换行；
- 只修改本任务列出的范围。

## 11. 验收标准

只有全部满足才可交付：

1. 所有改动位于 `chore/002-backend-bootstrap`，基于最新 `develop`；
2. Java 21、Maven、稳定 Spring Boot 3.x 配置正确；
3. `mvn clean test` 或明确报告的等价 Wrapper 命令通过；
4. 应用在无 MySQL、Redis、Qdrant、MinIO 和 AI 配置时可启动；
5. `GET /api/health` 返回 HTTP 200 和指定 JSON；
6. 统一 `Result<T>` 字段和值正确，且不依赖 Lombok；
7. 全局异常处理返回 HTTP 500，不泄露内部细节；
8. 应用上下文、健康接口和异常处理测试通过；
9. Security 和 DataSource 的临时排除已在代码/配置与 README 中清楚体现；
10. Actuator 未暴露敏感管理端点；
11. 日志配置可用且不包含敏感数据或本地绝对路径；
12. 没有实现任何禁止的业务能力；
13. 没有提交构建产物、私密配置或无关文件；
14. `git diff --check` 通过；
15. PR 目标为 `develop`，未自动合并。

## 12. Git 与提交要求

使用分支：

```text
chore/002-backend-bootstrap
```

只创建一个范围清晰的最终提交：

```text
feat(server): bootstrap backend project
```

推送任务分支并创建 PR 到 `develop`。

建议 PR 标题：

```text
feat(server): bootstrap backend project
```

PR 描述必须包含：

- 任务背景与范围；
- 实际使用的 Java、Maven、Spring Boot 和 MyBatis-Plus 版本；
- 新增/修改文件；
- 依赖清单及预留依赖说明；
- 临时自动配置排除及移除计划；
- 实际执行的测试和启动验证结果；
- 健康接口响应；
- 风险、限制与回滚方式；
- 明确声明未实现用户、认证、数据库、Redis、AI、RAG、WebSocket 和部署功能。

不得自动合并 PR。

## 13. Codex Cloud 最终回复格式

完成任务后按以下顺序回复：

1. 完成摘要；
2. 分支、commit SHA 和 PR 链接；
3. 修改文件清单；
4. 实际版本与依赖；
5. 测试命令及结果；
6. 启动与健康接口验证结果；
7. 临时排除、已知限制和风险；
8. 需要项目所有者人工确认的事项；
9. 下一任务建议（只说明，不执行）。

完成后停止，等待项目所有者审核。
