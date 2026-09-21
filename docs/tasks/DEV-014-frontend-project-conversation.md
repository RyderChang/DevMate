# DEV-014：前端项目对话与同步交互

## 1. 任务元信息

| 项目         | 内容                                                                 |
| ------------ | -------------------------------------------------------------------- |
| 任务编号     | `DEV-014`                                                            |
| 任务名称     | 前端项目对话与同步交互                                               |
| 所属阶段     | 第二阶段——AI 对话                                                    |
| 任务类型     | 前端业务能力、对话与消息交互、路由、错误恢复及自动化测试             |
| 状态         | 待实施；本任务书不代表前端对话已经完成                               |
| 前置任务     | DEV-013 经项目所有者审核并合并到 `develop`                           |
| 目标分支     | `develop`                                                            |
| 建议实现分支 | `feature/dev-014-frontend-project-conversation`                      |
| 预计主要目录 | `devmate-web/`、相关 README                                          |
| 明确不涉及   | 后端契约、流式输出、取消生成、RAG、工具调用、Markdown 渲染、代码执行 |

本任务书基于 2026-09-21 刷新的远端 `develop` 提交
`45fcae14bf8dcb4f182bdfa6b0340015985229eb`，以及尚未合并的 DEV-013
[PR #25](https://github.com/RyderChang/DevMate/pull/25) 修复提交
`af2c6e2cfacc0e52e0f4b261af04348f75b157fa` 编写。DEV-014 实现不得直接从 DEV-013
功能分支继续；必须等待 PR #25 合并，从合并后的最新 `develop` 创建独立分支，并重新核对实际接口、
类型、测试数量和文档状态。

## 2. 背景与目标

DEV-013 提供项目隔离的对话、消息分页、同步 AI 生成、调用审计、幂等请求和单会话生成租约。
当前前端只有认证与项目 CRUD，用户不能在浏览器中创建对话、查看历史或发送消息。

DEV-014 交付第二阶段的最小前端纵向切片：

1. 从项目详情进入该项目的对话列表；
2. 创建项目对话并进入对话页；
3. 分页浏览对话和按顺序加载历史消息；
4. 使用同步、非流式接口发送一条消息并展示用户消息、助手回复与生成状态；
5. 使用客户端 UUID 配合后端幂等约束，避免重复点击和不确定网络结果产生重复模型调用；
6. 正确处理加载、空数据、失败、重试、AI 未启用、生成冲突和会话失效；
7. 保持项目、对话和消息内容的纯文本安全渲染；
8. 通过现有 Vitest、Vue Test Utils 和 jsdom 覆盖 API、路由和关键交互。

完成本任务后只能声明“前端项目对话与同步交互可用”。流式 token、停止生成、RAG 引用、
Markdown、代码高亮、对话管理和生产级费用治理仍未完成。

## 3. 启动条件与基线核查

### 3.1 启动条件

1. DEV-013 PR 已由项目所有者审核并合并；不得由 Agent 自动合并。
2. 更新远端引用，确认本地 `develop` 与 `origin/develop` 一致且工作区干净。
3. 从最新 `develop` 创建 `feature/dev-014-frontend-project-conversation`。
4. 核对对话 Controller、DTO、VO、错误映射、消息顺序及分页默认值；若已变化，先更新任务书或在 PR 中说明。
5. 保持 DEV-010、DEV-011 的认证、项目归属、路由和陈旧请求保护测试通过。
6. 后端 AI 可以保持默认关闭；前端自动化测试必须 Mock API，不调用真实模型或公共网络。

### 3.2 当前前端基线

- Vue 3、TypeScript、Vite、Element Plus、Vue Router、Pinia 和 Axios 已接入；
- 统一 Axios 实例位于 `src/api/http.ts`，默认超时 10 秒并统一处理 `401`；
- 项目 API、公共 `ApiResult<T>`、`PageResult<T>` 与 ID 安全整数校验已经存在；
- 登录用户默认进入 `/projects`，项目页面位于受保护布局下；
- 项目详情已具备返回、编辑和删除操作，可增加进入对话的入口；
- 项目页面已有加载、失败、重试、资源不可用和陈旧响应保护模式；
- 当前没有对话类型、对话 API、对话路由、消息组件或生成状态；
- 不应为本任务新增生产依赖或第二套全局状态、HTTP 客户端和 Token 存储。

## 4. 冻结的产品与路由设计

### 4.1 路由

新增两个受保护路由：

| 路径                                                 | 路由名              | 用途                         |
| ---------------------------------------------------- | ------------------- | ---------------------------- |
| `/projects/:projectId/conversations`                 | `conversation-list` | 当前项目的对话列表与创建入口 |
| `/projects/:projectId/conversations/:conversationId` | `conversation-chat` | 对话消息与同步发送           |

要求：

- 两个路由必须位于现有 `requiresAuth` 布局下并使用懒加载；
- 项目详情增加“项目对话”入口，不新增全局独立 AI 菜单或跨项目对话聚合页；
- 路径中的 `projectId`、`conversationId` 必须为正 JavaScript 安全整数，非法值不得发送请求；
- 对话页必须同时保留项目 ID 和对话 ID，不能仅凭裸对话 ID 查询；
- 登录安全重定向、会话恢复、退出登录和项目路由行为不得回归；
- 本任务不增加对话编辑、删除、归档、恢复、搜索或自动标题入口。

### 4.2 对话列表

对话列表页必须提供：

- 项目基本信息或清晰的项目返回入口；
- 加载、空数据、失败、重试和成功状态；
- 对话标题、生成状态、创建时间、更新时间及进入对话操作；
- `page`、`pageSize` 与 URL 查询参数同步；默认 `page=1`、`pageSize=20`；
- 可选页大小 `10`、`20`、`50`、`100`，不超过后端限制；
- 非法查询参数在请求前规范化，并使用路由替换为合法 URL；
- 创建对话表单或对话框；标题可选，去除首尾空白后最多 200 个 Unicode 字符；
- 创建成功后使用服务端返回的 ID 进入对话页；重复点击不得发送并发创建请求。

列表顺序完全使用后端返回的 `updatedAt DESC, id DESC`，前端不重新排序。`GENERATING`
对话应显示明确状态，但列表轮询不在本任务范围内；用户可进入对话页或手动重试加载。

### 4.3 对话页

对话页必须先验证路径 ID，再加载项目和对话，确保标题、返回路径和资源状态准确。页面至少包含：

- 返回当前项目对话列表和项目详情的入口；
- 对话标题与 `IDLE` / `GENERATING` 可理解状态；
- 消息加载状态、无消息状态、历史失败与重试；
- 按 `sequenceNo ASC` 展示的 USER / ASSISTANT 消息；
- 文本输入、字符计数、发送按钮和生成中状态；
- 同步响应完成后追加服务端返回的用户消息和助手消息；
- 生成失败时保留草稿并提供安全、可操作的重试说明；
- 320px 窄屏和桌面宽度下均可完成查看与发送。

消息必须使用文本插值或 `textContent` 等价方式展示，并保留换行。禁止使用 `v-html`、Markdown
解析或代码执行。超长内容必须换行，不得撑破布局。

## 5. 后端 API 与前端类型契约

### 5.1 API

DEV-014 只消费 DEV-013 已有接口：

| 方法   | 路径                                                            | 用途             |
| ------ | --------------------------------------------------------------- | ---------------- |
| `POST` | `/projects/{projectId}/conversations`                           | 创建对话         |
| `GET`  | `/projects/{projectId}/conversations?page=1&pageSize=20`        | 分页查询对话     |
| `GET`  | `/projects/{projectId}/conversations/{conversationId}`          | 查询对话         |
| `GET`  | `/projects/{projectId}/conversations/{conversationId}/messages` | 分页查询消息     |
| `POST` | `/projects/{projectId}/conversations/{conversationId}/messages` | 同步生成一次回复 |

前端不得发送 `ownerUserId`、provider、model、提示词、工具、base URL、API Key、Token 用量或生成状态。
项目和对话归属始终由后端判定。

### 5.2 类型

按合并后的实际响应定义等价 TypeScript 类型：

```ts
type GenerationState = "IDLE" | "GENERATING";
type ConversationRole = "USER" | "ASSISTANT";

interface Conversation {
  id: number;
  projectId: number;
  title: string;
  generationState: GenerationState;
  createdAt: string;
  updatedAt: string;
}

interface ConversationMessage {
  id: number;
  role: ConversationRole;
  content: string;
  sequenceNo: number;
  createdAt: string;
}

interface InvocationSummary {
  status: "SUCCEEDED";
  provider: string;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  durationMs: number | null;
  completedAt: string | null;
}

interface SendMessageResponse {
  conversationId: number;
  userMessage: ConversationMessage;
  assistantMessage: ConversationMessage;
  invocation: InvocationSummary;
}
```

所有 BIGINT 响应继续受现有 JSON number 限制。前端必须拒绝非正安全整数，不在本任务单方面把 ID
改成字符串，也不修改后端主键策略。服务端时间按 ISO 8601 接收，解析失败显示 `--`。

### 5.3 API 封装

新增集中式 conversation API 模块，至少提供：

- `listConversations(projectId, params)`；
- `createConversation(projectId, request)`；
- `getConversation(projectId, conversationId)`；
- `listConversationMessages(projectId, conversationId, params)`；
- `sendConversationMessage(projectId, conversationId, request)`。

所有路径段先通过公共安全整数校验。不得在页面中拼接基础 URL、读取 Token 或创建 Axios 实例。
对消息发送请求使用现有 HTTP 实例的单请求超时覆盖，建议 `130_000ms`，以覆盖后端允许的最长
120 秒 provider 读取超时和必要处理时间；不得把全局 10 秒超时整体放大。

## 6. 消息分页与加载策略

后端消息固定按 `sequenceNo ASC, id ASC` 分页。前端采用“最新一页 + 向前加载”策略：

1. 首次请求 `page=1&pageSize=50` 获取 `total`；
2. 若总页数大于 1，再请求计算出的最后一页并展示，不把第一页重复混入；
3. 用户点击“加载更早消息”时请求前一页并按序前插；
4. 通过消息 ID 和 sequenceNo 去重，不能因重试或页面请求重叠显示重复消息；
5. 追加发送响应时验证 `conversationId` 与当前路由一致，并按 sequenceNo 合并；
6. 路由切换或组件卸载后，旧响应不得覆盖新对话；
7. 本任务不实现无限滚动、虚拟列表、消息搜索或基于游标的分页。

如果实施时后端改为更适合最新消息的契约，应先同步任务书和类型，不在页面中隐式猜测排序。

## 7. 同步发送、幂等与恢复

### 7.1 输入规范化

- 提交前去除首尾空白；规范化后不能为空；
- 最大 8000 个 Unicode code point，使用 `Array.from(content).length` 或等价逻辑计算，不能直接以
  UTF-16 `string.length` 误判 emoji；
- 输入框显示字符计数，生成期间禁止重复发送；
- 不把草稿、消息或 clientRequestId 写入 localStorage、sessionStorage 或日志。

### 7.2 clientRequestId 生命周期

每次新的发送意图使用 `crypto.randomUUID()` 生成一个 UUID：

- 同一点击流程只生成一次，重复事件不得产生多个请求；
- 成功响应后清除该请求 ID 和已发送草稿；
- HTTP `400`、`403`、`404`、`502`、`503`、`504` 等明确响应表示本次请求已有确定结果，用户再次发送时使用新 UUID；
- 网络断开或客户端超时属于结果不确定，保留原 UUID 和原始内容；用户点击“重试确认结果”时复用同一 UUID；
- `409` 表示生成仍在进行，保留 UUID、禁用并发新发送，并允许稍后用同一 UUID 重试；
- 不通过自动循环重试制造重复费用；所有重试必须有上限并由用户明确触发。

若浏览器刷新或关闭导致内存状态丢失，本任务不承诺恢复 clientRequestId。重新进入页面应先读取消息和
对话生成状态；不得声称中止了服务端仍可能进行的调用。

### 7.3 生成状态

- 请求开始后显示“正在生成”，输入和发送按钮进入合理禁用状态；
- 成功时使用响应中的两条消息更新页面，不伪造本地消息 ID、时间或 Token；
- 失败时不把草稿显示成已持久化消息；
- `GENERATING` 状态禁止创建新的发送意图，提供手动刷新或稍后重试；
- 离开页面只能停止前端更新，不能宣称取消上游模型调用；
- 本任务不实现 token 流、打字机动画、停止按钮、后台轮询或断线续传。

## 8. 错误与安全语义

| 场景                  | 前端行为                                                      |
| --------------------- | ------------------------------------------------------------- |
| 非法项目或对话 ID     | 不发送请求，展示统一资源不可用状态                            |
| `400`                 | 保留草稿，显示安全参数提示                                    |
| `401`                 | 交由现有拦截器清理会话并跳转登录                              |
| `403`                 | 展示无权限提示，不尝试绕过                                    |
| `404`                 | 对项目或对话统一显示“不存在或无权访问”，不泄露资源存在性      |
| `409`                 | 显示已有生成正在进行，保留本次 UUID，禁止并发发送             |
| `502` / `503` / `504` | 显示 AI 暂时不可用或超时，保留草稿，不暴露 provider 正文      |
| 网络错误或客户端超时  | 标记结果不确定，允许使用同一 UUID 手动确认，不自动改用新 UUID |
| 消息历史加载失败      | 保留已成功加载的消息并提供重试，不把已有消息清空              |

当前统一响应只提供数值 `code` 与安全 `message`，多个 AI 错误可能共享相同 HTTP 状态和数值 code。
前端不得依赖英文 message 精确匹配来驱动关键状态；在契约没有稳定机器错误标识时，按 HTTP 状态提供
保守的通用恢复行为。

所有内容视为不可信输入：

- 不使用 `v-html` 或动态执行内容；
- 不将项目描述、对话标题、消息正文、Bearer Token、完整错误响应或 AI 配置写入日志；
- 不把隐藏按钮、前端路由或缓存当作权限控制；
- 不探测相邻 ID，不预取其他项目或对话；
- 用户可见 provider/model/Token 详情不是 MVP 必需项；如展示，只能来自成功响应且不得暗示费用金额。

## 9. 状态管理与组件边界

- API 类型和调用集中在 `src/api/`；
- 列表分页、创建对话、当前消息、草稿、clientRequestId、加载和错误状态优先保留在页面或领域 composable；
- 只有出现真实跨页面共享需求并定义失效规则时才使用 Pinia；
- 不持久化项目、对话、消息或草稿；切换账号后不能看到前一用户快照；
- 可以提取消息列表、消息气泡、发送框或 ID 校验工具，但不得为形式创建空组件；
- 请求版本或等价机制必须防止快速切换路由时的陈旧响应覆盖；
- 对话页不应复制认证、项目错误处理和日期格式化的已有逻辑。

## 10. 自动化测试要求

使用现有 Vitest、Vue Test Utils 和 jsdom，Mock conversation API，不访问真实后端或模型。

### 10.1 API 测试

- 五个 API 的方法、路径、查询参数、请求体和响应解包；
- projectId / conversationId 非法或非安全整数时不发请求；
- 发送接口只覆盖单请求超时，不改变全局客户端超时；
- 请求体不包含所有者、模型、工具或其他服务端字段。

### 10.2 路由与列表测试

- 两个路由受认证保护，直接访问、刷新和登录后安全重定向正常；
- 项目详情入口导航正确；
- 列表加载、空数据、失败、重试和成功；
- URL 分页规范化、翻页和页大小变更；
- 创建标题按规范化后长度校验，成功后进入服务端返回的对话 ID；
- 创建期间重复点击只产生一个请求；
- 404 不区分不存在、已删除或他人资源。

### 10.3 对话页测试

- 非法 ID 不调用 API；
- 项目、对话和消息加载成功、失败与重试；
- 首次加载计算最后一页，更早消息前插且去重；
- USER / ASSISTANT 消息按 sequenceNo 排序并仅按文本渲染；
- 空白、8000 code point、emoji 边界和超长消息校验；
- `crypto.randomUUID()` 每个新发送意图只调用一次；
- 成功追加服务端消息并清空草稿；
- 重复点击和 `GENERATING` 状态不发送并发请求；
- 网络结果不确定时复用 UUID，明确失败后的新发送使用新 UUID；
- `409`、AI 不可用、超时和无效响应显示安全恢复状态；
- 路由切换后的旧响应不能覆盖当前页面；
- `401` 继续触发现有会话清理；
- 不可信 HTML 字符串不会执行或生成未预期元素。

DEV-010、DEV-011 的 HTTP、认证、路由、项目页面和工具测试必须继续通过。测试数量以实施时实际结果为准，
不得把当前基线数量写成永久门槛。

## 11. 非目标

DEV-014 不实现：

- 后端 Controller、Service、Mapper、AI Gateway、配置、错误码或数据库 migration 修改；
- SSE、WebSocket、流式 token、停止生成、真正取消上游请求或断线续传；
- RAG、Embedding、Qdrant、文档上传、引用标注或代码检索；
- function calling、MCP、Web search、file search、computer use 或代码执行；
- Markdown、HTML、Mermaid、代码高亮、复制代码按钮或附件；
- 对话重命名、删除、恢复、导出、搜索、收藏或自动标题；
- 多模型选择、provider 选择、temperature、系统提示词或 API Key UI；
- Token 金额估算、用户配额、账单、全局速率限制或管理后台；
- 跨项目对话列表、分享链接、项目成员或协作；
- 消息编辑、删除、重新生成、分支对话或反馈；
- 新增生产依赖、端到端测试框架、富文本编辑器或全局设计系统；
- 真实付费模型自动测试、部署或发布。

发现上述需求时记录为后续任务，不扩大 DEV-014。

## 12. 验收标准

- [ ] DEV-013 已由项目所有者合并，DEV-014 从最新 `develop` 独立创建。
- [ ] 项目详情可以进入当前项目的对话列表。
- [ ] 对话列表路由和对话页路由均受认证保护。
- [ ] 非法或非安全整数 ID 不发送 API 请求。
- [ ] conversation API 集中封装并复用现有 Axios 实例。
- [ ] 同步发送只覆盖单请求超时，全局 10 秒超时保持不变。
- [ ] 对话列表覆盖加载、空、失败、重试、成功与 URL 分页。
- [ ] 创建对话校验规范化后的可选标题，重复点击不会并发提交。
- [ ] 对话页正确加载项目、对话与最新一页消息。
- [ ] 更早消息可以按序前插，重试或重叠页不会产生重复消息。
- [ ] 消息按服务端 sequenceNo 展示，不使用本地伪造的持久化字段。
- [ ] 输入按 Unicode code point 校验非空和 8000 上限。
- [ ] 每个新发送意图使用一个 UUID，重复点击不重复调用。
- [ ] 不确定网络结果可用同一 UUID 手动确认，确定失败后的新发送使用新 UUID。
- [ ] `GENERATING` 和 `409` 状态不会发起并发新生成。
- [ ] 成功后展示服务端返回的用户与助手消息，失败不伪报成功。
- [ ] `401`、`403`、`404`、`409`、`502`、`503`、`504` 与网络失败具有安全行为。
- [ ] 项目、对话和消息内容只按文本渲染，不使用 `v-html`。
- [ ] Token、密钥、完整消息和 provider 错误正文不会进入日志或持久化存储。
- [ ] 路由切换后旧请求不能覆盖新页面。
- [ ] 320px 和桌面布局可查看历史并发送消息。
- [ ] API、路由、列表、创建、消息加载、发送与恢复路径具有自动化测试。
- [ ] 既有前端测试无回归。
- [ ] `npm run type-check`、Lint、格式、测试、构建和依赖审计通过。
- [ ] README 与文档导航只按实际完成状态更新。
- [ ] 未修改后端、migration，未实现流式、RAG、工具或代码执行。
- [ ] PR 未自动合并，等待项目所有者确认。

## 13. 验证命令

在 `devmate-web/` 执行：

```bash
npm ci
npm run type-check
npm run lint
npm run format:check
npm run test
npm run build
npm audit
```

在仓库根目录执行：

```bash
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check origin/develop...HEAD
git status --short
```

浏览器手工验收至少覆盖：

1. 登录后从项目详情进入空对话列表；
2. 创建默认标题和自定义标题对话；
3. 发送消息并等待同步回复；
4. 加载多页历史消息；
5. AI 默认关闭时保留草稿并显示可理解提示；
6. 模拟慢请求、网络中断、`409` 和超时后的手动恢复；
7. 刷新和直接访问对话 URL；
8. 使用不存在、他人或非法项目/对话 ID；
9. 快速切换两个对话，确认旧响应不覆盖；
10. 在 320px 和桌面宽度完成创建、查看和发送。

手工验收使用隔离测试数据。真实模型冒烟测试不是合并门禁；如所有者主动启用，只记录脱敏结果，
不得保存消息正文、密钥或 provider 原始响应。

## 14. 预计文件影响范围

具体名称可按最新代码调整，但不得机械创建空文件：

```text
devmate-web/src/api/
├── conversations.ts
├── types.ts
└── __tests__/conversations.spec.ts

devmate-web/src/components/conversation/
├── ConversationMessageList.vue（确有复用时）
└── ConversationComposer.vue（确有复用时）

devmate-web/src/views/
├── ConversationListView.vue
├── ConversationChatView.vue
└── __tests__/
    ├── ConversationListView.spec.ts
    └── ConversationChatView.spec.ts

devmate-web/src/router/index.ts
devmate-web/src/router/__tests__/guards.spec.ts
devmate-web/src/views/ProjectDetailView.vue
devmate-web/src/views/__tests__/ProjectDetailView.spec.ts
devmate-web/src/assets/base.css（仅确有通用样式需要时）

README.md（实现完成后按事实最小更新）
devmate-web/README.md（实现完成后按事实最小更新）
docs/README.md（实现完成后按事实最小更新）
```

## 15. 实施顺序

1. 确认 DEV-013 合并，更新并核对最新 `develop`；
2. 创建独立 DEV-014 功能分支；
3. 复核后端 API、错误响应、分页排序和现有前端结构；
4. 定义类型、ID 工具和 conversation API，完成 API 测试；
5. 注册路由并在项目详情增加入口，补充路由回归测试；
6. 实现对话列表、URL 分页与创建流程；
7. 实现项目、对话和最新消息加载；
8. 实现更早消息加载、排序与去重；
9. 实现 Unicode 输入校验、UUID 生命周期和同步发送；
10. 实现 `409`、AI 不可用、超时、网络不确定结果与手动恢复；
11. 补齐陈旧响应、重复提交、不可信文本和窄屏测试；
12. 执行完整前端质量命令、依赖审计和文档检查；
13. 完成隔离环境浏览器验收并保留脱敏截图；
14. 创建范围单一提交和 PR，等待项目所有者审核，不自动合并。

## 16. PR、风险与回滚

建议提交和 PR 标题：

```text
feat(conversation): add frontend project chat
```

PR 必须说明：

- DEV-014 背景、范围和非目标；
- 路由、页面、API 类型和分页策略；
- 发送请求的单请求超时与 clientRequestId 生命周期；
- 同步生成、重复点击、`409` 和网络不确定结果的行为；
- 错误、安全渲染、陈旧响应与会话失效处理；
- 自动化测试数量、实际命令、CI 链接和桌面/窄屏截图；
- 是否执行真实模型冒烟测试；未执行必须明确写明；
- 构建体积告警变化、已知限制、风险和回滚方式；
- 明确说明未修改后端，未实现流式、RAG、工具和代码执行。

主要风险：

- 全局 Axios 10 秒超时若未单独覆盖，会把合法的长模型调用误判为网络失败；
- 网络超时后盲目生成新 UUID 可能造成重复付费调用；
- 消息升序分页若直接加载第一页，会让长对话默认停留在最旧消息；
- 路由切换、慢响应与组件卸载可能造成跨对话内容覆盖；
- 当前错误响应缺少稳定的机器错误标识，不能以英文文案作为业务协议；
- JSON number 无法安全表示全部 BIGINT，继续沿用现有安全整数限制；
- Element Plus 基线已有大 chunk 风险，本任务不顺带重构打包策略。

本任务没有数据库或后端行为变更。严重问题可回滚 DEV-014 前端提交并重新部署上一版静态资源；
后端对话数据不受影响。回滚不得删除对话数据、修改 V5 migration 或关闭后端安全校验。

## 17. 最终交付信息

完成实现时必须报告：

1. 已完成与未完成内容；
2. 修改文件和路由列表；
3. API 封装与类型位置；
4. 对话和消息分页策略；
5. clientRequestId、超时和失败恢复行为；
6. 各错误状态的实际表现；
7. 自动化测试覆盖、数量和实际命令结果；
8. 浏览器验收环境及脱敏截图；
9. 构建体积与依赖审计结果；
10. 分支、commit SHA 和 PR 链接；
11. 风险、限制与需人工确认事项；
12. 下一任务建议，只说明，不实施。

如任何强制检查未运行或失败，不得把 DEV-014 报告为完成。
