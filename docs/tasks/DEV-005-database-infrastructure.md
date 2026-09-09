# DEV-005：数据库基础设施建设

## 1. 任务元信息

| 项目 | 内容 |
| --- | --- |
| 任务编号 | DEV-005 |
| 任务名称 | 数据库基础设施建设 |
| 任务类型 | 后端基础设施 |
| 所属阶段 | 第一阶段——工程基座完善 |
| 执行者 | Codex Cloud |
| 基准分支 | `develop` |
| 执行分支 | `chore/005-database-infrastructure` |
| PR 目标分支 | `develop` |
| Commit | `feat(server): establish database infrastructure` |
| 主要修改范围 | `devmate-server/` |

> 本文件是 DEV-005 的执行契约，不代表任务已经实施。Codex Cloud 必须严格按范围执行，不得提前开发任何业务模块。

## 2. 当前背景

DEV-002 已建立 Java 21、Spring Boot 3、Maven Wrapper、健康检查和基础测试工程，并预留 MyBatis-Plus 与 MySQL Connector/J 依赖。

DEV-004 已完成并合并到 `develop`，后端目前具备：

- 完善后的 `Result<T>`、`ErrorCode` 和统一异常处理；
- SpringDoc OpenAPI 与 Swagger UI；
- `application.yml`、`application-dev.yml`、`application-prod.yml` 的配置边界；
- `src/main/resources/db/migration/` 目录约定；
- 基础日志与 README；
- 通用基础测试。

DEV-004 仍显式排除了 `DataSourceAutoConfiguration`，数据源配置只是占位契约；Flyway 尚未加入依赖，migration 不会执行；MyBatis-Plus 尚未通过真实 MySQL 验证。因此后续业务模块目前还没有可依赖的数据库运行基础。

DEV-005 只负责正式接通数据库基础设施：启用 MySQL 数据源、启用 Flyway migration、完成 MyBatis-Plus 基础配置，并建立基于 MySQL 8 的可重复集成测试策略。本任务不设计或实现业务数据模型。

### 2.1 已知验证债务

DEV-004 在 Codex Cloud 中曾因 Maven Central 返回 HTTP 403，未能完成真实 Maven 构建、测试和启动验证。DEV-005 执行前应重新尝试解析依赖并验证当前 `develop` 基线。

- 不得把 DEV-004 未执行的验证描述为已经通过；
- 不得通过删除依赖、关闭测试、修改仓库地址到不可信镜像或提交本地 Maven 缓存来规避网络限制；
- 若相同限制仍存在，应保存关键错误摘要，在 PR 中列出未执行项、影响和可在本地或 CI 补做的命令。

## 3. 前置检查

开始执行前必须：

1. 阅读根目录 `AGENTS.md`、`CONTRIBUTING.md`、`README.md`；
2. 阅读相关需求、架构、ADR 文档以及 DEV-002、DEV-004、DEV-005 任务文件；
3. 确认 DEV-004 已合并到远程 `develop`；
4. 检查 `develop` 最近提交，确认当前 Spring Boot、MyBatis-Plus、SpringDoc 和 Maven Wrapper 版本；
5. 检查 `pom.xml` 中现有 MySQL 驱动、MyBatis-Plus 与测试依赖，避免重复声明；
6. 检查三个 application 配置文件、现有自动配置排除、README 和 migration 目录；
7. 检查当前分支和工作区，保留用户已有修改；
8. 从最新远程 `develop` 创建 `chore/005-database-infrastructure`；
9. 检查 Java 21、Docker/Testcontainers 和 Maven 依赖下载是否可用；
10. 若存在无关未提交修改、依赖冲突或任务边界冲突，停止并向项目负责人报告。

禁止直接在 `main` 或 `develop` 上开发、提交或推送。

## 4. 任务目标

完成后必须达到：

