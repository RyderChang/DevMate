# DevMate 文档导航

本目录保存 DevMate 的需求、架构决策和后续研发文档。文档中的“目标架构”不代表已经部署。

## 当前文档

- [需求与架构基线](requirements/devmate-baseline.md)：已确认的产品范围、约束与路线图；
- [目标架构概览](architecture/README.md)：系统边界、组件关系和演进原则；
- [架构决策记录（ADR）](adr/README.md)：重要技术决策的索引与模板；
- [开发任务 DEV-001](tasks/DEV-001.md)：仓库治理任务说明；
- [开发任务 DEV-009](tasks/DEV-009-project-workspace.md)：项目空间后端基础能力说明；
- [开发任务 DEV-010](tasks/DEV-010-frontend-authentication.md)：前端认证流程、会话与路由保护说明；
- [开发任务 DEV-011](tasks/DEV-011-frontend-project-workspace.md)：前端项目列表、详情与 CRUD 交互说明。

## 第一阶段验证

- [开发任务 DEV-012](tasks/DEV-012-foundation-acceptance.md)：第一阶段验收与 CI 基线，保留编写时的历史核查记录；
- [本地开发指南](development/local-development.md)：隔离数据库、前后端启动、质量检查及清理；
- [第一阶段验收记录](testing/foundation-acceptance.md)：当前验证证据、限制与待确认事项。

## 第二阶段任务

- [开发任务 DEV-013](tasks/DEV-013-ai-gateway-conversation.md)：统一 AI Gateway、首个模型适配器与项目对话后端任务书。
- [开发任务 DEV-014](tasks/DEV-014-frontend-project-conversation.md)：已实现项目对话前端、同步消息交互、幂等恢复与测试。
- [开发任务 DEV-015](tasks/DEV-015-ai-conversation-acceptance.md)：第二阶段 AI 对话验收与联调基线，覆盖隔离 Stub、失败恢复、权限及浏览器验收。
- [第二阶段验收记录](testing/ai-conversation-acceptance.md)：验证证据、环境限制及 2026-09-27 所有者收口确认。

## 第三阶段任务

- [开发任务 DEV-016](tasks/DEV-016-knowledge-document-storage.md)：知识文档接入与存储边界，覆盖上传、元数据、私有对象存储与失败清理；原文件后端已合并。
- [ADR 0003：知识文档存储与恢复边界](adr/0003-knowledge-document-storage-and-recovery.md)：已接受的存储职责、失败状态、幂等及清理决策。
- [DEV-016 实施前核验](development/storage-preflight.md)：SDK 依赖锁、固定源码 MinIO 测试镜像、运行环境和复现命令；保存准备阶段历史证据。
- [知识文档 API](api/knowledge-documents.md)：上传、元数据、权限、UUID 重放、错误与删除契约。
- [文档处理 API](api/document-processing.md)：显式解析分块、状态、活动代、额度、恢复与删除交接。
- [DEV-017 验收记录](testing/document-processing-acceptance.md)：算法、迁移、处理 API 与竞态的实际验证结果。
- [知识文档接入验收](testing/knowledge-document-storage-acceptance.md)：真实存储合同、MySQL 状态机、竞态、输入及回归证据。

## 文档检索

- [DEV-021](tasks/DEV-021-document-retrieval.md)：有界文档检索，已随 #37 合并至 `develop`。
- [检索 API](api/document-retrieval.md)：归属、完整活动来源、去重补足、用量与不完整结果。
- [检索运行说明](development/document-retrieval.md)与[验收记录](testing/document-retrieval-acceptance.md)：真实 MySQL/Qdrant、query prefix 和受控模型样本。

## 计划中的文档

以下内容尚未建立，当前不提供虚假链接：

- 部署与运维指南；
- 安全与威胁模型；
- 测试策略。

## 国内模型对话接入

- [DEV-018：DeepSeek 官方对话提供商](tasks/DEV-018-deepseek-chat-provider.md)：独立适配器、配置及验证范围；[PR #32](https://github.com/RyderChang/DevMate/pull/32) 已合并。
- [ADR 0006](adr/0006-add-deepseek-chat-provider.md)：提供商选择、协议、安全与成本边界。
- [DeepSeek 接入验收](testing/deepseek-chat-provider-acceptance.md)：实际验证、修改文件与真实冒烟限制。

## 文档处理的后续任务

- [DEV-017：文档解析与分块任务边界](tasks/DEV-017-document-processing-boundaries.md)：确定性解析、版本化片段、接口与资源上限已落实，[PR #33](https://github.com/RyderChang/DevMate/pull/33) 已合并。
- [DEV-019：国内 Embedding 与索引实施准备](tasks/DEV-019-embedding-index-preparation.md)：已交付国内候选、冻结 tokenizer 和真实数据库合同证据。
- [Embedding 准备说明](development/embedding-index-preflight.md)与[核验记录](testing/embedding-index-preflight-acceptance.md)：复现、版本/hash、实际结果与限制。
- [ADR 0007](adr/0007-freeze-local-embedding-and-vector-contracts.md)：Accepted；所有者接受固定本地 Qwen、资源额度与未知写入债务边界。
- [DEV-020：文档向量索引](tasks/DEV-020-document-vector-indexing.md)：显式索引、版本化完整发布和有界恢复。
- [索引 API](api/document-indexing.md)、[运行说明](development/document-indexing.md)与[索引验收记录](testing/document-indexing-acceptance.md)：默认关闭、真实 Linux 模型服务与测试边界。
- [DEV-020 审核修复](testing/document-indexing-review.md)：明确拒绝的操作证明、关闭索引后的实际恢复及回归证据。
- [ADR 0004：文档解析、分块与索引边界](adr/0004-document-processing-and-index-boundaries.md)：Superseded by ADR 0005；区分存储、处理和向量投影，索引模型及费用需另行确认。
- [解析、分块与索引实施安排](development/document-processing-implementation-plan.md)：DEV-017 落地顺序、删除交接及索引备选模型、token 和预算建议；最新实施顺序见 DEV-019。
- [ADR 0005：处理请求映射与向量检索的有界恢复](adr/0005-bound-processing-replays-and-retrieval.md)：Accepted；已接受的资源与检索完整性修订，处理部分已实施。

## 带引用的 RAG 对话

- [DEV-022](tasks/DEV-022-rag-conversation.md)：后端 RAG 已随 #39 合并至 `develop`；原任务书保留实施时的依赖记录。
- [ADR 0008](adr/0008-bound-rag-conversation-and-citations.md)：Accepted，接口、租约、来源快照与长期额度合同。
- [RAG API](api/rag-conversation.md)、[运行与恢复](development/rag-conversation.md)、[DEV-022 验收记录](testing/rag-conversation-acceptance.md)：默认关闭的后端合同及其合并前验收历史。
- [DEV-023](tasks/DEV-023-frontend-rag-citations.md)与[验收记录](testing/frontend-rag-citations-acceptance.md)：默认关闭的前端文档问答、历史引用展示及实际验证结果。
- [DEV-029 评估](testing/rag-failure-stage-and-quality-assessment.md)：RAG 502 阶段证据、Q4 排序与逐断言引用问题。
- [DEV-030](tasks/DEV-030-rag-json-output-diagnostics.md)与[验收记录](testing/rag-json-output-diagnostics-acceptance.md)：Q9 JSON 失败的脱敏细分诊断、验证与限制。
