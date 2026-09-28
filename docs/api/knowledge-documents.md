# 知识文档 API

关联 [DEV-016](../tasks/DEV-016-knowledge-document-storage.md) 与 [ADR 0003](../adr/0003-knowledge-document-storage-and-recovery.md)。
所有入口要求 Bearer JWT、`user` authority 和当前用户拥有的未删除项目。管理员也不能跨归属访问。
路径没有 `/api` 前缀；响应沿用 `Result`，`code` 为整数，失败 `message` 为受控说明。

## 上传与重放

`POST /projects/{projectId}/documents`，Content-Type 为 `multipart/form-data`。
表单必须恰好包含一个 `file` 文件和一个 UUID `clientRequestId` 字段，不接受额外字段、同名重复字段或 query 参数。

- 仅 UTF-8 `.txt`、`.md`，扩展名大小写不敏感；允许 BOM，原字节保持不变。
- 拒绝空文件、纯空白、非法 UTF-8、NUL；不渲染 Markdown、不执行内容或抓取链接。
- 文件最多 5 MiB（5,242,880 字节），整个请求最多 6 MiB（6,291,456 字节）；客户端 MIME 不作为格式依据。
- 文件名先 NFC 规范化并去除首尾空白，最多 200 个 Unicode code point；拒绝路径分隔符、控制/格式字符、`.`、`..` 及非法 surrogate。
- 每项目最多 100 份未清理文档及 100 MiB 原文件；上传预留、失败和待删除文档均计入容量。
- 同名或同内容的新 UUID 创建独立文档，不覆盖已有对象。并发上传每实例最多 4 个，限流发生在 multipart 解析之前。

首次成功及成功重放均返回 HTTP 200：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "projectId": 1,
    "filename": "notes.md",
    "fileType": "md",
    "byteSize": 7,
    "sourceType": "UPLOAD",
    "storageState": "STORED",
    "failureCode": null,
    "createTime": "2026-09-28T02:00:00Z",
    "updateTime": "2026-09-28T02:00:00Z"
  }
}
```

`STORED` 只确认原始文件已存储。SHA-256、bucket、key、写入 token、内部租约、凭据和下载 URL 不对外返回。
幂等键按用户、项目、UUID 隔离；指纹包含规范化文件名、类型、实际字节数和原始 SHA-256。

| 同 UUID 的情形                      | HTTP 与处理                                        |
| ----------------------------------- | -------------------------------------------------- |
| 指纹不同                            | 409，不修改原记录                                  |
| `UPLOADING`，写入或提交结果尚未确认 | 409，不重复 PUT；继续使用原 UUID 和原文件确认      |
| `STORED`                            | 200，返回原文档                                    |
| `FAILED` 或 `DELETE_PENDING`        | 409，终态不能恢复成功                              |
| 失败/删除已完成清理                 | 清理后 24 小时内 409；超过窗口后允许作为新接入处理 |
| 校验、容量或关闭状态在预留前拒绝    | 不创建映射，后续请求重新校验                       |

首次 503/504 不能单独决定接入终态。调用方不得在结果未知时自动更换 UUID。
成功且未删除的文档持续保留映射；重放同样要求文件和当前归属检查。

## 元数据与删除

| 方法和路径                                               | 行为                                                                                                                    |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `GET /projects/{projectId}/documents?page=1&pageSize=20` | 返回 `PageResult`：`page`、`pageSize`、`total`、`items`；页码 1..10000，页大小 1..100，默认 20；按创建时间倒序、ID 倒序 |
| `GET /projects/{projectId}/documents/{documentId}`       | 返回上述元数据；可见状态仅 `UPLOADING`、`STORED`、`FAILED`                                                              |
| `DELETE /projects/{projectId}/documents/{documentId}`    | HTTP 202 + 成功 `Result<Void>`，立即隐藏，异步清理；待删除时重复 DELETE 仍为 202，清理完成后 404                        |

项目不存在、属于其他用户或已软删除统一 404。正确项目下的文档不存在、跨项目或已隐藏统一 404。
软删除项目后所有文档 API 立即不可访问，扫描器通过 project 公开维护查询发现残留对象。
关闭知识存储时，授权元数据和删除标记仍可用；上传返回 503，物理清理暂停。

## 错误与运行边界

下表的标识为服务端错误枚举名称，响应沿用整数 `code` 与受控 `message`，不新增字符串错误字段。

| HTTP      | 标识/情形                                                                                                                    |
| --------- | ---------------------------------------------------------------------------------------------------------------------------- |
| 400       | `INVALID_PARAMETER`：UUID、分页、正整数 ID、multipart 结构、文件名、内容校验失败                                             |
| 401 / 403 | 未认证 / 无 `user` authority                                                                                                 |
| 404       | `PROJECT_NOT_FOUND`、`DOCUMENT_NOT_FOUND`                                                                                    |
| 409       | `DOCUMENT_REQUEST_CONFLICT`、`DOCUMENT_REQUEST_IN_PROGRESS`、`DOCUMENT_REQUEST_TERMINATED`、`DOCUMENT_CAPACITY_EXCEEDED`     |
| 413       | `DOCUMENT_TOO_LARGE`：文件或请求过大                                                                                         |
| 415       | `DOCUMENT_FORMAT_UNSUPPORTED`；非上传入口拒绝 multipart，或上传 Content-Type 不支持时 `MULTIPART_UNSUPPORTED`                |
| 429       | `DOCUMENT_UPLOAD_LIMITED`：实例上传并发已满                                                                                  |
| 503       | `KNOWLEDGE_SERVICE_DISABLED`、`KNOWLEDGE_STORAGE_UNAVAILABLE`（含临时目录容量/权限不可用）、`KNOWLEDGE_DATABASE_UNAVAILABLE` |
| 504       | `KNOWLEDGE_STORAGE_TIMEOUT`                                                                                                  |

进入 trace filter 后的请求返回服务端生成的 `X-Trace-Id`；认证层提前拒绝的请求不保证该头。
不提供原文件下载、预览或公开 URL。受限读取仅是内部存储边界，DEV-017 解析调用方会再次校验业务归属。
显式处理契约见[处理 API](document-processing.md)。删除会在同事务取消处理并撤销活动代；
原文件即使先清理，父文档和原文件额度仍保留到派生片段、处理预留与恢复依据清完，再物理清账。
OpenAPI 位于已认证的 `/v3/api-docs`，运行配置与人工处理见[本地开发指南](../development/local-development.md)。
