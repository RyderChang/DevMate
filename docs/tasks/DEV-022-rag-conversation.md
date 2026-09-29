# DEV-022：带可验证引用的 RAG 对话后端

日期：2026-09-29。状态：**已授权实施；验收结果见独立记录**。

## 前置与启动条件

- 所有者于 2026-09-29 明确接受 ADR 0008 全部接口、租约与新增额度提议，允许从 #37 最新 head 创建独立依赖分支；#36/#37 暂不合并。
- 实施分支 `feature/dev-022-rag-conversation`，基线为 #37 `0432cafeda9e4be8d30f698a91fb76ffb90d7bab`，PR 以 `feature/dev-021-document-retrieval` 为目标。
- 已核验 #36 `fa21f704232a2e273c4c20d2d6f0a92396f8e87d` 与 #37 上述 head 的 Foundation、Embedding Preflight 均 completed/success；#36/#37 保持未合并。
- 本地 Embedding 沿用已接受 ADR 0007；V1–V9 不修改，新增 V10。验证结果以最终实施提交为准。
- 仅后端，默认关闭；CI 使用 Stub Chat Gateway、合成 Embedding、真实固定 MySQL/Qdrant。不读取私人密钥、不请求真实收费模型，金额授权 CNY 0。
- [API](../api/rag-conversation.md)、[运行说明](../development/rag-conversation.md)、[验收记录](../testing/rag-conversation-acceptance.md)记录冻结合同与实际结果。前端、部署、真实付费调用及合并另行决定。

## 目标与公开边界

conversation 通过 knowledge 公开检索/资格服务取得当前用户、项目的活动来源，再通过提供商无关的 AiGateway 生成有界回答。
不得跨模块读 knowledge Mapper，不把供应商 SDK、向量过滤、原始 HTTP 或模型服务地址暴露给 conversation/API。
保留现有普通聊天行为和 project-chat-v1；新增默认关闭的独立 RAG 路径、project-rag-v1 模板及可核验引用。

已接受 API：

`POST /projects/{projectId}/conversations/{conversationId}/rag-messages`

请求仅含 UUID clientRequestId 和 content。内容 strip 后非空、完整 Unicode、最多 8000 UTF-16 code units，并满足固定 query prefix 后不超过 6000 tokens。
身份来自 JWT CurrentUser，必须有 user authority，并验证活动项目、对话和全部来源归属。
客户端不能提供 owner、model、来源清单、过滤 JSON、预算、URL 或提示词模板。

成功仍用 Result；保留 userMessage、assistantMessage、invocation 摘要，增加 rag 摘要与最多 5 个结构化 citations。
rag 摘要包含 retrievalId、固定 spec、queryTokens、检索轮数/检查点数、模板版本及完成时资格检查时间。
引用 ID 为服务端分配的 C1–C5；来源描述来自可信快照，含文档名、document/processing/index IDs、
处理/索引代、parser/strategy、source/chunk SHA、ordinal、规范化 code point 区间和行号。相似度不表示答案置信度。

固定错误：既有 401/403/404/INVALID_PARAMETER/AI 服务错误保持语义；
RAG_DISABLED 为 503，RAG_CONTEXT_UNAVAILABLE、RAG_RETRIEVAL_INCOMPLETE、RAG_SOURCE_CHANGED、
RAG_REQUEST_CONFLICT、RAG_RECORD_LIMIT 为受控 409。新增数据库状态不可确认错误 RAG_DATABASE_UNAVAILABLE 为 503；完整文案见 API。

## 编排、租约与幂等

1. 先检查 JWT/归属/开关；短事务按项目、对话锁顺序处理请求映射，保存规范化请求摘要、RAG 模式、USER 消息、
   PENDING invocation 和绝对租约截止时间。完整 prompt、检索正文和向量不进入 invocation。
