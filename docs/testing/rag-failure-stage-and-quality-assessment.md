# DEV-029：RAG 502 阶段证据及分项质量评估

日期：2026-10-10。任务与运行前标准见 [DEV-029](../tasks/DEV-029-rag-failure-stage-and-quality-assessment.md)。对照数据来自未合并的 [DEV-027 PR #45](https://github.com/RyderChang/DevMate/pull/45) 与 [DEV-028 PR #46](https://github.com/RyderChang/DevMate/pull/46)。原始 DEV-027 `tmp/dev-027/results.json` SHA-256 为 `850e1229196331839b05f05f68e8d35b16c514fda2600044fbc747d864b33d95`；DEV-028 top‑20 数据 `tmp/dev-028/retrieval-results.json` SHA-256 为 `024285cc229b985c4a58264616fe510361e5170550329c45927b8971077be205`。两份文件均在 Git 忽略目录，不提交回答或片段正文。

## 一、502 阶段证据

V11 在 RAG invocation 详情中加入受数据库 `CHECK` 约束的 `failure_stage` 与 `failure_category`。只为已分类的 `AI_RESPONSE_INVALID` 失败写入；公开 HTTP 仍为 502，响应不含诊断值。内部排查时按已授权的 invocation ID 联查 `ai_invocations` 和 `rag_invocation_details`，并读取 `status`、`error_code`、`chat_state`、三个 token 用量字段及这两个诊断字段。诊断类别是代码定义的常量，不保存上游错误体、原始模型输出、提示词、密钥或异常 message。

Stub 验证已经取得**阶段可区分**的确定性证据：DeepSeek 模拟非预期 HTTP、非 `stop` 结束及畸形信封分别归入固定类别；RAG Stub 网关抛出 `AI_RESPONSE_INVALID`/`FINISH_REASON` 时，持久阶段为 `PROVIDER_RESPONSE`，无 receipt/usage；网关正常返回但引用 `C9` 不在提供源中时，阶段为 `RAG_OUTPUT`/`CITATION_IDS`，已收到的 30 tokens usage 保留。两种情形重放原 UUID 均不重新调用网关。成功与非 502 失败诊断为空，MySQL 拒绝只写一个字段或阶段/类别不相容的组合。

历史 Q4、Q6、Q9 只有相同的公开 502，旧 schema 没有阶段列，隔离数据库已清理；**三次历史请求的具体失败阶段仍未知**。新复测只能说明新 UUID 对应的阶段，不能倒推旧请求。

截至本记录，DEV-029 的真实付费复测**尚未运行**：自动审批先要求确认向 DeepSeek 发送七份冻结文档的片段，所有者已明确授权；本机执行还需所有者在自己的 PowerShell 隐藏输入密钥。临时脚本 `tmp/dev-029/run_live_rag.py` 已限制为 Q4/Q6/Q9 各一次并从 `origin/develop` 的七份 SHA 校验副本上传，零次新 DeepSeek 请求已发生。该状态仅是等待输入，不应写作三题的新阶段结论。
按 [DeepSeek 官方人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)的 Flash 高峰档，以每次 98,304 输入 tokens 和 1,024 输出 tokens 作保守预算，三次估计约 **0.6144 元**，低于授权的 2 元。实际失败请求可能缺少可见 usage，最终扣费仍以服务商账单为准。

## 二、Q4 检索排序评估

固定题目询问 `CHUNKED` 后是否已建好向量索引、上传或处理是否自动触发索引。冻结 E7 位于 [需求基线](../requirements/devmate-baseline.md)第 24–25 行：`CHUNKED` 仅表示整代片段发布；E8 位于[索引合同](../api/document-indexing.md)第 19 行：上传/处理不自动索引。DEV-027 的 top‑5 检索只覆盖 E8，导致全题集证据槽召回 **16/17**，完整证据题 **8/9**。

DEV-028 对相同七份 SHA、同一 Q4 原问题独立重建索引并扩大显式检索至 topK=20。结果为 HTTP 200、一轮检查 21 点、`incomplete=false`。前五名的文件、片段 SHA 和 score 与原 top‑5 相同；E7 所在片段实际排**第 19**，score `0.403412`，而第五名 score `0.512070`，差 `0.108658`。E7 的来源有效、片段已索引；当前缺口属于该问题下的**相似度排序加 top‑5 截断**，并非来源丢失或权限过滤。E7 所在片段覆盖需求基线第 1–28 行，主题较宽；“主题稀释”仍是假设，尚无因果实验。

| 候选处理                        | 对这次缺口的判断                                                      | 代价及下一步证据                                                    |
| ------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 直接把 RAG topK 改为 20         | 不能仅凭此认定修复：模型仍最多接收 5 个完整片段，且有上下文上限。     | 会改变已冻结的 API 合同和额度；先做候选压缩实验。                   |
| 先取较多候选，再重排并保留 5 个 | E7 已在 top‑20 内，因此有可重排的候选空间；尚无证据证明重排会选中它。 | 需离线对十题和负例评估召回、误排、耗时、额外模型/额度及来源重校验。 |
| 将复合问题拆成两个检索意图      | 可能让 `CHUNKED` 的状态定义更显著；本次未运行变体。                   | 需固定拆分规则和最大查询次数，重新测 17 个证据槽及隔离/额度。       |
| 调整基线文档的分块              | 可能缩短 E7 的宽主题片段；本次未重建变体索引。                        | 需版本化策略并重索引，比较完整题集；不能从单题推断整体提升。        |

因此排序方向的下一步是**冻结候选方案和对照集后离线试验**。top‑20 的 E7 命中只作诊断，不回写 DEV-027 的 16/17 或宣称 RAG 已能引用 E7。

## 三、逐断言引用约束评估

现有 [提示词模板](../../devmate-server/src/main/java/com/devmate/conversation/service/ProjectRagPromptBuilder.java)要求相关引用和证据不足时说明不确定；[输出校验器](../../devmate-server/src/main/java/com/devmate/conversation/service/RagOutputValidator.java)只检验严格 JSON、合法引用 ID 与内联标记成员关系。它不把自然语言拆成原子断言，也不检验“该片段是否支持这句话”。下表针对 DEV-027 成功回答中已发现问题的四题，按原子断言逐项复核；`C` 编号是各题自己的候选，不能跨题混用。

| 题  | 原子断言或引用行为                                                | 判定                                 | 证据与约束缺口                                                                                                      |
| --- | ----------------------------------------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------- |
| Q1  | 检索 API `topK` 默认 5、允许 1–20；客户端不能给 score 阈值        | 支持                                 | C1 对应[检索合同](../api/document-retrieval.md)第 11–13 行。                                                        |
| Q1  | score 是同规格 Cosine 相似度，非答案置信度；无客户端 requestId    | 支持                                 | C1 第 17–19 行、C3 第 29 行。                                                                                       |
| Q1  | 检索 API 未知字段一律拒绝为 400                                   | 与当前接口行为冲突                   | C1/C3 只列参数错误，未声明未知字段规则；DEV-028 隔离请求中额外字段返回 200。回答混入 RAG 请求 DTO 的拒绝规则。      |
| Q1  | RAG 对话固定 topK=5、客户端不可覆盖预算                           | 支持但超出所问                       | C5 的 RAG 合同能支持；增加了另一个接口的叙述，且回答末尾笼统列出 C1/C3/C4/C5，没有逐句绑定。                        |
| Q2  | score 不等于答案可信度，且 hits 按 score 排序                     | 支持                                 | C1 对应检索合同第 17–19 行。                                                                                        |
| Q2  | 预算/轮次耗尽可使成功检索不完整                                   | 支持                                 | C1 第 21–22 行，C3 可辅助说明上限。                                                                                 |
| Q2  | 来源资格变化会影响可返回候选                                      | 支持；不能自动推出 `incomplete=true` | C1/C3 写有来源重校验；回答从“候选变少”跳到“不完整标记”缺少逐状态证据。                                              |
| Q2  | 固定 query/topK 边界、请求失败或 UNKNOWN 都是 `incomplete` 的原因 | 与状态合同混淆                       | 非法 query 是 400；失败和 UNKNOWN 并非带 `incomplete` 的 200 结果。C2/C4 属不同流程，不能证明该因果。               |
| Q2  | reason 枚举未逐项规定 `incomplete` 值                             | 支持的谨慎说明                       | C1 只列枚举；不能用这句抵消前文过宽断言。                                                                           |
| Q5  | 最近索引失败不撤销仍合格的旧活动代                                | 支持                                 | C1/C4 直接含[索引合同](../api/document-indexing.md)第 19 行。                                                       |
| Q5  | C5 可作为上述保留规则的引用                                       | 引用无关                             | C5 是[需求基线](../requirements/devmate-baseline.md)第 26–50 行，没有该保留规则；回答自己也承认 C5 未给出相反规则。 |
| Q10 | 资料不能确定当前生产 Qdrant 点数                                  | 正确弃答                             | 引文无生产点数；不能从检索上限或 `inspectedPoints` 推得生产总数。                                                   |
| Q10 | Qdrant 保存向量；文档索引与本地 Embedding 默认关闭                | 支持                                 | C2 对应需求基线第 19 行，C4 对应 [README](../../README.md)当前状态。                                                |
| Q10 | 整个 Qdrant 服务默认关闭                                          | 措辞扩大                             | C4 只限定“Qdrant 文档索引和本地 Embedding”，未说明服务整体关闭。                                                    |

逐项结果显示：**合法引用 ID 只证明来源可定位，不能证明每个断言被该来源支持，也不能排除多余引用**。对下阶段的约束建议为：版本化提示词要求只答所问、每个事实性句子就近标注最小证据集；离线评分要求原子断言支持率 100%、无矛盾断言、无无关引用，并单列资料外弃答。若改变输出 Schema，可让结构校验保证每个声明都有引用 ID；“引用是否真的支持声明”仍需人工或独立语义评估，不能由 ID 白名单和正则表达式保证。任何提示词/Schema 调整应另立任务，先用同一冻结题集比较，不在本任务改产品回答策略。

## 验证与限制

- `DeepSeekChatGatewayTest`、`RagPromptAndOutputTest`：JDK 21 共 14 项通过，失败/错误/跳过均 0；无真实模型调用。
- `python -B scripts/verify-knowledge-backend.py --tests RagMigrationIntegrationTest,RagConversationIntegrationTest`：固定 Linux/JDK 21、MySQL Testcontainers，26 项通过，失败/错误/跳过均 0；覆盖 V11 从空库和 V9 升级、阶段持久化及重放。
- 增加上游 HTTP 阶段用例后，`python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest` 再跑 25 项，失败/错误/跳过均 0。
- Windows JDK 22 直接执行相同集成套件时，RAG 对话 23 项通过，迁移测试的 Spring context 因本机回环连接失败而未执行；随后用固定 Linux/JDK 21 通过验证，不以 Windows 失败声称 migration 缺陷。
- 旧三次 502 的具体阶段、提供商扣费仍不可恢复。七份冻结文档、十题与一次 top‑20 查询是小样本；排序和语义结论不能外推生产项目。