- Spring Boot 正式启用数据源自动配置；
- dev 与 prod profile 使用清晰、安全的 MySQL 环境变量配置；
- 应用通过 HikariCP 连接 MySQL 8；
- Flyway 在应用启动阶段自动校验并执行 migration；
- migration 目录、命名、不可变规则和初始化流程明确；
- MyBatis-Plus 与同一数据源正确集成；
- 使用真实 MySQL 8 容器验证连接、Flyway 和 MyBatis 基础能力；
- 测试不依赖开发者预装 MySQL、不访问共享数据库；
- README 能指导后续开发者完成配置、测试和本地启动；
- 没有任何业务表、业务实体、业务 Mapper 或业务功能。

## 5. 技术要求

### 5.1 固定技术基线

- Java：保持 `21`；
- Spring Boot：保持当前 `3.5.6`，除非仓库在任务执行前已经通过正式 PR 更新；
- MySQL：仅支持 MySQL 8.x，集成测试容器固定到明确的 MySQL 8 版本，不使用 `latest`；
- MyBatis-Plus：保持当前 `3.5.14`，不在本任务顺手升级；
- MySQL Connector/J：继续使用现有 runtime 依赖，并优先由 Spring Boot BOM 管理版本；
- Flyway：使用当前 Spring Boot BOM 管理的兼容稳定版本，不手工覆盖版本；
- 测试：JUnit 5、Spring Boot Test、Testcontainers MySQL；
- 连接池：使用 Spring Boot 默认 HikariCP，不另引入连接池。

若任务执行时上述版本与最新 `develop` 不一致，以构建文件为事实来源；不得自行升级，必须在 PR 中记录实际版本。

### 5.2 依赖要求

在 `pom.xml` 中按需增加：

- `org.flywaydb:flyway-core`；
- `org.flywaydb:flyway-mysql`；
- Spring Boot 的 Testcontainers 集成依赖（test scope）；
- Testcontainers MySQL 模块（test scope）。

要求：

- 复用现有 `mybatis-plus-spring-boot3-starter` 和 `mysql-connector-j`，不得重复添加；
- Spring Boot BOM 已管理的依赖不重复写版本；
- 测试依赖必须使用 `test` scope；
- Flyway 只管理 schema 版本，禁止同时引入 Liquibase；
- 不引入 H2、JPA、Hibernate、Jooq、其他 ORM 或数据库客户端；
- 执行 `dependency:tree` 检查 Flyway、MySQL 驱动、MyBatis-Plus 和 Testcontainers 的版本及冲突。

### 5.3 数据库通用约束

- 数据库类型为 MySQL 8；
- 字符集要求为 `utf8mb4`；
- 服务端、JDBC 会话与应用时间处理统一以 UTC 为基准；
- 生产凭据只能通过环境变量或部署环境秘密管理能力注入；
- 禁止在任何受 Git 跟踪文件中保存真实密码、Token 或生产连接串；
- 生产环境禁止自动建表、自动更新 schema 或使用 Flyway clean；
- 禁止使用 root 账户作为应用账户示例；
- 后续表结构只能通过新的 Flyway migration 变更。

## 6. 实现范围

### 6.1 启用数据库自动配置

修改 `application.yml` 中的自动配置排除：

- 移除 `DataSourceAutoConfiguration` 排除，正式启用数据源；
- 继续保留 `SecurityAutoConfiguration` 排除，认证由后续独立任务处理；
- 不创建自定义 `DataSource` Bean，优先使用 Spring Boot 标准数据源自动配置；
- 不创建多数据源、读写分离、分库分表或动态数据源；
- 数据库启用后，完整应用启动需要可用的数据库连接，这是 DEV-005 后的预期行为；
- Web MVC slice 等不需要数据库的测试不得被无意义地强制连接数据库。

### 6.2 数据源配置

整理 `application.yml`、`application-dev.yml` 和 `application-prod.yml`：

#### application.yml

仅放跨环境公共配置，例如：

- JDBC 驱动类型由 URL/驱动自动识别，除非实际验证需要显式声明；
- HikariCP 的合理基础参数；
- MyBatis-Plus 公共配置；
- Flyway 公共安全配置；
- 禁止固定激活 `dev` 或 `prod`；
- 禁止在公共配置中提供真实连接信息。

#### application-dev.yml

