# RAG 对话开发与恢复

[DEV-022](../tasks/DEV-022-rag-conversation.md) 在 #37 `0432cafeda9e4be8d30f698a91fb76ffb90d7bab` 上建立独立依赖分支。
所有者接受 ADR 0008 全部提议，#36/#37 保持未合并；本任务不合并、部署、实现前端或调用真实付费模型。
合同见 [API](../api/rag-conversation.md)，实际结果见[验收](../testing/rag-conversation-acceptance.md)。

## 开关与测试

`RAG_ENABLED=false`、`RAG_SCHEDULING_ENABLED=true`。启用新入口还需 AI Gateway 和知识存储、处理、索引、检索已启用。
没有新增模型/依赖或客户端可覆盖预算。关闭新入口后保留普通聊天和历史数据库；恢复扫描只结束过期调用，不调用任何模型。
既有 Embedding UNKNOWN 仍由独立恢复流程处理；RAG 不清理、退款或改写其账本。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest,RagPromptAndOutputTest,RagChatCallsTest
python -B scripts/verify-knowledge-backend.py
node scripts/check-docs.mjs --format-changed 0432cafeda9e4be8d30f698a91fb76ffb90d7bab
```

固定 Linux Java/MySQL/MinIO 测试运行时沿用已有脚本；CI 直接 Maven clean verify，再执行无失败/错误/跳过报告门禁。
门禁包含新增 RAG 套件，Foundation 和 Embedding Preflight 允许本次确切依赖 base。
全部网络使用 Stub Chat 与合成 Embedding；真实 MySQL 测试覆盖锁、迁移、额度、异常及来源竞争。

## 租约、来源和回执

首次短事务按项目、对话锁处理 UUID，再按全局/项目/对话额度行顺序预留；保存 USER、PENDING、mode、content SHA 与 11/12 分钟绝对截止。
RAG 写事务设置 10 秒事务截止，网络全部在事务外，检索最多一次；持久发送状态更新失败时不调用 Chat。knowledge 公开资格服务加入调用方短事务和项目锁。
真实来源的文档、处理、索引代、SHA、ordinal、区间、行号和正文须与实际提供的快照完整相同。
来源正文视为不可信 JSON 数据，指令固定且不声明工具；这不保证所有事实正确或消除全部提示词注入。

单次实际 Chat 回执的 usage 独立提交，再执行输出校验与回答发布。坏输出、来源改变、引用写入失败及旧响应都会保留已取得的 usage。
如果 detail 的回执更新失败，独立 invocation 仍可写时保留实际 usage，但请求失败并保留 UNKNOWN。
数据库全面不可写时只能保留先前 DISPATCHED/PENDING 记录，不能宣称 usage 已持久化；日志仅报告状态不可确认，
没有重发、退款、猜测 tokens 或通过模型再次获取答案的恢复操作。缺失回执需要按实际提供商记录人工核对。

monotonic CallBudget 对嵌套 LocalJsonClient 请求逐次取剩余时间；每次 HTTP 的截止含响应体。
固定已就绪模型的查询链上限为 tokenize 10 + Embedding 300 + 三轮 Qdrant 90 + Chat 120 = 520 秒；
额外模型 ended 状态检查最多 10 秒，懒加载 spec/tokenizer 握手最多 20 秒，也纳入同一累计预算。
正常启用时 Qdrant 初始化在 Bean 建立阶段完成；不把多次 collection/index 初始化误算为单次 30 秒 query。
已核对 Spring 6.2.11 SimpleClientHttpRequest 使用 fixed-length/chunked streaming POST，JDK 21 在该模式不执行隐式 POST 重发；没有配置重试拦截器。
数据库等待同样消耗执行时间，发送前/发布时检查绝对截止；11 分钟是应用截止，不是性能实测。
Chat 使用最多四个无队列虚拟线程，总截止等待会取消工作，底层 socket 连接/读取也取剩余预算，
响应体每次读取再次检查累计截止。未实际退出的工作继续占槽；迟到回执可记 usage，不能复活终态或释放新租约。

恢复每轮最多 20 个过期 PENDING，使用项目锁及旧 start fencing 结束调用/释放原租约，Chat DISPATCHED 转 UNKNOWN。
没有重新检索/发送、长期历史 TTL 或额度释放。项目或文档删除不能消除这些长期记录。

## 回滚

关闭 RAG_ENABLED 即停止新入口。保留 V10、既有会话、引用及额度和恢复扫描，普通聊天仍可用。
已共享执行的 V10 不删改，不删除 UNKNOWN 或源快照来释放额度。
不自动修改 #36/#37 的合并状态或 PR base；前置合并后再由所有者安排重定向和集成复验。
