# DEV-009：项目空间后端基础能力

## 1. 任务元信息

| 项目 | 内容 |
| --- | --- |
| 任务编号 | `DEV-009` |
| 任务名称 | 项目空间后端基础能力 |
| 所属阶段 | 第一阶段——工程基座、认证与项目空间 |
| 任务类型 | 后端业务能力、数据库迁移、安全边界与自动化测试 |
| 前置任务 | DEV-004、DEV-005、DEV-006、DEV-007、DEV-008 |
| 目标分支 | `develop` |
| 建议实现分支 | `feature/dev-009-project-workspace` |
| 预计主要目录 | `devmate-server/` |
| 明确不涉及 | 前端、AI、RAG、GitHub 同步、文件上传、项目成员协作 |

---

## 2. 任务背景

DevMate 的第一阶段目标是完成工程基座、认证与项目空间。目前仓库已经具备：

- Java 21 与 Spring Boot 3 后端工程；
- Vue 3 与 Vite 前端工程基座；
- 统一响应、异常处理、OpenAPI 与结构化日志基础；
- MySQL、Flyway、MyBatis-Plus 与 Testcontainers 测试基础设施；
- 用户注册、登录、JWT 身份认证与当前用户查询；
- `USER`、`ADMIN` 角色及 `user`、`system` 权限；
- 基于 Spring Security 的角色授权和权限授权；
- 从数据库实时加载权限的认证链路。

现有认证链路已经能够回答“当前请求是谁”和“当前用户拥有哪些角色与权限”，但系统还没有可以承载后续能力的项目空间。AI 对话、知识库、GitHub 仓库分析、代码审查和测试生成都必须归属于某个项目，并且必须以项目所有者为安全隔离边界。

本任务需要实现最小但完整的项目空间后端能力，使经过认证的普通用户可以：

1. 创建自己的项目；
2. 分页查看自己的项目；
3. 查看自己的单个项目；
4. 修改自己的项目；
5. 删除自己的项目；
6. 无法读取、修改或删除其他用户的项目。

本任务的核心不是通用 CRUD，而是建立后续所有项目资源都必须遵循的所有权模型：

```text
Authenticated User
        ↓
userId from trusted JWT principal
        ↓
Project ownership query
        ↓
Project resource
```

客户端提交的任何 `ownerUserId`、用户名、角色或权限字段都不能成为所有权判定依据。项目所有者只能来自 Spring Security 中已经验证的 `CurrentUser.id()`。

---

## 3. 仓库现状与实现前提

实现 DEV-009 前必须再次检查实际仓库，不得只依赖本文档中的文件列表或历史描述。

当前已知基线如下：

- 后端根目录为 `devmate-server/`；
- 当前最大 Flyway migration 为 `V3__create_and_initialize_rbac.sql`；
- 用户物理表名为 `users`，主键类型为自增 `BIGINT`；
- `CurrentUser` 已包含可信的用户 ID、用户名和角色列表；
- 默认安全策略仅放行注册、登录和健康检查，其他接口需要认证；
- `USER` 角色默认具有 `user` authority；
- 统一响应类型为 `Result<T>`；
- 业务异常使用 `BusinessException` 与 `ErrorCode`；
- MyBatis 已开启 `snake_case` 到 `camelCase` 映射；
- 数据库会话按 UTC 工作；
- 数据库集成测试使用 MySQL 8.4.6 Testcontainers；
- 项目业务模块、项目表和项目 API 均尚未建立。

如果实现时上述事实已经变化，应以最新 `develop`、构建文件和已接受 ADR 为准，并同步修订本任务文档或在 PR 中说明差异。不得在事实不一致时机械实现。

---

## 4. 任务目标

DEV-009 必须完成以下目标：

1. 通过新的 Flyway migration 创建项目表及必要约束和索引；
2. 建立“一个项目属于且只属于一个创建者”的所有权模型；
3. 创建职责清晰的项目业务模块；
4. 提供创建、分页列表、详情、更新和删除 API；
5. 所有项目查询和写操作都强制带入当前用户 ID；
6. 防止通过修改路径参数、请求体或查询参数访问他人项目；
7. 列表接口使用统一、受限、稳定的分页结构；
8. 项目删除采用软删除，删除后对普通业务查询不可见；
9. 保持现有认证、RBAC、健康检查和统一异常能力不回归；
10. 使用单元测试、MockMvc 和 MySQL/Testcontainers 集成测试覆盖验收场景；
11. 为后续对话、知识库和 GitHub 集成提供稳定的 `projectId` 与所有权基础；
12. 不提前实现任何第二阶段或更后阶段能力。

---

## 5. 本任务冻结的设计决策

以下决定属于 DEV-009 的范围边界。实现中不得无说明地采用不同语义。

### 5.1 项目物理表名

使用复数物理表名：

```text
projects
```

原因：

- 与现有 `users` 表命名保持一致；
- 避免 `project` 与数据库、工具或未来 SQL 方言关键字发生潜在冲突；
- Entity、Mapper SQL、migration 和测试必须统一使用 `projects`。

### 5.2 所有权模型

MVP 中一个项目只有一个所有者：

```text
projects.owner_user_id → users.id
```

本任务不创建项目成员表，不支持转让所有权，不支持共享项目，也不支持组织或租户。

即使当前用户具有 `ADMIN` 角色或 `system` authority，也不得自动绕过项目归属检查。管理员跨用户访问属于后续管理与审计能力，必须通过独立任务设计。

验证管理员所有权边界时，测试用户应保留注册时获得的 `USER` 角色并额外分配 `ADMIN` 角色，使其同时拥有 `user` authority。这样访问他人项目得到的 404 才能证明所有权校验有效，而不是因为缺少 `user` authority 提前得到 403。

### 5.3 项目名称唯一性

