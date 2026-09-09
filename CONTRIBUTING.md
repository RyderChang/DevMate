# 贡献指南

## 分支职责

- `main`：可发布版本，只接受来自 `develop` 的发布 PR；
- `develop`：阶段集成分支，接收日常任务 PR；
- `feature/<issue>-<short-name>`：新功能；
- `fix/<issue>-<short-name>`：缺陷修复；
- `chore/<issue>-<short-name>`：工程或仓库维护；
- `docs/<issue>-<short-name>`：纯文档变更。

不得直接向 `main` 或 `develop` 提交，也不得强制推送共享分支。

## 提交规范

使用 Conventional Commits：

```text
<type>(<scope>): <imperative summary>
```

例如：`feat(auth): add JWT refresh flow`、`test(project): cover ownership checks`、`docs(architecture): record vector store decision`。

## 单任务开发流程

1. 阅读任务、README、相关文档及适用的 `AGENTS.md`；
2. 从最新 `develop` 创建符合命名规范的任务分支；
3. 检查并保护工作区中已有修改；
4. 只完成当前任务，必要时同步文档和测试；
5. 执行与改动匹配的最小充分验证；
6. 创建范围单一的提交，并向 `develop` 发起 PR；
7. 等待项目所有者审核和合并。

## Pull Request 要求

PR 必须说明背景与目标、主要改动、实际验证命令与结果、风险与回滚方式、未完成事项，以及适用时的截图。一个 PR 只处理一个任务；最终合并由项目所有者确认，禁止自动合并。

## 自检清单

- [ ] 改动与任务范围一致，没有顺手重构无关内容；
- [ ] 文档准确区分当前状态与规划状态，链接有效；
- [ ] 代码遵循模块边界、DTO/VO、校验、异常和日志规范；
- [ ] 新增或修改的行为具有匹配测试，或明确说明未验证的原因和风险；
- [ ] 未提交生成产物、调试文件或本地个性化配置；
- [ ] 未提交密码、Token、API Key、真实连接串或私有代码数据；
- [ ] PR 目标分支为 `develop`。