- 使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量；
- 可保留现有、明确标注为本地开发用途的非敏感 URL 与用户名默认值；
- 密码不得写入真实值；
- URL 默认指向 MySQL 8 本地开发库；
- 不使用生产主机、共享数据库或个人机器绝对路径；
- 文档必须说明启动前需要显式提供密码并准备数据库。

#### application-prod.yml

- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 必须全部由环境注入；
- 不提供 fallback 密码或可误用的生产默认值；
- 使用有限且可配置的连接池大小与连接超时；
- 禁止输出 SQL、连接串或凭据；
- 禁止 `ddl-auto`、schema 自动更新和调试日志。

#### HikariCP

至少明确并允许环境变量覆盖：

- 最大连接数；
- 最小空闲连接数；
- 连接超时；
- 空闲超时；
- 最大生命周期。

默认值应面向单体应用的开发基线，保持保守，不做未经测量的高并发调优。配置命名和默认值应在 README 中说明。

### 6.3 Flyway 正式接入

启用 Spring Boot Flyway 自动配置，并满足：

- migration 位置固定为 `classpath:db/migration`；
- 启动时启用 `validate-on-migrate`；
- 禁止 `clean`；
- 禁止 `baseline-on-migrate` 自动接管未知已有库；
- 禁止 `out-of-order`；
- 使用与应用相同的数据源和凭据，不建立第二套连接配置；
- 禁止同时使用 `schema.sql`、`data.sql` 或 ORM 自动建表；
- migration 失败必须阻止应用启动，不能忽略或捕获后继续运行；
- Flyway schema history 表使用默认名称，除非已有 ADR 明确要求更改。

### 6.4 基线 migration

在 `src/main/resources/db/migration/` 中增加首个版本化 migration，用于验证执行机制。

建议文件名：

```text
V1__baseline.sql
```

要求：

- migration 仅包含注释与 MySQL 可执行的无副作用探测语句，例如 `SELECT 1;`；
- 不创建用户表、权限表、项目表或任何其他业务表；
- 不创建伪业务表来证明 Flyway 可用；
- 除 Flyway 自动维护的 `flyway_schema_history` 外，不新增应用表；
- 文件使用 UTF-8，无 BOM；
- 文件一旦合并即视为不可变，后续只能新增更高版本 migration；
- 更新 migration README，明确命名、顺序、不可修改、回滚和校验规则。

如果实际 Flyway/MySQL 版本不接受无副作用 migration，停止并请求项目负责人确认替代方案，不得擅自创建业务表。

### 6.5 MyBatis-Plus 基础接入

本任务只验证 MyBatis-Plus 能使用已配置的数据源：

- 通过 YAML 建立最小公共配置；
- 启用 `map-underscore-to-camel-case`；
- 生产环境不输出 SQL；
- 不设置尚未决策的业务主键生成策略、逻辑删除字段或自动填充策略；
- 不创建无实际配置内容的 `MybatisPlusConfig`；
- 不添加分页插件，分页能力在首个需要分页的业务任务中接入并测试；
- 不创建生产源码中的 Mapper、Entity、Repository、Service 或 Controller；
- MyBatis 基础验证使用测试源码内的探针 Mapper 执行 `SELECT 1`，不得把探针放入生产代码。

### 6.6 测试数据库策略

数据库集成测试统一使用 Testcontainers 启动临时 MySQL 8 容器：

- 镜像必须固定明确的 MySQL 8 tag，不使用 `latest`；
- 优先使用与目标开发/生产环境相同的 MySQL 8 系列；
- 通过 Spring Boot Testcontainers 集成或动态属性注入连接信息；
- 每次测试从空数据库开始，由 Flyway 自动 migration；
- 不依赖开发者预装 MySQL；
- 不连接本地、共享、测试服务器或生产数据库；
- 不把容器端口、随机密码或运行时连接串提交到仓库；
- 测试结束后由 Testcontainers 清理容器；
- 禁止使用 H2 替代 MySQL 8 并声称兼容；
- 禁止配置测试跳过或在 Docker 不可用时静默通过。

可抽取一个测试基类或测试配置复用容器，但不得建立复杂测试框架。数据库相关测试应尽量复用同一个测试上下文，避免无意义地重复启动容器。