项目名称在 DEV-009 中不设置唯一约束。同一用户可以创建同名项目，不同用户也可以创建同名项目。

原因：

- 当前产品基线没有定义项目名称唯一语义；
- 后续项目可能通过 GitHub 仓库、内部编码或其他标识区分；
- 不应在需求未确认时引入影响用户行为的唯一约束。

### 5.4 删除语义

项目删除采用软删除：

- `deleted = 0`：正常项目；
- `deleted = 1`：已删除项目；
- `delete_time`：删除时间，未删除时为 `NULL`。

本任务不提供恢复接口、回收站接口或物理清理任务。软删除是为了避免未来项目下挂对话、文档、索引和审查结果后发生不可恢复的数据误删；恢复与保留策略由后续独立任务定义。

所有普通项目查询必须显式排除 `deleted = 1` 的记录。不得只依赖 Controller 隐藏，也不得让已删除项目继续被详情、更新或重复删除接口访问。

### 5.5 分页语义

项目列表采用一页起始的分页参数：

```text
page = 1
pageSize = 20
```

约束：

- `page` 最小为 `1`；
- `page` 最大为 `10000`，避免无界深分页；
- `pageSize` 最小为 `1`；
- `pageSize` 最大为 `100`；
- 默认 `pageSize` 为 `20`；
- 排序固定为 `update_time DESC, id DESC`；
- 本任务不允许客户端传入任意排序字段或原始 SQL 排序表达式。

固定的二级 `id DESC` 排序用于保证多个项目更新时间相同时结果顺序稳定。

### 5.6 不存在与越权的响应语义

对于项目详情、更新和删除操作：

- 项目不存在：返回 `404 PROJECT_NOT_FOUND`；
- 项目已软删除：返回 `404 PROJECT_NOT_FOUND`；
- 项目存在但属于其他用户：同样返回 `404 PROJECT_NOT_FOUND`。

不得通过 `403`、不同错误消息、不同响应体或额外查询结果向调用者泄露其他用户项目是否存在。

以下场景仍按安全基础设施处理：

- 未提供有效认证：`401 UNAUTHORIZED`；
- 已认证但没有 `user` authority：`403 FORBIDDEN`。

### 5.7 更新并发语义

DEV-009 不实现版本号或乐观锁。并发更新采用数据库最后提交者生效的基础语义。

如果后续出现多人协作、自动同步或编辑冲突需求，应通过独立任务增加版本字段或条件更新，不得在本任务中提前引入。

---

## 6. 开发范围

### 6.1 数据库 migration

在现有最大版本之后新增：

```text
V4__create_projects_table.sql
```

不得修改、删除、重命名或重新格式化 `V1`、`V2`、`V3` migration。

### 6.2 `projects` 表结构

字段至少包括：

| 字段 | 类型与约束 | 说明 |
| --- | --- | --- |
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 项目主键 |
| `owner_user_id` | `BIGINT NOT NULL` | 项目所有者，对应 `users.id` |
| `name` | `VARCHAR(100) NOT NULL` | 规范化后的项目名称 |
| `description` | `VARCHAR(1000) NULL` | 可选项目说明 |
| `deleted` | `TINYINT(1) NOT NULL DEFAULT 0` | 软删除标记 |
| `create_time` | `TIMESTAMP(6) NOT NULL` | UTC 创建时间 |
| `update_time` | `TIMESTAMP(6) NOT NULL` | UTC 更新时间 |
| `delete_time` | `TIMESTAMP(6) NULL` | UTC 删除时间 |

表必须包含：

- 明确命名的主键约束；
- `owner_user_id` 到 `users.id` 的外键；
- 明确的外键删除策略；
- 支持所有者列表查询的复合索引；
- 对 `deleted` 值的有效性约束；
- 合理的时间默认值和更新时间规则。

建议约束和索引名称：

```text
pk_projects
fk_projects_owner_user
ck_projects_deleted
idx_projects_owner_deleted_updated_id
```

推荐复合索引字段顺序：

```text
(owner_user_id, deleted, update_time, id)
```

实现者必须使用 `EXPLAIN` 或可验证的数据库元数据说明该索引与实际列表查询模式匹配，不得仅因为文档列出名称就机械创建无效索引。

### 6.3 外键删除策略

`projects.owner_user_id` 建议使用：

```sql
ON DELETE RESTRICT
```

原因：

- 当前没有用户删除业务；
- 项目是未来对话、知识和审查数据的聚合根；
- 删除用户时不应级联物理删除全部项目数据；
- 用户注销、数据导出和数据清理应由后续任务定义完整策略。

如果实现时已有经过确认的用户删除策略或 ADR 与此冲突，应停止并请求项目所有者确认，不得自行改为 `CASCADE`。

### 6.4 时间字段

时间继续遵循项目基线：

- MySQL 会话使用 UTC；
- `create_time` 和 `update_time` 使用微秒精度；
- `update_time` 在项目更新和软删除时更新；
- `delete_time` 只在软删除时写入；
- API 使用 ISO 8601 返回时间；
- 不在 Controller 中使用本机默认时区计算数据库时间。

### 6.5 项目业务模块

项目是新的独立业务模块。新增代码应按领域模块组织，而不是继续扩大根级巨型目录。

建议结构：

```text
com.devmate.project
├── controller
│   └── ProjectController
├── dto
│   ├── CreateProjectRequest
│   └── UpdateProjectRequest
├── entity
│   └── ProjectEntity
├── mapper
│   └── ProjectMapper
├── service
│   └── ProjectService
└── vo
    ├── ProjectResponse
    └── ProjectPageResponse（如分页结构不放在 common）
```

如果已有通用分页类型，应复用。若不存在，可以增加职责明确的通用分页响应类型，例如：

```text
com.devmate.common.api.PageResult<T>
```

