# 文档检索开发与验证

[DEV-021](../tasks/DEV-021-document-retrieval.md) 是依赖 #36 的独立任务；[API](../api/document-retrieval.md)与[实际验收](../testing/document-retrieval-acceptance.md)区分检索与后续 RAG 对话。
默认关闭，不部署、不调用付费模型。

## 常规验证与配置

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
python -B scripts/verify-knowledge-backend.py --tests DocumentRetrievalIntegrationTest,LocalEmbeddingGatewayTest,QdrantVectorStoreIntegrationTest
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-service/test_service.py
node scripts/check-docs.mjs --format-changed feature/dev-020-document-vector-indexing
```

最终完整后端由 Foundation CI 的 Maven clean verify 和无跳过报告门禁核验，前端全部常规检查也执行。
CI 不启动真实权重、不用私人账户。工作流只增加本次堆叠 PR 的确切 base 分支，保留 develop/main 原规则。
#36 合并后可把本 PR 重定向 develop；不自动合并或自动重定向。

`KNOWLEDGE_RETRIEVAL_ENABLED=false`、`KNOWLEDGE_RETRIEVAL_SCHEDULING_ENABLED=true`。
启用还要求存储/处理/索引开关；固定 origin/collection 和模型运行时沿用[索引运行说明](document-indexing.md)。
索引和查询共享一个无排队推理槽，本地竞争且确定未发送是失败；远端不确定仍须持久证明。
固定服务的 NOT_STARTED 必须同时核验 429 BUSY/JOURNAL_FULL、操作 ID、spec 和 fingerprint，不能把普通限流或 ABSENT 当作证明。
所有写入开关关闭后仍保留不在启动联网的恢复适配器；已有债务触发时才核验模型状态/既有 Qdrant collection。

查询共享项目/全局日 token 行，预留现有容量中的一条债务、4096 逻辑字节和 0 持久点，不另加预算。
终态操作首次发送后固定 24 小时、每轮最多 20 条清理；UNKNOWN 无 TTL。
独立 `knowledge_retrieval_operations` 无父 FK，不存查询正文或向量；关闭开关仍保留恢复扫描。
每次提交生成新操作，无自动重试；没有活动来源不调用模型。短事务预留先于推理，网络在事务外。
每轮刷新完整来源并排除已检查点，返回前在同一短事务重读来源和片段。
资格以最终数据库检查时为准，不能把整个 HTTP 响应与并发删除放进同一数据库事务。

## 受控真实模型与 Qdrant

先完成索引运行说明中的冻结 Linux volume 安装。以下只启动本任务容器，不发布端口：

```powershell
New-Item -ItemType Directory -Force tmp/dev-021 | Out-Null
docker run -d --name devmate021-model --label devmate.retrieval-test=dev021 --platform linux/amd64 `
  --memory=8g --cpus=4 --pids-limit=128 --cap-drop=ALL --security-opt=no-new-privileges `
  --network=none --read-only --tmpfs /tmp:rw,size=128m `
  -e PYTHONPATH=/runtime -e PYTHONDONTWRITEBYTECODE=1 -e OPENBLAS_NUM_THREADS=1 `
  -v "${PWD}:/workspace:ro" -v devmate020-embedding-runtime:/runtime:ro -v "${PWD}/tmp/dev-021:/journal" `
  python@sha256:4c2cf9917bd1cbacc5e9b07320025bdb7cdf2df7b0ceaccb55e9dd7e30987419 `
  python -B /workspace/scripts/embedding-service/server.py --model-dir /workspace/tmp/dev-019/modelscope --journal /journal/model-operations.sqlite
docker run -d --name devmate021-qdrant --label devmate.retrieval-test=dev021 --platform linux/amd64 `
  --network container:devmate021-model --memory=512m --cpus=1 --pids-limit=128 --cap-drop=ALL `
  --security-opt=no-new-privileges --read-only --tmpfs /qdrant/storage:rw,size=128m --tmpfs /qdrant/snapshots:rw,size=64m `
  -e QDRANT__SERVICE__HOST=127.0.0.1 -e QDRANT__TELEMETRY_DISABLED=true `
  qdrant/qdrant@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10
```

等待模型输出 ready/spec/fingerprint，不复制未知日志。query_smoke.py 使用合成重复字符：

```powershell
docker exec devmate021-model python -B /workspace/scripts/embedding-service/query_smoke.py
```

验证固定 prefix 的 6000 输入、完整向量、持久结束和 6001 拒绝；前缀尾空格会与后续 token 合并，不能简单相加。
真实 JDK 样本使用 Spring 事务与烘焙两个合成文档，验证预期 top-1、BIGINT、来源过滤和排除：

```powershell
New-Item -ItemType Directory -Force tmp/dev-021/jdk/classes,tmp/dev-021/jdk/lib | Out-Null
foreach ($artifact in @('jackson-databind','jackson-core','jackson-annotations')) {
  Copy-Item "$env:USERPROFILE/.m2/repository/com/fasterxml/jackson/core/$artifact/2.19.2/$artifact-2.19.2.jar" tmp/dev-021/jdk/lib/
}
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp 'devmate-server/target/classes;tmp/dev-021/jdk/lib/*' `
  -d tmp/dev-021/jdk/classes scripts/embedding-service/RetrievalSmoke.java
docker run --rm --platform linux/amd64 --network container:devmate021-model --memory=512m --cpus=1 `
  --cap-drop=ALL --security-opt=no-new-privileges --read-only -v "${PWD}:/workspace:ro" `
  eclipse-temurin@sha256:677919d2f5cfc06a966b17d7b1b06c177fdf31928c60bcf20ac3016bca8a90b8 `
  java -cp '/workspace/devmate-server/target/classes:/workspace/tmp/dev-021/jdk/classes:/workspace/tmp/dev-021/jdk/lib/*' RetrievalSmoke
```

样本直接核对真实协议；MySQL 活动资格、正文和 API 由真实 MySQL/Stub 模型套件验证。
样本成功不能代表真实项目质量。collection 后缀只能用字母数字；classpath 必须含三项 Jackson jar。
确认任务标签和无其他使用者后，仅停止本任务两个准确名称，不全局 prune；保留 UNKNOWN journal。
回滚关闭检索资格/写入，保留 V9 和恢复扫描；RAG 对话另立任务。