### 6.7 README 更新

更新 `devmate-server/README.md`，至少说明：

- DEV-005 后端数据库基础能力的真实状态；
- MySQL 8、JDK 21、Docker/Testcontainers 的要求；
- dev 与 prod profile 的用途；
- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 及 Hikari 配置变量；
- 如何准备本地开发数据库与最小权限应用账户；
- 如何启动 dev profile；
- Flyway 自动执行、校验失败阻止启动的行为；
- migration 文件命名和不可变规则；
- 如何执行数据库集成测试；
- 仍未实现任何业务表、业务 Mapper、用户、认证、Redis 或 AI 功能；
- 已知 Maven Central 403 验证债务是否已经解决。

README 中不得出现真实密码，不得把 Testcontainers 描述为生产部署方式。

## 7. 文件修改边界

### 7.1 允许修改

- `devmate-server/pom.xml`
- `devmate-server/README.md`
- `devmate-server/src/main/resources/application.yml`
- `devmate-server/src/main/resources/application-dev.yml`
- `devmate-server/src/main/resources/application-prod.yml`
- `devmate-server/src/main/resources/db/migration/README.md`
- `devmate-server/src/test/java/com/devmate/**` 中与数据库基础设施直接相关的测试和测试配置
- 因启用数据源而必须调整的现有应用上下文测试

### 7.2 允许新增

- `devmate-server/src/main/resources/db/migration/V1__baseline.sql`
- Testcontainers MySQL 测试基类或测试配置
- 数据源、Flyway、migration 和 MyBatis-Plus 基础集成测试
- 测试源码内的探针 Mapper

### 7.3 禁止修改

- `devmate-web/`
- `main` 分支
- 现有业务无关 Controller、统一响应、异常处理与 OpenAPI 行为
- 根目录工程化配置、部署目录和 GitHub Actions；如确需 CI 支持，应拆分后续任务
- 已合并的历史 migration；本任务创建并合并后的 `V1__baseline.sql` 也不得在后续任务直接修改
- 与 DEV-005 无关的文档或模块

如完成任务必须越过上述边界，应停止并请求项目负责人确认。

## 8. 禁止实现范围

DEV-005 禁止：

- 创建用户表、角色表、权限表、关联表或任何认证数据结构；
- 创建项目、文档、文件、会话、消息、知识库、向量、审查、测试生成或 AI 相关表；
- 创建任何业务 Entity、DTO、VO、Mapper、Repository、Service 或 Controller；
- 实现用户注册、登录、JWT、Spring Security 权限逻辑；
- 实现 Redis、缓存、限流、Session 或 WebSocket；
- 实现 AI、RAG、GitHub、对象存储、文件上传或第三方服务；
- 引入 JPA/Hibernate、H2、Liquibase、多数据源或分库分表；
- 创建 Docker Compose、部署配置或 CI/CD 工作流；
- 实现数据库备份、恢复、监控或生产运维平台；
- 修改前端；
- 顺手升级 Spring Boot、Java、MyBatis-Plus、SpringDoc 或 Maven Wrapper；
- 修改、删除或重新排序已执行的 migration；
- 为通过测试而跳过数据库测试、降低断言或回退到内存数据库。

## 9. 测试要求

### 9.1 配置测试

- 验证 `DataSourceAutoConfiguration` 已启用；
- 验证 `SecurityAutoConfiguration` 的临时排除仍保留；
- 验证测试连接信息来自 Testcontainers，而非 dev/prod 凭据；
- 验证 profile 配置可以正确绑定数据源与 Hikari 参数；
- 验证生产配置未包含密码默认值；
- 不在测试日志或断言失败信息中打印完整密码或连接串。

### 9.2 数据库连接测试

- 使用 Testcontainers MySQL 8；
- 验证 Spring `DataSource` 能取得连接；
- 验证数据库产品为 MySQL，主版本为 8；
- 执行 `SELECT 1` 验证连接可用；
- 验证连接使用测试容器，不连接外部数据库。

### 9.3 Flyway migration 测试