不要为了形式创建空的 Domain、Repository 或 Application 层。也不要在 DEV-009 中迁移或重构现有认证、RBAC 包结构。

### 6.6 `ProjectEntity`

Entity 必须准确映射 `projects` 表，至少包含：

```text
id
ownerUserId
name
description
deleted
createTime
updateTime
deleteTime
```

要求：

- 使用现有 MyBatis-Plus 与普通 Java 类风格；
- 主键继续采用数据库自增 `BIGINT`；
- 不引入 Lombok；
- Entity 只用于持久化，不直接作为请求或响应对象；
- 不允许客户端序列化控制 `ownerUserId`、`deleted` 或时间字段；
- 如果使用 MyBatis-Plus 逻辑删除注解，仍必须确保所有权条件不会被遗漏；
- 优先通过职责明确的 Mapper SQL 同时表达所有权和删除状态，不依赖开发者记忆在 Service 中二次过滤。

### 6.7 `ProjectMapper`

Mapper 只负责数据访问，不负责认证判断、HTTP 状态或业务异常转换。

至少提供：

1. 插入项目；
2. 根据 `projectId + ownerUserId + deleted = 0` 查询详情；
3. 根据 `ownerUserId + deleted = 0` 查询总数；
4. 根据 `ownerUserId + deleted = 0` 分页查询；
5. 根据 `projectId + ownerUserId + deleted = 0` 更新项目；
6. 根据 `projectId + ownerUserId + deleted = 0` 执行软删除。

安全要求：

- 项目详情、更新和删除 SQL 必须在同一条数据访问条件中包含 `owner_user_id`；
- 不得先按 `id` 查询全部项目，再在 Controller 中比较所有者；
- 不得提供被 Controller 直接调用的无所有权限制查询；
- 所有动态值使用 MyBatis 参数绑定；
- 不拼接来自请求的 SQL 片段；
- 分页 `offset` 必须安全计算并设置合理上限；
- 列表和 count 查询必须使用一致的可见性条件；
- 列表字段只选择响应所需列，禁止无理由使用 `SELECT *`。

允许 Mapper 继承 `BaseMapper<ProjectEntity>` 以遵循现有工程技术栈，但项目业务 Service 不得使用无所有权条件的 `selectById`、`updateById` 或 `deleteById` 实现资源访问。

### 6.8 项目应用服务

实现职责清晰的 `ProjectService`。

建议公开用例：

```java
ProjectResponse create(Long currentUserId, CreateProjectRequest request);

PageResult<ProjectResponse> list(Long currentUserId, int page, int pageSize);

ProjectResponse get(Long currentUserId, Long projectId);

ProjectResponse update(Long currentUserId, Long projectId, UpdateProjectRequest request);

void delete(Long currentUserId, Long projectId);
```

服务要求：

- `currentUserId` 不能为空；
- `currentUserId` 只能来自已认证 principal；
- 创建时强制将 `ownerUserId` 设置为 `currentUserId`；
- 创建、更新和删除具有明确事务边界；
- 只读查询不得持有不必要的写事务；
- 更新和删除受影响行数为 `0` 时转换为 `PROJECT_NOT_FOUND`；
- 创建插入失败时使用现有安全的内部错误规范；
- 不把 SQL、数据库异常细节或其他用户 ID 暴露给客户端；
- Entity 到 VO 的转换位于 Service 或明确的模块内转换组件；
- Controller 不包含资源归属规则、分页 SQL 或实体转换逻辑。

### 6.9 输入规范化

项目名称：

- 必填；
- 去除首尾空白后不能为空；
- 规范化后长度为 `1..100`；
- 保留名称中间的合法空格；
- 不自动改写大小写；
- 不根据名称生成永久业务标识；
- 不接受纯空白名称。

项目描述：

- 可选；
- 非空时去除首尾空白；
- 规范化后最大 `1000` 个字符；
- 空字符串或纯空白可统一保存为 `NULL`；
- 不接受客户端提交 HTML 后由服务端当可信内容渲染；
- 本任务只保存纯文本语义，不实现富文本或 Markdown 渲染。

长度限制以规范化后的值为准。请求进入业务用例后必须先执行上述规范化，再检查名称和描述长度；不得静默截断超长内容。DTO 可以承担空值和结构初检，但不得在未经规范化的原始字符串上直接使用 `@Size` 作为最终长度判断。若采用自定义约束、反序列化器或其他方案提前完成规范化，必须确保创建与更新使用完全一致的规则，并通过测试证明校验发生在同一规范化结果上。

### 6.10 通用分页响应

如果仓库尚无分页响应结构，新增统一类型，字段建议为：

```json
{
  "page": 1,
  "pageSize": 20,
  "total": 2,
  "items": []
}
```

类型要求：

- `page`：当前页，从 `1` 开始；
- `pageSize`：实际使用的页大小；
- `total`：符合当前过滤条件的记录总数，使用 `long`；
- `items`：当前页数据，无数据时为空数组而不是 `null`。

不要在本任务中加入未经需求确认的字段，例如 `totalPages`、`hasNext`、游标、排序对象或 HATEOAS 链接。后续如确有需要可兼容扩展。

### 6.11 跨模块公开边界

项目模块必须为后续对话、知识库、GitHub、审查和测试生成模块提供公开的应用服务边界，用于验证：

```text
currentUserId 是否拥有仍然有效的 projectId
```

可以在 `ProjectService` 中提供职责明确的方法，也可以在确有复用价值时增加小型 `ProjectAccessService`。建议语义：

```java
void requireOwnedActiveProject(Long currentUserId, Long projectId);
```

或返回不包含持久化细节的最小项目引用。要求：

