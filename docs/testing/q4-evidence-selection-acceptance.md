# DEV-033：Q4 RAG 候选直接证据验收

日期：2026-10-11。[任务合同](../tasks/DEV-033-q4-evidence-selection.md)先作为 `0e615d0` 提交；独立基线为 `develop` 的 `1a6dab6c67a7a200c08e78e975b4d3be5e0f1e93`。未合并 #45/#46/#49/#50；本修复没有继承它们的提交，历史材料以缓存哈希及公开来源核验。本轮真实 Embedding、Chat 请求均为 **0 次**，没有部署或修改原实验数据。

## 原因、修正与实测边界

原 Q4 Cosine 前五为索引 API 1–20、索引 API 17–38、需求基线 26–50、检索 API 1–24、README 14–40。它们直接覆盖 E8（索引 API 第 19 行），但没有包含 E7（需求基线第 24–25 行），也没有独立的 CHUNKED 标识符。DEV-028 同查询缓存中 E7 的需求基线 1–28 片段在第 19 名，score 0.403412，源和片段 SHA 均合法；原因是排序截断，而非未索引。

新增 `RagEvidenceSelector`，由 `RetrievalService.searchForRag` 使用，RagService 接入该公开应用边界。[ADR 0009](../adr/0009-bound-rag-literal-evidence-selection.md)仍为待所有者审查的提案。只在已取得、当前资格及摘要通过的 Cosine 前 20 候选中补位，最多替换两项，保留全部已覆盖标识符。普通 knowledge/search 继续纯 Cosine topK；没有改变查询、模型规格、轮次、过滤、额度、Schema、提示词或默认开关。

Q4 新五候选保留原前四，替换 README 为需求基线 1–28，按原 score 排在第 5。E7 新位置为 C5（chunk SHA `1fd4eab20ec999280a14bfdeb7c2061e44d728d1db09fe5a8bffcba2e9df1224`），E8 仍由 C1/C2 完整覆盖。测试同时通过实际 `ProjectRagPromptBuilder` 核对：空历史、冻结 Q4 下，五个来源及 E7/E8 均保留在实际提供的来源集合中，没有被输入上限裁掉。这里是源集合覆盖，**没有新模型回答或最终引用/逐断言绑定结论**。

## 冻结全题的缓存重放

公共测试夹具 [q4-evidence-replay.json](../../devmate-server/src/test/resources/rag/q4-evidence-replay.json)只含十个原问题、score、必要的公开文档片段及定位，不含模型回答、完整提示词、调用 UUID、密钥或本地数据库 ID。七份源字节从冻结 Git 提交 `b0b58142af63963bc36ced05df064e4253475794` 核验；两缓存哈希、20 个去重片段哈希均核验通过。夹具 SHA-256：`23513b1cf8a221223151f23f0079ba14d96ca2b31d4392447e6f196f73a93285`。

Q4 重放原前 20；其余九题只有已保存的原前五，不冒称取得了新的完整候选池。原前五已覆盖问题的全部字面标识符，规则在这种情况下严格不变；测试另加任意低分标识符候选验证这种不变性。下表每槽判定均要求**同原文件 SHA 且完整包含冻结证据行**，不是语义正确评分。

| 题  | 原五候选覆盖 | 新 RAG 五候选覆盖 | 回归                                 |
| --- | ------------ | ----------------- | ------------------------------------ |
| Q1  | E1、E2       | E1、E2            | 原五个及顺序不变                     |
| Q2  | E3、E4       | E3、E4            | 原五个及顺序不变                     |
| Q3  | E5、E6       | E5、E6            | 原五个及顺序不变                     |
| Q4  | E8；缺 E7    | E7、E8            | 仅替换原第 5 个                      |
| Q5  | E9           | E9                | 原五个及顺序不变                     |
| Q6  | E10、E11     | E10、E11          | 原五个及顺序不变                     |
| Q7  | E12、E13     | E12、E13          | 原五个及顺序不变                     |
| Q8  | E14、E15     | E14、E15          | 原五个及顺序不变                     |
| Q9  | E16、E17     | E16、E17          | 原五个及顺序不变                     |
| Q10 | 无正向槽     | 无正向槽          | 原五个及顺序不变；未生成或判断新弃答 |

缓存重放来源覆盖由 **16/17 → 17/17**，九道有证据题全部槽覆盖由 **8/9 → 9/9**。普通显式检索 API 的本基准仍为 16/17，不能把其结果当成新 RAG 实际选择结果。本有限十题已经参与问题诊断和规则设计，属于回归基准，不是盲测或通用召回率。

## 实际验证

- `mvnw.cmd -o -B -ntp -f devmate-server/pom.xml '-Dtest=RagEvidenceSelectionTest#q4KeepsFiveCandidatesAndIncludesBothFrozenDirectEvidenceSlots+remainingNineQuestionsKeepTheirCandidatesAndFrozenCoverage' test`：关闭补位、走原 Cosine 路径时，两项按预期失败，分别为 E7 缺失及 16/17，证明回归能够检出原问题。
- Java 21 `mvnw.cmd -o -B -ntp -f devmate-server/pom.xml '-Dtest=RagEvidenceSelectionTest,RagPromptAndOutputTest,RagOutputDiagnosticTest' test`：修复后 **15 项通过**，失败/错误/跳过均 0。覆盖字面词边界、大小写、重复内容、20 候选/8 标识符/2 替换上限、唯一覆盖保留和稳定排序，以及源集合实际进入提示词。首次 Windows 沙箱内测试未完成，已停止，批准后重跑；未把中断运行记为通过。
- Java 21 `python -B scripts/verify-knowledge-backend.py --tests RagEvidenceSelectionTest,DocumentRetrievalIntegrationTest,RagConversationIntegrationTest,RagPromptAndOutputTest,RagOutputDiagnosticTest`：锁定 Linux 运行时、真实 MySQL/MinIO 与模型/向量 Stub，**57 项通过**，失败/错误/跳过均 0（8 项候选、15 项检索、27 项 RAG、7 项提示词/输出）。包含任意低分候选不改变其余题前五、普通 API 仍为 Cosine、删除锚点后的资格重读与排除、实际 RAG 网关收到五个选中来源；原权限、预算、换代、不完整、幂等及失败回归通过。检索独立调用仍一次 Embedding，无新真实模型；本次测试运行容器已由脚本清理。
- `node scripts/check-docs.mjs --format-changed origin/develop`：83 份 Markdown、376 个相对链接及变更格式通过；夹具 JSON 的 Prettier 检查及 `git diff --check` 通过。

## 修改、风险与回滚

修改主代码：RagEvidenceSelector、RetrievalService、RagService；测试：RagEvidenceSelectionTest、DocumentRetrievalIntegrationTest、RagConversationIntegrationTest，以及公共回放夹具；文档：DEV-033、ADR 0009、两份检索/RAG API、导航和本记录。没有新依赖、migration、客户端参数或账单改写。

字面匹配未必是语义支持；前 20 以外、无标识符、超过两次补位、唯一覆盖无法保留或动态输入超限都可能仍有缺口。Q4 缓存通过不能证明新全栈模型质量或生产普遍效果，若需新的真实检索/模型验证须另行明确授权次数、预算和外发范围。

最终 CI 以本 PR 头提交为准。PR 保持草稿、目标 develop，合并由所有者确认；撤销本任务代码和文档可回滚，无需改数据库或重建索引。全题逐断言引用核验与生产输出约束采纳未实施，原 DEV-032 的绑定 UNKNOWN 和本轮额度已用完状态不变。
