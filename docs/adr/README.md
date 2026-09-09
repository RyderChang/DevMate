# 架构决策记录

架构决策记录（ADR）用于保存对系统结构、约束或演进具有长期影响的决策，避免决策只存在于对话或代码注释中。

## 状态与编号

ADR 使用四位递增编号，文件名格式为 `NNNN-short-title.md`。状态可为 `Proposed`、`Accepted`、`Superseded` 或 `Rejected`；已经接受的 ADR 不直接改写结论，应由新 ADR 替代并互相引用。

## 索引

- [0000：ADR 模板](0000-template.md)
- [0001：首版采用模块化单体](0001-use-modular-monolith.md) — Accepted
