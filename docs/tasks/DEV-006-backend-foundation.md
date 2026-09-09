# DEV-006：后端基础业务骨架实现

## 1. 任务元信息

| 项目 | 内容 |
| --- | --- |
| 任务编号 | `DEV-006` |
| 任务名称 | 后端基础业务骨架实现 |
| 任务类型 | Backend Implementation |
| 目标模块 | `devmate-server/` |
| 基准分支 | `develop` |
| 开发分支 | `feat/006-backend-foundation` |
| PR 目标分支 | `develop` |
| Commit message | `feat(server): implement backend foundation` |
| 前置任务 | `DEV-002`、`DEV-004`、`DEV-005` |

> 本文档是供 Codex Cloud 执行的开发任务规范。执行前必须阅读仓库根目录中的 `AGENTS.md`、`CONTRIBUTING.md`，以及与本任务相关的既有任务文档，并遵守其中的约束。若本文档与仓库级规范冲突，以 `AGENTS.md` 和 `CONTRIBUTING.md` 为准。

## 2. 背景说明

DevMate 已完成后端工程初始化、后端基础能力设计和数据库基础设施建设。目前需要将 DEV-004 中确定的分层、统一响应和异常处理规范落实为可运行、可测试的基础业务骨架。

本任务不开发具体业务功能，而是为后续用户、认证、商品、订单等模块提供稳定且一致的后端开发基础。实现应建立在现有 `devmate-server/` 工程之上，复用 DEV-005 已接入的数据库、Flyway 和测试基础设施，不得重复初始化工程或破坏已有配置。

## 3. 开发目标

完成以下目标：

1. 落地统一的 Controller 响应结构 `Result<T>`。
2. 建立可扩展的业务异常及错误码体系。
3. 使用全局异常处理器统一处理参数校验异常、业务异常和未预期的系统异常。
4. 提供 `GET /health` 基础健康检查接口，验证应用启动、组件扫描和响应封装正常。
5. 整理后端基础配置，保留数据库与 MyBatis-Plus 的可配置能力，且不提交任何真实凭据。
6. 规范后端包结构，为后续业务模块提供明确的代码归属位置。
7. 为本任务新增或调整必要的自动化测试，确保基础能力可回归验证。

## 4. 实现范围

### 4.1 统一响应结构

实现通用响应类 `Result<T>`，至少包含以下字段：

- `code`
- `message`
- `data`

应满足以下要求：

- 支持泛型数据。
- 提供清晰、统一的成功与失败构造方式，例如静态工厂方法；避免各 Controller 手工重复拼装响应。
- 成功响应的默认语义与下列结构一致：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

- `data` 的实际 JSON 类型由接口返回值决定；无业务数据时可为 `null`，不强制伪造空对象。
- 本任务新增的 Controller 必须使用 `Result<T>`。同时检查当前后端已有 Controller：如仅包含初始化示例且调整不会扩大任务范围，应统一返回结构；不得借机改造未关联的业务代码。
- 是否使用 Java `record`、普通类、Lombok 或现有项目约定，以 DEV-004 和仓库当前代码风格为准，不得无必要引入新依赖。

### 4.2 基础错误码管理

在 `common/constant` 或 DEV-004 已明确的对应位置建立基础错误码定义。实现形式可采用常量类或枚举，但必须与现有设计保持一致。

至少覆盖：

- 成功
- 参数校验失败
- 业务处理失败
- 系统内部错误

错误码必须集中管理，不得在异常处理器和 Controller 中散落魔法数字或重复字符串。错误码与 HTTP 状态码的对应关系应清晰且可测试。

### 4.3 基础异常体系

实现 `BusinessException`，至少支持：

- 传入自定义业务错误信息。
- 传入或关联统一错误码。
- 保留异常原因的扩展能力（如项目规范需要）。

不得在本任务中创建用户、商品、订单等具体业务异常。

### 4.4 全局异常处理

实现 `GlobalExceptionHandler`，使用 Spring Web 的全局异常处理机制，将异常统一转换为 `Result<?>`。

至少处理以下三类异常：

1. 参数校验异常：覆盖当前技术栈下常见的请求体校验和请求参数校验异常；返回可理解的校验失败信息。
2. 业务异常：捕获 `BusinessException`，保留其错误码和安全的业务提示。
3. 系统异常：捕获未预期异常，记录完整服务端日志，并向客户端返回通用系统错误信息。

技术约束：

- 响应的 HTTP 状态码与响应体错误码应遵循 DEV-004 的既有规范；如果 DEV-004 未规定具体映射，则采用明确、一致且有测试覆盖的映射方案。
- 不得将堆栈、SQL、路径、数据库地址或其他内部实现细节返回给客户端。
- 系统异常必须记录异常堆栈，参数校验失败和可预期业务异常不应滥用 error 级别日志。
- 异常处理顺序应避免宽泛异常处理器吞掉更具体的异常。

