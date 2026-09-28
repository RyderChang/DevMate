# 文档解析与分块 API

DEV-017 实现，受 [ADR 0005](../adr/0005-bound-processing-replays-and-retrieval.md) 与
[任务契约](../tasks/DEV-017-document-processing-boundaries.md) 约束。
路径前缀为 `/api`，JWT 和 `USER` 权限沿用既有文档 API。所有路径均核对用户、项目和文档归属；
跨用户、跨项目、删除项目或删除文档统一返回 `404 Document not found`。

## 显式触发

`POST /api/projects/{projectId}/documents/{documentId}/processing`

请求为 `application/json`：

```json
{ "clientRequestId": "11111111-1111-4111-8111-111111111111" }
```

仅接受严格 UUID 格式并转为小写。策略固定为 `utf8-text-v1` / `text-window-v1`，不接受用户指定策略、
对象位置或参数。只有 `STORED` 文档可处理；上传不会自动触发。首次接受和同 UUID 在途重放返回 202；
处理完成后的重放或新 UUID 绑定同策略活动代返回 200。请求体不含正文。

同 UUID 的指纹包含文档 ID、原 SHA 和首次冻结的策略。不同指纹返回 409；失败或取消的请求稳定返回 409，
新 UUID 才可创建新尝试。已有在途记录或尚未清完的失败/退役代阻止新代。
已完成活动代的创建请求随活动代保留；绑定它的新 UUID 固定保留首次接受后的 24 小时，重放不续期。
失败或退役创建请求在该代清理后再保留 24 小时。过期 UUID 可重新解释；重用时在同事务中清理该过期映射。
其他过期但未清理记录和已删除父文档的终态映射仍计入额度。
每文档最多 100、每用户/项目最多 10,000 条，满额不影响已有未过期 UUID 重放。

## 查询状态

`GET /api/projects/{projectId}/documents/{documentId}/processing`

统一响应的 `data` 包含：

| 字段            | 含义                                                                      |
| --------------- | ------------------------------------------------------------------------- |
| `latest`        | 最近一次处理记录；从未处理时为 null                                       |
| `active`        | 当前完整发布的处理代；尚无完整代或已撤销时为 null                         |
| `positionBasis` | 固定 `NORMALIZED_UNICODE_CODE_POINT`                                      |
| `indexed`       | 索引启用且当前处理代具备完整活动索引时为 true；`CHUNKED` 本身不代表已索引 |

`latest` / `active` 的摘要字段为 `processingId`、`generation`、`state`、`sourceSha256`、
`normalizedSha256`、`parserVersion`、`strategyVersion`、`chunkCount`、`textBytes`、`errorCode`。
`normalizedSha256` 在完整发布前为 null。状态为 `PENDING`、`PROCESSING`、`CHUNKED`、`FAILED`、`CANCELLED`。
新代失败时，`latest` 可以是 FAILED，同时 `active` 仍指向此前有效的 CHUNKED 代。
暂存期间的数量仅是进度元数据，不能据此读取正文。

接口不返回正文、文件名、对象 key、租约、内部诊断或片段列表。内部 `ProcessingTransactions.readActive`
再次锁定有效归属和父文档，仅读取完整活动代，每页最多 100 块；没有对外片段、预览或下载端点。
显式索引与状态见[文档索引合同](document-indexing.md)，处理发布不自动触发索引。

## 限制与错误

| 情形                   | HTTP 与安全错误                                     |
| ---------------------- | --------------------------------------------------- |
| 无 JWT / 无 USER 权限  | 401 / 403                                           |
| 归属不符或父资源删除   | 404 `Document not found`                            |
| 非法 JSON、UUID 或路径 | 400 `Invalid request parameter`                     |
| 原文件未 STORED        | 409 `Document storage is not complete`              |
| 指纹冲突               | 409 `Processing request fingerprint conflicts`      |
| 失败或取消重放         | 409 `Processing request has terminated`             |
| 在途处理或旧代清理未完 | 409 `Document processing or cleanup is in progress` |
| 请求映射额度不足       | 409 `Processing request capacity exceeded`          |
| 片段额度不足           | 409 `Project chunk capacity exceeded`               |
| 处理或存储开关关闭     | 503 `Document processing is disabled`               |
| 数据库结果未确认       | 503 `Document state cannot be confirmed`            |

后台安全失败码：`INTEGRITY_MISMATCH`、`INVALID_TEXT`、`CHUNK_LIMIT_EXCEEDED`、`PROCESSING_TIMEOUT`、
`STORAGE_TIMEOUT`、`STORAGE_UNAVAILABLE`、`DOCUMENT_DELETED`、`PROCESSING_VERSION_UNAVAILABLE`。
不受支持的冻结策略明确失败，不用当前默认规则解释旧请求。日志不记录原文和上游异常。

处理独立默认关闭，设置 `KNOWLEDGE_PROCESSING_ENABLED=true` 且 `KNOWLEDGE_ENABLED=true` 才接受新任务/领取。
每 60 秒扫描至多 20 个到期记录，实例最多两个执行；实际领取可能晚于到期时间。
单次读取及解析截止 30 秒、租约 2 分钟；暂时读取故障最多重试三次，等待 1/2/4 分钟。
确定性校验失败不自动重试。重领先分批清理旧暂存，再获取新的操作版本。
关闭处理时元数据可读、删除可标记、数据库清理继续；未完成尝试回到 PENDING，旧版本失效。
关闭存储还会暂停远程原文件清理。

原文件 5 MiB，每代 8,192 块 / 8 MiB UTF-8 文本，每项目 256 MiB 文本与预留。
预留 8 MiB，暂存将预留转为已用，发布释放剩余预留；失败残留和退役片段仍占额，清理后释放。
同文档同时至多两代有内容/预留/活动或在途状态。额度不等于 MySQL 磁盘占用。

删除与发布使用同一项目、文档锁序，立即持久化取消与活动代撤销。数据库片段每次清理最多 128 块。
原文件即使先删除，父文档仍保留到片段、额度与处理恢复记录全部清理，再解除请求映射并物理清账。
迁移、验证和回滚见 [DEV-017 验收记录](../testing/document-processing-acceptance.md)。