- 校验必须同时包含项目 ID、所有者 ID 和 `deleted = 0`；
- 不向其他模块返回 `ProjectEntity`；
- 不暴露 `ProjectMapper`；
- 不允许调用方自行传入“跳过所有权”标记；
- 不为 ADMIN 增加隐式绕过；
- 验证失败统一抛出 `PROJECT_NOT_FOUND`；
- 当前项目 API 应复用相同的所有权语义，避免形成两套实现；
- 必须有单元测试覆盖成功、他人项目、已删除项目和不存在项目。

后续业务模块只能依赖该公开应用服务边界，不得跨模块直接调用 `ProjectMapper`。

---

## 7. API 契约

所有接口继续返回 `Result<T>`，Content-Type 为 JSON。除特别说明外，所有项目接口都要求：

```java
@PreAuthorize("hasAuthority('user')")
```

可以在 Controller 类级别设置统一方法授权，也可以逐方法设置，但测试必须证明所有项目端点都受到保护。

### 7.1 创建项目

```http
POST /projects
Authorization: Bearer <token>
Content-Type: application/json
```

请求示例：

```json
{
  "name": "DevMate",
  "description": "AI-assisted software development workspace"
}
```

成功响应：HTTP `200`

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 10001,
    "name": "DevMate",
    "description": "AI-assisted software development workspace",
    "createTime": "2026-09-12T05:00:00Z",
    "updateTime": "2026-09-12T05:00:00Z"
  }
}
```

要求：

- 所有者由 `CurrentUser.id()` 强制写入；
- 请求 DTO 不定义可写的所有者字段；
- 即使 JSON 额外包含 `ownerUserId`，也不能改变实际所有者；
- `deleted` 初始值必须为 `0`；
- `delete_time` 初始值必须为 `NULL`；
- 返回值不得暴露数据库内部删除标记；
- 创建响应不得包含其他用户信息、角色或 Token。

### 7.2 分页查询当前用户项目

```http
GET /projects?page=1&pageSize=20
Authorization: Bearer <token>
```

成功响应：HTTP `200`

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 2,
    "items": [
      {
        "id": 10002,
        "name": "Recent project",
        "description": null,
        "createTime": "2026-09-12T06:00:00Z",
        "updateTime": "2026-09-12T06:00:00Z"
      },
      {
        "id": 10001,
        "name": "Earlier project",
        "description": "Example",
        "createTime": "2026-09-12T05:00:00Z",
        "updateTime": "2026-09-12T05:00:00Z"
      }
    ]
  }
}
```

要求：

- 只返回当前用户项目；
- 排除软删除项目；
- 无项目时 `total = 0` 且 `items = []`；
- 顺序固定为 `update_time DESC, id DESC`；
- 超出总页数时返回空列表，不返回 404；
- 不接受 `ownerUserId` 查询参数切换所有者；
- 不实现跨用户管理员列表；
- 不返回密码、JWT、用户角色或其他不相关字段。

### 7.3 查询项目详情

```http
GET /projects/{projectId}
Authorization: Bearer <token>
```

成功响应：HTTP `200`，`data` 使用 `ProjectResponse`。

失败语义：

- 无有效认证：`401`；
- 无 `user` authority：`403`；
- 项目不存在、已删除或不属于当前用户：`404 PROJECT_NOT_FOUND`。

### 7.4 更新项目

```http
PUT /projects/{projectId}
Authorization: Bearer <token>
Content-Type: application/json
```

请求示例：

```json
{
  "name": "DevMate Platform",
  "description": "Updated project description"
}
```

DEV-009 使用完整资源更新语义：

- `name` 必填；
- `description` 可为 `null`；
- 未提供 `description` 与显式 `null` 均表示清空描述；
- 不实现 JSON Patch；
- 不允许修改 `id`、`ownerUserId`、删除状态或时间字段；
- 更新成功后返回更新后的 `ProjectResponse`；
- 项目不存在、已删除或不属于当前用户时返回相同的 404。

### 7.5 删除项目

```http
DELETE /projects/{projectId}
Authorization: Bearer <token>
```

成功响应：HTTP `200`

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

要求：

- 只执行软删除；
- 同时更新 `deleted`、`delete_time` 和 `update_time`；
- 删除后列表不可见；
- 删除后详情返回 404；
- 删除后更新返回 404；
- 对同一项目重复删除返回 404；
- 删除他人项目返回 404；
- 本任务不级联删除任何用户、角色或 RBAC 数据；
- 当前尚无项目子资源，不创建伪造的级联清理逻辑。

---

## 8. DTO 与 VO

### 8.1 `CreateProjectRequest`

字段：

```text
name
description
```

建议使用 Jakarta Validation 做请求结构初检，并由 Service 对规范化后的值完成最终长度校验：

- `name`：DTO 使用 `@NotBlank`；去除首尾空白后再次确认非空且长度为 `1..100`；
- `description`：允许为 `null`；非空时去除首尾空白，空白结果转为 `null`，否则长度不得超过 `1000`；
- 不得直接对原始字符串使用 `@Size(max = 100)` 或 `@Size(max = 1000)` 作为最终判断，因为这会在规范化前拒绝本应合法的输入；
- 规范化后的长度违规继续返回统一的 HTTP `400` 参数错误，不得依赖数据库截断或异常完成校验。

DTO 不得包含：

```text
id
ownerUserId
deleted
createTime
updateTime
deleteTime
```

### 8.2 `UpdateProjectRequest`

字段和基础校验与创建请求一致。更新使用完整资源更新语义，不需要额外定义“字段是否出现”的复杂状态。

### 8.3 `ProjectResponse`

至少返回：

```text
id
name
description
createTime
updateTime
```

默认不返回 `ownerUserId`，因为所有普通接口只操作当前用户自己的项目，返回该字段没有必要。若实现者认为后续前端确实需要所有者 ID，必须在 PR 中说明用途，并确认不会形成未来跨用户接口的错误依赖。

