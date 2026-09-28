# 0007：冻结本地 Embedding 与向量恢复合同

- 状态：Accepted
- 日期：2026-09-28
- 关联：[DEV-019](../tasks/DEV-019-embedding-index-preparation.md)、[核验记录](../testing/embedding-index-preflight-acceptance.md)
- 继承：[ADR 0005](0005-bound-processing-replays-and-retrieval.md)；不改写其已接受的边界。
- 确认日期：2026-09-28。
- 确认依据：所有者明确回复“接受 ADR 0007 和上述全部提议，执行 DEV-020”，接受任务书的全部资源额度及受控本地模型测试进程；索引默认关闭、付费金额 CNY 0，不部署生产。
- DEV-019 合并本身不构成接受依据；本次单独确认授权 DEV-020 实施，生产启用与付费仍需另行决定。

## 背景与选择

国内候选比较见[准备说明](../development/embedding-index-preflight.md)。百炼云 API 的公开合同与价格可查，
但本次检查没有取得候选模型的冻结精确 tokenizer 或调用前计数一致性证据。
响应中的 `usage.total_tokens` 是调用后数据，不能证明本地公开 Qwen tokenizer 与云端 alias 相同。
因此暂不采用云候选实施精确 token 费用预留，也不使用聊天 DeepSeek 配置替代 Embedding。

首选提议为 Qwen 官方 `Qwen3-Embedding-0.6B` 的固定本地版本，1024 维、Cosine。
权重、词表、模型配置与运行时独立冻结；本地计算有硬件成本，云 API 支出仍为零。
官方[固定版本模型卡](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/blob/97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3/README.md)
提供 tokenizer、末 token 池化和归一化使用方式；本次证据只验证结构合同，不证明 DevMate 检索质量。

## 固定规格与输入

| 项目          | 提议                                                                                                   |
| ------------- | ------------------------------------------------------------------------------------------------------ |
| 内部规格      | `qwen3-0.6b-1024-cosine-v1`                                                                            |
| 权重修订      | `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3`                                                             |
| 权重 SHA-256  | `0437e45c94563b09e13cb7a64478fc406947a93cb34a7e05870fc8dcd48e23fd`                                     |
| 输出          | 完整 1024 维 float32，最后一个非 padding token 池化、L2 归一化；不截维                                 |
| tokenizer     | `tokenizers 0.22.1`，固定序列化资产及配置；官方 wrapper 核验使用 `transformers 4.57.1`                 |
| 本地推理核验  | Python 3.13.5、PyTorch 2.8.0、safetensors 0.6.2；CPU float32 / SDPA / 显式因果和 padding mask / 4 线程 |
| 单输入 / 单批 | 最多 6000 tokens；最多 4 个输入且合计最多 6000 tokens，padding 后总 token 位置同样最多 6000            |
| 响应          | 最多 1 MiB；输入索引、模型规格、数量、维度、数值、范数和 usage 全部校验                                |
| 模型并发      | 1 个推理进程、1 个在途批次；不自动加载第二份权重                                                       |
| 提议截止时间  | tokenize 连接 2 秒、总计 10 秒；Embedding 连接 2 秒、总计 300 秒；Qdrant 总计 30 秒                    |

本地 CPU 路径必须显式传入布尔因果/padding mask，避免冻结 wrapper 的无 mask GQA 内存回退；
短输入等价性及边界资源以实际探针验证，不修改已冻结的模型文件或依赖源码。

这些限额是应用保护提议，不能当作提供商上限或已测生产吞吐。核验结果与环境限制以验收记录为准。
本地模型的 tokenizer 配置存在 `model_max_length=131072`，模型配置与模型卡上下文为 32768；
按更低的模型约束理解，应用进一步收紧为 6000，不能将 tokenizer 元数据当作模型容量承诺。

document 不加角色或指令前缀。未来 query 使用固定英文前缀：

```json
{
  "query_prefix": "Instruct: Given a software engineering question, retrieve relevant Java and Spring project documentation\nQuery: "
}
```

其中 `Query:` 后有一个 ASCII 空格，随后直接连接查询文本；该空格属于规格和 token 计数。
查询仅冻结未来合同，本任务及 DEV-020 不实施检索。
实际 tokenizer 执行 NFC，并在末尾加入 `<|endoftext|>`（151643）；用户字面的特殊标记按上游 tokenizer 识别。
这不触发聊天、工具或指令执行。源正文、DEV-017 摘要、块区间和编号保持原值，模型内部 NFC 不写回片段。
不使用 chat template，不启用 truncation。任何活动片段超限时整代明确失败，不能丢块或暗中再分块。

## 运行边界与响应

