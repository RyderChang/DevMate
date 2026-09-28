# 0006：新增 DeepSeek 官方对话适配器

- 状态：Accepted
- 日期：2026-09-28
- 关联任务：[DEV-018](../tasks/DEV-018-deepseek-chat-provider.md)
- 接受依据：所有者要求先试用 DeepSeek，并明确选择官方 API；常规实现选择授权 Agent 判断。
- 扩展：[ADR 0002](0002-ai-gateway-and-application-managed-conversations.md) 的提供商无关 Gateway 和应用侧状态；保留其首个 OpenAI 适配器结论。

## 背景与决策

主要使用者面向中国市场，不能将 OpenAI API 购买作为项目对话的使用条件。
新增 `deepseek` 提供商，业务模块继续只调用 `AiGateway`；部署者显式选择一种提供商，
不自动路由、fallback 或让页面传入模型 URL/密钥。
现有默认 `AI_PROVIDER=openai` 保留兼容性，文档推荐试用时明确设置 `AI_PROVIDER=deepseek`。

依据 [DeepSeek 官方首调示例](https://api-docs.deepseek.com/)与
[Chat Completions 契约](https://api-docs.deepseek.com/api/create-chat-completion/)，
使用 `POST /chat/completions`，把应用指令作为 system 消息、受限历史作为 user/assistant 消息。
显式 `stream=false`、`thinking.type=disabled`、`max_tokens`；不发送 tools、文件、图像、
托管 conversation、之前的 response ID 或未在 Chat Completions 契约中列出的 `store`。
OpenAI Responses 适配器继续发送 `store=false`；两种协议的 HTTP DTO 不离开 infrastructure。

非思考模式按[官方思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)设置，
只显示 `message.content`，忽略 `reasoning_content`，不能把隐藏推理写入消息或日志。
只接受唯一 index=0、assistant 文本、`finish_reason=stop` 的完整回复；
长度截断、过滤中断、工具调用或多候选均受控失败。
严格校验 UTF-8、响应体字节上限和非负整数 usage 三项及加和，不把缺失用量当作零。

## 配置、安全与成本

沿用现有 5 秒连接、60 秒读取、1,024 输出 tokens、1 MiB 响应体和 2 分钟生成租约默认值，
所有现有硬上限继续生效。读取超时是客户端读取等待上限，不承诺提供商计费请求可被撤回。
不自动重试任何上游请求；未知结果或失败 UUID 遵循已有对话终态与重放契约。

官方 base URL 默认 `https://api.deepseek.com`，可选 `/v1` 路径；只允许官方 HTTPS origin、
默认或 443 端口，不接受 userinfo、query、fragment 或其他路径。
密钥和模型显式注入环境；错误和异常 cause 不携带上游正文、密钥或错误 URL。
模型 ID 使用配置值并记录，远端 alias 会变更，不能宣称不可变模型快照。

截至本次核查，官方当前模型名见[模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)，
试用建议配置 `deepseek-flash`。价格存在缓存命中、峰谷、输入与输出差异，
本任务仅记录现有三项 token 用量，不建立人民币或美元账单，也不保存缓存费用细目。
没有应用金额硬预算；真实试用应使用充值有限的专用账户并查看官方账单。
配置输出上限和不重试用于限制单次生成，不能替代金额预算。

## 验证与后续

新配置和适配器使用 MockRestServiceServer，真实 MySQL 测试验证 API、隔离、幂等、失败终态及审计。
CI 无密钥、无真实付费调用；本地缺少密钥时仅交付接入和模拟验证，真实质量/费用冒烟独立记录。

截至本次核查未找到 DeepSeek 官方 Embedding API 契约，聊天兼容性不代表具备向量能力。
DEV-017 解析与分块与供应商无关；国内 Embedding 或本地模型在索引任务另行核验规格与成本。
后续思考、流式、第三方平台或金额预算另立任务，不扩大本次适配器。
