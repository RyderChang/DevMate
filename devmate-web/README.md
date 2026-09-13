# DevMate Web

DevMate 的 Web 客户端。当前已具备注册、登录、会话恢复、受保护路由和退出登录；项目管理、AI 对话、知识库、代码审查和测试生成等业务功能仍未实现。

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
- npm 10 或更高版本（唯一支持的包管理器）

使用 nvm 时可执行 `nvm use` 切换到项目版本。

## 本地开发

```bash
npm install
cp .env.example .env.local
npm run dev
```

开发服务器默认由 Vite 提供。环境文件不是启动的必要条件；未配置 API 地址时统一请求实例使用 `/api`。Vite 会将开发请求代理到 `VITE_DEV_PROXY_TARGET` 并移除 `/api` 前缀，使其匹配后端的 `/auth/*` 接口。

## 认证流程

- `/register` 创建账号，成功后返回登录页；
- `/login` 建立登录会话；
- `/` 是受保护首页，刷新时通过 `/auth/me` 恢复当前用户；
- API 请求自动携带 Bearer Token，收到 `401` 后清理会话并返回登录页；
- 页面右上角提供退出登录入口。

访问令牌只保存在当前标签页的 `sessionStorage` 中。关闭标签页或退出登录后需要重新登录；当前不支持刷新令牌或“记住登录”。前端路由守卫仅改善交互，实际认证、权限和项目归属仍由后端校验。

## 质量检查

```bash
npm run type-check
npm run lint
npm run format:check
npm run test
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

当前范围只完成前端认证闭环。项目空间页面、管理员页面、刷新令牌、AI、RAG、GitHub 集成、代码审查和测试生成均由后续独立任务实现。
