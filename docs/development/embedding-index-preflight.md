# Embedding 与索引准备说明

证据日期：2026-09-28。范围为 [DEV-019](../tasks/DEV-019-embedding-index-preparation.md)。
结论与限制见[验收记录](../testing/embedding-index-preflight-acceptance.md)，规格与资源额度见
[Proposed ADR 0007](../adr/0007-freeze-local-embedding-and-vector-contracts.md)。
本次不交付索引业务、数据库 migration、生产部署或收费调用。

## 国内候选

| 项目         | 百炼 `qwen3.7-text-embedding`                                                                               | 百炼 `text-embedding-v4`               | 本地 Qwen3-Embedding-0.6B                          |
| ------------ | ----------------------------------------------------------------------------------------------------------- | -------------------------------------- | -------------------------------------------------- |
| API / origin | 北京工作空间 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings`，Bearer key | 同地域工作空间合同，以账户实际文档为准 | 尚无本项目服务；拟受控 loopback 模型进程           |
| 维度         | 默认 1024，可选 256/512/768/1024/1536/2048/2560                                                             | 默认 1024，固定请求所选维度            | 本次固定完整 1024                                  |
| 官方限制     | 单行 128000 tokens、最多 20 行                                                                              | 单行 8192 tokens、最多 10 行           | 模型上下文 32768；应用另限 6000/批4/批合计6000     |
| 北京同步价格 | CNY 0.0005 / 1000 输入 tokens                                                                               | CNY 0.0005 / 1000 输入 tokens          | 不产生云模型 API 费用；硬件/电力/运维另计          |
| 冻结精确计数 | 本次未找到可验证的精确 tokenizer/预调用一致性证据                                                           | 同左，不能直接借用公开 Qwen tokenizer  | 官方 tokenizer、修订、摘要可冻结并离线复现         |
| 本次实际验证 | 官方文档核对；未调用、未验证账户或账单                                                                      | 同左                                   | tokenizer、配置与权重摘要；实际 CPU 结果见验收记录 |

云 API 参数、地域地址、同步价格与 `usage` 依据
[百炼同步向量接口](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)及
[中文向量化说明](https://help.aliyun.com/zh/model-studio/embedding)。价格仅是检查日公开北京标准价，
不构成已授权预算、折扣/税费承诺或未来有效价格。Batch 的另列价格不混入同步账本。
其他地域、flash、视觉向量或 OpenAI 兼容聊天不能替代这两个候选的合同。

本地模型依据 [Qwen 官方固定修订](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/tree/97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3)，
模型卡标注 Apache-2.0。数据镜像仅使用 [Qwen 官方 ModelScope 仓库](https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B)，
下载的字节必须匹配冻结 HF SHA-256；镜像的 master 本身不被视为不可变版本。
选本地是满足冻结计数的技术提议，不是根据公开榜单推断本项目效果。

## 核验文件与边界

`scripts/embedding-preflight/` 与业务类路径完全分开：

- `environment.json`：固定规格及两个 linux/amd64 Docker 镜像摘要。
- `requirements-tokenizer.txt`：tokenizers 0.22.1 的 Windows/Linux wheel 哈希，安装时关闭依赖解析。
- `fixtures/qwen/`：冻结原始 tokenizer 的无损压缩存档及必要配置、逐文件摘要、许可与来源；权重不入库。
- `fixtures/tokenizer-cases.json`：20 个官方 fast/slow 交叉核对样例。
- `contract.py`、`test_offline.py`：离线计数、6000 边界与响应结构/float32/usage 拒绝合同。
- `test_http.py`：纯 loopback 合成 HTTP、超时、429、重定向、大小和 UTF-8；不接触外部模型。
- `test_vector.py`、`isolation.py`：真实隔离 Qdrant/MySQL，SQL journal 只是提议合同 fixture，非业务表。
- `download_cache.py`、`test_download_cache.py`：按固定权重 SHA 隔离缓存，最终摘要失败清除确定块并验证下次恢复。
- `fetch_model.py`、`verify_goldens.py`、`probe_model.py`：明确执行的可选数据下载/官方核对/CPU 冒烟。

响应 fixture 中的 basis vectors 是人工合成，不能当作 Qwen 模型结果。
HTTP 合同只冻结所需字段与失败行为，完整运行服务和 spec 指纹握手由 DEV-020 实现并集成验收。

## 离线与数据库复现

在仓库根目录，Python >=3.11、Docker Linux amd64；本次 Windows 实测 Python 3.13.5。
Ubuntu 24.04 CI 使用 runner 自带 Python 3.12，wheel 是 cp39-abi3 manylinux x86_64。
Windows PowerShell：

```powershell
python -m venv tmp/dev-019/minimal
tmp/dev-019/minimal/Scripts/python.exe -m pip install --no-deps --require-hashes -r scripts/embedding-preflight/requirements-tokenizer.txt
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_offline.py
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_download_cache.py
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-preflight/test_http.py
python -B scripts/embedding-preflight/pull_images.py
python -B scripts/embedding-preflight/test_vector.py
```

Linux 使用 `tmp/dev-019/minimal/bin/python`。安装 wheel 与拉镜像需要网络；测试本身不下载词表/模型，
不访问私人账户。任何缺依赖、Docker 不可用、镜像不符或测试失败均非零退出，不跳过。
自动化见 [Embedding Preflight](../../.github/workflows/embedding-preflight.yml)。

Qdrant 1.19.1 新增仅用于任务合同，依据[官方发布](https://github.com/qdrant/qdrant/releases/tag/v1.19.1)，
不升级业务依赖；MySQL 8.4.6 沿用现有锁定镜像。
Qdrant 仅绑定 127.0.0.1 随机 REST 端口、不发布 gRPC，512 MiB/1 CPU、cap-drop ALL、禁止提权；
MySQL 1 GiB/1 CPU、不发布宿主端口、临时随机密码，只通过容器内 exec 访问。
Qdrant 的独立命名卷用于重启持久性；MySQL restart 复用自身数据卷。
Docker restart 可能重新分配随机宿主端口，脚本每次重新读取实际绑定。

容器/卷名含随机 run ID，label 为 `devmate.embedding-preflight=<run>`；`finally` 按 label 校验后清理本轮资源。
若宿主进程被强杀，可先检查：

```powershell
docker ps -a --filter label=devmate.embedding-preflight --format '{{.Names}} {{.Labels}}'
docker volume ls --filter label=devmate.embedding-preflight
```

核对确属中断的 run、无运行任务后，只删除该 run 的明确容器与命名卷。
禁止全局 prune，不能删除其他开发数据库。padding 后 token 位置合计同样限制为 6000，批次规划需按最长输入乘批大小收紧。

模型与 venv 缓存留在已忽略的 `tmp/dev-019/`，无常驻进程。

## 可选本地权重与运行时复现

仅下载官方数据，不运行模型仓库自定义代码。下载脚本固定目标为忽略目录 `tmp/dev-019/frozen-model`，
有文件大小、范围、摘要、最多三次尝试与整体截止；8 路范围下载仅为本次大文件复现。整体 30 分钟截止阻止新下载请求，每次网络读取最多 90 秒。
镜像当前字节不符固定摘要时立即失败，不能绕过检查或改锁来迁就下载。
权重块缓存按固定 SHA 分目录；组装结果不符时移除已知块及无效组装文件，下次执行重新下载，
不会一直复用同长度损坏缓存，也不会删除该目录下无关文件。

```powershell
python -B scripts/embedding-preflight/fetch_model.py --mirror
python -m venv tmp/dev-019/venv
tmp/dev-019/venv/Scripts/python.exe -m pip install --no-deps --require-hashes -r scripts/embedding-preflight/requirements-model-windows.txt
tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/verify_goldens.py tmp/dev-019/frozen-model
tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/probe_model.py tmp/dev-019/frozen-model --explicit-causal-mask
tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/probe_model.py tmp/dev-019/frozen-model --boundary --explicit-causal-mask
tmp/dev-019/venv/Scripts/python.exe -B scripts/embedding-preflight/probe_model.py tmp/dev-019/frozen-model --batch-boundary --explicit-causal-mask
```

这份推理依赖锁针对 Windows x64 / CPython 3.13，包含实际 wheel SHA；它不是业务依赖或 Linux 部署锁。
本次执行下载目标为 `tmp/dev-019/modelscope`，其最终摘要与上面固定版本相同。
Python 进程只用 CPU float32、SDPA、显式布尔因果/padding mask、4 线程、left padding、最后非 padding token 池化和归一化；
本地文件校验先于加载，`local_files_only=True`、`trust_remote_code=False`、离线环境变量，禁止截断。
可选探针不作为 CI 常规步骤；报告只含大小、计数、时间和内存，不打印正文或向量。
目标 Linux、GPU、生产最大并发、端到端服务截止时间及检索质量没有由 Windows 冒烟证明。