不得返回：

```text
deleted
deleteTime
数据库实体对象
用户密码
JWT
角色或权限内部关系
```

---

## 9. 错误码与异常处理

在现有 `ErrorCode` 中最小新增：

```text
PROJECT_NOT_FOUND
```

建议定义：

```text
code: 404
message: Project not found
httpStatus: 404 NOT_FOUND
```

如果项目错误码体系在实现前已经演进为独立业务编号，应遵循最新统一规则，不得制造第二套并行错误码。

错误处理要求：

- 参数校验失败：沿用 `INVALID_PARAMETER` 和 HTTP 400；
- 未认证：沿用 `UNAUTHORIZED` 和 HTTP 401；
- 缺少 `user` authority：沿用 `FORBIDDEN` 和 HTTP 403；
- 不可见项目：使用 `PROJECT_NOT_FOUND` 和 HTTP 404；
- 未预期数据库错误：返回安全的 `INTERNAL_ERROR`，日志记录异常但不向客户端泄露 SQL 或连接信息；
- 不使用 `null` 假装成功；
- 不捕获并吞掉数据库异常；
- 不把其他用户的 ID、项目名或存在状态写入面向客户端的错误消息。

---

## 10. 安全要求

### 10.1 可信身份来源

Controller 必须通过 Spring Security 获取 `CurrentUser`：

```java
@AuthenticationPrincipal CurrentUser currentUser
```

只允许使用：

```java
currentUser.id()
```

作为项目所有权条件。不得从以下来源接受所有者身份：

- 请求体；
- 路径参数；
- 查询参数；
- 自定义客户端 Header；
- 用户名字符串；
- 前端本地状态；
- JWT 外未经现有安全链路验证的数据。

### 10.2 IDOR 防护

每个基于项目 ID 的操作必须同时校验：

```text
project.id = requestedProjectId
AND project.owner_user_id = currentUserId
AND project.deleted = 0
```

必须有自动化测试证明：

- 用户 A 不能查询用户 B 的项目；
- 用户 A 不能修改用户 B 的项目；
- 用户 A 不能删除用户 B 的项目；
- 用户 A 的列表不包含用户 B 的项目；
- 修改请求中的伪造 `ownerUserId` 不会转移项目；
- 管理员身份默认也不能绕过所有权。

### 10.3 方法授权

所有项目端点要求 `user` authority。不能仅依赖 URL 已经被 `authenticated()` 覆盖，因为 DEV-009 需要显式表达该模块对 RBAC 权限的依赖。

方法授权应位于公开 Controller 或应用服务边界，不应只放在前端或测试控制器中。

### 10.4 日志与敏感数据

允许记录：

- traceId；
- HTTP 方法和 URI；
- 安全的业务错误码；
- 必要的内部项目 ID 和用户 ID，但避免在高频 INFO 日志中重复输出。

禁止记录：

- 完整 JWT；
- Authorization Header；
- 密码或密钥；
- 完整项目描述；
- 未来项目源码或文档内容；
- 数据库连接凭据；
- 面向客户端返回的堆栈信息。

### 10.5 批量赋值与越权字段

不得将请求 JSON 直接绑定到 `ProjectEntity`。必须使用 DTO，并由 Service 显式复制允许修改的字段。

即使当前 Jackson 配置会忽略未知字段，也必须有测试证明以下字段不能生效：

```json
{
  "ownerUserId": 999,
  "deleted": true,
  "id": 999,
  "createTime": "2000-01-01T00:00:00Z"
}
```

---

## 11. 事务与一致性

### 11.1 创建事务

项目插入和响应构建应处于清晰的创建用例中。插入失败时不得返回成功或残留半完成状态。

### 11.2 更新事务

更新操作必须：

1. 使用当前用户 ID 和项目 ID 约束更新目标；
2. 更新规范化后的名称和描述；
3. 更新 `update_time`；
4. 重新读取或可靠构建更新后的响应；
5. 确保整个用例具有事务边界。

更新后重新读取时仍必须带所有权和未删除条件。

### 11.3 删除事务

删除操作必须在同一事务中写入：

```text
deleted = 1
delete_time = current UTC time
update_time = current UTC time
```

不得出现 `deleted = 1` 但 `delete_time = NULL` 的正常删除结果。

### 11.4 远程调用

本任务不包含 AI、GitHub、对象存储或其他远程调用。不得为了预留未来能力把远程调用接口或空实现放进项目事务。

---

## 12. 测试要求

必须补充单元测试、Controller/API 测试和 MySQL/Testcontainers 集成测试。测试不得依赖执行顺序、真实时间、公共网络或开发者机器上的既有数据。

### 12.1 Migration 测试

至少验证：

- 空数据库可以顺序执行 V1 至 V4；
- `projects` 表创建成功；
- 所有要求字段存在且类型、可空性和默认值合理；
- 主键为自增 `BIGINT`；
- `owner_user_id` 外键指向 `users.id`；
- 外键删除策略为 `RESTRICT` 或等价的非级联策略；
- `deleted` 默认值为 `0`；
- 无效删除标记被约束拒绝；
- 复合索引存在且字段顺序正确；
- 删除拥有项目的用户会被外键阻止；
- V1、V2、V3 checksum 未因本任务发生变化；
- Flyway `validate` 成功。

### 12.2 Mapper 集成测试

至少验证：

- 插入项目后生成主键；
- 按所有者查询详情成功；
- 使用其他用户 ID 查询同一项目返回空；
- 按所有者统计只计算未删除项目；
- 分页只返回当前所有者项目；
- 分页排除软删除项目；
- 分页按 `update_time DESC, id DESC` 稳定排序；
- 更新只能命中当前用户的未删除项目；
- 他人更新受影响行数为 `0`；
- 软删除只能命中当前用户的未删除项目；
- 重复删除受影响行数为 `0`；
- 软删除后详情查询不可见。

