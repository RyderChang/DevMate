# DEV-034 执行准备：费用证据、关闭门禁与环境边界

本文件保留 2026-10-12 冻结审查；2026-10-13 新增的关闭 HTTPS 实现与事务账本见[后续说明](claim-citation-https-ledger.md)。原 scope/预算/阻断不变，其中客户端缺失状态是冻结时记录，不代表新代码不存在或已获真实授权。

日期：2026-10-12。从干净 `9673b39b78b7e99721c090e92d33b0c3b57bb638` 继续当前分支和草稿 #53。所有者要求自行执行下一步；本次落实[新轮计划](claim-citation-new-round-plan.md)剩余条件的离线核查与可测试门禁，不构成付费授权，不新增真实模型调用，也不进入生产采纳。

## 已取得的证据与未证明部分

从 DeepSeek 官方模型仓库的固定修订 `2cba9e42aa026125f3ed06c6d98c1db82f7ca027` 下载四份公开文件到忽略目录 `tmp/dev-034/token-evidence`。仅读取 JSON 与 AST 字面量，不 import/exec 下载的源码，不安装 tokenizer/transformers，不下载权重或运行模型。逐文件 URL、字节 SHA、离线核查结论记录于 [token-evidence.json](../../scripts/rag-claim-review/token-evidence.json)，规范 SHA 为 `a7bdc556b1ae31ecbae157182e5d54e9510cf85ba10e40330b4ac85b38578173`。

| 文件                                                                                                                                                | SHA-256                                                            | 核查内容                                                                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| [config.json](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/2cba9e42aa026125f3ed06c6d98c1db82f7ca027/config.json)                     | `8be45ce0476004a3f529fd896115a4a2e800a129ad2d3ec05b16050f52e21879` | max_position_embeddings 为 1048576                                                                     |
| [tokenizer.json](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/2cba9e42aa026125f3ed06c6d98c1db82f7ca027/tokenizer.json)               | `c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b` | 空 normalizer、保留边界的 Split、无前缀空格的 ByteLevel、256 字节基础符号均在 BPE 词表中、无附加前后缀 |
| [tokenizer_config.json](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/2cba9e42aa026125f3ed06c6d98c1db82f7ca027/tokenizer_config.json) | `6ac8c8dc065ed118161d02dd532749ae3f52c243deac27872134fae2f50d8547` | 不自动加 BOS/EOS；model_max_length 为 1048576                                                          |
| [encoding.py](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/2cba9e42aa026125f3ed06c6d98c1db82f7ca027/encoding/encoding.py)            | `502bdaec8a3fd88ebc24c4721a7038fbe42f2063c664638127056107920035c1` | 人工只读核查文本 system+user、非思考生成前缀；程序仅解析固定字符串常量，不执行该文件                   |

对这个已发布参考 BPE 配置，UTF-8 字节各形成初始符号，合并只会减少符号数；空 normalizer 不扩展文本，无模板 token 插入的 ByteLevel 后处理调整定位。因而纯文本内容的参考 token 上界可按其 UTF-8 字节数计。两个字符串消息、空历史/无工具的非思考参考前缀由五个固定字面量组成，共 **80 UTF-8 字节**；这是参考格式的字节上界，不是精确 token 计数，也不是托管 API 的费用证明。新增 source 摘要检查不取代对完整固定源码的人工审查。

