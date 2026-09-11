# DEV-008：RBAC 权限体系基础建设

## 1. 任务背景

DevMate 已完成以下后端基础能力：

- DEV-004：后端架构与分层规范
- DEV-005：Flyway、MySQL、Testcontainers 数据库基础设施
- DEV-006：统一响应、异常处理和基础测试体系
- DEV-007：用户注册、登录、JWT、Spring Security 和当前用户接口

当前认证链路为：

```text
User
  ↓
JWT
  ↓
Spring Security
  ↓
受保护接口
```

系统已经能够识别用户身份，但尚未具备角色和权限授权能力。

本任务需要引入 RBAC（Role-Based Access Control），将安全体系升级为：

```text
User
  ↓
Role
  ↓
Permission
  ↓
Spring Security
  ↓
业务接口
```

该能力将作为管理后台、AI 能力权限控制、项目资源权限和企业级功能扩展的基础设施。

---

## 2. 任务目标

在现有认证体系上实现 RBAC 基础能力：

1. 建立用户、角色和权限之间的数据库关系。
2. 支持根据用户 ID 查询角色及权限。
3. 将用户角色写入 JWT。
4. 在 Spring Security 中同时支持角色授权和权限授权。
5. 注册用户自动获得 `USER` 角色。
6. 提供可验证的管理员接口保护能力。
7. 确保权限变更无需重新签发 JWT 即可在后续请求中生效。

授权表达式必须支持：

```java
@PreAuthorize("hasRole('ADMIN')")
```

以及：

```java
@PreAuthorize("hasAuthority('user')")
```

---

## 3. 开发范围

### 3.1 RBAC 数据库设计

通过新的 Flyway migration 创建以下表。Migration 版本号必须基于仓库现有最大版本顺序递增，不得修改任何已有 migration。

#### `role`

字段至少包括：

| 字段            | 要求           |
| ------------- | ------------ |
| `id`          | 主键、自增、BIGINT |
| `name`        | 角色名称，非空      |
| `code`        | 角色编码，非空且唯一   |
| `description` | 角色说明         |
| `create_time` | 创建时间，非空      |
| `update_time` | 更新时间，非空      |

初始角色：

- `USER`
- `ADMIN`

角色编码统一使用大写英文。

### `permission`

字段至少包括：

| 字段            | 要求           |
| ------------- | ------------ |
| `id`          | 主键、自增、BIGINT |
| `name`        | 权限名称，非空      |
| `code`        | 权限编码，非空且唯一   |
| `description` | 权限说明         |
| `create_time` | 创建时间，非空      |
| `update_time` | 更新时间，非空      |

初始权限编码：

- `user`
- `system`

权限编码统一使用小写英文。需求中重复出现的 `user` 视为同一权限，不得生成重复记录。

#### `user_role`

字段至少包括：

- `id`
- `user_id`
- `role_id`

约束要求：

- `user_id` 关联已有 `users.id`。
- `role_id` 关联 `role.id`。
- 建立 `user_id + role_id` 联合唯一约束。
- 为外键查询建立必要索引。
- 外键删除策略必须明确且合理。

#### `role_permission`

字段至少包括：

- `id`
- `role_id`
- `permission_id`

约束要求：

- `role_id` 关联 `role.id`。
- `permission_id` 关联 `permission.id`。
- 建立 `role_id + permission_id` 联合唯一约束。
- 为外键查询建立必要索引。
- 外键删除策略必须明确且合理。

如果表名 `role` 或 `permission` 在目标数据库中需要转义，应在 migration 和 Mapper SQL 中统一处理，不得产生不同环境下行为不一致的问题。

### 3.2 初始化数据

通过 Flyway 初始化以下关系：

```text
USER  → user
ADMIN → system
```

要求：

- `USER`、`ADMIN` 和权限编码不得重复。
- 不允许通过开放注册接口直接注册为 `ADMIN`。
- 对 migration 执行前已存在的用户，补充 `USER` 角色关系。
- 数据初始化不得依赖固定自增 ID，应通过角色和权限编码建立关联。

### 3.3 领域模型和 Mapper

按照当前项目的 Entity 与 MyBatis 规范创建：

- `RoleEntity`
- `PermissionEntity`
- `UserRoleEntity`
- `RolePermissionEntity`

对应 Mapper：

- `RoleMapper`
- `PermissionMapper`
- `UserRoleMapper`
- `RolePermissionMapper`

至少提供以下数据能力：

