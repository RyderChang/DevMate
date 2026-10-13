# DEV-034：托管费用合同的追加证据审查

日期：2026-10-13，从干净 `0196def` 继续 `chore/dev-034-transient-claim-review` 与[草稿 #53](https://github.com/RyderChang/DevMate/pull/53)。所有者要求自行执行下一步，本阶段仅寻找更紧的官方费用证据并离线复算；不改变预算、范围、模型、payload、门禁或评审环境状态，不发模型请求。

## 审查结论

**仍未证明 40 次请求在 ¥2.00 内的托管费用上界，不重新冻结授权计划。** 本轮取得更精确的公开参考开销与共享上下文算术，但没有取得托管 tokenizer 修订绑定、内容预处理上界或 JSON 模式内部 framing 的合同。机器审查为 `BLOCKED_HOSTED_BOUND`、`NOT_GRANTED`、`liveEnabled=false`、`hostedInputTokenBound=null`、`hostedReservedMicros=null`。

原待审计划 scope `c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750`、执行审查 scope `b11957ebaedeb61e4383fa7bf634be923d354cdfcec07f798a6bfa878f9efd7b`、¥2.00 cap 与已冻结的 **¥84.213760 保守回退**全部保留。新的参考估算和候选算术均不能替换该冻结审查或代表实际/预期账单。真人完整计划、授权交接及评审环境的独立确认仍未完成，真实 Chat/Embedding 新增均为 0。

## 官方资料与可复现范围

已在本机只读下载固定官方 recipe 修订 `8cadfede7063c896b944e7bae05daa3549ae97ea` 的六份文件，以及四份官方文档页面到忽略目录 `tmp/dev-034/hosted-cost-sources`。逐文件 SHA/bytes/固定链接记录在 [cost-contract-sources.json](../../scripts/rag-claim-review/cost-contract-sources.json)，规范摘要 SHA 为 `b0b36d622b22c8ef0ebea8c34566098d2dee975d6983d4ff64cc5d638f3e3a2d`。只读文本/JSON，不运行、构建、import 下载源码，不安装 tokenizer/recipe 或权重。

| 官方来源                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | 本轮能够确认                                                                    | 无法据此确认                                                          |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| [人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 2026-10-13 Flash 高峰输入未命中 ¥2/百万、输出 ¥8/百万；模型路由为 V4.1 Flash    | 执行日不变价或 tokenizer/framing 与固定发布文件相同                   |
| [Token 用量](https://api-docs.deepseek.com/quick_start/token_usage/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | 文档提供离线参考计算，实际处理量以响应 usage 为依据                             | UTF-8 字节加固定 framing 是托管硬保证                                 |
| [Chat 请求合同](https://api-docs.deepseek.com/api/create-chat-completion/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | max_tokens 限制实际生成；输入与生成共享上下文；JSON 输出由 response_format 选择 | 独立输入 token/费用 cap；本轮审查的 schema 未列出可固定输入预算的参数 |
| [JSON 输出指南](https://api-docs.deepseek.com/guides/json_mode/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | 要求消息含 JSON 指令，设置输出上限，可能返回空内容                              | 托管内部 framing/预处理的固定 token 上界                              |
| [recipe README](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/README.md)及[tokenizer 指南](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/docs/tokenizer.md)                                                                                                                                                                                                                                                                                                                                                                          | 参考库用于连接自行提供的推理/传输，tokenizer 应与模型/编码器匹配                | 官方托管服务运行同一固定修订                                          |
| [V4 参考 renderer](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/deepseek-recipe-encoding/src/v4/mod.rs)、[V4.1 扩展](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/deepseek-recipe-encoding/src/v4/dsv41.rs)、[tokenizer 调用](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/deepseek-recipe-encoding/src/tokenizer.rs)及[JSON 序列化](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/deepseek-recipe-core/src/util/json_formatter.rs) | 两条纯文本消息/非思考/无工具下的参考边界；JSON 模式追加 105 UTF-8 bytes         | 托管环境是否同样处理用户文字、控制标记和 JSON 模式                    |

初次网页抓取部分页面超时，随后已通过官方公开地址取得上述四份 HTML 快照并审查实际内容；不以抓取失败或搜索摘要冒充合同。HTML 的 SHA 固定此次观察，不是未来页面稳定或提供商签名。CI 只校验已审查摘要/算术，不联网重抓或宣称复算未提交的完整源码。

官方用量页另链接 `https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip`。此前两次申请只读检查包内说明/配置，均因自动审批服务“所选模型容量不足”而未执行；这属于审批服务失败，不是安全性否决。所有者随后明确要求重试，本次审批通过且只读检查已完成，结果见下节；没有绕过审批或执行包内演示。

## 官方 tokenizer ZIP 的补查结果

2026-10-13 从官方用量页当前链接取得 ZIP，1911504 bytes，SHA `e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495`。仅读取 ZIP 目录、JSON 与 Python AST/源码文本，不解压执行、不安装 transformers/tokenizer，也不使用演示中的 trust_remote_code。包内有一项目录及三个文件，无 README：

| 包内文件              | bytes   | SHA-256                                                            |
| --------------------- | ------- | ------------------------------------------------------------------ |
| deepseek_tokenizer.py | 277     | `4ab78a66b76bb70fb462c6b240c8721abd4e02219a5fffb2d746bf1d3568c24e` |
| tokenizer_config.json | 3128    | `841f8cf146e3f0ad1082594a31f68ecf7608c20467ef355081333d85bbaeb1cb` |
| tokenizer.json        | 6367096 | `89085f12ef79460ac5f66d1119325ddfc694b4ab209d80bbd81d35f081dc9614` |

配置为 LlamaTokenizerFast、model_max_length=16384、禁自动 BOS/EOS；tokenizer 为无 normalizer 扩展的 BPE。chat_template 没有 V4.1 的 System 控制标记、thinking 或 response_format 分支；演示仅调用文本 encode，并要求 trust_remote_code=True。这些是本包的静态观察，不能据命名推断确切历史模型版本。包内 tokenizer 的 SHA 与原固定 V4.1 发布文件 `c90dfa...` 不同，配置中没有已审查的托管 deepseek-flash 修订绑定。

**16384 是该文件的配置值，不是托管服务的输入/上下文合同，不能用它替换原 1048576 回退或据此把费用压到 ¥2.00 内。** 官方用量页仍以返回 usage 为实际数量依据；单个离线演示没有证明全部四组的内容预处理、非思考前缀与 JSON 内部 framing。补查结论仍是 UNPROVEN，预算和真实发送门禁不变。

新增 [tokenizer_archive.py](../../scripts/rag-claim-review/tokenizer_archive.py)、[固定摘要](../../scripts/rag-claim-review/tokenizer-archive-review.json)与离线负例。摘要 SHA 为 `74b9f3a369c63a370457919ebfe204a0d0aa52a367f4cb8adfe0537f577e84d5`，标记 NOT_GRANTED/liveEnabled=false/usableForPaidReservation=false。默认入口只核对已审查摘要；`--replay-local` 读取本机固定 ZIP 并重算包/成员 SHA、JSON/AST 观察，源码始终作为数据，不联网或执行。CI 运行默认入口与合成负例，不下载或宣称复现未提交的 ZIP。ZIP 摘要独立登记，不改写此前十份来源或原费用审查 scope。

## 新的参考开销与费用算术

[cost_contract.py](../../scripts/rag-claim-review/cost_contract.py) 通过现有生产请求桥逐项重新生成原 40 payload，核对原 SHA/全部字段；正文及候选只在捕获管道/内存中驻留。程序不重写生产模板或执行 recipe。纯数据计算仅适用于固定的 system/user 两个字符串消息、无历史/工具/图像、显式非思考；形状或 scope 漂移固定拒绝。

只对已审查的公开参考实现，沿用原官方 BPE 配置的字节上界：message UTF-8 bytes + 80 bytes 非思考角色前缀；json_mode/combined 的 20 个样本还须加 **105 bytes** 的参考 schema 指令与 Python 风格 JSON schema。该开销来自 renderer 的 ResponseFormat::JsonObject 分支及空格分隔序列化；原 80 bytes 结论仅针对基础 framing，本轮补齐参考 JSON 分支，未回写原证据。

40 个参考输入上界合计 **474284 tokens**，按 40 × 1024 输出及本轮核对高峰价格计算为 **¥1.276248**。这只是固定参考范围内的字节上界算术，**不是托管费用上界**，不能据低于 ¥2.00 的结果发出请求。每题仅保存题组、原 payload SHA、数值开销与 `usableForPaidReservation=false`，不保存 prompt/摘录。

官方 Chat 文档还给出共享上下文约束。若输入 I、实际输出 O 满足 `I + O ≤ 1048576`，且 `O ≤ 1024`，价格为 `2I + 8O` micros，则最大值在 `O=1024`：

```text
每次候选 = 2 × (1048576 − 1024) + 8 × 1024 = 2103296 micros
40 次候选 = 84131840 micros = ¥84.131840
与 ¥2.00 的差额 = ¥82.131840
```

这是基于已审查文档的联合约束推导，比原分别取输入/输出最大值更紧，但仍远超预算；没有重核所有失败计费和执行日价格，也未将其冻结为可发出的新授权。原审查的 ¥84.213760 保守回退保持不变。

## 需要取得的证据

按固定 40 次 × 1024 输出，高峰输出预留为 `40960 × 8 = 327680 micros`。要满足 ¥2.00，必须证明整个范围的输入 token 上界不超过：

```text
(2000000 − 327680) ÷ 2 = 836160 tokens
```

等额分配时每次阈值为 20904 tokens；这是预算要求，不是 API 参数或已经证明的输入限制。原字节/framing 假设合计 534812 input tokens，剩余 301348 tokens 仅为假设下的算术空间，不能当作已获安全余量。

后续证据须覆盖所有四组：托管 deepseek-flash 与可固定 tokenizer 修订的绑定；用户文本和控制标记的预处理/扩展上界；完整消息 framing，尤其 JSON 模式的内部注入上界；输出上限、失败/未知计费及执行日价格。一组历史 usage、参考 tokenizer 结果、比例经验、用户报告或自行选择更大的 framing 常数均不能证明完整合同。本轮没有付费探测、改范围、借用缓存/空闲折扣、改预算或向提供商发送消息。

## 离线复现与剩余边界

```powershell
python -B scripts/rag-claim-review/cost_contract.py
python -B scripts/rag-claim-review/cost_contract.py --verify-sources
python -B scripts/rag-claim-review/tokenizer_archive.py
python -B scripts/rag-claim-review/tokenizer_archive.py --replay-local
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
```

默认入口重算 40 payload、参考开销及费用阈值，与 [cost-contract-audit.json](../../scripts/rag-claim-review/cost-contract-audit.json) 比较；新审查 SHA 为 `37e71d794d657379c732a6d186699651c0e86a963cecb259cdbc8dd08584d43d`，它是证据审查摘要而非新授权 scope。`--verify-sources` 只读取上述十份本机快照并逐 bytes/SHA 复现，额外检查参考 JSON 分支字符串，不执行下载源码；缺失/漂移固定失败，不下载或跳过。必须使用现有 JDK21/生产桥与固定 Git 资料；默认流程无密钥或网络。

新离线回归覆盖 40 payload 完全复算、JSON 开销、预算阈值、联合约束/旧回退不变、UNPROVEN/null 不升级、题组/数值/价格/shape/scope 漂移、源码复现固定错误与无网络/exec、正文/payload 不进入摘要；ZIP 补查还覆盖成员/profile 漂移、恶意源码仅作 AST 数据、配置长度不冒充托管限制、缺失归档与关闭门禁。Foundation 自动发现新测试并运行默认费用/ZIP 摘要审查，不运行可选快照/ZIP 复现。原锁、账本、HTTPS/真实授权关闭入口和所有历史结果均保留。回滚撤回本阶段审查脚本/测试/摘要、离线 CI 新命令和文档，不触碰冻结计划、历史账本或生产数据。
