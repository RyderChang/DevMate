# DEV-019 Embedding 与索引准备核验记录

日期：2026-09-28；任务分支 `chore/dev-019-embedding-index-preflight`。
执行基线 `c4f2782b9301e05483b2f7ae1ba4e1d70717a0e5`，工作区最初干净。
该基线的 [Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/36418635830)
成功：178 项后端测试，失败/错误/跳过均为 0，前端全部通过。
这里的 178 项是已核对的基线 CI 结果，不是本轮重新执行的本地 Maven 结果。

## 交付与实际边界

已形成国内官方云 API/本地模型比较、固定 Qwen 规格提议、离线 tokenizer/HTTP fixture、
真实隔离 Qdrant/MySQL 合同和 [DEV-020](../tasks/DEV-020-document-vector-indexing.md) 任务书。
[ADR 0007](../adr/0007-freeze-local-embedding-and-vector-contracts.md) 状态为 Proposed，
不把准备任务的授权解释为接受运行架构/资源额度或启用收费。

核对现有 V7 与 `ProcessingTransactions.readActive(owner, project, document, offset, limit)`：
MySQL 管理活动完整处理代并经归属校验分页读取，DEV-020 应复用公开边界，不跨模块访问 Mapper。
当前 `AiGateway` 是 chat 能力；索引没有业务实现，不能将对话输出当作向量。
本轮不修改 `devmate-server`、`devmate-web`、业务依赖、API 或 migration。

选型结论为固定本地 Qwen 技术提议。云候选尚缺精确计数一致性证据；
没有云账户核验、付费请求或账单结果，也没有吞吐、生产部署或检索质量结论。
详细官方来源、复现命令与清理见[准备说明](../development/embedding-index-preflight.md)。

## 环境和数据锁

- Windows x64，Python 3.13.5；Intel Core i9-12900H，14 核/20 逻辑处理器，宿主内存 34,003,587,072 bytes。
- Docker 29.8.0，Linux amd64；Docker 可见 20 CPU、16,591,253,504 bytes 内存。
- Qdrant 1.19.1、MySQL 8.4.6 的确切镜像摘要见 `scripts/embedding-preflight/environment.json`，实际服务返回版本已检查。
- 本地模型修订 `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3`；权重 1,191,586,416 bytes，
  SHA-256 `0437e45c94563b09e13cb7a64478fc406947a93cb34a7e05870fc8dcd48e23fd`，最终全文件校验成功。
- 词表/配置逐文件锁定，`tokenizer.json` 原始大小 11,423,705 bytes，仓库只保存无损 gzip 资产，CI 不下载词表。
- 最小环境仅装带官方 wheel hash 的 tokenizers 0.22.1；完整模型探针依赖另锁 25 项 Windows wheel，
  不加入业务类路径。权重、venv 和下载缓存位于忽略的 `tmp/dev-019/`。

## 最终本地合同测试

| 实际命令（仓库根目录）                                                                                                                       | 结果                                                                             |
| -------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `tmp/dev-019/minimal/Scripts/python.exe -m pip install --no-deps --require-hashes -r scripts/embedding-preflight/requirements-tokenizer.txt` | 最小干净 venv 安装成功，没有下载模型/词表或解析额外依赖                          |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_offline.py`                                                      | 7 项通过，0.443 秒；含 20 个官方 golden、5999/6000/6001 计数及 18 个异常响应子例 |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_http.py`                                                         | 3 项通过，1.968 秒；纯 loopback 实际 HTTP，无外部提供商                          |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_vector.py`                                                       | 7 项通过，18.995 秒；真实 Qdrant/MySQL，包括重启                                 |
| `tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/verify_goldens.py tmp/dev-019/modelscope`                                | 官方 fast 与 slow+NFC/EOS 的 20 个样例全部一致；HTTP 两行 token 为 3/6，合计 9   |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/fetch_model.py --mirror`                                              | 已有固定缓存路径的校验通过，不重新下载、不加载权重                               |

常规合同合计 **17 个 unittest 方法**，失败/错误/跳过均为 0；20 个 golden 和异常子例不另冒充方法数。
离线响应测试拒绝错模型、缺/重复/Boolean index、错维、非有限/Boolean 数值、float32 溢出、
下溢零向量、非单位范数、usage 缺失/错误、重复 JSON 字段及错误 UTF-8。
HTTP 测试执行有界读取、拒绝重定向，超时/429 后没有自动重发。

