# DEV-028：RAG 质量异常分项诊断记录

日期：2026-10-09。任务书：[DEV-028](../tasks/DEV-028-rag-quality-diagnosis.md)。输入为 [DEV-027 PR #45](https://github.com/RyderChang/DevMate/pull/45) 的忽略目录原始结果；两份输入 SHA-256 与任务书一致。本任务未再调用 DeepSeek 或其他付费模型，未改产品行为。

## 一、Q4、Q6、Q9 的 HTTP 502

| 题  | 显式检索结果                    | RAG POST | 可观察的错误                                                     |
| --- | ------------------------------- | -------- | ---------------------------------------------------------------- |
| Q4  | 200，五个命中，E8 命中、E7 缺失 | 502      | `AI provider returned an invalid response`；无回答、引用和 usage |
| Q6  | 200，五个命中，E10/E11 均命中   | 502      | 同一公开错误；无回答、引用和 usage                               |
| Q9  | 200，五个命中，E16/E17 均命中   | 502      | 同一公开错误；无回答、引用和 usage                               |

三题的显式检索均为一轮、检查 21 个点、`TOP_K` 且 `incomplete=false`。候选正文总字符数分别为 4471、4404、4468；成功的 Q10 为 4816，显式候选长度没有与 502 对应的简单阈值关系。Q4 缺 E7 不会直接映射为这个 502：它仍有五个有效命中；Q6、Q9 的冻结证据均齐全。RAG 的空命中与检索不完整分别有独立的 409 错误。

已确认的**错误码合流**：[RagService](../../devmate-server/src/main/java/com/devmate/conversation/service/RagService.java) 在检索、来源构建后调用网关，并在收到结果后调用输出校验；[DeepSeekChatGateway](../../devmate-server/src/main/java/com/devmate/ai/infrastructure/deepseek/DeepSeekChatGateway.java) 与 [RagOutputValidator](../../devmate-server/src/main/java/com/devmate/conversation/service/RagOutputValidator.java) 都可产生 `AI_RESPONSE_INVALID`。网关把部分上游 4xx、过大/畸形响应、非 `stop` 结束原因、不合规格的 choices/content/usage 映射到该码；输出校验把非严格 `{answer,citationIds}` JSON、未知/重复引用编号或不匹配的内联编号映射到同一错误。现有 [网关单元测试](../../devmate-server/src/test/java/com/devmate/ai/DeepSeekChatGatewayTest.java) 覆盖这些拒绝路径，本次用 Java 21 执行 8 项，失败/错误/跳过均 0。

**逐题根因仍未知**。DEV-027 保存的公开 API 502 响应只有统一消息，没有上游 HTTP 状态、`finish_reason`、原始提供商包、解析阶段或失败 usage；当时的隔离 MySQL 容器已按标签清理。按代码，[RagTransactions.receipt](../../devmate-server/src/main/java/com/devmate/conversation/service/RagTransactions.java) 在网关成功解析后、RAG 正文校验前独立持久化 usage；若当时保留了 `rag_invocation_details.chat_state` 和 `ai_invocations`，可辅助区分阶段，但本次已无可核验快照。不能把三个相同 502 推断为相同模型截断、同一种 JSON 错误或相同扣费。

后续最小诊断改动应在不记录原始提示词、正文、密钥或完整提供商响应的前提下，为每个失败记录受限的阶段代码与 traceId/invocationId，例如上游 HTTP 类别、结束原因类别、网关字段校验类别、RAG JSON/引用校验类别及可确认的 usage 状态；用本地 Stub 分别验证这些分类和权限/日志边界。当前任务不加入这种行为变更，也不重试已用尽授权次数的请求。

## 二、Q4/E7 的 top‑5 缺口

冻结 E7 是 [需求基线](../requirements/devmate-baseline.md)第 24–25 行的“`CHUNKED` 仅表示整代片段发布”。在 DEV-027 的同一活动索引代中，Q10 曾返回包含该行的第 0 号片段（第 1–28 行，chunk SHA 前缀 `1fd4eab20ec9`）；Q4 返回的是同文档第 1 号片段（第 26–50 行，排名 3，score `0.555976`）。两个片段的 `indexId` 均为 6，来源 SHA 相同。这已排除“目标行未分块/未索引/整份文档无活动来源”。

为测定具体排名，独立运行 ID `a001f1b1` 重新索引同样七份冻结公开文档，只把 Q4 原问题的显式检索设为 `topK=20`。HTTP 200；一轮检查 21 个点，返回 20 个命中，`reason=TOP_K`、`incomplete=false`。E7 所在片段为**第 19 名**，score **`0.403412`**；前五名的文件、chunk SHA 与分数均与 DEV-027 两轮 top‑5 完全一致。七份源 SHA 和 20 个返回 chunk SHA 均校验通过。隔离容器已按准确标签清理，诊断原始结果仅在 Git 忽略目录 `tmp/dev-028/retrieval-results.json`。

**确认原因是该查询下的排序截断**：目标片段存在且有资格，但相似度排名远低于固定 top‑5。其片段还包含需求基线的多种主题，而专门的索引合同片段占前两名；“广泛主题稀释了局部事实”是合理假设，尚无单独的因果实验。当前 RAG 固定 top‑5，不能直接把客户端 topK 提至 20。后续可独立比较提问拆分、证据重排或分块方案，并用 DEV-027 冻结题集重新评分；本次不更改检索实现或原 16/17 分数。

## 三、回答中的过度断言与多余引用

| 题  | 原回答的附加说法                                                   | 核查结果                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| --- | ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q1  | 文档检索 API 的未知请求字段会被拒绝为 400                          | **已证伪于当前隔离后端**。[检索请求 DTO](../../devmate-server/src/main/java/com/devmate/knowledge/dto/RetrievalRequest.java)和[检索控制器](../../devmate-server/src/main/java/com/devmate/knowledge/controller/RetrievalController.java)没有专门拒绝该字段；在新建空项目里，普通请求与增加 `unexpected: 1` 的请求都返回 HTTP 200、`NO_ACTIVE_SOURCES`。显式拒绝未知字段的是 [RAG 请求 DTO](../../devmate-server/src/main/java/com/devmate/conversation/dto/RagMessageRequest.java)的 `@JsonAnySetter`。模型把另一个接口的规则套到了检索接口。 |
| Q2  | 固定 query/topK 边界、请求失败或 UNKNOWN 会使检索结果 `incomplete` | **与实现的状态含义不符**。[RetrievalService](../../devmate-server/src/main/java/com/devmate/knowledge/application/RetrievalService.java) 的成功响应在预算耗尽或来源变化时可保持 `incomplete=true`；`TOP_K`、`EXHAUSTED`、`NO_ACTIVE_SOURCES` 为 false。非法 query 返回参数错误，模型/数据库故障抛出错误，不是一个标记 incomplete 的成功结果。核心 E3/E4（score 非答案置信度、预算可使结果不完整）仍正确。                                                                                                                                     |
| Q5  | 在支持 E9 的 C1/C4 之外还列出 C5                                   | **引用不直接相关**。C1/C4 已由[索引合同](../api/document-indexing.md)第 19 行直接证明 E9；C5 是需求基线的阶段状态说明，没有该保留规则。结论本身正确。                                                                                                                                                                                                                                                                                                                                                                                         |
| Q10 | “Qdrant 默认关闭”                                                  | **措辞过宽**。[README](../../README.md)说明的是“Qdrant 文档索引和本地 Embedding 默认关闭”，不能概括为整个 Qdrant 服务默认关闭。资料外生产点数的弃答仍正确。                                                                                                                                                                                                                                                                                                                                                                                   |

已确认的机制边界：[固定提示词](../../devmate-server/src/main/java/com/devmate/conversation/service/ProjectRagPromptBuilder.java)要求使用资料、引用并解释冲突与不确定性，但未约束只回答问题中的事实点或逐个断言绑定证据；[输出校验](../../devmate-server/src/main/java/com/devmate/conversation/service/RagOutputValidator.java)检查 JSON 结构、允许的引用 ID 与内联编号一致性，不验证每句话是否由该引用支持。它允许语义上不相关的合法引用通过。不能由此证明模型为何在这次采样中扩写，但可以确认现有确定性校验不会拦截 Q1/Q2 的语义外延或 Q5 的多余 C5。

后续修复候选为收紧版本化 RAG 提示词中的回答范围和逐断言引用要求，并增加人工/规则可复核的 claim-to-evidence 评估；若要改变 API 的未知字段行为，须另做接口决策和测试，不通过提示词把检索 API 说成 RAG API。任何模型质量复测都需新的明确付费授权，并应与 DEV-027 冻结题集分开记录。

## 验证、限制与建议顺序

- 两份 DEV-027 忽略目录结果的 SHA-256 与预先提交的 DEV-028 任务书相同；诊断只读取公开资料和本地代码。
- `mvn -q -Dtest=DeepSeekChatGatewayTest test`（Java 21）：8 项通过，失败/错误/跳过 0；仅验证本地网关拒绝路径，不代替三次真实提供商响应。
- `python -B tmp/dev-028/probe_unknown.py`：隔离空项目基线和额外字段请求均 HTTP 200、`NO_ACTIVE_SOURCES`，无付费调用。
- `python -B -m py_compile tmp/dev-028/run_local_q4.py`：通过。
- `python -B tmp/dev-028/run_local_q4.py --retrieval-only`：七份文档成功索引，Q4 `topK=20` HTTP 200，E7 排名 19；无付费调用。
- `docker ps -a --filter label=devmate.eval=dev028-a001f1b1`：无残留容器。
- 文档格式、相对链接、Git 空白检查在提交前执行；临时脚本、原始结果与本地服务 journal 不纳入 Git。

建议下一任务先增加 502 的分阶段、脱敏诊断与相应 Stub 回归，之后单独评估排序/重排方案和回答范围约束。三项均应先设独立验收，再决定是否改产品代码。实际 DeepSeek 扣费仍须由所有者在服务商账户核对；本诊断没有取得失败请求的 usage。
