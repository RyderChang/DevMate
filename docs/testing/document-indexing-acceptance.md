# DEV-020 文档索引验收记录

日期：2026-09-28 至 2026-09-29。分支 `feature/dev-020-document-vector-indexing`，基线 `1386b75d331690ce356b9000a8f248226b21eb18`。
所有者明确接受 ADR 0007、全部任务额度及受控本地模型测试进程；不部署生产，付费额度 CNY 0。
任务要求见 [DEV-020](../tasks/DEV-020-document-vector-indexing.md)，合同见[索引 API](../api/document-indexing.md)，复现见[运行说明](../development/document-indexing.md)。

## 实现与修改文件

- `devmate-server/src/main/java/com/devmate/ai/embedding/`：公开精确计数和 Embedding Gateway、冻结规格、JDK 有界 loopback HTTP。
- `devmate-server/src/main/java/com/devmate/knowledge/`：IndexController、IndexService、IndexTransactions、IndexRecovery、IndexJournal、QdrantVectorStore、索引 DTO/配置；处理换代与删除交接，处理状态展示真实活动索引资格。
- `devmate-server/src/main/resources/db/migration/V8__create_document_indexing.sql`：独立索引、点、操作、请求映射与工作/日/容量账本；V1–V7 未修改。
- `devmate-server/src/main/resources/application.yml`、`common/api/ErrorCode.java`：默认关闭配置和统一业务错误。
- `devmate-server/src/test/java/`：索引/MySQL、真实 Qdrant、JDK HTTP 三套新增验收，以及既有 migration 版本断言更新。
- `scripts/embedding-service/`：Linux 单推理进程、持久 SQLite 操作 journal、kill/join 截止时间、冻结 wheel/镜像及 Python/JDK 冒烟。
- `.github/workflows/embedding-preflight.yml`、`scripts/check-knowledge-test-reports.py`：合成 supervisor 测试及完整后端报告门禁。
- 根目录与服务 README、scripts README、docs 导航、ADR 0007、DEV-020、处理/索引 API、开发与本记录：授权、合同、运行与证据同步。

## 本地验证

Windows 使用 Java 21 编译，固定 Linux Java 容器运行 Testcontainers；没有使用 H2 代替 MySQL。

| 实际命令                                                                                                                                        | 结果                                                                                                              |
| ----------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `python -B scripts/verify-knowledge-backend.py`                                                                                                 | 完整 199 项通过，失败/错误/跳过均 0；包括既有 178 项和初版新增 21 项，V1–V8 从空 MySQL 顺序执行、完整报告门禁通过 |
| `python -B scripts/verify-knowledge-backend.py --tests LocalEmbeddingGatewayTest,QdrantVectorStoreIntegrationTest`                              | 9 项通过，失败/错误/跳过均 0                                                                                      |
| `python -B scripts/verify-knowledge-backend.py --tests DocumentIndexingIntegrationTest,DocumentProcessingIntegrationTest,ProcessingServiceTest` | 36 项通过，失败/错误/跳过均 0；在处理状态与旧代保留的最后行为调整后执行                                           |
| `python -B scripts/verify-knowledge-backend.py --tests DocumentIndexingIntegrationTest`                                                         | 最终 18 项通过，失败/错误/跳过均 0；真实 MySQL 故障注入验证发送前回滚与发送后债务保留                             |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-service/test_service.py`                                                           | 6 项通过，含单进程忙时拒绝、重放、重启 UNKNOWN、超时终止、实际 loopback HTTP 和精确 token 边界                    |
| 前端 `npm run type-check`、`lint`、`format:check`、`test`、`build`                                                                              | 全部通过；14 文件 / 89 项测试通过                                                                                 |
| `npm audit --registry=https://registry.npmjs.org --https-proxy=http://127.0.0.1:7897`                                                           | 0 vulnerabilities；使用 Node 24.14.1 附带 npm 11.11.0，未使用 PATH 中旧 npm 6                                     |
| `node --test scripts/ai-stub.test.mjs`                                                                                                          | 7 项通过，零跳过                                                                                                  |

