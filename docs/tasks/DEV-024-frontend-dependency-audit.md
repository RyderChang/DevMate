# DEV-024 前端依赖安全审计修复

## 背景与目标

DEV-023 的前端 Foundation CI 在 `npm audit` 发现锁定依赖 10 项漏洞（8 high、2 moderate），使安全门禁失败。本任务从 `develop` 独立处理依赖和 ESLint 配置，恢复零漏洞审计，同时维持原有检查规则及前端行为。

## 范围

- 使用仓库指定的 npm 10.9.3 更新 `devmate-web` 的直接依赖和锁文件。
- 升级存在可用修复版本的 Vue、ESLint Vue 插件及受影响的传递依赖。
- `braces` 公告没有修复版本；移除唯一引入它的 `@vue/eslint-config-typescript`，用现有 TypeScript ESLint 推荐配置和 Vue 解析器组成等效检查配置。
- 保留 `npm audit` CI 门禁，不修改业务代码、后端、部署或其他任务的 PR。

## 验收标准

1. 从最终锁文件执行 `npm ci`，再执行官方 registry 的 `npm audit`，报告 0 vulnerabilities。
2. TypeScript 和 Vue 文件的有效 ESLint 规则与原配置一致，`lint`、`type-check`、`format:check`、前端回归测试与生产构建通过。
3. 文档检查和 `git diff --check` 通过；PR 说明风险、回滚及与 DEV-023 的合并顺序。

## 状态

实现及本地验证见[验收记录](../testing/frontend-dependency-audit-acceptance.md)。远端 CI 与项目所有者合并决定以对应 PR 状态为准。
