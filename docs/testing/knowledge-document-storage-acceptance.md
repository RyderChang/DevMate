# DEV-016 知识文档接入验收

本记录仅验收原文件后端，关联[任务书](../tasks/DEV-016-knowledge-document-storage.md)、
[ADR 0003](../adr/0003-knowledge-document-storage-and-recovery.md) 和[API](../api/knowledge-documents.md)。
未实施解析、分块、Embedding、Qdrant、RAG 对话、文档前端或部署。
`STORED` 仅代表原字节存储成功，不表示可检索；实现 PR 由所有者审核合并。

## 授权与基线

2026-09-28 所有者明确要求合并 [#29](https://github.com/RyderChang/DevMate/pull/29)，并在本聊天从最新
`origin/develop` 创建 `feature/dev-016-knowledge-document-storage` 继续同一任务。
#29 合并前最新 head `daba2cb3a1b88474b4def7b26ea252b84b5ea140` 的 Storage Preflight 与 Foundation 检查成功，
随后以预期 head 合并，merge commit 为 `c0ad179070c70996a9e308d610205403d0a972e7`。
刷新 `origin` 后从该最新 develop 建立指定分支，起始工作区干净。V1–V5 未修改。

## 本次实测环境

| 项目        | 本次值                                                                                                                           |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 宿主        | Windows 11 家庭版中文 10.0.26200，64 位                                                                                          |
| 编译        | Oracle JDK 21.0.7+8-LTS-245；Maven Wrapper 3.9.10                                                                                |
| 测试运行时  | Linux/amd64，Temurin 21.0.12+8 JRE，固定 digest `677919d2f5cfc06a966b17d7b1b06c177fdf31928c60bcf20ac3016bca8a90b8`               |
| Docker      | Desktop 4.92.0，客户端/服务端 29.8.0，Linux/amd64；docker-java API 1.44                                                          |
| Python / Go | Python 3.13.5 / Go 1.26.5                                                                                                        |
| MySQL       | 8.4.6，digest `c296d65ee6ab3ce2f608c1d1b2bdd3c08b087a5834101d76a6db2e00875216cc`                                                 |
| MinIO       | 固定官方 commit `9e49d5e7a648` 与锁定工具链自建镜像；manifest `22d886b8a16cea295dcbbca55aea28fd8354e72a4e829eb269678ef07d07c923` |
| SDK         | AWS SDK 2.55.6，同步 S3/URLConnection；143 项原有后端依赖版本/作用域不变，新增锁定 30 项                                         |

MinIO 的完整源码 commit、二进制/config 摘要及构建锁见[准备记录](../development/storage-preflight.md)。
本次重新执行构建与四项预检，未以历史环境替代。服务由 Testcontainers 创建，MinIO 9000/MySQL 3306
只绑定宿主 loopback 随机端口，控制台不公开，无共享数据目录或私人账户。凭据、数据库和 bucket 名运行时生成。
真实 HTTP 测试 Tomcat 也只监听 loopback 随机端口。

临时文件的实际权限验证运行于 Linux POSIX；Windows ACL 分支未在本次运行时验收，不宣称原生 Windows 集成测试通过。

## 验收矩阵

| 证据                                     | 覆盖                                                                                                                                                                                                                     |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `DocumentValidatorTest`（5）             | 原始 BOM/SHA、UTF-8/NUL/空白、5 MiB 边界与虚报大小、Unicode 名称和指纹                                                                                                                                                   |
| `DocumentServiceTest`（3）               | 归属先于文件/远端操作、关闭状态、提交异常安全 503 与临时副本清理、日志不泄漏诊断/文件名/定位                                                                                                                             |
| `KnowledgePropertiesTest`（4）           | 默认关闭无需凭据、硬限额、超时/租约余量、非法 endpoint 不回显、prod 禁止 HTTP                                                                                                                                            |
| `UploadTempFilesTest`（3）               | 多实例持久预算、锁定活动目录/遗留目录、symlink/未知文件保留、所有者权限                                                                                                                                                  |
| `DocumentApiIntegrationTest`（8）        | JWT、user/admin 与跨用户/跨项目/软删除隔离、分页 ties/上限、表单结构、同 UUID 重放、同名独立对象、第五并发 429、认证 OpenAPI 无内部定位                                                                                  |
| `DocumentHttpIntegrationTest`（1）       | 真实 Tomcat multipart：5 MiB 成功、5 MiB+1/6 MiB 文件拒绝、恶意边界安全 400、其他入口在解析前拒绝 multipart、临时 spool 清理与 trace                                                                                     |
| `DocumentLifecycleIntegrationTest`（17） | 真实 MySQL V6 约束/索引/归属、并发幂等与额度、PUT 响应丢失、更新失败/提交后异常、NOT_STARTED 崩溃、迟到 PUT 与删除、项目删除、多扫描实例、重建扫描器恢复、终态不复活、清理失败/精确退避、运维 SQL、24 小时映射和批次上限 |
| `S3ObjectStorageContractTest`（5）       | 真实私有 MinIO 的 PUT/checksum、受限 GET/原 SHA、HEAD token/损坏、匿名拒绝、重复 DELETE/缺失、错误凭据/missing bucket、503 不重复 PUT、有界超时                                                                          |

生命周期测试使用可控 Clock、事务提交同步和 CountDownLatch；不靠任意 sleep 判断并发。
Stub 外部调用断言不在数据库事务中；真实 MinIO 合同单独验证生产适配器，不能把 Stub 结果当成服务商兼容性。
所有 MySQL 验证来自空库 V1–V6，不使用 H2。

## 实际命令与结果

2026-09-28 在上述新环境实际执行：

| 命令                                                                                                                                            | 结果                                                                         |
| ----------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `python -B scripts/storage-preflight/build-minio.py`                                                                                            | 锁定源码/工具链构建成功，镜像摘要匹配                                        |
| `python -B scripts/storage-preflight/verify.py`                                                                                                 | 58 项 Maven hash 锁匹配；143 项基线不变 +30 项 SDK；4 测试，零失败/错误/跳过 |
| `python -B scripts/verify-knowledge-backend.py --tests DocumentLifecycleIntegrationTest,DocumentHttpIntegrationTest,DocumentApiIntegrationTest` | 修复夹具后 26 项通过，零失败/错误/跳过；局部开发验证                         |

`python -B scripts/verify-knowledge-backend.py` 完整运行通过；随后收紧遗留目录夹具，实际执行
`python -B scripts/verify-knowledge-backend.py --tests UploadTempFilesTest`，3 项通过，零失败/错误/跳过。
`python -B scripts/check-knowledge-test-reports.py` 与 `python -B scripts/summarize-tests.py` 在完整运行后通过。

完整后端已执行 122 项（33 组）测试，零失败、零错误、零跳过；八组 knowledge 验收报告门禁通过。
文档检查执行 `node scripts/check-docs.mjs --format-changed origin/develop`，39 份 Markdown、116 个相对链接及格式通过。
`git diff --check`、POM/JSON/Python 语法、工作流/配置格式、V1–V5 内容对比及任务范围检查通过。
Foundation 与 Storage Preflight 的最终 head 状态由实现 PR 的 Checks 提供，不能用本地通过代替 CI。
Windows 编译和固定 Linux JRE 执行流程见[开发指南](../development/local-development.md)，CI 使用 JDK `clean verify`。
原始日志只保留在忽略的本地目录，未发布凭据或诊断正文。

已处理的验证问题：事务异常夹具最初在提交前抛出而触发回滚，改为真实 `afterCommit` 注入；
OpenAPI 断言误命中 `inputTokens`，改为匹配完整字段名；运维 SQL 使用数据库 UTC，而测试 Clock 较早，现从 SQL
提交后的持久化 `next_attempt_at` 对齐时钟。完整迁移测试遗漏 V6 的三张表清单，已补齐精确断言，未降低验证。
固定 Temurin 镜像是 JRE，不能编译，替代脚本明确先在宿主 JDK 编译再执行测试。

## 合并前审查补充验证

2026-09-28 针对 #30 的审查补充修复临时文件故障分类：创建、上传复制或校验读取发生 I/O 故障时，
返回安全的 `503 KNOWLEDGE_STORAGE_UNAVAILABLE`；畸形 UTF-8 仍返回 `400 INVALID_PARAMETER`。
异常响应不包含原始诊断，已创建的临时副本继续释放。API 文档已有该 503 契约，无需修改接口。

先添加创建与复制故障回归测试并执行
`python -B scripts/verify-knowledge-backend.py --tests DocumentValidatorTest`：7 项中 2 项失败，
均复现预期 503、实际 400。修复后增加校验读取故障测试并执行
`python -B scripts/verify-knowledge-backend.py --tests DocumentValidatorTest,DocumentServiceTest,UploadTempFilesTest`：
三组共 14 项通过，零失败、零错误、零跳过，其中 `DocumentValidatorTest` 现在为 8 项。
两次命令均先设置 `JAVA_HOME` 为宿主 JDK 21，再使用上述固定 Linux JRE。

上述为匹配本次修复的局部验证；前文 122 项完整结果属于初验，未作为补充修复后的全量结果。
最新提交的完整 CI 状态以 #30 的 Checks 为准。

## 风险、运行前置与回滚

唯一 PUT 的结果未知且对象缺失时，容量与定位可能持续保留，直到取得可信结束证据；五次重试耗尽不伪装为失败清理成功。
开启恢复后的多实例扫描仍通过 MySQL 领取，没有依赖内存队列。数据库提交未知返回安全 503，同 UUID 查询最终结果。
关闭存储会暂停远端清理；恢复需重启启用，已持久化记录继续处理。
对象已物理删除后不能回收；24 小时保留的是最小请求终态，备份副本遵循另行确定的保留政策。
上线前需所有者准备 HTTPS、私有 bucket、最小权限账户、专用临时目录和备份/删除政策，本任务未发布任何环境。

回滚关闭新存储操作并保留 V6 数据及对象，不能清库、删 bucket 或改写已执行 migration。
人工重试只恢复核对/清理，完整步骤和安全 SQL 见开发指南。仅声明本次锁定 MinIO/SDK 组合实测，其他 S3 服务商未验证。

建议下一任务单独确认文档解析、分块及索引状态和解析器安全边界，本次不实施。

## 本任务变更文件

以下 65 个文件均属于 DEV-016；PR diff 提供逐行审查。未改动前端源码、已有 migration 或部署目录。

```text
.github/workflows/foundation.yml
README.md
devmate-server/README.md
devmate-server/pom.xml
devmate-server/src/main/java/com/devmate/common/api/ErrorCode.java
devmate-server/src/main/java/com/devmate/common/exception/GlobalExceptionHandler.java
devmate-server/src/main/java/com/devmate/common/web/TraceIdFilter.java
devmate-server/src/main/java/com/devmate/conversation/service/ConversationTransactionService.java
devmate-server/src/main/java/com/devmate/knowledge/application/DocumentRecovery.java
devmate-server/src/main/java/com/devmate/knowledge/application/DocumentService.java
devmate-server/src/main/java/com/devmate/knowledge/application/DocumentTransactions.java
devmate-server/src/main/java/com/devmate/knowledge/application/DocumentValidator.java
devmate-server/src/main/java/com/devmate/knowledge/application/ObjectLocation.java
devmate-server/src/main/java/com/devmate/knowledge/application/ObjectStorage.java
devmate-server/src/main/java/com/devmate/knowledge/application/RemotePhase.java
devmate-server/src/main/java/com/devmate/knowledge/application/Reservation.java
devmate-server/src/main/java/com/devmate/knowledge/application/StorageFailure.java
devmate-server/src/main/java/com/devmate/knowledge/application/StorageState.java
devmate-server/src/main/java/com/devmate/knowledge/application/UploadSlots.java
devmate-server/src/main/java/com/devmate/knowledge/application/ValidatedDocument.java
devmate-server/src/main/java/com/devmate/knowledge/application/Verification.java
devmate-server/src/main/java/com/devmate/knowledge/config/KnowledgeConfiguration.java
devmate-server/src/main/java/com/devmate/knowledge/config/KnowledgeProperties.java
devmate-server/src/main/java/com/devmate/knowledge/config/KnowledgeWebConfiguration.java
devmate-server/src/main/java/com/devmate/knowledge/controller/DocumentController.java
devmate-server/src/main/java/com/devmate/knowledge/controller/UploadAdmissionFilter.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/DisabledObjectStorage.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/DocumentMapper.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/DocumentRequestRow.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/DocumentRow.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/S3ObjectStorage.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/UploadTempFiles.java
devmate-server/src/main/java/com/devmate/knowledge/vo/DocumentResponse.java
devmate-server/src/main/java/com/devmate/project/mapper/ProjectMapper.java
devmate-server/src/main/java/com/devmate/project/service/ProjectService.java
devmate-server/src/main/java/com/devmate/project/vo/DeletedProjectReference.java
devmate-server/src/main/resources/application.yml
devmate-server/src/main/resources/db/migration/README.md
devmate-server/src/main/resources/db/migration/V6__create_knowledge_document_storage.sql
devmate-server/src/test/java/com/devmate/database/ConversationMigrationIntegrationTest.java
devmate-server/src/test/java/com/devmate/database/DatabaseInfrastructureIntegrationTest.java
devmate-server/src/test/java/com/devmate/database/MySqlIntegrationTestBase.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentApiIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentHttpIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentLifecycleIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentServiceTest.java
devmate-server/src/test/java/com/devmate/knowledge/DocumentValidatorTest.java
devmate-server/src/test/java/com/devmate/knowledge/KnowledgePropertiesTest.java
devmate-server/src/test/java/com/devmate/knowledge/S3ObjectStorageContractTest.java
devmate-server/src/test/java/com/devmate/knowledge/UploadTempFilesTest.java
docs/README.md
docs/adr/0003-knowledge-document-storage-and-recovery.md
docs/api/knowledge-documents.md
docs/architecture/README.md
docs/development/local-development.md
docs/development/storage-preflight.md
docs/requirements/devmate-baseline.md
docs/tasks/DEV-016-knowledge-document-storage.md
docs/testing/knowledge-document-storage-acceptance.md
scripts/README.md
scripts/check-knowledge-test-reports.py
scripts/retry-knowledge-document.sql
scripts/storage-preflight/backend-baseline.lock.json
scripts/storage-preflight/verify.py
scripts/verify-knowledge-backend.py
```
