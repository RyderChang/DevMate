# 架构决策记录

架构决策记录（ADR）用于保存对系统结构、约束或演进具有长期影响的决策，避免决策只存在于对话或代码注释中。

## 状态与编号

ADR 使用四位递增编号，文件名格式为 `NNNN-short-title.md`。状态可为 `Proposed`、`Accepted`、`Superseded` 或 `Rejected`；已经接受的 ADR 不直接改写结论，应由新 ADR 替代并互相引用。

## 索引

- [0000：ADR 模板](0000-template.md)
- [0001：首版采用模块化单体](0001-use-modular-monolith.md) — Accepted
- [0002：AI Gateway 与应用侧会话状态](0002-ai-gateway-and-application-managed-conversations.md) — Accepted
- [0003：知识文档存储与恢复边界](0003-knowledge-document-storage-and-recovery.md) — Accepted；DEV-016 已实施并合并
- [0004：文档解析、分块与索引边界](0004-document-processing-and-index-boundaries.md) — Superseded by 0005；保留原始接受结论
- [0005：处理请求映射与向量检索的有界恢复](0005-bound-processing-replays-and-retrieval.md) — Accepted；所有者明确接受映射额度、固定保留窗口与检索补足修订
- [0006：新增 DeepSeek 官方对话适配器](0006-add-deepseek-chat-provider.md) — Accepted；显式提供商选择，非思考、非流式、纯文本
- [0007：冻结本地 Embedding 与向量恢复合同](0007-freeze-local-embedding-and-vector-contracts.md) — Proposed；DEV-019 核验证据，本地 Qwen、资源额度与未知向量债务等待接受
