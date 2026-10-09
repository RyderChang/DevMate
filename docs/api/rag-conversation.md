# RAG 对话 API

[ADR 0008](../adr/0008-bound-rag-conversation-and-citations.md) 已于 2026-09-29 接受。
RAG 请求是同步、默认关闭的后端接口，不声明工具；普通聊天仍使用 `project-chat-v1`。

DEV-023 在现有项目对话前端增加默认关闭的文档问答入口，并扩展历史消息只读响应；[任务说明](../tasks/DEV-023-frontend-rag-citations.md)。

## 请求与权限

`POST /projects/{projectId}/conversations/{conversationId}/rag-messages`

JWT + `user` authority，身份取自 CurrentUser；活动项目与对话必须属于该用户。

```json
{
  "clientRequestId": "3f8e3918-5236-43f0-9178-a3ab9d1a3312",
  "content": "Spring 默认事务回滚规则是什么？"
}
```

UUID 规范化为小写；content strip 后非空、完整 Unicode、最多 8000 UTF-16 code units。
固定 query prefix、NFC/EOS 后精确计数最多 6000 tokens，不截断查询。未知请求字段拒绝为 400；
客户端不能提供 owner、model、来源、过滤器、预算、URL 或模板。

成功使用既有 `Result`，data 含 conversationId、userMessage、assistantMessage、invocation、rag、citations。
消息与 invocation 摘要字段沿用普通聊天；assistantMessage.content 为已验证的 answer，不是原始模型 JSON。

rag 包含 retrievalId、固定 spec、queryTokens、rounds、inspectedPoints、templateVersion=`project-rag-v1`、
checkedAt（UTC ISO 8601，发布时资格检查）及 offsetUnit=`NORMALIZED_UNICODE_CODE_POINT`。
该时间记录原回答的发布资格；重放时不会伪装为新的生成或更新该时间。

citations 最多 5 项，按 citationId 排序，每项包含 citationId、source 与 available。
source 完全由服务端授权快照构造：pointId、documentId、filename、processingId、indexId、processingGeneration、
indexGeneration、parserVersion、strategyVersion、sourceSha256、chunkSha256、ordinal、start、end、startLine、endLine。
start/end 是规范化文本的零基 Unicode code point 半开区间；行号沿用文档处理定位。
不返回引用正文、模型编造的定位或置信度。

## 幂等、失败与历史

普通聊天与 RAG 共用 `(conversation_id, client_request_id)` 唯一键。
新记录同时冻结 mode 和规范化 content SHA-256；同 UUID 跨模式或内容不同为 RAG_REQUEST_CONFLICT。
V10 之前的普通聊天记录默认 CHAT，保留原 UUID 重放语义。

合法 RAG 重放先于记录额度检查，返回原已保存消息、usage 与引用；不检索、不占新的 Embedding 额度、不调用聊天模型。
失败重放返回原稳定错误；仍在途为 AI_REQUEST_IN_PROGRESS；已超总截止的 PENDING 可原子收口为 AI_REQUEST_EXPIRED。
源文档删除或换代后，成功重放仍返回历史回答与最小定位，available=false；不读取退役来源正文。
删除原文档不能撤回已经保存的回答摘要。父项目删除后该 API 仍按活动项目归属返回 404。

`GET /projects/{projectId}/conversations/{conversationId}/messages` 保留原分页、升序和五个消息字段，增加 `evidence` 字段。
成功 RAG 助手消息的 `evidence` 为 `{ "rag": RagSummary, "citations": CitationResponse[] }`；普通聊天、用户消息和旧数据返回 `null`。
`rag.checkedAt` 是原发布时刻，不随历史读取刷新；`citations[].available` 根据当前活动来源重新核验，删除或换代后为 `false`，历史定位仍保留。
历史页最多 100 条、引用最多 500 条；读取不调用检索、Embedding 或聊天模型，也不返回来源正文。
该字段在 RAG 写入口关闭时仍可读取；项目/对话归属、父项目删除和权限规则不变。
普通聊天及 RAG POST 成功响应结构不变。

