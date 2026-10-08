# DevMate

DevMate 是围绕软件项目上下文、面向真实研发流程的 AI 助手平台。

> **当前状态：第三阶段——默认关闭的带引用 RAG 对话。** 仓库已具备 Foundation 能力、前端项目空间，以及默认关闭、
> 可审计且按项目隔离的对话后端、OpenAI 与 DeepSeek 对话适配器和同步前端聊天交互。
> 第二阶段已补齐隔离联调工具与验收记录，并于 2026-09-27 经所有者确认收口。
> DEV-016 原文件接入、DEV-017 解析分块与 DEV-018 DeepSeek 对话接入均已合并。
> [DEV-019](docs/tasks/DEV-019-embedding-index-preparation.md) 已交付国内 Embedding 与真实向量合同准备，
> ADR 0007 与全部资源额度已由所有者接受。[DEV-020](docs/tasks/DEV-020-document-vector-indexing.md) 实现显式文档索引、
> 独立清理债务及受控本地模型服务；[索引验收记录](docs/testing/document-indexing-acceptance.md)区分实际测试与后续限制。
> [DEV-021](docs/tasks/DEV-021-document-retrieval.md) 提供完整来源过滤、资格重校验与有界补足的文档检索；默认关闭。
> [DEV-022](docs/tasks/DEV-022-rag-conversation.md) 接入默认关闭的 RAG 对话、可验证来源、幂等与长期记录额度；
> [RAG API](docs/api/rag-conversation.md)与[验收记录](docs/testing/rag-conversation-acceptance.md)说明验证和限制。
> #36/#37/#39 已合并至 `develop`。DEV-023 增加默认关闭的前端文档问答和历史引用展示；生产启用、真实项目质量评估与真实付费调用另行决定。

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

已接入部分的具体版本以构建文件为准；Redis 业务尚未接入；Qdrant 文档索引和本地 Embedding 默认关闭，MinIO 接入原文件存储边界。AI Gateway
提供 OpenAI Responses 与 DeepSeek 官方 Chat Completions 适配器，且默认关闭，不配置密钥也可构建和测试。
国内试用可显式选择 `AI_PROVIDER=deepseek`；配置与真实冒烟步骤见[本地开发指南](docs/development/local-development.md)。

## Monorepo 目录

| 目录              | 规划用途                        | 当前状态                             |
| ----------------- | ------------------------------- | ------------------------------------ |
| `devmate-server/` | Spring Boot 后端                | 已具备认证、项目空间、对话与文档 API |
| `devmate-web/`    | Vue 3 前端                      | 已具备认证、项目空间和项目对话流程   |
| `deploy/`         | 部署配置与环境模板              | 尚未实现                             |
| `docs/`           | 需求、架构、ADR、API 与开发文档 | 基线建设中                           |
| `scripts/`        | 可复用的本地开发与检查脚本      | 文档链接检查与脱敏测试摘要           |

## 路线图

- [x] 需求与架构基线
- [x] 后端工程基座、认证与项目空间
- [x] 前端认证
- [x] 前端项目空间
- [x] AI 对话
- [ ] RAG 知识库（原文件、解析分块、显式索引、文档检索和默认关闭的 RAG 对话已实现；管理页面与真实项目质量评估待实施）
- [ ] GitHub 只读分析
- [ ] AI 代码审查
- [ ] 测试生成
- [ ] 管理、可观测性与部署

## 文档与协作

- 从[文档导航](docs/README.md)了解需求、架构与 ADR；
- 按[本地开发指南](docs/development/local-development.md)启动隔离环境；
- [第一阶段验收记录](docs/testing/foundation-acceptance.md)区分验证结果与尚待完成项；
- [第二阶段验收记录](docs/testing/ai-conversation-acceptance.md)记录对话联调、失败恢复和浏览器证据；
- [Foundation CI](.github/workflows/foundation.yml)执行前后端检查，阶段确认依据见对应验收记录；
- 提交改动前阅读[贡献指南](CONTRIBUTING.md)和 [AGENTS.md](AGENTS.md)。

## 安全

不要提交密码、Token、API Key、真实连接串、私有仓库源码或其他敏感内容。示例配置只能使用清晰的占位符。

## License

暂未指定 / To be decided。
