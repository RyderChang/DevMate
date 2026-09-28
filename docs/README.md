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

- [开发任务 DEV-016](tasks/DEV-016-knowledge-document-storage.md)：已确认的知识文档接入与存储边界，覆盖上传、元数据、私有对象存储与失败清理；尚未实施。
- [ADR 0003：知识文档存储与恢复边界](adr/0003-knowledge-document-storage-and-recovery.md)：已接受的存储职责、失败状态、幂等及清理决策。
- [DEV-016 实施前核验](development/storage-preflight.md)：SDK 依赖锁、固定源码 MinIO 测试镜像、运行环境和复现命令；功能尚未实施。
- [DEV-017：文档解析与分块任务边界](tasks/DEV-017-document-processing-boundaries.md)：2026-09-28 已确认确定性解析、版本化片段、接口与资源上限，功能尚未实现；DEV-016 实现见待合并的 [PR #30](https://github.com/RyderChang/DevMate/pull/30)。
- [ADR 0004：文档解析、分块与索引边界](adr/0004-document-processing-and-index-boundaries.md)：Accepted；区分存储、处理和向量投影，索引模型及费用需另行确认。

## 计划中的文档

以下内容尚未建立，当前不提供虚假链接：

- API 文档；
- 部署与运维指南；
- 安全与威胁模型；
- 测试策略。
