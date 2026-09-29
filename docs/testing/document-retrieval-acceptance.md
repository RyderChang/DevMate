# DEV-021 文档检索验收记录

日期：2026-09-29。分支 `feature/dev-021-document-retrieval`，基线 DEV-020 `2a662dde55f62214f6fb5c1f7f36472f1aee6def`。
所有者允许从 #36 创建依赖分支、暂不合并；本任务不合并、不部署、不调用付费模型。
范围与复现见 [DEV-021](../tasks/DEV-021-document-retrieval.md)、[API](../api/document-retrieval.md)、[运行说明](../development/document-retrieval.md)。

## 修改文件

- knowledge 的 RetrievalController/Service/Transactions/Recovery/Journal、检索类型/DTO/VO/配置：权限、完整来源、补足、共享额度和独立 journal。
- ai/embedding/SerializedEmbeddingGateway、IndexConfiguration、IndexRecovery：索引/查询共享无队列槽，只有本地明确未发送可直接结束。
- QdrantVectorStore：固定来源和排除集合的 query adapter、严格响应校验，无客户端过滤。
- V9\_\_create_document_retrieval.sql、application.yml、ErrorCode：独立元数据、默认关闭与统一错误，V1–V8 未改。
- DocumentRetrievalIntegrationTest、QdrantVectorStoreIntegrationTest、LocalEmbeddingGatewayTest 与 migration 断言：资格/额度/错误/竞态及真实合同。
- query_smoke.py、RetrievalSmoke.java、test_service.py、报告门禁、堆叠 PR 的确切 CI base，以及 README/导航/任务/API/运行与本记录。

## 实际验证

| 命令                                                                                                                                                                                                                                  | 结果                                                                                                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `python -B scripts/verify-knowledge-backend.py --tests DocumentRetrievalIntegrationTest`                                                                                                                                              | 首轮 12 项通过，失败/错误/跳过均 0，真实 MySQL V1–V9                                                                                    |
| `python -B scripts/verify-knowledge-backend.py --tests QdrantVectorStoreIntegrationTest,DocumentIndexingIntegrationTest,DocumentProcessingIntegrationTest,DatabaseInfrastructureIntegrationTest,ConversationMigrationIntegrationTest` | 52 项通过，失败/错误/跳过均 0；含 204 个高分退役点、交叉来源/BIGINT/排除、迁移及处理/索引回归                                           |
| `python -B scripts/verify-knowledge-backend.py --tests DocumentRetrievalIntegrationTest,LocalEmbeddingGatewayTest,DocumentIndexingIntegrationTest`                                                                                    | 37 项通过，失败/错误/跳过均 0；13 项检索、6 项 JDK/共享槽、18 项索引；之后仅追加同一成功场景的 MockMvc 200/统一响应断言，由最终 CI 核验 |
| `tmp/dev-019/minimal/Scripts/python.exe -B scripts/embedding-service/test_service.py`                                                                                                                                                 | 最终 7 项通过，含 query golden、prefix 的 6000/6001 与 supervisor 合同                                                                  |
| `docker exec devmate021-model python -B /workspace/scripts/embedding-service/query_smoke.py`                                                                                                                                          | 真实 Linux prefix+6000 推理和 6001 拒绝通过；1024 维、单位范数、持久 SUCCEEDED，62.291 秒                                               |
| 固定 Java 21 的 `RetrievalSmoke`                                                                                                                                                                                                      | 真实模型/Qdrant query 通过；query 28 tokens，两文档预期 top-1、来源过滤与排除通过，top-1 score 0.77799845                               |

模型指纹 `8b2a926ad442ada42819ea0630054c1d6b42592e1506e0a5d9ec16c272cff062`。
上限 8 GiB / 4 CPU / 128 PID，cgroup 峰值 `4,430,123,008` 字节（约 4.13 GiB），没有第二份权重。
真实样本直接验证协议，MySQL/API 套件使用合成 Stub，不能合称为一次全栈真实模型 API 测试。
仅一个合成样本，不能给出真实项目召回率或质量结论。

初次 query fixture 忽略前缀尾空格合并得到 5999，测试失败；修正后保留严格 6000/6001 断言，7 项通过。
初次 JDK 样本缺 Jackson classpath、collection 后缀有非法下划线均失败，未记通过；修正后完成上述真实样本。
`87fa462f7a5a59056fbecedd8ee46aa27f57f109` 的 [Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/36519868474)
已通过：完整后端 222 项，失败/错误/跳过均 0；前端 89 项及所有常规检查通过；
[Embedding Preflight](https://github.com/RyderChang/DevMate/actions/runs/36519868473) 通过。
之后的审核修复见下节；最终提交/CI 以 [PR #37](https://github.com/RyderChang/DevMate/pull/37) 最新记录为准。

## 2026-09-29 审核修复

核查项目权限、完整来源过滤、最终资格重读、200 点/3 轮、共享额度及单次模型调用；
未发现上述检索路径的额外独立阻塞问题。前置 #36 的两条问题按[索引审核记录](document-indexing-review.md)修复，
本分支用普通合并保留历史、共享模型槽和双方所有测试，没有合并 GitHub PR。

查询也只接受同次发送的、操作 ID/规格/指纹匹配的 NOT_STARTED 回执；其他未知不变。
新增真实 MySQL 回归核对 FAILED、失败仍计 token、固定 24 小时预留及恢复清理；
关闭索引/检索写入后仍可取得已有模型操作的持久结束证明，禁用状态不在启动联网。
Python supervisor/query 合并后 9 项通过；本轮 Java 回归 49 项通过，失败/错误/跳过均 0，
含 14 检索、8 JDK/共享槽、19 索引和 8 真实 Qdrant。完整 CI 的最终提交与结果由 PR 补充；回归命令如下：

```powershell
python -B scripts/verify-knowledge-backend.py --tests DocumentRetrievalIntegrationTest,LocalEmbeddingGatewayTest,DocumentIndexingIntegrationTest,QdrantVectorStoreIntegrationTest
```

完整 CI 随后发现依赖修复引入的默认装配问题：关闭写入后保留的真实 Qdrant 对象也实现 VectorSearch，
与专用检索 Bean 形成两个候选。受影响套件替换了 VectorStore，未覆盖该真实运行类型。
新增不替换适配器的 RetrievalConfigurationTest，先复现 1 项执行/1 错误，再把专用检索 Bean 明确为 Primary；
禁用检索仍选择其受控禁用实现，索引清理适配器继续保留。默认装配回归纳入完整报告门禁。
修复后 `python -B scripts/verify-knowledge-backend.py --tests RetrievalConfigurationTest,DevMateApplicationTests`
两项通过，失败/错误/跳过均 0；完整默认应用在真实 MySQL 中启动，没有替换两个适配器。
原 `7c5f4b0` 完整后端 CI 因装配歧义失败，不记通过；最终修复 head 的完整 CI 由 PR 核验并记录。

## 边界与恢复

查询共享已有 token/容量行，失败不退款；终态首次发送后固定 24 小时有界回收。
UNKNOWN 无 TTL，只能由模型持久结束证据转终态；父资源删除不能丢失定位/预留。
资格变化或预算耗尽可能给出 incomplete，不等于没有相关知识；结果以最终数据库检查的资格为准，事务不包围网络/HTTP 发送。
检索默认关闭、付费 CNY 0、生产未部署；RAG 对话、前端、真实项目质量评价属于后续任务。
回滚关闭检索开关，保留 V9/journal 与恢复扫描；最终合并由所有者决定。
