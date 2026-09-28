# DEV-018：DeepSeek 官方对话提供商

## 目标与授权

2026-09-28 所有者要求主要面向中国市场，先试用 DeepSeek，并明确选择“DeepSeek 官方 API”。
本任务优先于 DEV-017 实现，在独立 `feature/dev-018-deepseek-chat-provider` 分支交付；
前置 #30、#31 已经所有者明确授权并合并，最新基线为 `9721fc42030c3014858fd8720126660184b9b520`。
决策见 [ADR 0006](../adr/0006-add-deepseek-chat-provider.md)。

## 范围

- 保持业务依赖 `AiGateway`，新增 DeepSeek 官方非流式、非思考、纯文本 Chat Completions 适配器。
- 部署者用 `AI_PROVIDER=deepseek`、`DEEPSEEK_API_KEY` 与 `DEEPSEEK_MODEL` 选择提供商。
- AI 默认关闭；保留现有 OpenAI 配置兼容性，不要求 OpenAI 密钥才能启用 DeepSeek。
- 使用已有会话、身份、项目隔离、UUID 重放、租约、上下文、输出及响应体上限。
- 检查完成原因、文本、响应标识和整数用量；不接受部分回复、工具调用或隐藏推理作为可见答案。
- 提供商异常返回稳定安全错误，认证/余额/限流/超时/网络失败不自动重复付费请求。
- 合同、配置路由和真实 MySQL 对话验证使用 Mock 上游，CI 不调用真实模型。
- 更新本地配置、冒烟步骤和验证证据。

## 非目标与验收

本任务不包含 Embedding、Qdrant、RAG、处理片段、思考或流式模式、多提供商自动路由、fallback、
BYOK 页面、第三方代理接入或金额预算平台。项目对话页面和数据库结构不变。

交付要求：无 OpenAI 凭据也能选择 DeepSeek；关闭状态无需模型密钥启动；官方 HTTPS 地址受限；
跨用户访问在上游调用前拒绝；成功用量与模型提供商可追踪；同一 UUID 不重复调用；失败释放租约；
不保存隐藏推理或原始 HTTP 响应；既有 OpenAI 和后端回归通过。
真实模型冒烟单独记录，未配置本地密钥时不能把模拟结果当作官方服务可用性或模型质量结论。

验证记录与所有修改文件见 [DeepSeek 接入验收](../testing/deepseek-chat-provider-acceptance.md)。
真实试用步骤见[本地开发指南](../development/local-development.md)。
