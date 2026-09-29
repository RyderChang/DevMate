# DEV-021：有界文档检索

2026-09-29，所有者要求“自行实施下一步”，并明确选择“暂不合并，允许从 #36 创建依赖分支”。
分支 `feature/dev-021-document-retrieval` 基于 DEV-020 commit `2a662dde55f62214f6fb5c1f7f36472f1aee6def`；
本 PR 暂以 DEV-020 分支为目标，前置 PR 合并后再改为 develop。本任务不合并 #36。

## 范围与前置

继承已接受的 [ADR 0005](../adr/0005-bound-processing-replays-and-retrieval.md)、
[ADR 0007](../adr/0007-freeze-local-embedding-and-vector-contracts.md)、DEV-020 完整索引。
只实现 knowledge 文档检索公开能力与 JWT API；不实现 RAG 对话、前端、生产部署、付费模型或新的模型规格。
默认关闭。受控本地模型使用同一冻结运行时、单推理 worker 与 6000 tokens 上限。
同一应用内索引与查询共享无排队的推理槽；本地竞争且确定尚未发送时是终止失败，远端未知仍须持久证明。

## 冻结合同

API 与限额在实现前记录于[检索 API](../api/document-retrieval.md)。查询严格使用 ADR 0007 的 query prefix、NFC/EOS 和完整 1024 维。
查询模型用量共享 DEV-020 的项目/全局 UTC 日 token 行，不另开额度；每查询仅一次显式尝试，失败和 UNKNOWN 不退款、不自动重发。
每模型查询先预留一条现有清理债务和 4096 逻辑字节（没有持久向量点），保留有界、独立的最小模型操作 journal；
确定终态记录自首次发送固定 24 小时后有界清理，UNKNOWN 无 TTL。该预留共享现有项目 10,000/全局 100,000 条债务与逻辑容量上限，不能绕过索引额度。
只持久化来源规格、查询摘要、操作 UUID、token/日期、状态、安全错误与耗时；不保存查询正文或向量。
MySQL 不可写时不能新发推理。远程调用均在事务外；模型结束必须有本地服务持久证明，数据库回执失败保留 DISPATCHED/UNKNOWN。

## 检索与可见性

MySQL 先按归属取得最多 100 份未删除文档的完整活动处理/索引组合及固定规格。
Qdrant 强制 owner/project/spec 和完整 source_key；不接受客户端过滤 JSON、模型或 URL。
每轮最多 100 候选，累计最多检查 200 个不同点、最多 3 轮，topK 为 1–20。
每轮刷新资格并排除已检查点，响应校验来源/ordinal/摘要/BIGINT；正文只从授权 MySQL 活动片段读取。
累积、去重、按 score 降序及稳定 point ID 升序排序；旧代与删除后的命中不能占最终 topK。
资格变化后须重校验已累积命中，预算耗尽或资格持续变化时返回明确 incomplete/reason，不能误报无相关内容。
返回来源版本、文件名、规范化 Unicode code point 区间、行号与片段编号，供后续引用；不执行或渲染片段。

## 验收

- 真实 MySQL V1–V9 空库迁移，JWT/归属、默认关闭、参数与统一错误；已有索引/处理/存储回归。
- query prefix/精确计数、6000/6001、模型错误/UNKNOWN、数据库发送前失败和发送后回执失败、共享额度与独立 journal。
- 大量退役/交叉来源点排在有效点之前，跨归属/BIGINT、完整来源过滤、排除去重与排序；真实固定 Qdrant 验证。
- 资格读后删除/换代、累计命中失效、多轮补足、200 点/3 轮预算和不完整标记。
- 无账户 CI、真实冻结 Linux 模型的 query HTTP 冒烟及一个合成检索样本；质量结论仅针对样本，不能代表真实项目检索质量。
- 文档、报告门禁、完整后端和前端 CI；记录实际命令、计数、commit/PR 与限制。

回滚关闭检索开关，保留 V9/journal 和 UNKNOWN 恢复，不修改旧 migration。
RAG 对话是后续独立任务，本分支不实施。
