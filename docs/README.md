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

## 计划中的文档

以下内容尚未建立，当前不提供虚假链接：

- API 文档；
- 部署与运维指南；
- 安全与威胁模型；
- 测试策略。