knowledge 只调用 ai 公开的独立 Embedding 能力，不访问其 Mapper 或供应商 DTO。
JDK 端不通过字符估算 token。实现将冻结 tokenizer 与模型放在同一受控本地模型进程，
分别提供有界计数和 Embedding 合同：先取得精确计数，再原子预留本地工作额度，最后执行模型推理。
独立进程的依据是权重内存、CPU 和截止时间隔离；它不是拆分业务数据库或通用 AI Worker 平台。
DEV-020 已实现服务和 JDK 适配器，并完成受控 Linux 合成输入的真实集成；
结果与复现命令见[索引验收记录](../testing/document-indexing-acceptance.md)。索引仍默认关闭，生产部署另行决定。

服务端配置固定受信 origin；默认只允许 loopback，无客户端 URL，无跨 origin 跳转。
启动握手核对规格、模型修订、权重/词表摘要、池化和运行时，并运行冻结 tokenizer 样例；
不一致时拒绝就绪和请求，不允许自动切换到云端或新模型。
若部署改为独立主机，认证、TLS、网络白名单和运行时锁须先补充审查，不能沿用 loopback 信任假设。

响应采用固定本地合同：model ID、规格指纹、唯一的整数 `index`、完整 data 清单和精确 `usage.total_tokens`。
提供商有可选 `prompt_tokens` 时也必须一致；不要求百炼其他模型一定返回这个可选字段。
拒绝重复 JSON 字段、错误 UTF-8、Boolean 数值、NaN/Infinity、float32 溢出或下溢导致的零向量，
以及单位范数误差超过 0.001 的向量。响应重排只能按 index，不靠到达顺序。
合成 HTTP fixture 是合同期望，不是已部署的模型服务。

## 费用与有限资源

付费提供商默认关闭，工作/项目 UTC 日/全局 UTC 日金额硬限额均为 CNY 0。
缺少价格版本、币种、有效期或任一硬限额时，可支出金额也是零；本地方案不伪造云价格为零来绕过账本。
将来引入付费模型必须另行冻结精确 tokenizer、价格和显式金额授权，再实施原子预留与 usage 对账。
预算用固定精度数值；每次可能付费尝试单独占额，发送后结果未知不退款、不自动重复调用。
价格过期、usage 异常或数据库不可写均阻止后续收费。

本地推理也要限制工作量：提议每索引代最多 1,000,000 tokens，项目 UTC 日最多 2,000,000，
全局 UTC 日最多 10,000,000；默认索引开关关闭。这些是所有者已接受的资源上限，不是已用量或质量目标。
UNKNOWN 本地推理占用已预留的 token 工作量，确定终止后可受控重试并新增尝试；不执行无限重放。

## 向量发布与未知写入

Qdrant 合同固定 1.19.1 linux/amd64 镜像摘要；MySQL 沿用 8.4.6 固定摘要。
collection 按规格隔离，启动时检查 1024/Cosine 与 payload keyword 索引，现存不兼容 collection 明确失败。
稳定点 UUID 包含归属、文档、处理代、索引代、规格及 ordinal。
BIGINT 在 Qdrant JSON 中使用十进制字符串，避免经过 JavaScript 等环节损失精度。
payload 只保存来源定位；全文仍经 knowledge 授权服务读取。

MySQL 是可见性权威。每批向量写入前短事务持久记录确定点清单、来源组合、操作版本和状态，
事务外执行 `wait=true` 写入；完整确认且资格未变化才发布整代，部分成功不能提前 `INDEXED`。
未来检索必须强制 owner/project/spec 及完整来源组合过滤，再做 MySQL 二次校验；不分别拼文档/代列表。

真实实验确认：响应丢失可发生在 upsert 成功后；先删除且查询不存在也不能排除旧 upsert 迟到。
租约、客户端超时、一次删除回执或暂时不存在，都不能证明远端操作结束。
因此退役代先撤销资格；UNKNOWN 操作、点定位、清理债务必须独立持久化，不依赖即将删除的父记录。
未知操作未证明结束时持续保留最小 tombstone 并有界重试清理。
只有确认生产者已经停止且操作已结束，再次清理并核对后，才释放操作定位与向量容量。
无法证明时进入人工核对，不能用时间 TTL 自动宣告干净。

未知债务同样有硬数量/字节上限，计入已删除文档/项目；满额阻止新索引，不阻止删除与恢复。
具体提议、锁顺序和验收见 [DEV-020](../tasks/DEV-020-document-vector-indexing.md)。

## 后果、待确认与回滚

本地方案可冻结精确计数，代价是模型进程、内存和运维；云方案保留为后续候选。
生产硬件、Linux 推理运行时、真实服务 HTTP 截止时间和检索质量仍须独立验证。
DEV-019 仅交付合同证据与任务书，没有 INDEXED、检索 API、业务 migration 或常驻服务。
接受本 ADR、具体资源额度和进入 DEV-020 是后续决策；启用收费另需明确预算。
本次回滚可撤回准备文档和脚本；不改变既有文档状态、数据库或已接受 ADR。
