# DEV-030：RAG JSON 输出诊断验收记录

日期：2026-10-10。任务标准见 [DEV-030](../tasks/DEV-030-rag-json-output-diagnostics.md)。本记录只描述新代码可为**未来请求**提供的诊断；PR #47 的 Q9 已保存 `JSON_SCHEMA`，未保存模型原文，仍不能判定是哪一种 JSON 缺陷。

## 实现与安全边界

V12 通过新的 Flyway migration 扩展现有 `failure_category` 的 `CHECK` 约束，保留旧 `JSON_SCHEMA`，增加 `JSON_SIZE`、`JSON_DUPLICATE_KEY`、`JSON_SYNTAX`、`JSON_TRAILING`、`JSON_SHAPE`。没有新增字段或改变 V11。`RagOutputValidator` 在原有严格校验失败时，才对同一有界字符串进行一次不启用重复键检测的只读复核；复核结果绝不用于发布回答。审核发现 `AiChatResult` 拒绝 `null`，DeepSeek 空正文也由网关先归为 `PROVIDER_RESPONSE` / `MESSAGE_CONTENT`，因此移除原计划中生产不可达的 `JSON_ABSENT`；校验器对防御性 `null` 输入返回固定 `JSON_SYNTAX`。

诊断记录只含固定类别，不含模型正文、字段名、解析器 message 或提示词。公开错误保持 `AI_RESPONSE_INVALID` 502，答案与引用合同、usage 和 UUID 重放规则不变。提示词、提供商参数和检索排序没有调整。

## 确定性验证

实现前先执行 `devmate-server/mvnw.cmd -B -ntp -f devmate-server/pom.xml '-Dtest=RagOutputDiagnosticTest' test`：2 项中 1 项按当时标准预期失败，`null` 输出的旧类别为 `JSON_SCHEMA`；审核后纠正了这个生产不可达的标准。该旧红测不作为五类可达性的证据。

初版实现后执行 `devmate-server/mvnw.cmd -B -ntp -f devmate-server/pom.xml '-Dtest=RagOutputDiagnosticTest,RagPromptAndOutputTest' test`：JDK 22 下 7 项通过，0 失败、0 错误、0 跳过。当时的 fixture 覆盖六种计划类别、有效 JSON、原有 answer/引用失败，以及异常固定公开文案；其中 `JSON_ABSENT` 后经审核确认为生产不可达。`git diff --check` 通过。

PR 审核修订后执行 `devmate-server/mvnw.cmd -B -ntp -f devmate-server/pom.xml '-Dtest=RagOutputDiagnosticTest,RagPromptAndOutputTest,DeepSeekChatGatewayTest' test`：JDK 22 下 16 项通过，0 失败、0 错误、0 跳过；新增网关空正文固定类别回归，校验器现覆盖五种生产可达的 JSON 类别。`git diff --check` 再次通过。

`node scripts/check-docs.mjs --format-changed 420484c54a9bae4173b50c8abeac083f4276d83d` 检查 75 个 Markdown 文件、291 个相对链接和改动文档格式，通过。

[PR #48 的 Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/38017779195) 在固定 Linux/JDK 21、MySQL Testcontainers 上通过：后端 271 项，0 失败、0 错误、0 跳过；其中 `RagMigrationIntegrationTest` 3 项、`RagConversationIntegrationTest` 26 项、`RagOutputDiagnosticTest` 2 项均通过，V12 已从空库顺序执行，也通过 V11 旧诊断行升级验证。无跳过报告门禁和前端检查均通过。

## 未确认事项

本机 Docker 服务未启动，本机只运行了 JDK 22 单元测试；MySQL 8 / Testcontainers 验证来自上述 CI。旧 Q9 的具体失败类别仍未知。本任务没有向 DeepSeek 发送请求，新增真实费用为零；若需未来 Q9 复测，须另行明确授权。
