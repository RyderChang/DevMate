# DEV-024 前端依赖审计验收记录

## 基线与修复

从 `develop` 的 `f3d6e5b` 创建独立任务分支。原锁文件在 npm 官方公告端点报告 **10 项漏洞（8 high、2 moderate）**。直接依赖升级到 Vue `3.5.43` 与 `eslint-plugin-vue` `10.11.1`；锁文件将 `brace-expansion` 和 `source-map-js` 更新到有修复的版本。`@vue/eslint-config-typescript` 的 `fast-glob → micromatch → braces` 是 `braces` 的唯一入口；[该公告](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)当时没有修复版本，因此移除共享配置并显式使用 `typescript-eslint` 和 `vue-eslint-parser`。保留 Vue、TypeScript 推荐规则及 Vue `<script lang="ts">` 限制。

版本选择依据：[Vue 服务端渲染公告](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6)、[postcss-selector-parser 公告](https://github.com/advisories/GHSA-rj75-hqrm-r3gf)、[source-map-js 公告](https://github.com/advisories/GHSA-68fv-2mgg-jv7q)和[brace-expansion 公告](https://github.com/advisories/GHSA-q2hr-2g5m-vwhr)。Vue SSR 不在当前 Vite 客户端运行路径中，但依赖仍进入锁文件和审计门禁。

## 实际验证

以下命令在 `devmate-web/` 执行，npm CLI 使用忽略目录 `../tmp/dev-024/npm10/` 中的仓库指定版本 10.9.3；官方 registry 只用于审计和本任务的干净安装。

| 命令或检查                                                                                                              | 结果                                                                   |
| ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `node ../tmp/dev-024/npm10/node_modules/npm/bin/npm-cli.js ci --no-fund --registry=https://registry.npmjs.org`          | 标准安装（未跳过脚本）：342 个包安装，审计 343 个包，0 vulnerabilities |
| `node ../tmp/dev-024/npm10/node_modules/npm/bin/npm-cli.js audit --registry=https://registry.npmjs.org`                 | 0 vulnerabilities                                                      |
| `./node_modules/.bin/vue-tsc --build --force`、`./node_modules/.bin/eslint .`、`./node_modules/.bin/prettier --check .` | 均通过                                                                 |
| `./node_modules/.bin/vitest run`                                                                                        | 14 个测试文件、89 项测试全部通过                                       |
| `./node_modules/.bin/vite build`                                                                                        | 成功；仍有约 1,048 kB 共享 JS chunk 体积告警                           |
| `eslint --print-config` 比较 `src/App.vue` 与 `src/main.ts`                                                             | 两个样本的有效规则变化均为 0；Vue 解析器版本与原配置同为 10.4.1        |
| `node scripts/check-docs.mjs --format-changed f3d6e5b`、`git diff --check`                                              | 69 个 Markdown、270 条相对链接及格式检查通过；diff 检查通过            |

本机未使用仓库指定的 Node 22.19.0，而是已安装的 Node 24.14.1；远端 CI 按 `.nvmrc` 和 `packageManager` 复核。Vite/Vitest 在受限沙箱中对子进程 `realpath` 返回 `EPERM`，在获准的普通本机进程中运行通过。

## 风险与后续

依赖变动可能影响真实浏览器渲染；本任务没有桌面/移动端手工浏览器截图，已有前端回归与构建通过。没有修改 CI 审计门禁。DEV-023 的 #40 基于旧 `develop` 锁文件，在本 PR 合并后仍须更新基线并重跑 CI；两项 PR 都由项目所有者安排合并。回滚可恢复旧锁文件与 ESLint 配置，但会重现安全门禁失败。