### 12.3 Service 单元测试

使用 Mockito 或现有测试工具覆盖：

- 创建时使用当前用户 ID，而不是请求中的任何字段；
- 名称和描述规范化；
- 原始值仅因首尾空白超过字段上限、但规范化后合法时可以通过；
- 规范化后名称超过 `100` 或描述超过 `1000` 时返回统一参数错误；
- 空白描述保存为 `null`；
- 列表返回稳定分页结构；
- 无数据时 items 为空集合；
- 详情找不到时抛出 `PROJECT_NOT_FOUND`；
- 更新找不到或越权时抛出相同异常；
- 删除找不到、已删除或越权时抛出相同异常；
- Entity 转换不会泄露删除字段；
- Mapper 写入异常不会被静默吞掉。

### 12.4 API 与安全集成测试

至少覆盖以下矩阵：

| 场景 | 预期 |
| --- | --- |
| 未登录创建项目 | `401` |
| 未登录查询列表 | `401` |
| 已认证但无 `user` authority | `403` |
| 普通用户创建项目 | `200` |
| 创建请求名称为空 | `400` |
| 创建请求名称超长 | `400` |
| 创建请求描述超长 | `400` |
| 请求伪造 `ownerUserId` | 实际所有者仍为当前用户 |
| 用户只查看自己的项目列表 | `200` 且不含他人项目 |
| 空列表 | `total=0`、`items=[]` |
| 非法 page 或 pageSize | `400` |
| page 超过 10000 | `400` |
| pageSize 超过 100 | `400` |
| 超出总页数 | `200` 且 `items=[]` |
| 当前用户查看自己的项目 | `200` |
| 当前用户查看他人项目 | `404` |
| 当前用户修改自己的项目 | `200` |
| 当前用户修改他人项目 | `404` 且数据不变 |
| 当前用户删除自己的项目 | `200` |
| 当前用户删除他人项目 | `404` 且数据未删除 |
| 删除后再次查看 | `404` |
| 删除后再次修改 | `404` |
| 重复删除 | `404` |
| 同时拥有 USER 与 ADMIN 角色的用户访问他人项目 | `404` |
| 非法 JWT | `401` |

### 12.5 分页边界测试

分页测试至少构造足够数据验证：

- 第一页和第二页没有重复项目；
- 同一更新时间的项目按 ID 稳定排序；
- `total` 不受页码影响；
- 其他用户项目不计入 `total`；
- 已删除项目不计入 `total`；
- `page=1,pageSize=1` 正常；
- 最大 `page=10000` 能安全计算 offset；
- 最大 `pageSize=100` 正常；
- `page=0`、负数、`10001`、`pageSize=0`、负数和 `101` 被拒绝；
- offset 计算使用足够宽的整数类型，不发生溢出后变成负数。

### 12.6 回归测试

必须确保以下既有能力继续正常：

- 健康检查；
- 用户注册；
- 用户登录；
- `/auth/me`；
- JWT 生成和校验；
- RBAC 角色加载；
- 数据库权限实时加载；
- `/admin/ping` 的 ADMIN/USER 授权差异；
- 统一参数错误；
- 统一业务异常；
- 现有 Flyway migration；
- 原有全部测试。

最终必须在 `devmate-server/` 执行：

```bash
./mvnw test
```

Windows 可执行：

```powershell
.\mvnw.cmd test
```

全部测试必须通过，不得跳过 Testcontainers 测试，也不得使用 H2 结果代替 MySQL 8 兼容性结论。

---

## 13. API 文档要求

如果项目当前通过 springdoc 自动生成 OpenAPI，新增接口、请求和响应类型必须可被正确发现。

至少检查：

- `/v3/api-docs` 包含五个项目端点；
- 请求字段名称、必填规则和长度约束正确；
- 分页参数默认值与边界可理解；
- `ProjectResponse` 不暴露 Entity 内部字段；
- Bearer 认证要求与现有 OpenAPI 配置一致；
- 不为了让 Swagger 匿名访问而放宽现有安全白名单。

本任务不要求编写独立静态 API 网站，但 API 行为变化必须在必要的后端 README 或 API 文档中同步说明。

---

## 14. 文档同步要求

实现 DEV-009 时至少评估并按事实更新：

```text
devmate-server/README.md
README.md
docs/README.md
```

当前根 README 和部分后端说明可能仍把已初始化能力描述为“尚未实现”。实现 PR 必须只把实际已完成的能力标记为完成，不得把前端项目页面、AI、RAG 或 GitHub 集成写成已可用。

后端 README 应至少列出：

- 项目 API；
- 认证要求；
- 分页默认值和上限；
- 项目所有权隔离语义；
- 软删除行为；
- 本地验证命令；
- 当前仍未实现的项目成员、GitHub、AI 和文件能力。

---

## 15. 非目标

DEV-009 不实现：

- Vue 项目列表或项目详情页面；
- 前端登录态接入；
- 项目成员、邀请、协作者或团队；
- 组织、租户或企业空间；
- 项目所有权转让；
- 管理员跨用户查看或管理项目；
- 项目恢复或回收站 API；
- 软删除数据的定时物理清理；
- 项目标签、收藏、排序配置或封面；
- 项目模板；
- GitHub 仓库绑定、Token、Webhook、同步或代码拉取；
- 文档上传、文件上传、MinIO 或 S3；
- Qdrant、Embedding 或 RAG；
- AI Gateway、模型配置、提示词或对话；
- 代码审查或测试生成；
- Redis 缓存、分布式锁或限流；
- WebSocket 或 SSE；
- 审计日志系统；
- 项目级细粒度权限表；
- 批量创建、批量更新或批量删除；
- 任意字段排序、复杂筛选或全文搜索；
- 乐观锁或分布式事务；
- 用户删除和注销流程；
- 自动部署或生产数据库操作。

