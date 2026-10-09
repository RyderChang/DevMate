# DEV-027 预冻结 RAG 质量评估记录

日期：2026-10-09。冻结任务书：[DEV-027](../tasks/DEV-027-rag-quality-benchmark.md)，先于本次请求作为 `4a060e9` 提交。基线 `develop` 为 `b0b58142af63963bc36ced05df064e4253475794`。本文按实际执行阶段记录，不用尚未取得的付费回答推断引用质量。

## 阶段一：本地检索

隔离运行 ID `02bc4ee6`。七份冻结公开文档的原文件 SHA-256 均与任务书及上传清单一致，显式处理至 `CHUNKED` 并索引至 `SUCCEEDED`。使用本地 `qwen3-0.6b-1024-cosine-v1`；十题各调用一次 `knowledge/search`，`topK=5`，HTTP 均为 200。全部响应 `incomplete=false`、`reason=TOP_K`、返回五项。50 个返回片段的 `SHA-256(text)` 与 `chunkSha256` 一致。原始请求、全部候选、定位及正文保留在 Git 忽略目录 `tmp/dev-027/retrieval-results.json`。

下表以冻结的原文件 SHA 与目标行区间为依据；数字为证据槽在 top‑5 的首个排名，`—` 表示缺失。

| 题  | 冻结证据槽及命中排名       | 全部命中 |
| --- | -------------------------- | -------- |
| Q1  | E1=1，E2=1                 | 是       |
| Q2  | E3=1，E4=1                 | 是       |
| Q3  | E5=1，E6=1                 | 是       |
| Q4  | E7=—，E8=1                 | 否       |
| Q5  | E9=1                       | 是       |
| Q6  | E10=1，E11=1               | 是       |
| Q7  | E12=2，E13=1               | 是       |
| Q8  | E14=1，E15=1               | 是       |
| Q9  | E16=2，E17=4               | 是       |
| Q10 | 资料外弃答题，无正向证据槽 | 不计入   |

微观证据槽召回为 **16/17（94.1%）**；完整证据题为 **8/9**。Q4 的固定 E7 对应 `docs/requirements/devmate-baseline.md` 第 24–25 行。该题 top‑5 虽出现同文件第 26–50 行，但不含 E7；第 1、2 名来自索引合同，可支持“上传/处理不自动索引”这一 E8 事实。依冻结规则，不以邻近行或推论补算 E7。`TOP_K`、`incomplete=false` 只描述这次检索状态，不能代替事实完整性判定。

## 阶段二：真实回答与引用

尚待运行。此阶段须以每题独立空历史对话提交原问题，最多十次 DeepSeek `deepseek-flash` 请求、总预算不超过人民币 5 元。记录原回答、返回 citation 定位、各 E 槽的事实与引用判定、额外断言、Q10 是否弃答及实际 usage。阶段一的候选不代表之后 RAG 内部那次检索的实际候选；未暴露的提示词片段仍应标为不可观测。

## 已执行验证与限制

- `node scripts/check-docs.mjs --format-changed origin/develop`：冻结任务书格式与相对链接通过（结果文档完成后复跑）。
- `python -B -m py_compile tmp/dev-027/run_live_rag.py`：通过；忽略目录脚本未纳入 Git。
- `python -B tmp/dev-027/run_live_rag.py --retrieval-only`：七份文档索引成功，十次检索 HTTP 200，容器按标签清理；真实聊天调用 0 次。
- `python -B tmp/dev-027/score_retrieval.py tmp/dev-027/retrieval-results.json`：逐槽排名、来源 SHA 与 50 个返回片段 SHA 校验通过。

本地公开资料的小样本仅揭示此次检索的证据覆盖，尚不能得出生成答案与引用充分的结论。付费调用需要所有者在本机终端隐藏输入 API Key；不在文档、命令或聊天中保存密钥。DeepSeek 最终扣费只能由所有者在服务商账户核对。
