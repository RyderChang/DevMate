# DEV-034 新轮离线冻结计划

日期：2026-10-12。本计划属于[DEV-034](../tasks/DEV-034-claim-citation-review.md)，只准备新样本的逐断言评审；没有付费授权，也不决定生产输出约束采纳。所有者已确认离线计划使用 `deepseek-flash` 并显式关闭思考模式；这次确认仅改变待审计划的模型选择。

## 可审查的范围

机器计划为 [new-round-plan.json](../../scripts/rag-claim-review/new-round-plan.json)。完整授权 scope 是该文件的规范 JSON SHA-256：`c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750`。规范化规则为递归键排序、紧凑分隔符、UTF-8、非 ASCII 字符直接编码、禁止非有限数值；文件排版不影响 scope。`plan.py` 先核对硬编码 scope，再重新生成全部 40 个 payload，逐字段比较；任何漂移固定失败。该 SHA 是待授权范围标识，不是有效授权。

| 项目       | 冻结值                                                                                                                                                                    |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 状态       | `authorization=NOT_GRANTED`、`liveEnabled=false`、`readiness=BLOCKED_INPUT_BOUND_PROOF`；没有 HTTP 客户端、密钥读取、live CLI 或真实授权类型                              |
| API        | `POST https://api.deepseek.com/chat/completions`；`application/json`、非流式                                                                                              |
| 模型       | 精确请求标识 `deepseek-flash`；官方当前版本 DeepSeek-V4.1-Flash；`thinking={"type":"disabled"}`                                                                           |
| 输入       | Q1–Q10 × baseline/example/json_mode/combined；每题原五候选；Q4 保留原选择，不应用 #51                                                                                     |
| 次数       | 最多 40 Chat、0 Embedding；每题组最多一次；旋转组序与原实验一致，顺序在机器计划 samples 中冻结                                                                            |
| 上限       | 每次输出 1024 tokens；并发 1、超时 120 秒、响应最多 1048576 bytes；重试 0，不恢复、不补额                                                                                 |
| 外发       | 当前题公开题干、原五候选的文本与冻结引用元数据、生产 v1 系统模板及该组固定配置；空 history 与固定合成 project 元数据                                                      |
| 不外发材料 | 七份完整源仅本机校核；历史回答、历史账本、评审记录、人工备注、私密项目材料均不加入请求                                                                                    |
| 模板       | 直接调用未改变的生产 `ProjectRagPromptBuilder`，`project-rag-v1`；example/combined 追加原实验 `project-rag-json-example-exp-v1` 形状示例                                  |
| 格式       | baseline/example 不设置 response_format；json_mode/combined 固定 `{"type":"json_object"}`；其余参数由固定 payload 冻结，未设置的采样参数使用提供商默认值                  |
| 来源       | 评审 manifest `2ff08dc4060f74fe18cc10112b9988e3fa4bf2c11439699407b65ba3b2f5a506`；公开 Git 快照 `b0b58142af63963bc36ced05df064e4253475794`                                |
| 请求元数据 | 原 #50 头 `5f2862126ba4f553804d8946d5ecd5b6b4987fc8` 的公开夹具完整副本，SHA `5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26`；不导入其 live 程序或提交 |
| 每次预留   | 每个 samples 条目冻结题组、精确 payload SHA、payloadBytes、inputTokenBound、maxOutputTokens 与 reservedMicros；最小 ¥0.032926、最大 ¥0.037198                             |
| 总预留     | ¥1.397304（1397304 micros），拟定总上限 ¥2.00；全部预留按原范围计算，不自行减题、换模型或加额度                                                                           |

`project` 的固定内容是 id 1、name `DEV-027 public docs`、description `Isolated real answer evaluation`、createdAt/updatedAt 为 epoch，来自原实验合同；候选元数据包括原公开文档/处理/索引编号、版本、SHA 与定位，不使用真实用户项目数据。完整值可在请求桥及公开请求夹具审查。原工具只保留七项评审定位，因此另冻结完整公开请求元数据；两份夹具的所有题干和 50 个定位必须逐项相同。

请求桥仅在测试目录中，通过捕获的 stdin/stdout 管道准备 payload。固定测试 ObjectMapper 对 Map 排序，消除 Java 不同进程的 Map 遍历顺序差异；不改生产 builder、模板或 JSON 校验器。生产 builder/请求桥源码的 LF 规范 SHA 也纳入计划。payload 本身与来源文本仅在内存中；提交文件只存公开元数据、SHA、配置和预留，不存回答、完整提示词或来源摘录。

## 正式价格与预留的成立条件

