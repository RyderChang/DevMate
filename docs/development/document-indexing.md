# 文档索引开发与本地模型核验

[DEV-020](../tasks/DEV-020-document-vector-indexing.md) 使用已接受的
[ADR 0007](../adr/0007-freeze-local-embedding-and-vector-contracts.md)。公开合同见
[索引 API](../api/document-indexing.md)，实际结果见[索引验收记录](../testing/document-indexing-acceptance.md)。

## 日常测试

Java 21、既有固定 MinIO 镜像、Docker Linux amd64；不需要模型账户或密钥。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
python -B scripts/verify-knowledge-backend.py
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-service/test_service.py
node scripts/check-docs.mjs --format-changed origin/develop
```

Linux CI 直接执行 Maven `clean verify`；后端门禁要求索引/MySQL、真实 Qdrant 和 JDK HTTP 合同套件均存在、无跳过。
Embedding Preflight CI 另运行合成 supervisor 测试。模型权重不会在日常 CI 下载。

## 受控 Linux 模型运行时

`scripts/embedding-service/runtime_environment.json` 固定 Python 3.13.5 Linux amd64 镜像 digest，
`runtime-linux.lock.json` 与 `requirements-linux.txt` 固定 25 项 CPU wheel 版本、来源与 SHA-256。
安装使用 `--no-deps --require-hashes`；不升级聊天或 Java 业务依赖。
Qwen 权重使用 DEV-019 的已验证缓存，所有文件重新核对固定摘要后才加载；不执行模型仓库代码。

首次安装（将路径替换为本机仓库绝对路径；依赖进入本任务 Linux volume，下载缓存进入忽略的 `tmp/`）：

```powershell
docker volume create --label devmate.index-test=dev020 devmate020-embedding-runtime
docker run --rm --platform linux/amd64 --memory=8g --cpus=4 --pids-limit=128 --cap-drop=ALL --security-opt=no-new-privileges `
  -v "${PWD}:/workspace:ro" -v devmate020-embedding-runtime:/runtime `
  python@sha256:4c2cf9917bd1cbacc5e9b07320025bdb7cdf2df7b0ceaccb55e9dd7e30987419 `
  python -m pip install --target /runtime --no-deps --require-hashes -r /workspace/scripts/embedding-service/requirements-linux.txt
```

官方 CPU wheel 大文件可使用 `python -B scripts/embedding-service/download_cpu_wheel.py` 有界下载，
最终仍验证同一冻结大小与 SHA；缓存损坏会清除确定块，不改 hash 或版本。
可在忽略目录生成安装清单，只将 torch URL 替换为其已验证的 `file:///workspace/tmp/dev-020/wheels/` 路径。
必须等待安装命令成功退出，再启动模型。Windows bind mount 的大量小文件复制曾使安装目录暂不完整；
使用 Linux volume 并核对安装完成后的全部冻结 distribution，不能在安装仍运行时启动服务。

模型进程的启动模板：

```powershell
docker run --rm --name devmate020-model --label devmate.index-test=dev020 --platform linux/amd64 `
  --memory=8g --cpus=4 --pids-limit=128 --cap-drop=ALL --security-opt=no-new-privileges --network=none --read-only --tmpfs /tmp:rw,size=128m `
  -e PYTHONPATH=/runtime -e PYTHONDONTWRITEBYTECODE=1 -e OPENBLAS_NUM_THREADS=1 `
  -v "${PWD}:/workspace:ro" -v devmate020-embedding-runtime:/runtime:ro -v "${PWD}/tmp/dev-020:/journal" `
  python@sha256:4c2cf9917bd1cbacc5e9b07320025bdb7cdf2df7b0ceaccb55e9dd7e30987419 `
  python -B /workspace/scripts/embedding-service/server.py --model-dir /workspace/tmp/dev-019/modelscope --journal /journal/model-operations.sqlite
