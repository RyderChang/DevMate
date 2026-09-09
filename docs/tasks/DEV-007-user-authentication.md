# DEV-007：用户认证基础能力实现

## 1. 任务元信息

| 项目 | 内容 |
| --- | --- |
| 项目名称 | DevMate |
| GitHub 仓库 | `RyderChang/DevMate` |
| 任务编号 | `DEV-007` |
| 任务名称 | 用户认证基础能力实现 |
| 任务类型 | Backend Feature Implementation |
| 目标模块 | `devmate-server/` |
| 基准分支 | `develop` |
| 开发分支 | `feat/007-user-authentication` |
| PR 目标分支 | `develop` |
| Commit Message | `feat(server): implement user authentication` |
| 前置任务 | DEV-004、DEV-005、DEV-006 |

> 本文件是供 Codex Cloud 执行的开发任务规范。执行前必须阅读仓库中的 `AGENTS.md`、`CONTRIBUTING.md`、DEV-004～DEV-006 相关任务文档，并以最新 `develop` 分支中的实际代码、包结构和依赖版本为准。

## 2. 背景说明

DevMate 已完成后端工程初始化、分层架构规范、数据库基础设施以及统一响应和异常处理等基础能力。当前 `develop` 已包含：

- Java 21、Spring Boot 3 与 Maven Wrapper；
- Controller、Service、Mapper、Entity、DTO、VO 分层约定；
- Flyway、MySQL 与 Testcontainers 基础能力；
- `Result<T>`、`ErrorCode`、`BusinessException`、`GlobalExceptionHandler`；
- `HealthController` 与基础测试体系。

DEV-007 在现有基础上实现最小可用的用户注册、登录和 JWT 身份认证能力，为后续商品、订单、消息等业务模块提供统一用户身份基础。本任务不得绕开或重复实现 DEV-006 已建立的统一响应与异常处理机制。

## 3. 开发目标

本任务需要完成以下目标：

1. 通过 Flyway 建立用户数据表，并保证迁移可在项目支持的数据库与测试环境中正常执行；
2. 实现与用户表对应的 `UserEntity` 及必要的 Mapper、Service、DTO、VO；
3. 实现用户名注册，并使用 BCrypt 保存密码密文；
4. 实现用户名密码登录，并签发 JWT；
5. 实现 JWT 生成、解析、校验和请求身份注入；
6. 接入 Spring Security，建立白名单与默认认证规则；
7. 提供最小化的当前用户接口，用于验证认证链路；
8. 使用自动化测试覆盖注册、登录、密码安全和访问控制。

所有接口响应必须使用现有 `Result<T>`，业务失败必须复用现有 `ErrorCode`、`BusinessException` 和 `GlobalExceptionHandler`。如确需扩展错误码，应在现有设计上做最小增量，不得另建一套响应或异常体系。

## 4. 实现范围与约束

### 4.1 分层要求

实现应遵循现有包结构和 DEV-004 规范，职责至少包括：

- Controller：接收参数、触发校验、调用 Service、返回 VO；
- Service：注册、登录、用户查询和密码校验等业务逻辑；
- Mapper：用户持久化与按用户名查询；
- Entity：映射用户表；
- DTO：注册与登录请求；
- VO：登录结果、用户基本信息；
- Security：JWT 工具、认证过滤器、Security 配置及必要的认证上下文组件。

Controller 不得直接访问 Mapper，不得在 Controller 内实现密码校验、JWT 签发等业务逻辑。

### 4.2 依赖要求

在现有 Maven 工程内按需增加并使用：

- Spring Security；
- BCrypt 密码编码能力；
- 与 Java 21、Spring Boot 3 兼容且仍在维护的 JWT 实现。

新增依赖必须版本明确或由 Spring Boot 依赖管理统一管理，不得重复引入功能相同的 JWT/安全库，不得为本任务引入 OAuth2、Redis、Session 集群或其他无关依赖。

### 4.3 安全边界

- 禁止保存或记录明文密码；
- 禁止使用 MD5、SHA 单次散列或自定义简单 Hash 替代密码编码；
- 禁止在日志、异常消息或 API 响应中输出密码、密码密文、JWT 密钥；
- JWT 签名密钥必须通过外部配置注入，不得硬编码在 Java 源码中；
- 配置文件只能提供适合本地开发的占位方式，生产密钥不得提交仓库；
- 对不存在的用户和密码错误，客户端应获得一致的登录失败提示，避免泄露用户名是否存在；
- `UserEntity.password` 不得出现在任何接口响应中；
- JWT 无效、过期、缺失或格式错误时，不得导致未处理异常或 500 响应。

