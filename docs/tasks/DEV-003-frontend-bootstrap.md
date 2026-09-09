# DEV-003：前端工程初始化

## 任务元信息

| 项目 | 内容 |
| --- | --- |
| 任务编号 | DEV-003 |
| 任务类型 | 工程初始化 |
| 目标模块 | `devmate-web/` |
| 建议分支 | `chore/003-frontend-bootstrap` |
| 基准分支 | `develop` |
| PR 目标分支 | `develop` |
| 提交信息 | `feat(web): bootstrap frontend project` |

> 本文件是供 Codex Cloud 执行的任务说明，不代表 DEV-003 已经实施。执行前必须阅读仓库根目录的 `AGENTS.md`、`CONTRIBUTING.md` 及相关设计文档；如有冲突，以作用域内最新的 `AGENTS.md` 和已确认的项目文档为准。

## 一、任务背景

DevMate 采用前后端分离架构。后端基础工程已由 DEV-002 建立，本任务负责初始化独立的前端工程 `devmate-web/`。

前端技术基线为 Vue 3 + Vite + TypeScript。本阶段只建立可启动、可构建、可持续扩展的前端工程基座，为后续页面与业务模块开发提供统一入口、目录、依赖和开发规范。

本任务不实现登录、AI 对话、项目管理、代码审查、测试生成、知识库等业务功能，也不对接真实后端接口。

## 二、任务目标

完成 DevMate 前端基础工程初始化，使其具备：

- 标准的 Vue 3 + Vite + TypeScript 工程结构；
- Element Plus、Axios、Vue Router、Pinia 的基础集成；
- 最小可运行的根路由、占位页面和布局；
- 可复用的 Axios 请求实例及环境变量配置边界；
- ESLint、Prettier、TypeScript 的基础质量检查；
- 清晰的安装、开发、检查和构建文档；
- 可由后续任务直接扩展的目录约定。

## 三、范围与技术要求

### 3.1 技术栈

必须使用：

- Vue 3，采用 Composition API；
- Vite；
- TypeScript，开启适合 Vue 3 工程的严格类型检查；
- Element Plus；
- Axios；
- Vue Router；
- Pinia；
- npm 作为唯一包管理器；
- ESLint 与 Prettier 作为基础代码规范工具。

依赖选择要求：

- 使用执行时相互兼容的稳定版本，不使用 alpha、beta 或 RC 版本；
- 不为追求“最新”而升级任务范围外的依赖；
- 在 `package.json` 中声明 Node.js 运行时要求，并通过 `.nvmrc` 或 `.node-version` 固化所选版本；
- 提交 `package-lock.json`，不得同时引入 pnpm、Yarn 或其他锁文件；
- 版本选择和必要理由应记录在 PR 描述中。

### 3.2 工程原则

- 使用现代 Vue 3 单文件组件和 `<script setup lang="ts">`；
- 保持初始化内容最小、清晰、可维护；
- 环境相关配置通过 Vite 环境变量管理；
- 不在源码中写入密钥、令牌或个人环境配置；
- 不通过占位业务逻辑模拟“已完成功能”。

## 四、目录与文件要求

目标逻辑结构如下：

```text
devmate-web/
├── src/
│   ├── api/
│   ├── assets/
│   ├── components/
│   ├── layouts/
│   ├── router/
│   ├── stores/
│   ├── utils/
│   ├── views/
│   ├── App.vue
│   ├── env.d.ts
│   └── main.ts
├── .env.example
├── .nvmrc 或 .node-version
├── .prettierignore
├── .prettierrc.json
├── eslint.config.js（或等价的现代 ESLint 配置）
├── index.html
├── package.json
├── package-lock.json
├── README.md
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
└── vite.config.ts
```

说明：Git 不跟踪空目录。`components/`、`utils/` 等当前没有真实基础文件的逻辑目录，可先在 `README.md` 中声明并在后续首次使用时创建；不得仅为展示目录而添加无意义源码或 `.gitkeep`。Vite/TypeScript 版本生成的等价配置文件可以保留，但必须满足本任务的类型检查与构建要求。

### 4.1 需要修改的现有文件

- `devmate-web/README.md`
  - 将现有占位内容更新为本任务要求的前端工程说明。

如根目录 `.gitignore` 尚未覆盖前端构建产物，可在不破坏现有规则的前提下最小修改：

- `.gitignore`
  - 确保忽略 `node_modules/`、`dist/`、本地环境变量文件及常见 Vite 缓存；
  - `.env.example` 必须允许提交；
  - 不得删除或覆盖后端及仓库已有忽略规则。

### 4.2 需要创建的核心文件

至少创建：

- `devmate-web/package.json`
- `devmate-web/package-lock.json`
- `devmate-web/index.html`
- `devmate-web/vite.config.ts`
- `devmate-web/tsconfig.json`
- 必要的 TypeScript 子配置文件
- ESLint 配置文件
- Prettier 配置及忽略文件
- Node.js 版本声明文件（`.nvmrc` 或 `.node-version`，二选一）
- `devmate-web/.env.example`
- `devmate-web/src/env.d.ts`
- `devmate-web/src/main.ts`
- `devmate-web/src/App.vue`
- `devmate-web/src/api/http.ts`
- `devmate-web/src/assets/base.css`
- `devmate-web/src/layouts/DefaultLayout.vue`
- `devmate-web/src/router/index.ts`
- `devmate-web/src/stores/index.ts`
- `devmate-web/src/views/HomeView.vue`