完整 199 项在补充最后映射/并发/删除/数据库故障用例之前执行；不能把增量计数相加当作最终完整回归。
最终完整版本由 PR 的 Foundation Backend CI 执行，GitHub 检查记录是其状态依据。
`node scripts/check-docs.mjs --format-changed origin/develop`：57 个 Markdown、224 个相对链接和改动格式通过；`git diff --check`、新增 Python 语法及 workflow 格式检查通过。DEV-019 的 offline/cache/HTTP 三组共 11 项也通过。PR CI 尚待创建 PR 后执行，不在本地结果中记通过。

## 真实 Linux 模型与 JDK HTTP

固定 Linux amd64 Python 3.13.5 镜像、25 项 SHA 锁定 CPU wheel、原固定 Qwen 权重；启动检查全部 distribution、模型摘要、20 项计数 fixture 及短输入原生/显式 mask 的数值等价性。
单推理 worker，cgroup 上限 8 GiB / 4 CPU / 128 PID，网络 none，绑定容器 loopback，不发布端口。
模型指纹：`8b2a926ad442ada42819ea0630054c1d6b42592e1506e0a5d9ec16c272cff062`。

`docker exec devmate020-model-linux python -B /workspace/scripts/embedding-service/smoke.py` 实际结果：

| 合成输入                     | 精确 tokens | 完整维度与范数            | 端到端秒数 |
| ---------------------------- | ----------- | ------------------------- | ---------- |
| 中文项目文档 + Spring 短输入 | 9 + 4       | 1024、单位范数通过        | 0.161      |
| 单输入上限                   | 6000        | 1024、单位范数通过        | 48.673     |
| 单批上限                     | 4 × 1500    | 每输入 1024、单位范数通过 | 28.781     |

本轮 cgroup 内存峰值 `4,019,646,464` 字节（约 3.74 GiB）；这些是此机器的合成输入测量，不是生产吞吐或质量承诺。
另在固定 Java 21 容器中运行 `EmbeddingSmoke`、共享模型 loopback 网络：2 输入、13 tokens、完整 1024 维及持久结束证据通过，模型指纹与 Python 相同。
运行说明提供编译、Jackson classpath 和固定 Java 镜像的复现命令。

测试只使用合成文本。已停止本任务的模型/安装容器，保留忽略目录中的 journal/权重/wheel 缓存和带任务标签的 Linux runtime volume；没有生产部署。

## 环境问题与处理

初次 tempfile 测试在 Windows 沙箱受 ACL 限制，未记通过；改用本机测试权限与仓库忽略目录完成。
官方 R2 CPU wheel 下载截断，未绕过 hash；改用 PyTorch 官方原站的有界范围下载，验证完整 `183,917,315` 字节及同一冻结 SHA。
安装到 Windows bind mount 时，在文件复制尚未完成的目录启动模型被 distribution 校验拒绝，未记通过；改用 Linux volume、等待 hash 安装成功后完成上述真实测试。

## 边界、风险与回滚

独立 journal 无父文档/处理 FK，记录全部预期点与未知操作；模型及向量丢回执、发送后崩溃、过期租约、父资源删除均保留定位和预留。
未知债务没有清账 TTL；向量 UNKNOWN 没有自动终止证据，不能用一次查询无点释放容量。
源资格、租约和操作版本每次外部边界前后检查；发布核验完整 ordinal/摘要/来源组合，部分成功不替换仍有效旧代。

默认关闭索引。首版对确定失败采用一次工作尝试，比最多三次的上限更严格；UNKNOWN 永不自动重发。
容量按 1024 float32 加 512 字节/点逻辑余量预留，不保证物理磁盘容量；UNKNOWN 会占用额度并可能需要有审计证据的人工处理，当前没有人工清账 API。
没有检索/RAG、前端索引页面、生产部署、付费 API 或检索质量结论；这些不是本任务验收项。

回滚关闭 `KNOWLEDGE_INDEXING_ENABLED` 和读取资格，继续恢复已存在清理债务；保留 V8/账本，不删表或修改旧 migration。
最终合并与生产启用由所有者决定。建议下一任务为文档检索/RAG，另立任务继承 ADR 0005/0007，本分支不实施。