## 5. 数据库设计要求

### 5.1 Flyway Migration

在现有 Flyway migration 目录中新增下一个连续版本的 SQL 文件。执行前检查已有迁移版本，不得覆盖、重命名或修改已经合并的 migration。

创建逻辑上的用户表。若 `user` 在项目当前 MySQL/H2 配置中存在关键字兼容风险，可使用物理表名 `users`，但 Entity 映射、SQL、测试和文档必须保持一致，并在 PR 说明中注明该选择。

### 5.2 字段要求

用户表至少包含：

| 字段 | 要求 |
| --- | --- |
| `id` | 非空主键；采用与项目现有持久化策略一致的合理生成方式 |
| `username` | 非空；唯一；长度应受控 |
| `password` | 非空；长度足以保存 BCrypt 密文；仅保存加密结果 |
| `nickname` | 可空或提供合理默认值；长度应受控 |
| `avatar` | 可空；长度足以保存头像 URL 或资源标识 |
| `role` | 非空；新注册用户使用普通用户默认角色 |
| `create_time` | 非空；记录创建时间 |
| `update_time` | 非空；记录最后更新时间 |

数据库约束至少包括：

- 主键约束；
- `username` 唯一约束或唯一索引；
- 必要的非空约束；
- 与当前数据库命名风格一致的表名、字段名与索引名。

Migration 必须能够在 MySQL 及项目现有自动化测试环境中成功执行。不得通过关闭 Flyway、跳过 migration 或在测试中手工建表来规避兼容问题。

### 5.3 `UserEntity`

创建 `UserEntity` 并准确映射用户表字段。实体应遵循现有 Entity 约定，不承担请求参数或响应对象职责。密码字段只能用于内部认证和持久化，不得直接序列化给客户端。

## 6. API 要求

### 6.1 通用约定

- 基础路径：`/auth`；
- 请求与响应使用 JSON；
- 成功与失败统一使用现有 `Result<T>`；
- DTO 使用 Jakarta Validation 完成基础参数校验；
- 参数校验错误、用户名重复、登录失败等场景应由现有全局异常体系转换为统一响应；
- 不得在 Controller 中返回 Entity。

### 6.2 用户注册

**接口**

`POST /auth/register`

**请求字段**

| 字段 | 必填 | 要求 |
| --- | --- | --- |
| `username` | 是 | 去除首尾空白后不能为空；长度限制应明确且与数据库一致 |
| `password` | 是 | 不能为空；设置合理的最小与最大长度 |
| `nickname` | 否 | 如提供则校验长度；未提供时采用统一的合理默认值 |

**处理要求**

1. 校验请求参数；
2. 检查用户名是否已存在；
3. 使用 `BCryptPasswordEncoder` 编码密码；
4. 保存用户，角色设为普通用户；
5. 返回不含密码字段的用户基本信息。

重复用户名必须返回明确、对客户端安全的业务错误。除应用层检查外，数据库唯一约束仍须生效；并发注册造成的唯一约束冲突也不得以未处理的 500 响应暴露。

### 6.3 用户登录

**接口**

`POST /auth/login`

**请求字段**

| 字段 | 必填 | 要求 |
| --- | --- | --- |
| `username` | 是 | 不能为空 |
| `password` | 是 | 不能为空 |

**处理要求**

1. 根据用户名查询用户；
2. 使用 BCrypt 的 `matches` 能力校验密码；
3. 用户不存在或密码错误时返回统一的登录失败业务错误；
4. 校验成功后生成 JWT；
5. 返回 Token 和用户基本信息。

**响应数据至少包含**

| 字段 | 说明 |
| --- | --- |
| `token` | JWT 字符串 |
| `tokenType` | 固定为 `Bearer`，或遵循项目已有统一约定 |
| `expiresIn` | Token 有效期，单位须在实现与文档中统一 |
| `user` | 当前用户基本信息，不含密码及其他敏感字段 |

用户基本信息至少包含：`id`、`username`、`nickname`、`avatar`、`role`。

### 6.4 当前用户

为验证完整认证链路，实现最小接口：

`GET /auth/me`

要求：

- 必须携带有效的 `Authorization: Bearer <token>`；
- 从 Spring Security 当前认证上下文取得用户身份；
- 返回当前用户基本信息；
- 不得信任客户端额外提交的用户 ID；
- 不实现用户资料修改、头像上传等用户中心功能。

