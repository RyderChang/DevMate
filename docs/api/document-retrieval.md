# 文档检索 API

DEV-021 实现前冻结，继承 ADR 0005/0007；仅文档检索，不生成聊天回答。

`POST /projects/{projectId}/knowledge/search`，JWT + user authority + 项目归属。

```json
{ "query": "Spring 事务回滚规则", "topK": 5 }
```

query 非空、最多 8000 个 UTF-16 code units、必须完整 Unicode；topK 省略为 5，范围 1–20。
计数包含固定 query prefix、模型内部 NFC 和 EOS，最多 6000 tokens；不截断或调用付费模型。
客户端不能设置模型、归属、来源过滤、服务 URL、score 阈值或检索预算。

200 的统一 Result.data 包含 spec、retrievalId（没有活动来源则 null）、queryTokens、topK、rounds、inspectedPoints、
incomplete、reason 以及 hits。reason 为 TOP_K、EXHAUSTED、NO_ACTIVE_SOURCES、BUDGET_EXHAUSTED 或 SOURCES_CHANGED。
hits 按 score 降序、pointId 升序；包含 pointId、score、documentId、filename、processingId、indexId、
处理/索引代号、解析/分块版本、原 SHA、chunk SHA、ordinal、规范化 code point 区间、行号、text。
score 是同规格 Cosine 相似度，不是答案置信度。text 是不可信纯文本，后续使用者须另做提示词隔离。

每轮刷新最多 100 个活动来源组合，向量端先过滤，再在 MySQL 重校验正文与来源；最多 100 候选/轮、
200 不同点/请求、3 轮。删除/换代后的内容不能作为活动命中返回；预算限制使结果可能不完整。
没有活动来源时直接返回空列表，不调用模型。

404 PROJECT_NOT_FOUND（包括跨归属），400 INVALID_PARAMETER，503 DOCUMENT_RETRIEVAL_DISABLED、
DOCUMENT_RETRIEVAL_UNAVAILABLE，409 DOCUMENT_RETRIEVAL_LIMIT；数据库故障为 503 KNOWLEDGE_DATABASE_UNAVAILABLE。
失败不回退到聊天、其他模型、无过滤查询或字符估算。

没有客户端 requestId，也没有自动 HTTP 重试；再次提交是新的显式查询并重新占额。
每次模型发送前短事务预留现有项目/全局日额度与一条独立操作债务；UNKNOWN 不退款或重发。
索引与查询共享一个无排队的应用推理槽；本地竞争在确定未发送时结束为 FAILED，远端不确定不能据此清账。
终态 journal 首次发送后固定 24 小时清理；UNKNOWN 无 TTL、仅持久结束证据可转终态。
检索默认关闭，运行时与源资格还要求存储/处理/索引开关；付费 CNY 0。
