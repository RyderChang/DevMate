# DEV-020 审核与修复记录

2026-09-29，所有者要求自行审核并准备下一任务。本记录针对 [PR #36](https://github.com/RyderChang/DevMate/pull/36)
的 `2a662dde55f62214f6fb5c1f7f36472f1aee6def`；未合并、未部署、未调用付费服务。

## 确认的问题与修复

- P1：明确的模型启动前拒绝被当作未知发送，ABSENT 不能解除 UNKNOWN，造成无推理的长期债务。
  现在只接受固定服务的 429 BUSY/JOURNAL_FULL，且验证 operation_id、spec、fingerprint、NOT_STARTED。
  服务在同一锁内检查重放、取得模型槽并持久插入新操作；忙时重放既有 UUID 返回 409，不伪造未启动证明。
  明确拒绝记为 FAILED/ENDED，不退款、不自动重试；普通 429、错配、畸形、超时及 ABSENT 仍不解除未知债务。
- P2：关闭索引开关会替换成空适配器，已有向量和模型债务无法恢复。
  现在写入资格与恢复连接分开；关闭时适配器不在启动联网，扫描已有债务时仍做真实模型状态和向量核验。
  Qdrant 恢复不创建缺失 collection/索引，不兼容或不可用时保留债务；V8、额度及 UNKNOWN 约束不变。

## 验证与环境

先增加回归，再修改实现。固定 Linux Java 测试首轮 31 项执行，4 项失败、错误/跳过 0，
分别复现启动前拒绝分类、禁用后的模型状态读取、MySQL UNKNOWN 以及禁用后的真实向量清理。
Python 首轮 8 项有 2 失败/1 错误，复现缺少拒绝定位与忙时重放。

修复后命令：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
python -B scripts/verify-knowledge-backend.py --tests LocalEmbeddingGatewayTest,QdrantVectorStoreIntegrationTest,DocumentIndexingIntegrationTest
tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-service/test_service.py
node scripts/check-docs.mjs --format-changed 2a662dde55f62214f6fb5c1f7f36472f1aee6def
git diff --check
```

Python 修复后 8 项通过；固定 Linux 的 31 项受影响 Java 回归通过，失败/错误/跳过均 0。
完整 CI 的最终提交与计数由本任务 PR 补充，未完成运行不记通过。
Windows tempfile ACL 和 Oracle JDK loopback 失败属于环境阻塞，未记业务验证通过；
使用现有测试权限及固定 Linux 验证环境，无升级依赖、真实权重或生产数据。

本轮修改 LocalJsonClient/LocalEmbeddingGateway、IndexConfiguration/IndexRecovery、QdrantVectorStore、
supervisor、对应 JDK/MySQL/Qdrant/Python 测试及运行/审核文档。
不把本次审核当作生产验收或真实项目检索质量结论。#37 必须纳入本次修复并重新验证，才能进入后续 RAG 编排。
