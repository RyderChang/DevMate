# DevMate

DevMate 是围绕软件项目上下文、面向真实研发流程的 AI 助手平台。

> **当前状态：第二阶段——AI 对话。** 仓库已具备 Foundation 能力、前端项目空间，以及默认关闭、
> 可审计且按项目隔离的对话后端和首个 OpenAI Responses 适配器；前端聊天交互尚未实现。

## 核心能力规划

- 基于项目上下文的 AI 对话；
- 面向文档与代码的 RAG 知识库；
- GitHub 仓库只读同步与项目分析；
- 带证据和严重级别的 AI 代码审查；
- 面向 Java/JUnit 的测试场景与测试代码生成。

## 技术栈规划

- 后端：Java 21、Spring Boot 3、Spring Security、MyBatis-Plus；
- 前端：Vue 3、Vite、Element Plus、Axios、Pinia；
- 数据与基础设施：MySQL 8、Redis、Qdrant、MinIO；
- 工程能力：Flyway、OpenAPI、JUnit 5、Testcontainers、Docker Compose、GitHub Actions。

已接入部分的具体版本以构建文件为准；Redis 业务、Qdrant 和 MinIO 尚未接入。AI Gateway
当前只提供 OpenAI Responses 适配器，且默认关闭，不配置密钥也可构建和测试。

## Monorepo 目录

| 目录              | 规划用途                        | 当前状态                           |
| ----------------- | ------------------------------- | ---------------------------------- |
| `devmate-server/` | Spring Boot 后端                | 已具备认证、项目空间和项目对话 API |
| `devmate-web/`    | Vue 3 前端                      | 已初始化，具备认证与项目空间主流程 |
| `deploy/`         | 部署配置与环境模板              | 尚未实现                           |
| `docs/`           | 需求、架构、ADR、API 与开发文档 | 基线建设中                         |
| `scripts/`        | 可复用的本地开发与检查脚本      | 文档链接检查与脱敏测试摘要         |

## 路线图

- [x] 需求与架构基线
- [x] 后端工程基座、认证与项目空间
- [x] 前端认证
- [x] 前端项目空间
- [ ] AI 对话
- [ ] RAG 知识库
- [ ] GitHub 只读分析
- [ ] AI 代码审查
- [ ] 测试生成
- [ ] 管理、可观测性与部署

## 文档与协作

- 从[文档导航](docs/README.md)了解需求、架构与 ADR；
- 按[本地开发指南](docs/development/local-development.md)启动隔离环境；
- [第一阶段验收记录](docs/testing/foundation-acceptance.md)区分验证结果与尚待完成项；
- [Foundation CI](.github/workflows/foundation.yml)执行前后端检查，阶段收口仍需所有者确认；
- 提交改动前阅读[贡献指南](CONTRIBUTING.md)和 [AGENTS.md](AGENTS.md)。

## 安全

不要提交密码、Token、API Key、真实连接串、私有仓库源码或其他敏感内容。示例配置只能使用清晰的占位符。

## License

暂未指定 / To be decided。