具体文件名可根据稳定版官方脚手架做小幅调整，但不得改变目录职责或扩大任务范围。

## 五、实现内容

### 5.1 Vue 3 + Vite 项目初始化

- 在现有 `devmate-web/` 目录内初始化，不得创建重复的嵌套项目目录；
- 清理脚手架自带的演示计数器、示例组件、示例图片和无关文案；
- 应用启动后仅展示 DevMate 前端基座的简洁占位内容；
- 不进行业务 UI 设计。

### 5.2 TypeScript 配置

- 配置 Vue 单文件组件类型支持；
- 设置 `@` 指向 `src/` 的路径别名，并保证 Vite 与 TypeScript 配置一致；
- 提供独立的类型检查命令；
- 构建命令必须在打包前执行类型检查，避免仅由 Vite 转译而跳过类型错误。

### 5.3 Element Plus 集成

- 在应用入口完成基础集成并引入必要样式；
- 保持配置简单、明确；
- 不引入业务主题、复杂国际化、自动导入插件或额外 UI 插件，除非它们是构建所必需且在 PR 中说明理由。

### 5.4 Axios 基础封装

在 `src/api/http.ts` 中创建统一 Axios 实例：

- `baseURL` 从 `VITE_API_BASE_URL` 读取，并提供适合本地开发的安全默认值 `/api`；
- 设置合理的请求超时时间；
- 预留请求拦截器和响应拦截器；
- 当前拦截器只执行与基础封装有关的透传和错误拒绝，不吞掉异常；
- 不添加 Token、刷新 Token、登录跳转、业务状态码判断、全局消息提示或重试逻辑；
- 页面中不得发起真实 API 调用。

`.env.example` 只能包含可公开的示例配置，不得包含任何真实凭据。可在 Vite 开发配置中为 `/api` 提供最小代理能力，但代理目标必须可由环境变量覆盖，并不得由占位页面主动调用。

### 5.5 Vue Router 基础配置

- 创建 Router 实例并在应用入口注册；
- 使用 HTML5 History 模式；
- 至少配置根路由 `/`；
- 根路由渲染基础布局和占位首页；
- 不实现路由守卫、动态路由、权限元数据或登录重定向。

### 5.6 Pinia 基础配置

- 创建并在应用入口注册 Pinia；
- 可提供统一导出入口；
- 不创建用户、权限、会话、AI 或其他业务 Store；
- 不引入持久化插件。

### 5.7 基础布局与占位页面

- 提供 `DefaultLayout.vue` 作为后续页面的基础布局占位；
- 提供 `HomeView.vue` 作为 `/` 路由页面；
- 页面只需说明 DevMate 前端工程已正常运行、业务功能将在后续任务实现；
- 不得将占位内容描述为已完成的业务功能。

### 5.8 基础开发规范

`package.json` 至少提供以下脚本或语义等价脚本：

- `dev`：启动 Vite 开发服务器；
- `build`：先进行 TypeScript 类型检查，再执行生产构建；
- `preview`：预览生产构建；
- `type-check`：执行 Vue/TypeScript 类型检查；
- `lint`：执行 ESLint；
- `format`：执行 Prettier 格式化；
- `format:check`：检查格式但不修改文件。

ESLint 和 Prettier 配置必须覆盖 Vue、TypeScript 及常用前端源码文件，并避免互相冲突。

### 5.9 README 文档

更新 `devmate-web/README.md`，至少说明：

- 模块用途与当前阶段；
- 技术栈；
- Node.js/npm 前置要求；
- 安装依赖命令；
- 本地启动命令；
- 类型检查、Lint、格式检查命令；
- 生产构建和预览命令；
- 环境变量配置方式；
- 主要目录职责；
- 当前未实现的业务功能。

## 六、明确禁止

本任务禁止：

- 实现登录、注册、用户中心或其他用户系统；
- 实现 AI 聊天页面或任何 AI 业务；
- 实现 Dashboard 业务页面；
- 实现商品页面、项目页面或其他业务页面；
- 实现代码审查、测试生成、知识库、GitHub 集成等功能；
- 引入 WebSocket；
- 实现权限控制、路由鉴权或 Token 处理；
- 调用真实后端 API，包括为了演示而调用健康检查接口；
- 进行完整 UI 视觉设计或建立复杂主题系统；
- 增加模拟业务数据、Mock 服务或业务状态管理；
- 引入 Vitest、Cypress、Playwright 等测试框架；测试框架应在出现可测试行为时由后续任务明确引入；
- 修改 `devmate-server/` 下的后端代码或配置；
- 引入 Docker、CI/CD、部署配置或任务范围外的工具；
- 提交 `node_modules/`、`dist/`、本地 `.env`、IDE 配置或其他生成产物；
- 顺手重构、格式化或改写与本任务无关的文件。