[官方用量说明](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/)仍以返回 usage 为实际数量依据；[官方 recipe](https://github.com/deepseek-ai/deepseek-recipe/blob/main/docs/tokenizer.md)要求 tokenizer 与对应模型/编码器匹配。本轮没有取得托管 `deepseek-flash` 对应同一 tokenizer/framing 修订的可执行合同保证，JSON mode 内部是否增加或改写 framing 也未证明。两项均保持 `UNPROVEN`。不能凭本地参考证明把原 `payloadBytes + 1024` 的 ¥1.397304 估算升级为托管账单硬上界；不通过新的付费请求“验证估算”，也不拿历史 usage 填补证明。

## 保守费用结果及冻结执行审查

2026-10-12 再次核验[官方人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)：非思考 Flash 高峰/缓存未命中输入 ¥2/百万 tokens，输出 ¥8/百万。原计划 SHA `c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750`、40 个 payload、组序、v1 模板、1024 输出、40 Chat / 0 Embedding 和拟定 ¥2.00 cap 全部不变。

在无法将更紧的输入证明绑定到托管 API 时，以官方 1M 上下文及固定配置的 **1048576 输入 tokens** 为保守回退，另外预留 **1024 输出 tokens**。输入与输出可能共享上下文，二者分别取上界是保守多预留，不能称为实际或预期账单。

- 每题组：`1048576 × 2 + 1024 × 8 = 2105344 micros`，即 **¥2.105344**。
- 原范围 40 次：**¥84.213760**；与拟定 cap **¥2.00** 相差 **¥82.213760**。
- 每次预留本身已超过总 cap；因此当前范围没有获得在 ¥2.00 内可执行的发送前费用证明。

[execution-preparation.json](../../scripts/rag-claim-review/execution-preparation.json)逐题保存相同 payload SHA、保守输入/输出上界与每次预留，整体冻结父计划 SHA、正式费率、全部 40 次、cap、证据 SHA、传输政策和环境状态。规范 scope 为 `b11957ebaedeb61e4383fa7bf634be923d354cdfcec07f798a6bfa878f9efd7b`。状态为 `BLOCKED_BUDGET_AND_AUTHORIZATION`、`NOT_GRANTED`、`liveEnabled=false`。这份新审查没有批准 ¥84.213760、没有提高原 cap 或减少题组；预算不满足是可审查的结果，不绕过失败来生成 PASS。

## 默认关闭门禁与 HTTP 协议离线演练

`execution.py` 每次重新核验固定证据与审查 scope、父计划、费用和政策。`require_live_ready()` / `OfflineHttpHarness.live()` 固定拒绝 `LIVE_NOT_AUTHORIZED`，不构造 socket/client、不读取密钥、不消费次数；CLI 和环境变量均没有开启路径，也没有有效真实授权类型。即使自行修改 JSON 或模拟批准变量，也只能漂移失败或继续拒绝。原 OfflineAdapter 的精确 Stub 类型限制完全不改。

同模块的 `OfflineHttpHarness.exchange` 只接受精确 `MemoryRequest` 与 `MemoryHttpStub`。它是内存 HTTP 政策演练，不是 HTTPS 模型客户端，不能证明 TLS、DNS、代理或真实网络实现已验收。政策冻结 HTTPS 证书/主机名校验、禁隐式环境代理、POST 固定端点、重定向 0、重试 0、并发 1、请求 98304 bytes、响应 1 MiB、全调用 120 秒 deadline；未来客户端必须逐项实现并独立审查，不能仅解除门禁或取消 Stub 类型限制。

离线演练核对请求的 preparation scope、题组、精确 payload SHA/bytes 与固定预留，核对 Stub 的未知保存确认后才交接。`durableUnknown` 只是 Stub 提供的模拟保存确认，**不是实际 fsync 凭据或真实授权**；本 harness 不替代既有账本，不提供真实 round 排他锁或跨进程治理，也不增加可使用的真实额度。真实发送仍须由治理层先完成账本和未知凭据的可靠保存，再让内部传输绑定同一次请求。

同一 MemoryRequest 对象和全部字段在响应时重核；复制、篡改和跨请求响应拒绝。非 200（含 3xx、429、5xx）不读取正文/Location/错误体，不跟随重定向；非 JSON、压缩编码、非法 UTF-8、超限响应或模拟全调用超时失败。超时/断开/中断保持未知，失败或未知后同一 harness 不再交接。合法 usage 在 provider 失败及输入/输出/费用超界时仍返回固定元数据，不返回答案/信封；成功信封只驻留内存，后续生产 RAG JSON 校验仍属于现有适配器。错误只返回固定类别，Stub 内容及缓冲区结束时清理。

## 评审环境与人工剩余条件

Tk 离线合成测试成功只证明窗口可用。私密桌面、系统或第三方录屏、剪贴板历史、调试器/崩溃捕获和所有者真人评审安排均标为 `OWNER_CONFIRMATION_REQUIRED`；未擅自将 agent 环境、合成窗口或对象清理升级为真人确认、无录制或物理内存擦除保证。本次不扫描私人进程/缓存、不读取凭据、不截图真实正文，也不关闭用户软件。

未来执行需先取得托管分词/framing 的更紧合同证据，或由所有者审查并另行明确改变预算/scope；本轮不作该选择。随后还需有效独立授权、真正的 HTTP/排他治理实现及本机环境确认。一般“继续”不构成这些确认。本轮完成验证与草稿 PR 更新后停止，保留预算/授权阻断，不自动进入真实调用。

## 复现与历史边界

```powershell
python -B scripts/rag-claim-review/execution.py
python -B scripts/rag-claim-review/token_evidence.py
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
```

第一条只比对已提交摘要和父计划，输出固定 scope、预算不足和 paidRequests=0，不需要网络或下载文件。第二条是本机可选来源复现，需要上表四份固定文件已位于忽略目录，逐字节重算 SHA，再比对提交的证据摘要；缺失/漂移固定失败，不自动下载、执行源码或跳过。CI 只使用已审查摘要及合成 profile 回归，**不宣称在 CI 重算过未提交的完整官方 tokenizer**。公开参考文件保留于本机忽略目录，不作为新的项目运行依赖。

历史 manifest、公开源快照、原结果 SHA、¥0.20 / 40 次 / 156015 tokens 账单、0/40 完整评审和全部 `claimCitationBinding=UNKNOWN` 均不变。#51 的 Q4 修正未应用，真实 Embedding/Chat 新增 0 次，#53 保持未合并草稿、目标 develop；不合并、部署或采纳生产约束。回滚仅撤回本节准备模块、证据摘要、回归、CI 命令和文档，原已冻结计划与历史材料保留。