- 从空数据库启动应用上下文；
- 验证 Flyway migration 成功执行；
- 验证 schema history 中记录 `V1__baseline.sql` 且状态成功；
- 验证重复启动时 migration 不会重复执行；
- 验证 validation 正常；
- 验证数据库中除 Flyway 内部历史表外没有应用业务表；
- 不在测试中调用 Flyway clean 作为常规清理手段。

### 9.4 MyBatis-Plus 基础测试

- 在测试源码中定义最小探针 Mapper；
- 通过 MyBatis/MyBatis-Plus 会话执行 `SELECT 1`；
- 验证返回值正确；
- 验证测试不依赖生产业务 Mapper 或实体；
- 不为测试创建业务表。

### 9.5 现有回归测试

- 原有 `Result`、异常处理、健康接口与 OpenAPI 测试继续通过；
- 数据库接入不得改变 `GET /api/health` 的响应契约；
- 不需要数据库的 MVC slice 测试应保持快速、隔离；
- 所有测试不得访问公共网络或真实外部服务，镜像与 Maven 依赖拉取除外。

## 10. 验证方式

### 10.1 环境检查

记录以下信息：

```bash
java -version
docker version
docker info
```

不得在输出中暴露 Docker registry 凭据或环境秘密。

### 10.2 Maven 测试与构建

在 `devmate-server/` 目录执行：

```bash
./mvnw test
./mvnw clean package
./mvnw dependency:tree
```

Windows 使用等价命令：

```powershell
mvnw.cmd test
mvnw.cmd clean package
mvnw.cmd dependency:tree
```

确认：

- Maven Wrapper 与依赖可以下载；
- 所有单元测试和 MySQL Testcontainers 集成测试通过；
- 构建成功；
- 依赖树中没有重复 ORM、H2、Liquibase 或明显版本冲突；
- `target/` 未进入 Git 变更。

### 10.3 启动验证

使用专用的本地 MySQL 8 测试实例和非 root 应用账户进行有限验证：

1. 准备全新的空数据库，字符集为 `utf8mb4`；
2. 通过环境变量注入 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；
3. 使用 dev profile 启动应用；
4. 确认 HikariCP 建立连接；
5. 确认 Flyway 执行或确认 `V1__baseline.sql` 已处于成功状态；
6. 确认应用成功启动；
7. 请求 `GET /api/health`，确认 HTTP 200 和既有响应；
8. 请求 `/v3/api-docs`，确认 OpenAPI 仍可用；
9. 正常终止应用；
10. 删除专用验证数据库或按本地管理约定清理，不得操作共享或生产数据库。

不得把个人长期数据库密码写入 shell 脚本、日志、PR 或任务结果。

### 10.4 Git 检查

提交前执行：

```bash
git diff --check
git status --short
```

确认没有真实凭据、`.env`、数据库数据文件、容器数据、日志、IDE 文件、Maven 缓存或构建产物。

### 10.5 受限环境处理

若 Codex Cloud 因 Maven Central HTTP 403、Docker 不可用、镜像拉取失败或其他环境限制无法执行验证：

- 保留准确的命令、退出状态和关键错误摘要；
- 明确区分“代码审查通过”“未执行”和“运行验证通过”；
- 不将未执行项标记为成功；
- 不使用 H2 替代 MySQL 8；
- 不关闭或删除 Testcontainers 测试；
- 在 PR 中提供项目负责人可在本地或 CI 执行的补验命令；
- 将未完成验证列为合并前风险，由项目负责人决定是否合并。

## 11. Acceptance Criteria