不要为了展示“完整项目管理系统”而提前实现上述内容。

---

## 16. 技术约束

1. 使用现有 Java 21、Spring Boot 3、Spring Security、MyBatis-Plus、Flyway 和 MySQL 技术栈。
2. 不新增 JPA、QueryDSL、额外 ORM 或新的安全框架。
3. 除非完成任务确有必要，不新增生产依赖。
4. 使用 Maven Wrapper，不依赖开发机器全局 Maven。
5. 不修改已有 migration。
6. 不在生产配置、测试或日志中写入真实密钥和账号。
7. 不跨模块直接调用其他模块 Mapper。
8. Controller 只处理协议、校验、principal 提取和应用服务调用。
9. Service 表达项目用例、所有权语义和事务边界。
10. Mapper 只表达参数化的数据访问。
11. 请求使用 DTO，响应使用 VO，禁止暴露 Entity。
12. 列表必须分页且限制最大页大小。
13. 时间继续按 UTC 存储和输出。
14. 不使用 Lombok 隐藏领域行为。
15. 不执行来源不可信的仓库代码。
16. 不接触真实用户项目数据或生产数据库。
17. 不降低 Spring Security 默认保护范围。
18. 不把隐藏按钮或前端路由当作资源权限控制。
19. 新增行为必须具有匹配测试。
20. 如果无法运行完整测试，必须明确说明原因和风险，不能声称任务完成。

---

## 17. 验收标准

只有以下条件全部满足，DEV-009 才能视为完成：

- [ ] 从最新 `develop` 创建独立任务分支。
- [ ] 新增 V4 migration，未修改 V1、V2、V3。
- [ ] `projects` 表可以在 MySQL 8 空库中通过 Flyway 创建。
- [ ] 主键、外键、检查约束和查询索引设计合理。
- [ ] 项目所有者外键使用非级联删除策略。
- [ ] 项目使用软删除并记录删除时间。
- [ ] 新建独立、职责清晰的项目业务模块。
- [ ] `ProjectEntity`、`ProjectMapper`、`ProjectService` 已实现。
- [ ] 创建和更新请求使用独立 DTO。
- [ ] 响应使用 VO，不直接暴露 Entity。
- [ ] 创建项目只能将当前认证用户设为所有者。
- [ ] 创建、列表、详情、更新和删除 API 已实现。
- [ ] 所有项目 API 都要求有效 JWT 和 `user` authority。
- [ ] 项目列表按当前用户隔离并强制分页。
- [ ] 页码最大为 10000，深分页受到明确限制。
- [ ] 页大小最大为 100。
- [ ] 项目列表具有稳定排序。
- [ ] 详情查询同时使用项目 ID、所有者 ID 和未删除条件。
- [ ] 更新同时使用项目 ID、所有者 ID 和未删除条件。
- [ ] 删除同时使用项目 ID、所有者 ID 和未删除条件。
- [ ] 不存在、已删除和他人项目统一返回 404。
- [ ] 管理员默认不能绕过项目归属。
- [ ] 客户端伪造所有者字段不能改变项目所有权。
- [ ] 项目模块提供可供后续模块复用的公开所有权校验服务边界。
- [ ] 其他模块不需要且不能直接依赖 `ProjectMapper` 完成项目归属判断。
- [ ] 删除后项目不再出现在列表和详情中。
- [ ] `PROJECT_NOT_FOUND` 接入统一异常响应。
- [ ] Migration、Mapper、Service、API 和安全测试覆盖任务矩阵。
- [ ] DEV-007 与 DEV-008 认证授权能力无回归。
- [ ] OpenAPI 能发现项目接口且不暴露内部字段。
- [ ] 相关 README 或 API 文档按实际状态更新。
- [ ] 没有前端、AI、RAG、GitHub 或文件存储实现。
- [ ] 没有真实密钥、构建产物、IDE 配置或调试文件进入提交。
- [ ] `./mvnw test` 全部通过。
- [ ] PR 描述列出实际验证命令和结果。
- [ ] PR 未被自动合并，等待项目所有者确认。

---

## 18. 预计文件影响范围

以下列表用于帮助审查，不要求机械创建完全相同的文件。如果仓库最新结构不同，应遵循更具体的现有约定。

```text
docs/tasks/DEV-009-project-workspace.md

devmate-server/src/main/resources/db/migration/
└── V4__create_projects_table.sql

devmate-server/src/main/java/com/devmate/project/
├── controller/
│   └── ProjectController.java
├── dto/
│   ├── CreateProjectRequest.java
│   └── UpdateProjectRequest.java
├── entity/
│   └── ProjectEntity.java
├── mapper/
│   └── ProjectMapper.java
├── service/
│   └── ProjectService.java
└── vo/
    └── ProjectResponse.java

devmate-server/src/main/java/com/devmate/common/api/
└── PageResult.java（仅当仓库尚无通用分页类型）

devmate-server/src/main/java/com/devmate/common/api/ErrorCode.java

devmate-server/src/test/java/com/devmate/project/
├── ProjectMapperIntegrationTest.java
├── ProjectServiceTest.java
└── ProjectApiIntegrationTest.java

devmate-server/src/test/java/com/devmate/database/
└── ProjectMigrationIntegrationTest.java

README.md（按实际状态最小更新）
devmate-server/README.md（按实际 API 最小更新）
docs/README.md（按文档导航需要更新）
```

不得因为预计列表中出现某文件就创建空壳、占位实现或重复抽象。

---

## 19. 实现顺序建议

建议按以下顺序执行，便于每一步保持可验证：