## 七、执行步骤

1. 阅读并遵守根目录 `AGENTS.md`、`CONTRIBUTING.md` 和相关设计文档。
2. 检查当前分支及工作区，确认 DEV-002 已合并且没有覆盖他人改动的风险。
3. 获取最新 `develop`，从其创建 `chore/003-frontend-bootstrap`。
4. 检查现有 `devmate-web/` 内容，在该目录内完成初始化，保留并更新已有 README。
5. 配置 Vue 3、Vite、TypeScript 及 Node.js 版本边界。
6. 集成 Element Plus、Vue Router 和 Pinia。
7. 完成 Axios 基础实例与环境变量示例，不发起真实请求。
8. 创建最小布局、根路由和占位首页。
9. 配置 ESLint、Prettier 和 npm 脚本。
10. 更新 `devmate-web/README.md`。
11. 执行全部验证命令，检查 Git 变更范围和生成产物。
12. 使用规定提交信息提交代码，并创建目标为 `develop` 的 Pull Request。

若脚手架默认行为与本任务冲突，应删除无关演示内容并以本任务范围为准。遇到会影响架构、包管理器、运行时版本或任务边界的歧义时，停止实施并在 PR 前请求项目负责人确认。

## 八、验证要求

在 `devmate-web/` 目录执行并记录结果：

```bash
node --version
npm --version
npm install
npm run type-check
npm run lint
npm run format:check
npm run build
```

生成并提交 `package-lock.json` 后，还应使用干净依赖安装进行复核：

```bash
npm ci
npm run build
```

必要时可短暂运行 `npm run dev`，确认开发服务器能够启动且 `/` 可访问；验证后必须正常终止进程，不得将运行产物提交到仓库。

同时确认：

- TypeScript 检查无错误；
- ESLint 与 Prettier 检查通过；
- Vite 生产构建成功；
- 根路由能够渲染基础布局和占位页；
- `node_modules/`、`dist/` 和本地环境文件未被 Git 跟踪；
- `git diff --check` 通过；
- 变更仅限本任务允许的前端基座文件及必要的根 `.gitignore` 调整；
- 未实现或调用任何业务功能。

若因环境或权限原因无法执行某项验证，必须在 PR 描述中明确写出未执行项、原因和潜在风险，不得将其表述为已通过。

## 九、验收标准

- [ ] `devmate-web/` 已形成 Vue 3 + Vite + TypeScript 工程基座。
- [ ] 使用 npm，提交 `package-lock.json`，且未出现其他包管理器锁文件。
- [ ] Node.js 版本要求已声明并在 README 中说明。
- [ ] Element Plus 已完成基础集成，应用可正常渲染。
- [ ] Axios 统一实例已创建，`baseURL` 可由环境变量配置。
- [ ] 请求与响应拦截器已预留，当前不包含认证或业务处理。
- [ ] Vue Router 已注册，`/` 路由可访问。
- [ ] Pinia 已注册，但没有业务 Store。
- [ ] 基础布局与占位首页存在，未包含业务 UI。
- [ ] TypeScript、ESLint、Prettier 配置有效。
- [ ] `npm install` 与 `npm ci` 可正常安装依赖。
- [ ] `npm run type-check`、`npm run lint`、`npm run format:check` 均通过。
- [ ] `npm run build` 成功。
- [ ] README 覆盖安装、启动、检查、构建、环境变量、目录职责和未实现功能。
- [ ] 未修改后端工程，未实现禁止范围内的功能。
- [ ] 未提交依赖目录、构建产物、密钥或本地环境文件。
- [ ] 分支、提交和 PR 流程符合仓库规范。

## 十、Git 与 Pull Request 要求

### 10.1 分支

必须从最新 `develop` 创建：

```text
chore/003-frontend-bootstrap
```

不得直接修改或提交到 `main`、`develop`。如该分支名已存在且不是本任务的可继续分支，不得强制覆盖，应停止并说明情况。

### 10.2 提交

完成实现与验证后使用：

```text
feat(web): bootstrap frontend project
```

提交前再次确认 `git status`、变更范围和敏感信息。不得使用 `--no-verify` 绕过检查。

### 10.3 Pull Request

- 源分支：`chore/003-frontend-bootstrap`
- 目标分支：`develop`
- 建议标题：`feat(web): bootstrap frontend project`
- 不得创建指向 `main` 的 PR；
- 不得自行合并 PR，等待项目负责人审核。

PR 描述至少包含：

- 任务目标与变更摘要；
- 关键依赖及 Node.js 版本选择；
- 实际新增/修改文件；
- 验证命令与真实结果；
- 明确说明未实现任何业务功能；
- 已知限制、未执行检查及后续建议（如有）。

## 十一、Codex Cloud 完成后的输出要求

执行结束后向项目负责人提供：

1. 实际变更摘要；
2. 新增和修改文件清单；
3. 依赖与运行时版本说明；
4. 验证命令及逐项结果；
5. 未执行项、风险或偏差；
6. Commit SHA；
7. Pull Request 链接。

在项目负责人确认并合并前，不得继续下一项开发任务。