- 根据角色编码查询角色。
- 根据用户 ID 查询角色编码列表。
- 根据用户 ID 查询权限编码列表。
- 为用户分配角色。
- 查询角色是否已分配给用户。
- 查询角色与权限的关联关系。

角色和权限查询应优先使用关联查询，避免逐条查询造成 N+1 问题。

只实现本任务需要的查询和关联写入能力，不实现通用 RBAC CRUD 管理接口。

### 3.4 用户角色服务

实现符合现有 Service 规范的用户角色查询能力。

核心输入：

```text
userId
```

核心输出：

```json
["USER"]
```

或：

```json
["ADMIN", "USER"]
```

要求：

- 返回角色 `code`，不返回数据库 ID。
- 结果不得包含重复角色。
- 输出顺序必须稳定，例如按照角色编码排序。
- 用户无角色时返回空集合，不返回 `null`。
- 用户不存在时按照项目现有业务异常规范处理。
- 角色分配写操作应具备事务边界。

可根据当前工程命名规范使用 `UserRoleService`，或将能力放入现有职责明确的服务中，但不得将 SQL 查询逻辑写入 Controller。

### 3.5 注册流程调整

调整现有用户注册流程：

1. 创建用户。
2. 查询 `USER` 角色。
3. 写入 `user_role` 关系。
4. 完成注册响应。

用户创建和默认角色分配必须处于同一个事务中。

如果 `USER` 角色初始化数据缺失：

- 注册必须失败并回滚。
- 使用现有 `BusinessException` 和 `ErrorCode` 体系返回错误。
- 不得创建没有默认角色的用户。
- 不得静默忽略角色分配失败。

重复注册、密码加密和现有注册响应行为不得被破坏。

### 3.6 JWT 扩展

将当前 JWT 中的单角色结构调整为多角色结构。

JWT 至少包含：

```text
id
roles
iat
exp
```

示例：

```json
{
  "id": 10001,
  "roles": ["USER"],
  "iat": 1789080000,
  "exp": 1789087200
}
```

多个角色示例：

```json
{
  "id": 10001,
  "roles": ["ADMIN", "USER"],
  "iat": 1789080000,
  "exp": 1789087200
}
```

要求：

- `roles` 必须是数组。
- 角色编码不得重复。
- 角色顺序保持稳定。
- 新签发 Token 不再使用旧的单值 `role` claim。
- 不得将权限列表写入 JWT。
- 保持现有签名、有效期校验和非法 Token 处理行为。
- 不在日志中输出完整 JWT 或其他敏感认证信息。

### 3.7 Spring Security 授权

启用方法级授权：

```java
@EnableMethodSecurity
```

认证成功后，将安全上下文中的权限映射为：

```text
角色 USER   → ROLE_USER
角色 ADMIN  → ROLE_ADMIN
权限 user   → user
权限 system → system
```

因此必须支持：

```java
hasRole('ADMIN')
hasRole('USER')
hasAuthority('user')
hasAuthority('system')
```

实现要求：

- JWT 中的角色用于构造 `ROLE_` 前缀的角色 authority。
- 根据 JWT 中的用户 ID，从数据库实时查询权限。
- 权限不得从 JWT 直接读取。
- 使用同一个仍在有效期内的 JWT 时，数据库中的权限关系变更应在后续请求中生效。
- 未认证请求返回 HTTP `401`。
- 已认证但权限不足的请求返回 HTTP `403`。
- 错误响应遵循现有 `Result<T>`、错误码和 JSON 响应规范。
- 保持现有公开接口、登录接口和注册接口的访问策略。
- 不得意外开放当前已经受保护的接口。

可以新增一个最小管理员授权验证接口：

```http
GET /admin/ping
```

该接口要求：

```java
@PreAuthorize("hasRole('ADMIN')")
```

接口仅用于验证管理员授权链路，返回现有统一响应结构，不承载权限管理业务。

`hasAuthority('user')` 应通过集成测试中的受保护方法或测试控制器完成验证，无需额外增加公开业务接口。

管理员测试数据应通过测试 fixture、Mapper 或数据库准备，不得增加公开的管理员注册或角色提升接口。

---

## 4. 非目标

DEV-008 不实现：

- RBAC 管理后台页面
- Vue 页面开发
- 角色或权限 CRUD API
- 用户角色管理 API
- 菜单权限
- 前端路由权限
- 动态权限缓存
- Redis
- OAuth 或第三方登录
- 多租户权限
- 数据权限
- 项目资源级权限
- 审计系统
- JWT 刷新 Token
- 超级管理员绕过机制