1. 更新远端引用并确认工作区干净；
2. 从最新 `develop` 创建任务分支；
3. 再次检查现有 migration、包结构、安全配置和测试基类；
4. 编写 V4 migration；
5. 增加并运行 migration 集成测试；
6. 实现项目 Entity 与 Mapper；
7. 增加 Mapper 集成测试，重点验证所有权查询；
8. 实现 DTO、VO、分页类型和 Service；
9. 增加 Service 单元测试；
10. 实现 Controller 与方法授权；
11. 增加完整 API/安全集成测试；
12. 运行现有认证与 RBAC 回归测试；
13. 更新与实际行为相关的文档；
14. 运行完整 `./mvnw test`；
15. 检查 diff、敏感信息、生成产物和任务边界；
16. 创建范围单一的 Conventional Commit；
17. 推送分支并创建以 `develop` 为目标的 PR；
18. 等待项目所有者审查和合并。

---

## 20. Git 流程

开始实现前：

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feature/dev-009-project-workspace
```

建议提交信息：

```text
feat(project): add project workspace foundation
```

如测试或文档适合单独审查，可拆分为范围明确的提交，例如：

```text
test(project): cover project ownership isolation
docs(project): document project workspace API
```

要求：

- 不直接提交到 `main` 或 `develop`；
- 不强制推送共享分支；
- 不改写其他任务历史；
- 不把无关格式化或重构混入提交；
- 不自动合并 PR；
- PR 基准分支为 `develop`；
- 一个分支只处理 DEV-009。

---

## 21. Pull Request 要求

### 21.1 PR 标题

```text
feat(project): add project workspace foundation
```

### 21.2 PR 描述必须包含

1. 任务编号 `DEV-009`；
2. 背景与目标；
3. `projects` 表字段、约束和索引；
4. 外键删除策略；
5. 软删除实现方式；
6. 项目所有权来源与 IDOR 防护方式；
7. API 列表和响应语义；
8. 分页默认值、上限和排序；
9. `PROJECT_NOT_FOUND` 的隐藏资源存在性语义；
10. 方法授权与 `user` authority；
11. 新增测试覆盖范围；
12. 实际执行的命令与结果；
13. 文档更新；
14. 风险与回滚方式；
15. 未完成项；
16. 明确说明未实现前端、AI、GitHub、RAG 和成员协作。

### 21.3 PR 检查项

- [ ] base 为 `develop`。
- [ ] head 为 `feature/dev-009-project-workspace`。
- [ ] Commit message 符合 Conventional Commits。
- [ ] V1、V2、V3 migration 未修改。
- [ ] 不存在无所有权限制的项目详情、更新或删除调用链。
- [ ] 没有前端改动。
- [ ] 没有 GitHub、AI、RAG、Redis 或对象存储实现。
- [ ] 没有项目成员或管理员越权能力。
- [ ] 没有敏感信息和构建产物。
- [ ] 所有自动化测试通过。
- [ ] PR 未自动合并。

---

## 22. 风险与审查重点

### 22.1 最高优先级：项目归属绕过

审查者应优先检查是否存在任何只按 `projectId` 查询、更新或删除的业务路径。只要 Controller 或 Service 能调用无所有权约束的方法，就可能形成 IDOR。

### 22.2 软删除遗漏

检查列表、详情、更新、删除和 count 是否一致包含 `deleted = 0`。遗漏会导致已删除项目重新出现或被修改。

### 22.3 分页 count 与 items 不一致

检查 count 和分页数据是否使用相同的用户及删除条件。不同条件会产生错误 total 或跨用户信息泄露。

### 22.4 DTO 批量赋值

检查是否把请求体直接转换或绑定为 Entity，导致 `ownerUserId`、`deleted` 或时间字段可以被客户端覆盖。

### 22.5 管理员误绕过

检查代码是否出现“ADMIN 可以查看所有项目”的未经需求确认逻辑。DEV-009 明确禁止该行为。

### 22.6 不必要的架构扩张

检查是否新增项目成员、仓库绑定、AI 占位服务、事件总线、缓存或复杂领域框架。它们均不属于本任务。

### 22.7 数据库回滚

由于 Flyway migration 一旦在共享环境执行不能修改，PR 合并前必须确认表名、字段名、外键策略和索引。回滚生产部署时应回滚应用版本，但数据库结构采用后续前向 migration 修正，不通过修改 V4 checksum 回滚。

---

## 23. Codex 最终交付信息

任务完成后必须向项目所有者报告：

1. 已完成内容和未完成内容；
2. 实际修改文件列表；
3. V4 migration 的表、约束、索引和删除策略；
4. 项目模块包结构；
5. 项目所有权校验的具体位置和 SQL 条件；
6. API 端点列表；
7. 分页结构和边界；
8. 软删除行为；
9. IDOR、401、403、404 测试结果；
10. 实际执行的验证命令及结果；
11. 分支名；
12. Commit SHA；
13. Pull Request 地址；
14. 风险、限制和需要人工确认的事项；
15. 下一任务建议，只说明，不实施。

如果任何强制测试未运行或失败，不得将 DEV-009 报告为完成。

---

## 24. DEV-009 完成后的项目状态

DEV-009 完成并合并后，项目应达到以下状态：

```text
第一阶段
├── 工程基座                 已具备
├── MySQL / Flyway          已具备
├── 统一响应与异常            已具备
├── 用户注册与登录            已具备
├── JWT 身份认证             已具备
├── RBAC 角色与权限           已具备
└── 项目空间后端              已具备
```

但仍不能声称第一阶段全部完成，因为至少还需要根据项目计划评估：

- 前端认证流程；
- 前端项目空间；
- CI 自动化；
- README 与文档状态收口；
- 第一阶段端到端验收。

DEV-009 完成后也不能声称 AI 对话、RAG、GitHub 分析、代码审查或测试生成功能已经可用。