### 4.5 健康检查接口

创建 `HealthController`，提供：

```http
GET /health
```

接口要求：

- 无需请求参数和身份认证。
- 返回 `Result<T>` 统一结构。
- `data` 中提供简洁、稳定的运行状态，例如 `status: "UP"`；不得返回敏感配置、环境变量、数据库凭据或详细系统信息。
- 本接口用于验证 Spring Boot 正常启动、Controller 扫描正常以及统一响应序列化正常。
- 不引入 Spring Boot Actuator，仅为健康检查接口新增 Actuator 超出本任务范围；若仓库已存在 Actuator，则不得破坏其配置。

### 4.6 基础配置整理

检查并完善 `devmate-server/src/main/resources/application.yml`。配置至少应覆盖或保留以下方面：

- 服务端口。
- 数据源 URL、用户名和密码的外部化配置。
- MyBatis-Plus 基础配置占位。
- 应用日志级别等基础日志配置。

配置要求：

- 优先使用环境变量占位和安全的非敏感默认值。
- 不得提交真实数据库地址、用户名、密码、令牌或其他秘密信息。
- 必须兼容 DEV-005 已建立的 Flyway 与测试数据库配置。
- 不得覆盖或删除现有测试 profile、Testcontainers、Flyway migration 等基础设施。
- 若默认配置依赖外部 MySQL，应保证自动化测试通过测试配置或容器配置运行，不得为了让测试通过而禁用 Flyway、删除数据库依赖或绕过上下文加载。
- 配置键必须与项目当前实际依赖及 Spring Boot 3 的配置方式一致，不得添加无效或臆造的配置项。

### 4.7 后端目录规范化

在现有 Java 根包下建立或整理以下分层包。不得在 `devmate-server/` 根目录直接创建这些 Java 目录；实际路径必须位于 `src/main/java/<base-package>/` 下。

```text
<base-package>/
├── controller/
├── service/
├── mapper/
├── entity/
├── dto/
├── vo/
├── common/
│   ├── result/
│   ├── exception/
│   └── constant/
└── config/
```

要求：

- `<base-package>` 必须复用当前 Spring Boot 启动类所在的根包，不得自行创建第二套根包。
- Java 包命名全部使用小写。
- 仅创建本任务实际需要的类和包；不要为了保留空目录而提交无意义占位文件。
- `Result<T>`、`BusinessException`、错误码定义、`GlobalExceptionHandler` 和 `HealthController` 应分别放入职责匹配的包中。
- 不得创建空的 Service、Mapper、Entity、DTO 或 VO 示例类。

### 4.8 自动化测试

补充与本任务相匹配的测试，至少验证：

- Spring 应用上下文可以加载。
- `GET /health` 可访问，HTTP 状态符合规范。
- `/health` 响应 JSON 包含 `code`、`message` 和 `data`，且运行状态正确。
- `BusinessException` 经全局异常处理后返回预期错误码和提示。
- 至少一种参数校验异常能被统一处理。
- 未预期系统异常不会向客户端泄露内部异常详情。

测试应通过测试专用 Controller、测试配置或直接调用异常处理器等受控方式覆盖异常分支，不得为测试向生产接口添加无业务意义的“故障触发”端点。

## 5. 文件影响范围

允许修改或新增的范围原则上限定为：

```text
devmate-server/pom.xml
devmate-server/src/main/java/<base-package>/controller/**
devmate-server/src/main/java/<base-package>/common/result/**
devmate-server/src/main/java/<base-package>/common/exception/**
devmate-server/src/main/java/<base-package>/common/constant/**
devmate-server/src/main/java/<base-package>/config/**
devmate-server/src/main/resources/application.yml
devmate-server/src/test/java/**
devmate-server/src/test/resources/**
```

说明：

- `pom.xml` 仅在实现参数校验或测试确实缺少必要依赖时允许最小化调整；优先复用已有依赖和 Spring Boot BOM，不得进行无关升级。
- 若仓库现有包路径或配置拆分与上述范围不同，应遵循现有结构并在 PR 中说明，不得机械复制出重复配置。
- 不得修改 `devmate-web/`。
- 不得修改与 DEV-006 无关的仓库治理、文档或业务文件。
- 除 Codex Cloud 执行本任务所需的实现变更外，不要改写本任务文档。

## 6. 技术要求

