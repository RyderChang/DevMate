# DeepSeek 官方对话接入验收

关联 [DEV-018](../tasks/DEV-018-deepseek-chat-provider.md)、[ADR 0006](../adr/0006-add-deepseek-chat-provider.md)。
分支 `feature/dev-018-deepseek-chat-provider`，基线 `9721fc42030c3014858fd8720126660184b9b520`。

## 已执行与待验证

2026-09-28，先执行
`python -B scripts/verify-knowledge-backend.py --tests DeepSeekChatGatewayTest,AiProviderConfigurationTest,OpenAiResponsesGatewayTest`：
18 项通过，零失败、零错误、零跳过。宿主 `JAVA_HOME` 为 JDK 21，测试运行于既有锁定 Linux Java 21 容器。

随后执行
`python -B scripts/verify-knowledge-backend.py --tests DeepSeekConversationIntegrationTest,ConversationApiIntegrationTest`：
13 项通过，零失败、零错误、零跳过；使用真实隔离 MySQL 8.4.6 与 Mock 上游。
完整执行 `python -B scripts/verify-knowledge-backend.py`：141 项、36 组全部通过，
零失败、零错误、零跳过；必需的八组知识存储验收报告门禁通过。
流程先以宿主 JDK 21 `clean package -DskipTests` 编译，再在锁定 Linux Java 21 中执行完整后端测试；
不以 H2 替代 MySQL，也没有真实模型调用。原始日志保留在忽略的本地目录，未发布。

`node scripts/check-docs.mjs --format-changed origin/develop`：46 份 Markdown、152 个相对链接及改动格式通过。
配置 YAML 的 Prettier 检查、`git diff --check` 和 17 个修改文件清单对照通过。
依赖构建文件、既有 V1–V6 migration 与前端均无差异。最终提交 CI 以功能 PR 的 Checks 为准。
上述自动验证期间未配置 `DEEPSEEK_API_KEY`，没有执行真实 DeepSeek 调用。
模拟上游验证不能证明官方服务可用性、响应时延、实际模型质量或账单费用。

## 所有者授权的真实调用

2026-09-28，所有者提供官方平台密钥并明确授权测试后，执行一次真实 HTTPS 请求。
被测实现为 `dd93d2561eecfe8c4c2b94d5ffe148c73836de8f` 的 `DeepSeekChatGateway`；
该提交的 [Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/36406407693) 已通过。
本次只补充验收记录，未修改应用实现或重新执行此前的 141 项自动测试。

用宿主 JDK 21 编译本地临时 Java 驱动，复用已构建的应用类和依赖，然后执行
`python -u -B tmp/dev-018-live/run.py`。驱动与依赖位于 Git 忽略的临时目录，不进入提交或 CI。
先以无敏感信息的输入验证隐藏输入不会回显，再通过隐藏输入及子进程标准输入传递密钥；
密钥仅在进程内使用，未写入文件、命令行参数、环境配置、Git 或验收输出。

驱动直接调用项目适配器，仅向 `https://api.deepseek.com/chat/completions` 发送
合成中文问题“Java 的 String 是否可变？”，要求一句简短中文回答。
请求指定 `deepseek-flash`、`stream=false`、`thinking.type=disabled`、`max_tokens=64`；
连接超时 5 秒、读取超时 30 秒，禁用重定向，不重试。

| 验收项                    | 实际结果                                  |
| ------------------------- | ----------------------------------------- |
| HTTP 状态与 POST 尝试次数 | 200；1 次                                 |
| 响应校验                  | 项目适配器的完整响应与整数 usage 校验通过 |
| 可见文本                  | 非空且包含中文                            |
| 输入 / 输出 / 合计 Token  | 25 / 16 / 41                              |
| 适配器耗时                | 861 ms                                    |
| 驱动内部总耗时            | 1052 ms                                   |
| 请求模型                  | `deepseek-flash`                          |

仅输出以上脱敏元数据，未保存或发布密钥、请求头、原始响应、回答正文或请求标识。
没有发送真实项目、仓库或文档内容。本次验证到适配器与官方服务的边界，
不构成真实提供商经页面/API 落库的端到端验证；该业务路径此前通过真实 MySQL 与 Mock 上游验证。
一次小请求不能说明整体质量、稳定性或长期延迟；没有保存远端模型快照，
没有取得账单金额，实际收费须由所有者核对官方账单。
密钥曾由所有者粘贴到聊天，测试结束后应在官方平台撤销并重新生成，后续只在本地隐藏输入配置。

## 验证范围

- `DeepSeekChatGatewayTest`：中文和 Unicode、system 与历史、非思考/非流式及输出上限；
  拒绝截断、工具、多候选、畸形 JSON/UTF-8、超大响应、缺失/小数/负数/溢出/不一致 usage；安全错误与不重试。
- `AiProviderConfigurationTest`：DeepSeek 无 OpenAI 凭据、默认关闭、兼容原 OpenAI 选择、
  缺少凭据和未知提供商拒绝、官方 HTTPS 地址/路径、错误不回显敏感 URL、模型和密钥控制字符拒绝。
- `DeepSeekConversationIntegrationTest`：Mock 上游与真实 MySQL，经 API 保存 provider/model/tokens、
  UUID 重放只调用一次、跨用户调用前拒绝、限流终态与释放租约；隐藏推理不进入消息。

## 修改文件

```text
README.md
devmate-server/README.md
devmate-server/src/main/java/com/devmate/ai/config/AiConfiguration.java
devmate-server/src/main/java/com/devmate/ai/config/AiProperties.java
devmate-server/src/main/java/com/devmate/ai/config/DisabledAiGateway.java
devmate-server/src/main/java/com/devmate/ai/infrastructure/deepseek/DeepSeekChatGateway.java
devmate-server/src/main/resources/application.yml
devmate-server/src/test/java/com/devmate/ai/AiProviderConfigurationTest.java
devmate-server/src/test/java/com/devmate/ai/DeepSeekChatGatewayTest.java
devmate-server/src/test/java/com/devmate/conversation/DeepSeekConversationIntegrationTest.java
docs/README.md
docs/adr/README.md
docs/adr/0006-add-deepseek-chat-provider.md
docs/architecture/README.md
docs/development/local-development.md
docs/tasks/DEV-018-deepseek-chat-provider.md
docs/testing/deepseek-chat-provider-acceptance.md
```

## 限制与回滚

默认不启用真实 AI；真实调用需要本地密钥与显式配置。无应用金额预算、缓存费用细目或远端模型快照。
读取超时不能证明上游未执行或未收费，不能自动重试结果未知请求。
本任务未新增依赖、migration、前端、Embedding、Qdrant 或 RAG。
回滚可关闭 `AI_ENABLED` 并重启，或选择既有 `openai` 提供商；已产生的对话与调用数据保留，不清库。
新功能 PR 提交审查；已执行上述一次授权收费冒烟，上线与本功能 PR 合并尚未执行。