2026-10-12 从[DeepSeek 官方中文价格页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)核验人民币计价：Flash 输入未命中高峰 ¥2/百万 tokens、空闲 ¥1；输出高峰 ¥8/百万、空闲 ¥4。计划全部按高峰且缓存未命中预留，不依赖缓存优惠、账户赠金、旧模拟费率、历史账单或美元汇率。官方价格页会变动，执行日须重新核验。

每次计划输入上界采用原实验的保守规则 `canonicalPayloadBytes + 1024 framing tokens`，输出上界固定 1024。预留 micros 为 `inputTokenBound × 2 + 1024 × 8`，总额逐次相加，无浮点舍入。它是基于字节和 framing 的工程估算；官方[用量说明](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/)提供估算而非此规则的硬保证。**¥1.397304 是该假设下的最坏计划预留，不能宣称是已证明的提供商账单硬上界。**

未来授权前须核实当前模型 tokenizer/聊天 framing 是否被此上界覆盖，或重新提交有证据的输入上界和 scope。返回后发现超上界会保留 usage 并停止，但事后停止不能保证已经产生的费用不超预留。不能用这项停止检查代替发送前的费用证明；尚未满足这项条件时不得把本计划称为可直接启动的付费授权。预算不足或范围变化重新提交完整计划。

模型标识不是不可变权重版本。官方当前文档[Chat Completions 合同](https://api-docs.deepseek.com/api/create-chat-completion/)使用 `deepseek-flash`，目前未列 `deepseek-chat`。不臆测旧 ID 可用或按旧价计费；若执行日模型路由、参数、正式价格或来源范围改变，必须停止并重新冻结。没有查询需要密钥的 models/balance API，也没有试发模型请求。

## 内存驱动与同请求交接

`provider.py` 是非流式信封解码边界；只通过精确类型 `StubTransport` 的 bytes 信封离线演练。适配器仍只接受精确 `StubGrant/OFFLINE_STUB` 与 `StubTransport`，不允许继承类、任意外部 client 或回调替换为网络传输。它不构成已接入的 HTTP 模型客户端。

先核验题组和全部候选，从固定生产 builder 准备精确 payload，匹配对应 plan 条目与预留，然后创建含 payload SHA、plan scope 的唯一 Ticket。发送前保存这些 SHA、UNKNOWN 次数/预留与未知评审凭据。返回必须持有同一内存 Ticket，所有字段未变；信封必须是该次 Stub 交接的同一 bytes 对象，不能传另一份值相等的 Ticket。提供商 id 不作为真实性证明，不持久化 id、原信封或 system_fingerprint。

只从该信封提取 usage；类型、非负值和总数配对必须合法。合法总 usage 先持久化，模型、finish、message、缓存分解、工具/隐藏思考失败仍保留 usage 并停止。缺失/非法 usage、重复 JSON 键、超大/非法 UTF-8、流式数据或歧义信封不能进入窗口；没有合法 usage 时保留 null 与全部预留。成功仅接受精确模型、一个 index=0 的 assistant choice、finish_reason=stop；不展示隐藏思考或执行 tool/function 内容。

信封 content 原样交给现有 Java `RagOutputValidator`。生产校验后实际返回 ID 的顺序决定引用列表，并且只从同 Ticket 的原候选生成 source/chunk SHA、ordinal 和字符/行定位；原有定位类型及一致性检查继续执行。Python 不解析 assistant RAG JSON，也不修复坏输出。usage 在生产结构失败前保存；八类生产结构失败保持未评审，可继续另一未尝试的列定题组；提供商失败、配对失败、未知、usage 超限、保存失败或中断停止 round，不补发失败题组。

Stub 回答、usage、窗口选择全部标为 OFFLINE_STUB/SYNTHETIC_CHOICES。测试证明内存交接协议，不证明任意 bytes 来自真实提供商。未来真实传输必须把请求与响应封装为内部同次调用的不可替换交接，单独审查真实授权、排他锁与跨进程治理、超时/重定向、响应上界和不录制环境；不能只放宽现有 Stub 类型限制。

## 授权前剩余事项与历史边界

提交本计划后停止。所有者尚需审查 scope、次数、预算、费用上界成立条件、真实驱动与本机评审安排，再独立明确授权新轮；模型选择确认不等于这些授权。真人语义验收仍未完成，没有新增窗口真人评审或真实输出。

新增真实 Embedding/Chat 均为 0。DEV-032 已耗尽的 40 次额度、所有者确认的 ¥0.20 / 40 次 / 156015 tokens、完整逐断言评审 0/40、历史全部 claimCitationBinding=UNKNOWN、原结果 SHA `ba267e58263b1de8efa7b9f8656ac6045c0a8a0804b0a857a880c803e727ef59` 均保持。新轮不得补改历史；#50/#51 未合并草稿、#52 已关闭未合并，#53 继续草稿目标 develop；不合并、部署或自动启动新轮。