2. 已有同一 clientRequestId 先重放，任何重放都不再次检索、占 Embedding 额度或调用聊天模型。
   模式/内容摘要不匹配为冲突；普通聊天与 RAG 共用既有唯一键，不能相互返回另一模式的结果。
   旧普通聊天记录按原契约兼容，不能因新增字段更改旧请求含义。
3. 事务外调用 knowledge 检索一次：固定 topK=5，继承 100/200/3 预算与原 token/journal，不额外发 query。
   没有来源/命中或检索 incomplete 时记录稳定失败、释放租约，不静默退回普通聊天，不调用 Chat Gateway。
4. 组装有界、带版本的提示词；在发送前通过 knowledge 公开边界重新验证所有实际选中来源。
   来源变化时失败，不重新检索或自动发送第二次聊天请求。
5. 单次同步、非流式 AiGateway 调用位于事务外。输出通过结构和引用验证后，
   在统一项目锁及短事务内重新检查来源、invocation 状态与租约，再原子保存 ASSISTANT、可信引用和实际 usage。
   knowledge 的资格检查须参与同一事务且不访问其他模块 Mapper，防止检查后删除与发布交叉。
6. 来源变动、迟到响应、无效输出或租约失效不得发布助手消息或复活 invocation。
   即使最终未发布，已经取得的实际聊天 usage 仍须记录；不得退款、猜测 token 或自动重发。
   支付/模型结果未知不表示未执行，Embedding UNKNOWN 继续由既有独立账本恢复。

固定 RAG 单请求总截止 11 分钟、绝对数据库租约 12 分钟；覆盖 tokenize 10 秒、Embedding 300 秒、
最多三次 Qdrant 各 30 秒及 Chat Gateway 最多 120 秒并留事务余量。
所有聊天入口须根据在途请求持久化的截止判断，不能由普通聊天两分钟默认值提前抢占 RAG；
普通聊天新请求仍使用现有租约，不把整个系统统一放宽。外部边界使用剩余总时间，禁止自动重试。
实现前核验嵌套检查与网络调用的实际最坏上限；提议值不能当作已测试性能。

## 提示词和输出验证

- project-rag-v1 保持模板指令独立。项目字段、用户内容、历史与检索片段均作为 JSON 编码的不可信数据；
  禁止拼进 developer/system 指令，禁止声明或执行任何工具、URL、shell 或文档内的操作请求。
- 最多 5 个完整片段；检索数据块（包含序列化定位）最多 8000 code points。超限从最低排名整片移除，
  不伪造截断位置或改写原 chunk SHA；不能把被移除来源列入引用白名单。
- 全部输入（指令、项目、数据、历史、当前消息及序列化开销）最多 24000 code points / 96 KiB UTF-8。
  当前消息与必要规则始终保留；先裁最旧历史，再移除低排名来源，仍超限则拒绝发送。
  输出 token 沿用已有硬上限，建议 RAG 最大 1024；客户端无覆盖能力。
- Chat 输出提议为严格 JSON：answer 字符串与 citationIds 数组。只允许这两项，无代码围栏/重复键；
  answer 最多 8000 code points/32 KiB UTF-8，citationIds 最多 5 个且唯一、只来自本次实际提供的 C1–C5。
  内联 [C1] 等编号也必须在 citationIds 中；不能让模型编造文件名、来源版本、坐标或可信 score。
- 输出中的文件/版本信息由服务端可信引用合成。无引用的证据回答、未知编号、畸形 JSON、额外工具输出均明确失败，
  不吞错、不自动修复重试。提示词与 Schema 合同用确定性 fixture 验证，不能据此宣称彻底消除提示词注入。
- 答案事实是否被引用支持仍需人工合成题评估；结构正确和检索非空不证明回答正确。

## 持久化与已接受新额度

使用新的 Flyway V10，禁止修改 V1–V9。
沿用 BIGINT、UTC、明确状态/唯一/检查约束与真实 MySQL 验证。