不要为了本任务提前实现后续功能。

---

## 5. 技术约束

1. 使用 Java 21、Spring Boot 3、Spring Security 和 MyBatis。
2. 遵循 DEV-004 已建立的 Controller、Service、Mapper、Entity、DTO、VO 和异常处理规范。
3. 所有接口响应继续使用现有 `Result<T>`。
4. 使用现有 `ErrorCode`、`BusinessException` 和全局异常处理体系。
5. 使用 Maven Wrapper，不依赖开发机器全局 Maven。
6. 使用 Flyway 管理数据库结构和初始化数据。
7. 不修改、删除或重命名已有 migration。
8. 数据库验证使用项目现有 MySQL/Testcontainers 基础设施。
9. 不引入 Redis、JPA、额外 ORM 或新的安全框架。
10. 不得在代码、配置、测试或日志中写入真实密钥和账号。
11. 不得破坏 DEV-007 已有注册、登录、JWT 校验和 `/auth/me` 行为。
12. 实现前应先检查仓库现有包结构和编码风格，不得创建重复或平行架构。
13. 除非确有必要，不新增生产依赖；新增依赖必须在 PR 中说明原因。

---

## 6. 测试要求

必须补充单元测试和集成测试，至少覆盖以下场景。

### 6.1 Migration 测试

- 新数据库可以从零执行全部 Flyway migration。
- 四张 RBAC 表创建成功。
- 外键、唯一约束和必要索引存在。
- `USER`、`ADMIN`、`user`、`system` 初始化正确。
- `USER → user` 和 `ADMIN → system` 关系正确。
- 已有用户能够获得 `USER` 角色。
- 重复的用户角色或角色权限关系被唯一约束拒绝。

### 6.2 注册测试

- 注册成功后用户拥有且只拥有一个默认 `USER` 角色。
- 重复注册仍按现有规则失败。
- 默认角色分配失败时，用户创建事务回滚。
- 注册接口不能通过请求参数获取 `ADMIN` 角色。

### 6.3 用户角色查询测试

- 普通用户返回 `["USER"]`。
- 多角色用户返回稳定排序且无重复的角色列表。
- 无角色用户返回空集合。
- 不存在的用户按照业务异常规范处理。

### 6.4 JWT 测试

- 登录签发的 Token 包含 `id`。
- Token 包含数组类型的 `roles`。
- Token 包含 `iat` 和 `exp`。
- Token 不包含 permission 列表。
- 新 Token 不再依赖旧的单值 `role` claim。
- 多角色用户的 Token 能正确保存全部角色。
- 非法、过期或签名错误 Token 仍按现有规则拒绝。

### 6.5 授权测试

至少验证：

| 场景                                       | 预期结果  |
| ---------------------------------------- | ----- |
| 未登录访问受保护接口                               | `401` |
| `USER` 访问普通认证接口                          | 成功    |
| `USER` 访问 `/admin/ping`                  | `403` |
| `ADMIN` 访问 `/admin/ping`                 | 成功    |
| 拥有 `user` 权限调用 `hasAuthority('user')` 方法 | 成功    |
| 不拥有 `user` 权限调用相同方法                      | `403` |
| 数据库新增权限关系后复用原 JWT 再次请求                   | 新权限生效 |
| 数据库移除权限关系后复用原 JWT 再次请求                   | 原权限失效 |

测试不得依赖执行顺序或开发机器上的既有数据库数据。

### 6.6 回归测试

确保以下已有能力继续正常：

- 用户注册
- 用户登录
- 当前用户查询
- JWT 认证
- Health 接口
- 统一异常响应
- 原有测试套件

最终必须执行：

```bash
./mvnw test
```

如果 Maven Wrapper 位于后端子目录，应在对应目录执行。

全部测试必须通过。

---

## 7. 验收标准

任务只有在以下条件全部满足时才可视为完成：