真实数据库七项验证包括：collection 1024/Cosine 与错维拒绝、稳定 UUID 重写幂等与代隔离、
完整组合过滤（交叉组合不得匹配）、跨 owner/project 隔离、9007199254740993 与相邻 ID 无精度损失、
第一批成功第二批失败不代表发布、服务重启后点和 UNKNOWN 定位保存、upsert 成功后丢回执、
删除确认后旧 upsert 迟到以及重复删除。
SQL journal 仅验证提议的最小持久定位/可见性合同，不证明业务扫描器、并发预算或删除交接已经实现。

测试结束后实际执行按 `devmate.embedding-preflight` label 检查容器/卷列表，两者为空。
MySQL 临时密码不入库、不输出；没有留下常驻模型或数据库服务。

## 本地模型运行

已执行原生 SDPA 短输入探针：输入计数 9/14/28，1024 维、有限、单位范数通过；
加载 1.419 秒、推理 0.834 秒、峰值 working set 3,905,134,592 bytes。
这是单次 CPU float32 / 4 线程实测，不能推广为生产 SLO。

原生 SDPA 的 6000-token 探针实际失败：CPU allocator 尝试分配 2,304,000,000 bytes 时内存不足。
没有降低计数边界或将失败记为通过。冻结 Transformers 4.57.1 的 SDPA wrapper 在无显式 mask 时
选用 GQA 路径；显式因果/padding mask 可走重复 K/V 的等价路径。

完成官方 hash 锁的 24 项小依赖重新安装，PyTorch 使用此前已校验的官方 Windows wheel；
`python -m pip check` 无依赖冲突。未修改任何已安装库源码。
修正后的实际命令和结果：

| 命令（前缀为 `tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/`） | 结果                                                                                                                  |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `probe_model.py tmp/dev-019/modelscope --explicit-causal-mask`                       | 9/14/28 tokens；原生/显式 mask 向量最大绝对误差 0；加载 1.249 秒、推理 0.755 秒、峰值 3,906,240,512 bytes             |
| `probe_model.py tmp/dev-019/modelscope --boundary --explicit-causal-mask`            | 完整 6000 tokens；加载 0.935 秒、推理 97.960 秒、峰值 3,964,907,520 bytes；1024 维、有限及单位范数全部通过            |
| `probe_model.py tmp/dev-019/modelscope --batch-boundary --explicit-causal-mask`      | 4 行各 1500 tokens，合计及 padding 后 6000；加载 0.632 秒、推理 24.508 秒、峰值 3,906,027,520 bytes；输出合同全部通过 |

所有计数含 EOS，未启用 truncation。新增 padding 后总位置最多 6000 的保护条件；
长度不齐的批次需重新切批，而非截短原片段。单次结果只支持本机该冻结路径可运行，不是生产性能承诺。

另执行 `pull_images.py`，两种锁定摘要均能从 registry 拉取；`node scripts/check-docs.mjs --format-changed origin/develop`
通过 54 份 Markdown / 206 个相对链接及改动格式；Python 全部脚本语法、工作流 Prettier 和 `git diff --check` 通过。

## 核验中的修正与剩余验收

首次响应 oracle 接受可表示为 float64 但无法表示为 float32 的 1e308，回归子例失败；
修正为 float32 范围及归一化检查，保留所有拒绝断言。
首次重启数据库测试使用旧动态端口而失败；改为每次重启后重新读取 loopback 绑定，再通过七项完整核验。
首次 HF 权重下载提前截断且摘要失败；没有加载该文件，使用官方国内镜像重下并核对固定 HF 摘要。

尚需 DEV-020 实现并验证：受控模型服务/JDK 协议和指纹、Linux 部署运行时与全输入资源、
业务 MySQL 预算/容量/请求映射、完整发布与所有删除交接竞态。
检索质量与 RAG 属于后续独立任务；本任务无敏感文档数据、GPU 或收费验证。
准备文档/脚本可通过新提交撤回，不改变既有业务数据、模型开关或已接受 ADR。