- [ ] 从最新 `develop` 创建 `chore/005-database-infrastructure`。
- [ ] 未直接修改或提交到 `main`、`develop`。
- [ ] Java 21、Spring Boot 和 MyBatis-Plus 版本保持不变。
- [ ] 现有 MySQL 驱动和 MyBatis-Plus 依赖得到复用，没有重复声明。
- [ ] Flyway Core 与 MySQL 支持模块已接入，版本由 Spring Boot 管理。
- [ ] Testcontainers MySQL 测试依赖仅存在于 test scope。
- [ ] `DataSourceAutoConfiguration` 排除已移除。
- [ ] `SecurityAutoConfiguration` 排除仍保留。
- [ ] dev 和 prod 数据源配置职责清楚，敏感值来自环境变量。
- [ ] HikariCP 基础参数可配置且默认值保守。
- [ ] 默认配置未开启 SQL 输出、自动建表或 schema 自动更新。
- [ ] Flyway 使用 `classpath:db/migration`，启动时自动执行并校验。
- [ ] Flyway clean、baseline-on-migrate 和 out-of-order 均未启用。
- [ ] 已添加 `V1__baseline.sql`，且未创建任何业务表。
- [ ] migration README 已说明命名、不可变和后续变更规则。
- [ ] 未同时使用 `schema.sql`、`data.sql`、JPA 自动建表或 Liquibase。
- [ ] MyBatis-Plus 已使用同一数据源完成基础接入。
- [ ] 未在生产源码中创建业务 Mapper、实体或无意义配置类。
- [ ] 数据库测试使用固定 MySQL 8 镜像的 Testcontainers，不使用 H2。
- [ ] 数据源连接测试通过并确认数据库为 MySQL 8。
- [ ] Flyway migration 与重复启动验证通过。
- [ ] MyBatis 测试探针通过 `SELECT 1`。
- [ ] 原有健康接口、异常处理和 OpenAPI 回归测试通过。
- [ ] `./mvnw test` 通过。
- [ ] `./mvnw clean package` 通过。
- [ ] 使用 dev profile 与专用 MySQL 8 的启动验证通过。
- [ ] README 已准确说明配置、migration、测试、启动和当前限制。
- [ ] 未提交任何真实秘密、数据库数据或生成产物。
- [ ] 未实现任何业务表、业务数据库访问或禁止范围内的功能。
- [ ] 使用规定 Commit 并创建目标为 `develop` 的 PR。
- [ ] 未自动合并 PR，等待项目负责人审核。

若 Maven Central 或 Docker 限制导致某项无法执行，该项不能勾选；必须在 PR 中作为未完成验证和合并风险明确列出。

## 12. Git 流程

### 12.1 分支

从最新远程 `develop` 创建：

```text
chore/005-database-infrastructure
```

不得直接向 `main` 或 `develop` 提交，不得强制推送、改写共享历史或处理任务外分支。

### 12.2 Commit

完成实现和所有可执行验证后提交：

```text
feat(server): establish database infrastructure
```

提交必须范围单一，不得使用 `--no-verify` 绕过检查。

### 12.3 Pull Request

- 源分支：`chore/005-database-infrastructure`
- 目标分支：`develop`
- 建议标题：`feat(server): establish database infrastructure`
- 不得创建指向 `main` 的 PR；
- 不得自动合并，等待项目负责人审核。

PR 描述至少包含：

- 背景、目标和实际变更摘要；
- 新增、修改和删除的文件；
- 数据源、HikariCP、Flyway 与 MyBatis-Plus 配置说明；
- Testcontainers 的 MySQL 镜像版本；
- 实际执行的命令、结果和运行环境；
- DEV-004 Maven 验证债务是否已补齐；
- 未执行验证、已知风险和本地/CI 补验方式；
- 凭据与 migration 的回滚说明；
- 明确声明没有创建业务表或实现业务功能。

## 13. Codex Cloud 完成后的输出要求

执行完成后向项目负责人报告：

1. 完成内容与未完成内容；
2. 新增、修改、删除文件清单；
3. 实际使用的 Java、Spring Boot、MyBatis-Plus、Flyway、MySQL 镜像和 Testcontainers 版本；
4. 数据源、Flyway、migration 与 MyBatis-Plus 的关键配置；
5. 实际执行的测试、构建、依赖检查和启动验证命令及逐项结果；
6. DEV-004 遗留 Maven 验证是否完成；
7. 未执行项、环境限制、风险和与任务契约的任何偏差；
8. 分支名称与 Commit SHA；
9. Pull Request 链接。

在项目负责人确认并合并前，不得进入下一项开发任务。
