# 文档索引 API 与状态合同

本合同在 DEV-020 实现前冻结，继承 [ADR 0007](../adr/0007-freeze-local-embedding-and-vector-contracts.md)。
均要求 JWT、`user` authority 和项目/文档归属；不存在、跨归属、删除后统一 404。

- `POST /projects/{projectId}/documents/{documentId}/indexing`，JSON `{"clientRequestId":"UUID"}`。
  新工作或在途重放返回 202；完整活动同规格复用返回 200；终态原 UUID 返回 200 的冻结结果，不重新执行。
- `GET /projects/{projectId}/documents/{documentId}/indexing`，200 返回最近工作 `latest`、仍有效的 `active` 和 `indexed`。
  无索引时两字段为 null，`indexed=false`。元数据只含索引/处理 ID、代号、规格、原 SHA、块清单摘要、数量、token 用量、状态与安全错误码。

UUID 按 owner/project 唯一并绑定文档、原 SHA、处理代与规格；跨文档重放 409 `DOCUMENT_INDEX_CONFLICT`。
归属先于重放。活动代创建映射保留至退役清理；其他映射自首次接受固定 24 小时，重放不续期。
终态映射保留冻结结果；到期清理前仍计入 100/文档、10,000/owner/project 限额。
提交已到期 UUID 时，在归属校验后的同一短事务仅清理该过期绑定再按新请求处理；有效绑定的重放不会续期。

`PENDING → RUNNING → SUCCEEDED` 只有完整清单、全部向量批确认、来源重新核对及短事务活动引用发布后成立。
已终止错误为 `FAILED`；删除或处理换代为 `CANCELLED`；发送结果无法确认则 `UNKNOWN`。
撤销活动引用后存在清理债务则 `CLEANUP_REQUIRED`；债务清理完成不恢复资格。
最近尝试失败不撤销仍满足来源资格的旧活动代。上传/处理不自动索引。

503 `DOCUMENT_INDEX_DISABLED`、409 `DOCUMENT_NOT_CHUNKED`、`DOCUMENT_INDEX_IN_PROGRESS`、
`DOCUMENT_INDEX_REQUEST_LIMIT`、`DOCUMENT_INDEX_CAPACITY_EXCEEDED`、`DOCUMENT_INDEX_TOKEN_LIMIT`。
数据库故障沿用 503 `KNOWLEDGE_DATABASE_UNAVAILABLE`。异步错误不返回 HTTP 原文或片段正文。

token 额度逐模型尝试按预留时 UTC 日期冻结，成功/失败/UNKNOWN 都不退款。
最多三次工作尝试；首版采用更保守的一次尝试，已终止失败也明确结束整代，所有 UNKNOWN 不自动重发。
向量预留计所有代和已删除父资源的独立债务：1024×4+512 字节/点，其中 512 为逻辑元数据余量，非物理磁盘保证。
债务预留按最坏单点批数加一条模型 UNKNOWN 定位；每条最多四点，最多 20 KiB 定位/逻辑向量数据。
所有额度固定按已接受的任务书上限执行；变更额度须单独决策，不接受客户端覆盖。

模型服务 loopback HTTP：`GET /spec`，`POST /tokenize`，`POST /embeddings`，`GET /operations/{UUID}`。
输入 `{spec, operation_id, input:[text...]}`；计数返回 `counts`（含 EOS），推理返回按 index 定位的 1024 float32 向量和精确 usage。
所有响应绑定冻结 `fingerprint`；启动核对全部 fixture。模型操作 journal 先于推理写入；崩溃遗留 RUNNING 为 UNKNOWN，
超时必须终止并 join 独立 worker 后才记录 TERMINATED；仅客户端超时不算终止。
向量 UNKNOWN tombstone 没有 TTL。没有生产者停止与操作结束证据，扫描清理后仍保留定位和容量并转人工核对。
本阶段不提供人工清账按钮。已确认完成的写入可自动重复删除、完整过滤核对后释放容量。

真实部署、检索和付费不属于本 API。
