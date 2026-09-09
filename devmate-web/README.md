# DevMate Web

DevMate 的 Web 客户端工程基座。当前阶段仅提供可运行的 Vue 应用、根路由、基础布局以及后续开发所需的公共集成；登录、项目管理、AI 对话、知识库、代码审查和测试生成等业务功能均未实现。

## 技术栈

- Vue 3（Composition API 与 TypeScript）
- Vite
- Element Plus
- Vue Router
- Pinia
- Axios
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

开发服务器默认由 Vite 提供。环境文件不是启动的必要条件；未配置 API 地址时统一请求实例使用 `/api`，当前页面不会发起任何后端请求。

## 质量检查

```bash
npm run type-check
npm run lint
npm run format:check
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
- `src/stores/`：Pinia 创建与后续领域 Store；
- `src/views/`：路由页面；
- `src/components/`：后续跨页面组件（首次需要时创建）；
- `src/utils/`：后续通用工具（首次需要时创建）。

## 当前范围

本工程仅验证前端基础设施。当前没有业务页面、认证与权限、路由守卫、业务 Store、真实 API 调用、Mock 服务或测试框架；这些能力必须由后续独立任务实现。
