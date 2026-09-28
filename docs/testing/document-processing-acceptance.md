# DEV-017 解析与分块验收记录

日期：2026-09-28。任务分支 `feature/dev-017-document-parsing-chunking`，基线
`9721fc42030c3014858fd8720126660184b9b520`。Git 直连刷新失败后，通过 GitHub compare API 确认该 SHA
与当时 `develop` identical。#30、#31 已合并。#32 是独立 DeepSeek 对话适配器，本分支不包含其改动。

## 最终 CI 与合并补充

2026-09-28 最终发布提交 `48d34c528cc81d2f191af8bfc45c49256f548887` 的
[Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/36415681962) 全部成功。
`./devmate-server/mvnw -B -f devmate-server/pom.xml clean verify`：162 项，失败、错误、跳过均为 0；
`python3 -B scripts/check-knowledge-test-reports.py` 门禁通过；前端及文档/工作流检查全部通过。
Git 直连失败后用连接器发布，文件树 `6abd4d3835e0df93aac15210473e71212f23e1b5` 与
本地已验证的 `654b620` 完全一致，原提交保留在本地 `refs/devmate/dev-017/prepublication`。

所有者随后明确要求自行合并，[PR #33](https://github.com/RyderChang/DevMate/pull/33) 已合并，
merge SHA `cd511aa6f11516086fe18b3194c5c4c68c43bb03`。
已审查的 [PR #32](https://github.com/RyderChang/DevMate/pull/32) 也已合并，
合并后的 `develop` 为 `4a154c7d48598b752e3765e03afd1fb8ae2140ed`；集成 CI 单独核对。
以下“待合并”“未取得 CI”等表述保留提交时的历史，当前结果以上述补充为准。
本次没有部署或启用解析、真实模型、索引或 RAG。

## 实现范围

新增 V7：处理记录、归属一致的片段、请求映射与项目片段容量；父文档保存活动代和递增代号。
纯 JDK 严格流式 UTF-8 解码，滚动窗口处理 BOM/换行、Unicode 位置、摘要、行号和已确认分块规则。
不持久化完整规范化全文；处理只使用有界内存，异常或线程结束后不留下磁盘临时内容。
所有写入先锁项目、文档、处理记录；远程读取与解析在事务外。
分批暂存和完整清单验证后才发布，清理/重领更新操作版本，旧线程不能继续写入。

POST/GET 仅暴露授权元数据；默认关闭，上传不触发处理。持久化扫描、两个执行槽、租约、
三次暂时读取重试、固定 UUID 保留与原子数量额度均接入。
文档删除及同步项目删除事件在同事务持久化取消；恢复正确性以数据库记录为依据。
原文件清理完成后显式检查派生清理，避免先删父元数据。功能关闭时数据库清理仍可继续。
没有新增依赖、修改 V1–V6、调用付费模型或实施前端、Embedding、Qdrant、RAG。

## 实际验证

- #32 审查：未发现阻塞问题；本轮在原 PR head 复跑四组相关测试 21 项通过，零失败/错误/跳过；
  46 Markdown /152 相对链接与格式通过。GitHub Foundation run 36407689655 成功，head `802cf86c`。
  核对了 [DeepSeek 官方协议](https://api-docs.deepseek.com/api/create-chat-completion/)，未调用真实模型。
  #32 保持待所有者合并，不在本分支修改。
- 宿主 JDK 21：`mvnw.cmd -B -ntp -o test -Dtest=TextChunkerTest`，11 项通过。
- `python -B scripts/verify-knowledge-backend.py --tests DatabaseInfrastructureIntegrationTest,DocumentLifecycleIntegrationTest,ProjectApiIntegrationTest`：
  25 项通过，零失败/错误/跳过；真实隔离 MySQL 8，空库 V1–V7、Flyway validate/idempotence、原文件恢复和项目 API 回归。
- 新增算法/处理：`python -B scripts/verify-knowledge-backend.py --tests TextChunkerTest,DocumentProcessingIntegrationTest`，
  当次 29 项通过，零失败/错误/跳过。
- 首次完整后端 159 项：158 通过、1 个旧迁移版本断言失败；更新 V6 到 V7 的准确版本断言，未降低其余约束。
- 最后一轮完整后端 161 项 /36 组：144 通过、17 个错误，零断言失败/跳过；
  全部错误来自 `DocumentLifecycleIntegrationTest` 的 Spring 上下文启动，原因是隔离 MySQL 连接超时。
  其余组包括真实 MinIO 读取、超限分类和超时合同均通过。完整命令此次未通过，不隐去环境失败。
- `python -B scripts/verify-knowledge-backend.py --tests DocumentLifecycleIntegrationTest,DocumentProcessingIntegrationTest`：
  39 项通过，零失败/错误/跳过；原文件生命周期 17 项和最终处理流程 22 项。
  覆盖此前启动失败组、原 SHA 改变、不支持的冻结版本、两个实例/执行槽及过期 UUID 的旧记录回收。
- `node scripts/check-docs.mjs --format-changed origin/develop`：45 Markdown /154 相对链接与格式通过；
  `application.yml` Prettier 检查和 `git diff --check` 通过。

最终代码涉及的 162 个不同测试已在上述完整运行及定向复验中覆盖，但这不是一次完整命令绿色的结论。
完整门禁以本 PR 最终 head 的 Foundation CI 为准；提交时尚未取得该运行结果。

本地验证仍沿用锁定 Linux Java 21、MySQL 8.4.6 与 MinIO 构建镜像，宿主先编译后在 Linux 执行测试。
原始日志和 Maven XML 只保留在忽略的本地目录；对外仅公布脱敏计数。
不是原生 Windows 网络合同或所有 S3 服务商兼容性结论。

## 文件与回滚

改动集中于 knowledge 处理应用服务、恢复/算法、Mapper、DTO/VO、控制器、开关及 V7；
既有原文件事务清理扩展、project 同事务删除事件与匹配测试；API、任务、迁移、使用说明和测试报告门禁同步更新。
最终文件清单以 PR Files changed 为准。

关闭 `KNOWLEDGE_PROCESSING_ENABLED` 并重启可暂停新处理及领取；授权元数据和删除仍可用。
保留 V7、所有原文件及持久恢复依据，不清库、不编辑已执行 migration。
需要回退应用时须保留 DEV-017 的父文档清理保护；直接回退到 DEV-016 应用不能正确清理新增派生记录，
应使用兼容修复提交或新的纠正 migration。已物理删除的文件和片段不能由开关恢复。
尚未获得本 PR 的 CI、所有者审核/合并或部署结论。索引配置与费用确认仍是独立后续任务。

## 修改文件

```text
README.md
devmate-server/README.md
devmate-server/src/main/java/com/devmate/common/api/ErrorCode.java
devmate-server/src/main/java/com/devmate/knowledge/application/DocumentTransactions.java
devmate-server/src/main/java/com/devmate/knowledge/application/ParsedDocument.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingDeletionListener.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingFailure.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingRecovery.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingService.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingStart.java
devmate-server/src/main/java/com/devmate/knowledge/application/ProcessingTransactions.java
devmate-server/src/main/java/com/devmate/knowledge/application/StorageFailure.java
devmate-server/src/main/java/com/devmate/knowledge/application/TextChunk.java
devmate-server/src/main/java/com/devmate/knowledge/application/TextChunker.java
devmate-server/src/main/java/com/devmate/knowledge/config/KnowledgeConfiguration.java
devmate-server/src/main/java/com/devmate/knowledge/config/ProcessingProperties.java
devmate-server/src/main/java/com/devmate/knowledge/controller/ProcessingController.java
devmate-server/src/main/java/com/devmate/knowledge/dto/ProcessingRequest.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/DocumentMapper.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/ProcessingMapper.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/ProcessingRequestRow.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/ProcessingRow.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/S3ObjectStorage.java
devmate-server/src/main/java/com/devmate/knowledge/vo/ProcessingResponse.java
devmate-server/src/main/java/com/devmate/knowledge/vo/ProcessingSummary.java
devmate-server/src/main/java/com/devmate/project/service/ProjectDeleted.java
devmate-server/src/main/java/com/devmate/project/service/ProjectService.java
devmate-server/src/main/resources/application.yml
devmate-server/src/main/resources/db/migration/README.md
devmate-server/src/main/resources/db/migration/V7__create_document_processing.sql
devmate-server/src/test/java/com/devmate/database/ConversationMigrationIntegrationTest.java
devmate-server/src/test/java/com/devmate/database/DatabaseInfrastructureIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentProcessingIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/ProcessingServiceTest.java
devmate-server/src/test/java/com/devmate/knowledge/S3ObjectStorageContractTest.java
devmate-server/src/test/java/com/devmate/knowledge/TextChunkerTest.java
devmate-server/src/test/java/com/devmate/project/ProjectServiceTest.java
docs/README.md
docs/api/document-processing.md
docs/api/knowledge-documents.md
docs/architecture/README.md
docs/development/document-processing-implementation-plan.md
docs/requirements/devmate-baseline.md
docs/tasks/DEV-017-document-processing-boundaries.md
docs/testing/document-processing-acceptance.md
scripts/README.md
scripts/check-knowledge-test-reports.py
```