```

服务只监听容器内 `127.0.0.1:8091`，不发布宿主端口。另一应用容器须用 `--network container:devmate020-model`
共享这一 loopback 网络；Windows Java 进程不能通过发布端口绕过 loopback 绑定。
启动核对 cgroup 的 8 GiB/4 CPU/128 PID 上限、全部 wheel、模型文件、20 项 tokenizer fixture 与短输入原生/显式 mask 等价性。
同一 journal 的文件锁阻止重复 supervisor；只启动一个推理 worker，忙时立即拒绝，不隐式排队。

```powershell
docker exec devmate020-model python -B /workspace/scripts/embedding-service/smoke.py
```

冒烟包含短输入、完整 6000、4×1500，结果仅打印计数、维度、范数、指纹与耗时。
`EmbeddingSmoke.java` 在同一网络的 Java 21 容器中运行，核对真实 JDK Gateway 到模型服务的完整 HTTP 合同。
先运行后端验证生成 `target/classes`，再准备忽略目录的 Jackson classpath（版本继承 Maven，不另行升级）：

```powershell
New-Item -ItemType Directory -Force tmp/dev-020/jdk/classes,tmp/dev-020/jdk/lib | Out-Null
foreach ($artifact in @('jackson-databind','jackson-core','jackson-annotations')) {
  Copy-Item "$env:USERPROFILE/.m2/repository/com/fasterxml/jackson/core/$artifact/2.19.2/$artifact-2.19.2.jar" tmp/dev-020/jdk/lib/
}
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp 'devmate-server/target/classes;tmp/dev-020/jdk/lib/*' `
  -d tmp/dev-020/jdk/classes scripts/embedding-service/EmbeddingSmoke.java
docker run --rm --platform linux/amd64 --network container:devmate020-model --memory=512m --cpus=1 `
  --cap-drop=ALL --security-opt=no-new-privileges --read-only -v "${PWD}:/workspace:ro" `
  eclipse-temurin@sha256:677919d2f5cfc06a966b17d7b1b06c177fdf31928c60bcf20ac3016bca8a90b8 `
  java -cp '/workspace/devmate-server/target/classes:/workspace/tmp/dev-020/jdk/classes:/workspace/tmp/dev-020/jdk/lib/*' EmbeddingSmoke
```

测试结束仅停止本任务 `devmate.index-test=dev020` 标签且已确认无其他使用者的模型容器；不全局 prune。
持久 journal、权重保留在忽略目录，Linux 依赖 volume 保留供复验；UNKNOWN journal 不因重启、超时或缓存清理宣告结束。

## 业务开关与恢复

`KNOWLEDGE_INDEXING_ENABLED` 默认 false；启用还要求原文件和处理开关启用。
模型 origin `EMBEDDING_LOCAL_ORIGIN` 和向量 origin `QDRANT_LOCAL_ORIGIN` 只接受字面 loopback HTTP origin。
collection 默认 `devmate_qwen3_v1`，可带测试后缀；启动验证 Qdrant 1.19.1、1024/Cosine 和 keyword 索引。
已有不兼容 collection 明确失败。服务器 origin 不由 API 请求指定。

每轮扫描最多 20 个工作，每工作一个有界计数/推理批；推理并发 1。计数完整后才推理，单批最多 4 输入、
总计及 padding 总位置均不超过 6000。租约 7 分钟覆盖一次 10 秒计数或 300 秒模型及 30 秒向量边界。
失败不静默丢块、不修改片段、不撤销仍有效旧索引。付费适配器未实现，CNY 0 的授权边界保持有效。

每次跨外部边界前冻结操作并检查资格/版本/租约；响应后再检查。数据库不可写时没有新的远程发送。
崩溃后已发请求不自动重发；模型结束须从同一 operation journal 取得 SUCCEEDED/TERMINATED 证据。
唯一例外是同次发送收到固定 supervisor 的 429 BUSY/JOURNAL_FULL，且回执同时匹配 operation_id、
spec、fingerprint 和 NOT_STARTED。服务在同一锁内检查旧操作并持久登记新操作，已有 UUID 永远不能获得这种拒绝证明。
明确未启动记为终止失败，token 不退款；普通 429、畸形或错配回执、ABSENT 和超时仍为 UNKNOWN。
向量 UNKNOWN 没有可证明的自动终止依据，保留定位和容量，定期有界删除但不自动清账。
父文件、片段和处理记录可独立删除，journal 继承来源、点 UUID、摘要及预留。
当前没有人工清账 API；人工处理须先证明生产者停止与远端操作结束，再经有记录的修复和审计清理。

回滚关闭索引开关和读取资格；清理扫描仍尝试已存在债务。继续保留 V8 和债务，不删表或修改旧 migration。
模型状态与 Qdrant 清理适配器在关闭开关后仍保留，启动不联网；只有已有债务才触发有界状态核验/删除。
清理按固定指纹/版本/collection 规格核验；恢复模式不创建缺失 collection 或 payload 索引，服务不可用时保留债务。
继续运行同一可信 loopback 服务并保持 scheduling-enabled，不能用重开索引写入来替代清理。
检索/RAG、生产部署及检索质量评估属于独立后续任务。