- 使用 Java 21 和仓库当前 Spring Boot 3 版本。
- 遵循 DEV-004 已确定的 Controller、Service、Mapper、Entity、DTO、VO 分层及命名规范。
- 使用 Spring 标准 Web 与 Validation 机制完成接口和异常处理。
- 统一响应和异常组件应保持轻量、可复用，不耦合任何具体业务模块。
- 不得重复引入已有依赖，不得擅自更换构建工具、数据库迁移工具或测试方案。
- 不得为了消除编译或测试错误而删除、跳过或禁用已有测试。
- 不得提交构建产物、IDE 配置、日志文件、数据库文件或秘密信息。
- 对公共类和复杂逻辑提供必要注释；避免模板化、无信息量注释。
- 实现前先检查当前工程实际结构和 DEV-004/DEV-005 的落地结果。如任务描述中的类名、包名或配置与已合并代码存在差异，应以保持架构一致、最小改动为原则处理，并在 PR 描述中明确说明。

## 7. 明确禁止范围

DEV-006 不实现以下内容：

- 用户注册或登录。
- JWT 认证。
- Spring Security 权限控制。
- 用户、商品、订单、聊天等业务模块。
- Redis 业务逻辑或限流。
- 数据库业务表、业务 Entity、Mapper 或 Service。
- 新增业务 Flyway migration。
- 前端页面、前端接口封装或前后端业务联调。
- API 文档平台、监控平台、CI/CD 或部署配置。
- 与本任务无关的依赖升级、重构或格式化。

## 8. Git 流程要求

1. 开始前获取远端最新状态，确认 `develop` 已包含 DEV-005 的合并结果。
2. 从最新 `develop` 创建分支：

   ```text
   feat/006-backend-foundation
   ```

3. 仅提交 DEV-006 范围内的变更。
4. 使用规定的提交信息：

   ```text
   feat(server): implement backend foundation
   ```

5. 推送开发分支并创建 Pull Request，目标分支为 `develop`。
6. PR 描述必须概括实现内容、配置策略、测试结果和任何与任务文档存在的合理差异。
7. 不得直接向 `develop` 或 `main` 提交。
8. 不得自动合并 PR；创建后等待人工审核与合并。

## 9. 验收标准

- [ ] 实现通用 `Result<T>`，包含 `code`、`message`、`data`，并提供统一成功/失败构造方式。
- [ ] 本任务新增 Controller 全部返回统一响应结构。
- [ ] 错误码集中管理，无散落的魔法数字和重复错误文本。
- [ ] `BusinessException` 支持自定义错误信息与统一错误码。
- [ ] `GlobalExceptionHandler` 能分别处理参数校验异常、业务异常和系统异常。
- [ ] 系统异常响应不泄露堆栈、SQL、配置或其他内部细节，同时服务端保留完整错误日志。
- [ ] `GET /health` 可访问，并返回统一结构和明确的运行状态。
- [ ] `application.yml` 包含端口、外部化数据源、MyBatis-Plus 和日志基础配置，且兼容 DEV-005。
- [ ] 仓库中未出现真实数据库密码、令牌或其他秘密信息。
- [ ] 基础包结构符合现有根包和 DEV-004 规范，没有无意义空类或占位文件。
- [ ] 未实现禁止范围中的用户、认证、商品、订单、聊天、Redis 或前端功能。
- [ ] 自动化测试覆盖健康接口、统一响应和三类异常处理的关键行为。
- [ ] Maven 构建和全部测试通过，未跳过或禁用已有测试。
- [ ] 变更仅位于允许范围，未包含无关重构或生成文件。
- [ ] PR 从 `feat/006-backend-foundation` 提交至 `develop`，且未自动合并。

## 10. 验证命令

在 `devmate-server/` 目录执行：

```bash
./mvnw test
```

如 Codex Cloud 的执行环境要求从仓库根目录运行，则执行：

```bash
./devmate-server/mvnw -f devmate-server/pom.xml test
```

验证结果必须确认：

- Maven 构建成功。
- Spring Boot 应用上下文启动成功。
- 全部自动化测试通过。
- `/health` 的 MockMvc 或等效集成测试通过，证明接口可访问且返回统一结构。

在测试通过后，可在具备安全本地配置的环境中进行非自动化启动验证；不得因此提交真实凭据：

```bash
./mvnw spring-boot:run
curl -i http://localhost:8080/health
```

若配置的服务端口不是 `8080`，应按实际端口访问。手工启动验证不得替代自动化测试，也不得要求 PR 审核者持有开发者的本地数据库密码。

## 11. 交付结果

Codex Cloud 完成执行后，应提供：

- 实际变更文件清单。
- 核心实现摘要。
- `./mvnw test` 的执行结果。
- `/health` 验证结果。
- Commit SHA 与提交信息。
- Pull Request 链接。
- 未完成项、风险或与本文档的差异说明（如无则明确说明无）。

完成上述交付后停止，不自动合并 Pull Request，等待人工审核。
