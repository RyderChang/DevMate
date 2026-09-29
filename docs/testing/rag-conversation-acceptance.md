# DEV-022 RAG 对话验收

日期：2026-09-29。分支 `feature/dev-022-rag-conversation`，基线 #37 `0432cafeda9e4be8d30f698a91fb76ffb90d7bab`。
所有者接受 ADR 0008 全部提议及独立依赖分支；#36/#37 未合并。仅后端、默认关闭、付费金额 CNY 0。
实现提交 `6c5dc79c0a11cdfc91cd9692e587be65a2380876`；独立草稿 [PR #39](https://github.com/RyderChang/DevMate/pull/39) 以 #37 分支为目标。
恢复修复提交 `fb63396b62dc0ecc601c365818d8e04d6e2475be`，以下完整验收对应这一源码；后续验收说明提交不改实现。

## 验证记录

- 首轮定向 35 项：1 failure / 1 error；修正测试表名与错误的正文检测断言。
- 第二轮定向 38 项：1 failure / 2 errors；修正事务代理 Spy 的 stubbing 目标（连带未完成 stubbing）及 OpenAPI 请求缺少 JWT。失败轮次不记通过。
- `python -B scripts/verify-knowledge-backend.py`：完整 255 项通过，失败/错误/跳过均 0；真实 MySQL/MinIO/Qdrant，新增 RAG 与 V9→V10 升级套件、无跳过门禁通过。这次完整运行发生在最终 OpenAI UTF-8/usage 与 HTTP 截止测试补强之前，不能当作最终源码完整测试数量。
- `python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest,RagPromptAndOutputTest,RagChatCallsTest,OpenAiResponsesGatewayTest,DeepSeekChatGatewayTest,LocalEmbeddingGatewayTest,AiProviderConfigurationTest,RagProviderDeadlineTest`：最终实现定向 55 项通过，失败/错误/跳过均 0。
- `node scripts/check-docs.mjs --format-changed 0432cafeda9e4be8d30f698a91fb76ffb90d7bab`：67 Markdown / 269 相对链接及改动格式通过；workflow Prettier 检查通过。

- `python -B scripts/verify-knowledge-backend.py --tests RagProviderDeadlineTest`：强化后的直接 Socket 预算验证 1 项通过，失败/错误/跳过均 0，证明配置后的 HTTP body 超时及一次 POST；不依赖外层 Future 制造通过结果。

- `python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest`：补充父项目删除边界后 19 项通过，失败/错误/跳过均 0。

- 实现提交 `6c5dc79` 的 [Foundation](https://github.com/RyderChang/DevMate/actions/runs/36540357201) 与 [Embedding Preflight](https://github.com/RyderChang/DevMate/actions/runs/36540357177) 均 completed/success：后端 `clean verify` 260 项，失败/错误/跳过 0；真实存储及无跳过门禁通过。前端 14 文件 / 89 项及 type-check、lint、format、build、audit、文档检查均通过。
- 复核新增“调度关闭、两入口接管已发送但无回执的过期 RAG”回归：20 项中 1 failure / 0 error / 0 skipped，证明旧状态错误地保留 DISPATCHED；修复同一 MySQL 事务内的 UNKNOWN 更新。
- `python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest,ConversationIntegrationTest`：修复后实际执行 RAG 集成 20 项全部通过，失败/错误/跳过 0。后一个过滤名称没有匹配测试类，普通聊天完整回归以 Foundation 为准，不将其记为额外测试。

- 恢复修复提交 `fb63396` 的 [Foundation](https://github.com/RyderChang/DevMate/actions/runs/36541659294) 与 [Embedding Preflight](https://github.com/RyderChang/DevMate/actions/runs/36541659243) 均 completed/success。实际执行 `./devmate-server/mvnw -B -f devmate-server/pom.xml clean verify`：261 项，失败/错误/跳过均 0；`python3 -B scripts/check-knowledge-test-reports.py` 无跳过门禁通过，包含真实 MySQL 8 空库和 V9→V10 迁移、MinIO、Qdrant。普通聊天 API 与两家适配器的完整回归包含在这 261 项中。
- 同一修复提交的 Foundation 前端：14 文件 / 89 项及 type-check、lint、format、build、audit、Stub fixture、文档与 workflow 检查全部通过；67 Markdown / 269 相对链接，audit 为 0 vulnerabilities。没有修改前端实现。

PR 最新 head 的检查状态仍须在合并前核对；上述真实网络仅连接测试 loopback Stub，未调用真实收费模型。

## 合成证据样本逐项核对

`syntheticEvidenceSamplesHaveValidatedSourcesAndExplicitUnsupportedOrConflictingAnswers` 使用固定 Stub 输出，逐项对照输入文档：

| 样本            | 提供的证据                                              | 预期回答与引用核对                                               |
| --------------- | ------------------------------------------------------- | ---------------------------------------------------------------- |
| Spring 默认事务 | runtime exceptions roll back; checked exceptions do not | 回答 checked exceptions 默认不回滚，C1 对应该规则                |
| 无依据问题      | 文档仅描述事务；没有生产 JVM 内存参数                   | 回答该文档未给出生产参数，C1 仅指已检查文档，不作为配置证据      |
| 相互矛盾政策    | A 要求 checked exceptions 回滚；B 要求不回滚            | 明示冲突和适用配置未知，同时引用 C1/C2，不替用户选择政策         |
| 恶意片段        | 正常 Spring 规则与要求 shell/泄露凭据/伪造 C9 的片段    | 规则/数据保持 JSON 隔离；回答只引用正常规则 C1，无工具声明或执行 |

这是固定 fixture 的结构与证据对照，不是对真实聊天模型的质量测量。项目所有者仍需在 PR 审核中人工复核事实支撑；未冒充已有人类签收。

## 范围与限制

引用快照、UUID 内容/模式、11/12 分钟截止、整片上下文裁剪、实际 usage 和原子长期记录额度属于本任务。
真实 MySQL 使用固定镜像并从空库执行 V1–V10；网络使用 Stub Chat、合成 Embedding。
V1–V9、模型/依赖版本、Embedding token/容量额度不修改。

合成样本含 Spring 事务规则、有意无依据问题、矛盾与恶意片段；结构与服务端定位可以自动验证。
人工检查只评估这些固定样本的引用支持，不能证明真实项目召回、回答准确性、生产吞吐或真实付费模型行为。
数据库全面不可写且回执不能持久化时保留 UNKNOWN，不猜测 usage；需提供商记录人工核对。
历史回答保留，删除原文档不能撤回已保存摘要。没有长期记录 TTL/清账 API。

未实现前端引用 UI、SSE、工具、自动执行代码、部署或真实收费调用；无适用 UI 截图。
回滚关闭 RAG_ENABLED，保留 V10 与历史；下一任务可单独准备前端引用展示及真实项目质量评价，本任务不实施。

## 修改文件

```text
.github/workflows/embedding-preflight.yml
.github/workflows/foundation.yml
README.md
devmate-server/src/main/java/com/devmate/ai/application/CallBudget.java
devmate-server/src/main/java/com/devmate/ai/config/AiConfiguration.java
devmate-server/src/main/java/com/devmate/ai/embedding/LocalJsonClient.java
devmate-server/src/main/java/com/devmate/ai/infrastructure/deepseek/DeepSeekChatGateway.java
devmate-server/src/main/java/com/devmate/ai/infrastructure/openai/OpenAiResponsesGateway.java
devmate-server/src/main/java/com/devmate/common/api/ErrorCode.java
devmate-server/src/main/java/com/devmate/common/exception/BusinessException.java
devmate-server/src/main/java/com/devmate/conversation/controller/RagController.java
devmate-server/src/main/java/com/devmate/conversation/dto/RagMessageRequest.java
devmate-server/src/main/java/com/devmate/conversation/entity/AiInvocationEntity.java
devmate-server/src/main/java/com/devmate/conversation/mapper/AiInvocationMapper.java
devmate-server/src/main/java/com/devmate/conversation/mapper/RagJournal.java
devmate-server/src/main/java/com/devmate/conversation/service/ConversationConfiguration.java
devmate-server/src/main/java/com/devmate/conversation/service/ConversationTransactionService.java
devmate-server/src/main/java/com/devmate/conversation/service/ProjectRagPromptBuilder.java
devmate-server/src/main/java/com/devmate/conversation/service/RagChatCalls.java
devmate-server/src/main/java/com/devmate/conversation/service/RagInput.java
devmate-server/src/main/java/com/devmate/conversation/service/RagOutputValidator.java
devmate-server/src/main/java/com/devmate/conversation/service/RagProperties.java
devmate-server/src/main/java/com/devmate/conversation/service/RagRecovery.java
devmate-server/src/main/java/com/devmate/conversation/service/RagService.java
devmate-server/src/main/java/com/devmate/conversation/service/RagTransactions.java
devmate-server/src/main/java/com/devmate/conversation/vo/CitationResponse.java
devmate-server/src/main/java/com/devmate/conversation/vo/CitationSource.java
devmate-server/src/main/java/com/devmate/conversation/vo/RagMessageResponse.java
devmate-server/src/main/java/com/devmate/conversation/vo/RagSummary.java
devmate-server/src/main/java/com/devmate/knowledge/application/RagSourceEligibility.java
devmate-server/src/main/java/com/devmate/knowledge/infrastructure/RetrievalJournal.java
devmate-server/src/main/resources/application.yml
devmate-server/src/main/resources/db/migration/V10__add_rag_conversation_contract.sql
devmate-server/src/test/java/com/devmate/ai/LocalEmbeddingGatewayTest.java
devmate-server/src/test/java/com/devmate/ai/OpenAiResponsesGatewayTest.java
devmate-server/src/test/java/com/devmate/ai/RagProviderDeadlineTest.java
devmate-server/src/test/java/com/devmate/conversation/RagChatCallsTest.java
devmate-server/src/test/java/com/devmate/conversation/RagPromptAndOutputTest.java
devmate-server/src/test/java/com/devmate/database/ConversationMigrationIntegrationTest.java
devmate-server/src/test/java/com/devmate/database/DatabaseInfrastructureIntegrationTest.java
devmate-server/src/test/java/com/devmate/database/RagMigrationIntegrationTest.java
devmate-server/src/test/java/com/devmate/knowledge/RagConversationIntegrationTest.java
docs/README.md
docs/adr/0008-bound-rag-conversation-and-citations.md
docs/adr/README.md
docs/api/rag-conversation.md
docs/development/local-development.md
docs/development/rag-conversation.md
docs/tasks/DEV-022-rag-conversation.md
docs/testing/rag-conversation-acceptance.md
scripts/check-knowledge-test-reports.py
```