空命中和 incomplete 均明确失败，不回退普通聊天。发送前、发布时重新验证全部实际提供的来源；
最终资格检查、引用与助手消息写入共享项目锁及短事务。来源变化、过期或坏输出不会发布助手消息。
模型仅提供严格 JSON `answer` 和 `citationIds`；拒绝重复键、尾随 JSON、围栏、额外键、工具输出、未知/重复编号、
无引用回答及未列入 citationIds 的内联 `[C1]` 编号。answer 最多 8000 code points / 32 KiB UTF-8。

V11 起，内部 `rag_invocation_details` 对可分类的 `AI_RESPONSE_INVALID`（HTTP 502）失败原子保存
`failure_stage` / `failure_category`。阶段为 `PROVIDER_HTTP`、`PROVIDER_RESPONSE` 或 `RAG_OUTPUT`；类别为固定安全枚举，
区分上游非预期状态、响应大小/编码/信封/结束原因/正文/用量，以及 RAG JSON 结构/回答内容/引用编号/内联标记。
这些字段不进入公开 API 或历史消息；旧记录、成功及非 502 失败为 NULL。`UNCLASSIFIED` 表示其他网关实现仅报告了统一 502，
不能据此推断提供商细节。阶段记录不含上游错误体、模型原文、提示词或密钥；排障需在受限数据库权限下按 invocation ID 查询。

## 错误

code 沿用统一响应的 HTTP 数值语义；下表名称用于后端状态与文档，message 为固定英文文案。

| 名称                     | HTTP | message                                  |
| ------------------------ | ---- | ---------------------------------------- |
| RAG_DISABLED             | 503  | RAG conversation is disabled             |
| RAG_CONTEXT_UNAVAILABLE  | 409  | No bounded document context is available |
| RAG_RETRIEVAL_INCOMPLETE | 409  | Document retrieval is incomplete         |
| RAG_SOURCE_CHANGED       | 409  | Document sources are no longer eligible  |
| RAG_REQUEST_CONFLICT     | 409  | Request mode or content conflicts        |
| RAG_RECORD_LIMIT         | 409  | RAG record capacity exceeded             |
| RAG_DATABASE_UNAVAILABLE | 503  | RAG state cannot be confirmed            |

既有 401、403、PROJECT_NOT_FOUND/CONVERSATION_NOT_FOUND 404、INVALID_PARAMETER 400、
AI 服务和文档检索错误继续适用，完整正文、提示词、原始输出、密钥与数据库异常不会出现在响应中。

## 固定资源边界

一次检索固定 topK=5，继承每轮 100 候选、累计 200 不同点、3 轮，查询共享既有 token/capacity/journal。
最多 5 个完整片段；检索数据序列化后最多 8000 code points，快照序列化最多 5 KiB。
全部输入连同 JSON 转义及 1 KiB 提供商封装余量最多 24000 code points / 96 KiB UTF-8。
先按低排名移除超限整片，再裁最旧历史以满足总输入限制；必要时再移除低排名整片。
当前问题与规则保留，无法容纳则拒绝。输出 tokens 为 `min(AI_MAX_OUTPUT_TOKENS, 1024)`。

总执行截止 11 分钟、数据库租约 12 分钟，均在首次事务中持久化；普通聊天新请求保留原租约。
各聊天入口根据当前在途请求的持久截止 fencing；聊天模型最多 120 秒，网络预算取剩余总时间与各边界上限的较小值。
同一对话单在途；Chat 最多 4 个无队列工作线程，超时工作线程确实退出前仍占槽；Embedding 保留既有单推理槽。
不自动重试检索或聊天。超时/UNKNOWN 不表示提供商未执行，不能据此退款或发送同 UUID。

RAG 调用长期记录限额：每对话 1000、每项目 10000、全局 100000，每次预留 1 条及 5120 逻辑元数据字节。
失败、UNKNOWN、项目软删除均占额，满额不新建失败垃圾记录；没有 TTL、释放或会话删除 API。
