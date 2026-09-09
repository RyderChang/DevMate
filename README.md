# DevMate

DevMate 是围绕软件项目上下文、面向真实研发流程的 AI 助手平台。

> **当前状态：Planning / Foundation。** 仓库目前仅建设需求、架构与协作基线，以下产品能力和技术组件均为规划状态，尚不可用。

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

具体版本将在相应工程初始化任务中确定，不代表当前已经安装或接入。

## Monorepo 目录

| 目录 | 规划用途 | 当前状态 |
| --- | --- | --- |
| `devmate-server/` | Spring Boot 后端 | 尚未初始化 |
| `devmate-web/` | Vue 3 前端 | 尚未初始化 |
| `deploy/` | 部署配置与环境模板 | 尚未实现 |
| `docs/` | 需求、架构、ADR、API 与开发文档 | 基线建设中 |
| `scripts/` | 可复用的本地开发与检查脚本 | 尚未实现 |

## 路线图

- [x] 需求与架构基线
- [ ] 工程基座、认证与项目空间
- [ ] AI 对话
- [ ] RAG 知识库
- [ ] GitHub 只读分析
- [ ] AI 代码审查
- [ ] 测试生成
- [ ] 管理、可观测性与部署

## 文档与协作

- 从[文档导航](docs/README.md)了解需求、架构与 ADR；
- 提交改动前阅读[贡献指南](CONTRIBUTING.md)和 [AGENTS.md](AGENTS.md)。

## 安全

不要提交密码、Token、API Key、真实连接串、私有仓库源码或其他敏感内容。示例配置只能使用清晰的占位符。

## License

暂未指定 / To be decided。