### 6.5 JWT 基础能力

JWT 至少包含可稳定标识用户身份的 Subject，并可按实现需要包含用户 ID、角色等最小 Claim。必须包含签发时间和过期时间。

JWT 组件至少支持：

- Token 生成；
- 签名与有效期校验；
- 用户身份解析；
- 过期、篡改、格式错误等无效 Token 的安全处理。

JWT 有效期和签名密钥应通过配置项注入。命名应遵循项目现有配置风格；测试配置应使用独立的测试值。

### 6.6 JWT 认证过滤器

提供一次请求一次执行的基础过滤器，行为要求：

1. 从 `Authorization` 请求头读取 Bearer Token；
2. Token 存在且有效时解析身份；
3. 加载或构建必要的认证主体；
4. 将认证信息写入 `SecurityContext`；
5. Token 缺失时继续过滤器链，由访问规则决定是否拒绝；
6. Token 无效时清理认证状态，并返回统一、可预期的未认证结果；
7. 不得记录完整 Token。

### 6.7 Spring Security 配置

建立适用于 REST API 的基础 Security 配置：

- 放行 `POST /auth/register`；
- 放行 `POST /auth/login`；
- 放行 `/health`；
- 测试或文档相关端点只有在仓库现有规范明确要求时才可额外放行；
- 其他接口默认要求认证；
- 接入 JWT 认证过滤器，并放在合适的认证过滤器之前；
- 使用无状态认证策略，不创建服务端登录 Session；
- 对当前纯 REST 接口关闭或按无状态 API 方式正确处理 CSRF；
- 未认证请求返回 HTTP 401；已认证但无权限的请求返回 HTTP 403；
- 401/403 响应应尽可能遵循现有统一响应结构。

不得使用已废弃的 Spring Security 配置方式。

## 7. 文件影响范围

实际路径必须以仓库当前结构为准。预计仅允许修改或新增以下类型文件：

| 范围 | 预期内容 |
| --- | --- |
| `devmate-server/pom.xml` | Spring Security、JWT 等必要依赖 |
| `devmate-server/src/main/resources/db/migration/` | 新增用户表 migration |
| `devmate-server/src/main/resources/` | JWT 相关非敏感配置项或占位配置 |
| `devmate-server/src/main/java/com/devmate/**/controller/` | Auth Controller |
| `devmate-server/src/main/java/com/devmate/**/service/` | 用户注册、登录和查询服务 |
| `devmate-server/src/main/java/com/devmate/**/mapper/` | User Mapper |
| `devmate-server/src/main/java/com/devmate/**/entity/` | `UserEntity` |
| `devmate-server/src/main/java/com/devmate/**/dto/` | 注册、登录请求 DTO |
| `devmate-server/src/main/java/com/devmate/**/vo/` | 用户信息、登录结果 VO |
| `devmate-server/src/main/java/com/devmate/**/security/` | JWT、过滤器、Security 配置及认证相关类 |
| 现有错误码/异常相关文件 | 仅在认证业务确需新错误码时做最小扩展 |
| `devmate-server/src/test/**` | 单元测试与集成测试 |
| 测试资源目录 | 测试专用 JWT 配置及数据库配置的必要调整 |

不得修改前端工程，不得创建商品、订单、WebSocket、Redis、OAuth 或管理后台相关文件。不得无理由移动、重命名或重构 DEV-006 已有基础类。

## 8. 测试要求

### 8.1 必测场景

至少覆盖以下场景：

**数据库与持久化**

- Flyway migration 成功执行；
- 用户可正常写入和按用户名查询；
- `username` 唯一约束有效；
- 时间字段和默认角色符合设计。

**注册**

- 合法用户名和密码注册成功；
- 用户名为空时注册失败；
- 密码为空或不符合长度规则时注册失败；
- 重复用户名注册失败；
- 数据库中的密码不等于请求明文；
- 保存后的密码可通过 BCrypt `matches` 验证；
- 注册响应不包含密码或密码密文。

**登录与 JWT**

- 正确用户名和密码登录成功；
- 登录响应包含 JWT 和用户基本信息；
- 错误密码登录失败；
- 不存在的用户名登录失败，且提示不泄露账户存在性；
- 生成的 Token 可解析出预期用户身份；
- 过期、篡改或格式错误的 Token 被拒绝。

**访问控制**