- [ ] 新增 Flyway migration，且没有修改已有 migration。
- [ ] `role`、`permission`、`user_role`、`role_permission` 表创建成功。
- [ ] 主键、外键、唯一约束和索引设计合理。
- [ ] 默认角色和权限初始化成功。
- [ ] 已有用户和新注册用户均能获得 `USER` 角色。
- [ ] 四个 Entity 和四个 Mapper 已实现。
- [ ] 可以根据用户 ID 查询角色编码列表。
- [ ] 可以根据用户 ID实时查询权限编码列表。
- [ ] JWT 使用 `roles` 数组并包含 `id`、`iat`、`exp`。
- [ ] JWT 中不包含权限列表。
- [ ] 已启用 `@EnableMethodSecurity`。
- [ ] `hasRole('ADMIN')` 可以正确授权。
- [ ] `hasAuthority('user')` 可以正确授权。
- [ ] 角色 authority 使用 `ROLE_` 前缀。
- [ ] 权限变化能够在复用原 JWT 的后续请求中生效。
- [ ] 未认证和无权限场景分别返回 `401` 与 `403`。
- [ ] 注册与默认角色分配处于同一事务。
- [ ] 不存在公开的管理员注册或角色提升接口。
- [ ] `/admin/ping` 的 ADMIN/USER 访问结果符合预期。
- [ ] DEV-007 现有能力没有回归。
- [ ] `./mvnw test` 全部通过。
- [ ] 未实现本任务非目标范围内的功能。

---

## 8. 文件影响范围

具体包名和 migration 版本必须以仓库当前结构为准。预计影响范围如下：

```text
docs/tasks/DEV-008-rbac-authorization.md

后端数据库 migration：
src/main/resources/db/migration/
  V{next}__create_rbac_tables.sql
  V{next}__initialize_rbac_data.sql
```

Migration 可以按项目现有规范合并为一个文件或拆分为连续版本，但必须保证顺序明确。

预计新增后端文件：

```text
RoleEntity
PermissionEntity
UserRoleEntity
RolePermissionEntity

RoleMapper
PermissionMapper
UserRoleMapper
RolePermissionMapper

UserRoleService 或等价职责明确的服务
AdminController（如采用 /admin/ping 验证接口）
```

预计调整：

```text
现有用户注册 Service
JwtService
JwtAuthenticationFilter
SecurityConfig
相关 ErrorCode
OpenAPI 配置（如新增 /admin/ping）
```

预计新增或调整测试：

```text
Flyway/Testcontainers migration 测试
用户注册默认角色测试
用户角色查询测试
JWT claims 测试
Spring Security 角色授权测试
Spring Security 权限授权测试
认证回归测试
```

不得仅依据本列表机械创建文件。Codex 必须先检查现有工程结构，并复用已有抽象与命名规范。

---

## 9. Git 流程

Codex Cloud 执行任务时应遵循以下流程：

1. 获取远程最新代码。
2. 从最新 `develop` 创建开发分支：

```bash
git checkout develop
git pull origin develop
git checkout -b feat/dev-008-rbac-authorization
```

3. 仅实现 DEV-008 范围内的内容。
4. 执行完整测试：

```bash
./mvnw test
```

5. 检查变更中不存在：

   - 密钥或敏感信息
   - 构建产物
   - IDE 私有文件
   - 无关格式化
   - 对已有 migration 的修改
   - 超出 DEV-008 范围的功能

6. 提交代码：

```bash
git commit -m "feat(server): implement RBAC authorization foundation"
```

7. 推送分支：

```bash
git push -u origin feat/dev-008-rbac-authorization
```

8. 创建 Pull Request：

```text
base: develop
head: feat/dev-008-rbac-authorization
```

不得直接提交到 `main` 或 `develop`，不得自动合并 Pull Request。

---

## 10. PR 要求

### PR 标题

```text
feat(server): implement RBAC authorization foundation
```

### PR 描述必须包含

1. 任务编号：`DEV-008`
2. 数据库表和约束说明
3. 初始化角色、权限及关联关系
4. 现有用户的默认角色迁移策略
5. 注册流程的事务调整
6. JWT claims 变更
7. Spring Security authority 映射规则
8. 权限实时查询机制
9. 新增或调整的接口
10. 测试覆盖范围
11. `./mvnw test` 执行结果
12. 已知限制和后续任务建议

### PR 检查项

- [ ] PR 目标分支为 `develop`
- [ ] 分支名为 `feat/dev-008-rbac-authorization`
- [ ] Commit message 符合约定
- [ ] 没有修改已有 Flyway migration
- [ ] 没有前端改动
- [ ] 没有权限管理 CRUD
- [ ] 没有引入 Redis 或缓存
- [ ] 没有泄露敏感信息
- [ ] 所有自动化测试通过
- [ ] PR 未被自动合并

### Codex 最终交付信息

任务完成后，Codex 应报告：

- 实际变更文件列表
- migration 版本号
- 角色和权限映射方式
- JWT claims 结构
- 权限实时查询实现方式
- 测试命令及结果
- Commit SHA
- Pull Request 地址
- 未完成项或风险（如有）

