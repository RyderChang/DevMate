# DevMate Web

DevMate 的 Web 客户端。当前已具备注册、登录、会话恢复、受保护路由、退出登录、项目空间，以及项目内同步 AI 对话流程。流式对话、知识库、GitHub 集成、代码审查和测试生成等功能仍未实现。

## 技术栈

- Vue 3（Composition API 与 TypeScript）
- Vite
- Element Plus
- Vue Router
- Pinia
- Axios
- Vitest、Vue Test Utils 与 jsdom
- ESLint 与 Prettier

## 前置要求

- Node.js 22.19.0（项目通过 `.nvmrc` 固定版本）
- npm 10.9.3（`packageManager` 固定版本，唯一支持的包管理器）

使用 nvm 时可执行 `nvm use` 切换到项目版本。

## 本地开发

```bash
npm ci
cp .env.example .env.local
npm run dev
```

开发服务器默认由 Vite 提供。环境文件不是启动的必要条件；未配置 API 地址时统一请求实例使用 `/api`。Vite 会将开发请求代理到 `VITE_DEV_PROXY_TARGET` 并移除 `/api` 前缀，使其匹配后端的 `/auth/*` 与 `/projects*` 接口。

## 认证流程

- `/register` 创建账号，成功后返回登录页；
- `/login` 建立登录会话；
- `/` 是受保护入口并重定向到 `/projects`，刷新时通过 `/auth/me` 恢复当前用户；
- API 请求自动携带 Bearer Token，收到 `401` 后清理会话并返回登录页；
- 页面右上角提供退出登录入口。

访问令牌只保存在当前标签页的 `sessionStorage` 中。关闭标签页或退出登录后需要重新登录；当前不支持刷新令牌或“记住登录”。前端路由守卫仅改善交互，实际认证、权限和项目归属仍由后端校验。

## 项目空间

- `/projects`：分页列表，页码与页大小同步到 URL；
- `/projects/new`：创建项目，成功后进入详情；
- `/projects/:projectId`：项目详情及删除入口；
- `/projects/:projectId/edit`：读取服务端最新数据后编辑。

项目请求复用统一 Axios 实例和 `401` 会话失效处理。不存在、已删除或属于其他用户的项目统一显示为“项目不存在或无权访问”；认证和项目归属的权威判定始终由后端执行。项目数据不持久化到浏览器存储。

## 项目对话

- `/projects/:projectId/conversations`：分页查看和创建当前项目的对话；
- `/projects/:projectId/conversations/:conversationId`：加载最新一页消息、向前加载历史并同步发送消息；
- 消息只按纯文本渲染，输入按 Unicode code point 校验，消息、草稿和请求 UUID 不写入浏览器存储；
- 网络结果不确定或服务端返回生成冲突时，由用户使用同一 UUID 手动确认，不自动重试；
- 同步发送单独使用 130 秒请求超时，其余请求继续使用统一客户端的 10 秒超时。

AI Gateway 默认关闭。未启用时，页面会保留草稿并显示安全提示；前端不会在浏览器中读取或保存模型密钥。

## 质量检查

统一启动步骤见[本地开发指南](../docs/development/local-development.md)，
完整检查与限制见[第一阶段验收记录](../docs/testing/foundation-acceptance.md)和
[第二阶段对话验收记录](../docs/testing/ai-conversation-acceptance.md)。后者包含本地 Stub、响应截断代理和 320px 浏览器验证。

```bash
npm run type-check
npm run lint
npm run format:check
npm run test
npm run build
npm audit
```

需要统一格式时运行 `npm run format`。

## 构建与预览

```bash
npm run build
npm run preview
```

`build` 会先进行 Vue/TypeScript 类型检查，再生成生产构建。

## 环境变量

复制 `.env.example` 为不会提交的 `.env.local`，按需设置：

- `VITE_API_BASE_URL`：Axios 实例的 API 基础路径，默认 `/api`；
- `VITE_DEV_PROXY_TARGET`：可选的 Vite 开发代理目标，仅由开发服务器读取。

所有暴露给浏览器的 `VITE_` 变量均视为公开信息，不得在其中存放密码、Token 或密钥。

## 目录职责

- `src/api/`：统一 HTTP 客户端及后续领域 API；
- `src/assets/`：全局样式和静态资源；
- `src/layouts/`：页面布局；
- `src/router/`：路由配置；
- `src/stores/`：Pinia 实例与认证 Store；
- `src/views/`：路由页面；
- `src/components/`：后续跨页面组件（首次需要时创建）；
- `src/utils/`：会话存储等通用工具。

## 当前范围

当前范围完成前端认证、项目空间和项目内同步 AI 对话主流程。项目成员、管理员页面、刷新令牌、文件上传、流式输出、RAG、GitHub 集成、代码审查和测试生成均由后续独立任务实现。