- `/auth/register`、`/auth/login` 和 `/health` 未登录可访问；
- 未携带 Token 访问 `/auth/me` 返回 401；
- 携带有效 Token 访问 `/auth/me` 成功；
- 携带无效或过期 Token 访问受保护接口失败；
- 默认规则下，非白名单接口不会被匿名放行；
- 认证异常不会返回敏感堆栈或 500。

### 8.2 测试实现要求

- 复用 DEV-005/DEV-006 已有测试基础设施；
- 数据库相关集成测试优先使用项目既有 Testcontainers 方案；
- 不得依赖开发者本机已启动的 MySQL；
- 测试数据相互隔离，结果可重复；
- 不得为了让测试通过而关闭 Flyway 或 Security；
- 测试中不得使用生产 JWT 密钥；
- 必须从 `devmate-server/` 目录执行并通过：

```bash
./mvnw test
```

如仓库规范要求从仓库根目录执行，应使用对应 Maven Wrapper 路径，并在 PR 中记录实际执行命令和结果。

## 9. 验收标准

满足以下全部条件方可认为 DEV-007 完成：

- [ ] 从最新 `develop` 创建并使用 `feat/007-user-authentication` 分支；
- [ ] 新增连续版本的 Flyway migration，且未修改历史 migration；
- [ ] 用户表字段、主键、唯一约束和非空约束符合要求；
- [ ] `UserEntity` 与数据库字段映射正确；
- [ ] `POST /auth/register` 可完成注册及参数校验；
- [ ] 重复用户名能够得到统一业务错误；
- [ ] 密码仅以 BCrypt 密文保存，任何响应均不包含密码字段；
- [ ] `POST /auth/login` 可校验密码并返回 JWT 与用户基本信息；
- [ ] JWT 支持生成、解析、签名校验和过期校验；
- [ ] JWT 密钥与有效期使用外部配置，不在 Java 源码中硬编码；
- [ ] JWT 过滤器正确建立 Spring Security 认证上下文；
- [ ] `/auth/register`、`/auth/login`、`/health` 可匿名访问；
- [ ] `/auth/me` 及其他非白名单接口默认需要认证；
- [ ] 未认证访问返回 401，禁止访问返回 403；
- [ ] 认证相关响应复用现有 `Result<T>` 和全局异常机制；
- [ ] 自动化测试覆盖数据库迁移、注册、登录、密码加密、JWT 和访问控制；
- [ ] `./mvnw test` 全部通过；
- [ ] 未实现本任务明确排除的功能；
- [ ] 提交内容聚焦 DEV-007，无无关格式化或重构；
- [ ] 使用规定 Commit Message 创建提交；
- [ ] 创建目标为 `develop` 的 PR，且未自动合并。

## 10. 明确排除范围

DEV-007 不实现：

- 商品模块；
- 订单模块；
- Redis 限流、Token 黑名单或分布式 Session；
- WebSocket；
- 用户中心完整功能；
- 用户资料编辑、头像上传、密码找回或密码修改；
- 管理后台和完整 RBAC 权限体系；
- OAuth、第三方账号或短信登录；
- Refresh Token、Token 主动注销与多设备会话管理；
- 前端登录和注册页面；
- 与认证基础能力无关的重构。

如执行中发现前置任务实现与本文档存在冲突，应优先遵循 `AGENTS.md`、`CONTRIBUTING.md` 和已合并代码的公共约定；不得擅自扩大范围。确需变更任务边界时，应停止执行并在 PR 前请求人工确认。

## 11. Git 流程

1. 获取远程最新状态，并确认 `develop` 已包含 DEV-006；
2. 基于最新 `develop` 创建分支：

```text
feat/007-user-authentication
```

3. 仅实现 DEV-007 定义的内容；
4. 执行完整测试并确认通过；
5. 使用以下 Commit Message：

```text
feat(server): implement user authentication
```

6. 创建 Pull Request，目标分支为 `develop`；
7. PR 描述应说明实现范围、数据库 migration、Security 白名单、JWT 配置方式及测试结果；
8. 不得自动合并，等待人工审核。

## 12. Codex Cloud 执行约束

- 开始编码前必须先检查当前仓库结构、已有依赖、迁移版本及 DEV-006 的响应/异常实现；
- 不得假定任务文档中的预计路径与实际仓库完全一致；
- 不得覆盖用户或其他任务已存在的修改；
- 不得修改 `main`；
- 不得跳过测试；
- 不得自动合并 PR；
- 如发现测试基础设施、JWT 密钥配置策略或表名兼容性存在无法安全判断的冲突，应停止并报告，而不是自行扩大设计。