提议在既有 invocation 保存 mode、请求摘要、模板、retrievalId/spec/安全状态及绝对截止；
引用快照按 invocation_id + citation_id 唯一、最多 5 条，不存完整检索文本、向量、原始模型 JSON或隐藏推理。
文档/处理/索引删除不能级联丢失历史引用的最小来源快照，也不能再次读取或返回已退役来源的片段正文。
重放已成功请求只返回原已保存回答和引用定位；按当前归属检查来源可用性并标注 unavailable，
不重新生成，不伪装仍为当前活动来源。历史回答作为用户会话记录保留，不能保证源文档删除后撤回所有已生成摘要。

新增 RAG 调用/引用为长期用户会话数据，仍需数量上限。保守提议：
每对话最多 1000 个 RAG invocation、每 owner/project 10000 个、全局 100000 个；
每次原子预留 1 个记录及最多 5 条引用（按 5 KiB 逻辑元数据占额，不是物理磁盘保证）。
失败、未知、父项目软删除均占额；同 UUID 合法重放先于限额检查；满额不创建新的失败记录。
资源锁先项目再对话；RAG 记录额度行始终全局再项目/对话，所有占额与释放路径一致。
不借新建会话或删除原文档释放额度。

本任务不新增会话删除/历史清理 API；释放长期记录须后续定义拥有者授权的数据保留策略，
不得用 24 小时 TTL 删除已保存对话引用。此新额度已明确接受；不改既有 Embedding 额度。
不新增收费适配器或价格推断；真实付费测试/启用不属于本任务授权。

## 验收矩阵

| 场景                                                | 必须证明                                                    |
| --------------------------------------------------- | ----------------------------------------------------------- |
| JWT/项目/对话/来源错配、删除、默认开关              | 受控统一响应；外部 Gateway 未调用，无越权正文               |
| 正常 RAG、普通聊天及跨模式相同 UUID                 | 引用来自可信快照；旧聊天兼容；重放无检索/聊天；错配冲突     |
| 没有命中、incomplete、发送前后来源换代              | 不回退普通聊天、不发布失效内容、不自动多发                  |
| 200 点/3 轮、6000/6001、上下文/输出上限             | 沿用真实合同；确定性整片/历史裁剪；当前消息保留             |
| 两入口并发、2 分钟后仍在途 RAG、绝对超时、旧响应    | 同一对话单在途；按持久截止 fencing；终态不复活              |
| prompt 注入、伪引用、重复键、未知字段/工具、坏 JSON | 无工具或执行；Schema/引用白名单失败；原始内容不泄露         |
| MySQL 发送前不可写、回执不可写、模型 UNKNOWN        | 不先发后记、额度不退款、无自动重试；既有 journal 仍可恢复   |
| 新记录额度边界、并发、满额重放、项目/文档删除       | 原子硬限额，不创建失败垃圾或绕过长期记录数量                |
| migration 与全部回归                                | 空 MySQL 8 顺序迁移；旧数据/聊天兼容；无跳过报告与前后端 CI |

无账户自动测试覆盖核心 Service、MockMvc、真实 MySQL，以及既有检索/索引与两家 Chat 适配器。
合成 RAG 样本覆盖 Spring 事务、有意无依据问题、相互矛盾与恶意片段；记录结构合规和人工依据核验，
不声称真实项目召回率、生产吞吐或真实收费模型测试结果。

## 交付与回滚

同任务更新 OpenAPI/API、ADR、配置、运行与验收记录，记录实际文件、命令、commit、PR 和未验证事项。
现有完整后端使用 Maven clean verify 与 knowledge 无跳过门禁；新增 RAG 套件也纳入门禁。
验证规模以最终基线为准，不冻结准备时的测试数量为未来数字。

回滚关闭新 RAG 入口并保留普通聊天、历史引用和既有未知债务恢复；已共享执行的 migration 不删改。
本任务不合并前置 PR、不实现前端/SSE/工具、不执行用户仓库代码、不部署。
后续可单独准备前端知识引用展示与真实项目检索/回答质量评价；本任务不启动它们。
