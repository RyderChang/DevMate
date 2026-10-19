# DEV-035：工作台视觉优化验收

日期：2026-10-19。任务见[DEV-035](../tasks/DEV-035-workspace-visual-polish.md)。从最新 develop `1a6dab6c67a7a200c08e78e975b4d3be5e0f1e93` 创建 `feature/dev-035-workspace-polish`，开始工作区干净。DEV-034 与 #53 留在原分支，不继承其未合并提交。

## 完成内容

- 全局主题变量统一蓝色主色、深色侧栏、浅色内容、边框、字体层级、表单、按钮、对话框、状态卡及键盘焦点；尊重减少动画的系统偏好，不加载外部字体或新增依赖。
- 工作台提供主导航、当前项目概览/对话入口、当前页面位置、账号与退出，以及可聚焦的“跳到主要内容”。非法项目 ID 不生成项目上下文链接。
- 项目卡片使用一致图标、描述、更新时间和底部操作区；长标题仍按纯文本换行。详情、表单及对话列表复用相同视觉语言。
- 聊天区区分作者、消息、引用和输入区域，保留发送/错误恢复；引用标签保持内容宽度，SHA 在窄屏可换行。320px 输入框跟随消息，避免吸底遮挡历史；桌面保留吸底输入。
- 登录/注册共享 AuthFrame，使用深色品牌区及简洁表单；窄屏收起辅助文案，保留原字段与校验。

API、权限、认证/会话存储、分页、删除确认、clientRequestId、未知结果手动确认、RAG 默认关闭及纯文本输出合同未改。没有真实 Chat/Embedding、付费试请求、模型凭据访问、生产开关或部署。

## 自动化验证

本机 Node.js 24.14.1，使用已有 node_modules，未修改依赖或 lockfile。CI 使用仓库固定的 Node/npm 版本。本机实际命令均在 devmate-web 运行：

```powershell
node node_modules/vue-tsc/bin/vue-tsc.js --build --force
node node_modules/eslint/bin/eslint.js .
node node_modules/prettier/bin/prettier.cjs --check .
node node_modules/vitest/vitest.mjs run
node node_modules/vite/bin/vite.js build
```

已完成的完整 Vitest 运行：**15 个文件、100 项通过**，4.52 秒。原 97 项业务回归保持，新增三项覆盖非法/合法项目上下文链接、路由切换后的选中状态与路径、跳转目标及退出清理/账号回退。首轮新测试误将布局自身和包含布局的 RouterView 双重挂载，出现重复导航；改为只挂载根 RouterView 后完整回归通过，没有降低断言或修改业务逻辑。最终样式修订后，类型、lint、全部前端格式检查再次通过。

最终生产构建成功（5.10 秒），保留 Element Plus 基线的大 chunk 告警（共享 JS 1053.30 kB / gzip 350.34 kB）。本任务不调整分包策略或隐藏告警。不在本机重跑后端，完整后端与 npm audit 由新头 Foundation CI 验证；对应新头结果在 PR 登记，不沿用 develop 或 #53 的旧 CI。

仓库根目录执行 `node scripts/check-docs.mjs --format-changed origin/develop`：82 份 Markdown、380 个相对链接及格式通过（最终文档修订后复核）；`git diff --check` 通过。文档检查首次自动审批超时，按工具返回允许重试一次后原只读命令成功，不把超时记作检查通过。

## 浏览器验收

仅使用回环地址：前端 `127.0.0.1:15179`，合成内存服务 `127.0.0.1:19092`。服务为仓库现有 [RAG 浏览器夹具](../../scripts/rag-browser-fixture.mjs) 的本机忽略副本 `tmp/dev-035/workspace-browser-fixture.mjs`，只增加三条合成项目（含超长中英文标题）以及 empty/error 登录样例；不连接实际后端/模型，不持久化项目或答案。临时服务副本不纳入提交，原夹具不改。重现基础页面可直接运行原夹具；多项目/空错误样例仅对应本机副本与以下截图，不能声称干净克隆包含该扩展服务。

```powershell
node tmp/dev-035/workspace-browser-fixture.mjs 19092
# 在 devmate-web/ 中：
$env:VITE_DEV_PROXY_TARGET = 'http://127.0.0.1:19092'
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15179 --strictPort
```

| 场景                | 实际结果                                                                                                                                           |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1440 × 900 桌面     | 深色侧栏/浅色内容区、三列项目、详情、对话列表和创建弹窗可读；长标题卡片操作区底边均为 652px，排列对齐。项目页 scrollWidth=clientWidth=1440。       |
| 320 × 800 项目/表单 | 长标题、卡片操作和分页可换行；创建页空名称显示校验提示。scrollWidth=clientWidth=305（竖向滚动条占 15px），无横向溢出。                             |
| 320 × 800 聊天/引用 | 消息、历史引用与输入框依次排列，合成发送成功并清空草稿；展开完整 SHA/定位仍 scrollWidth=clientWidth=305。恶意文件名只显示文本，DOM 中 img 数为 0。 |
| 空项目              | 显示“还没有项目”和“创建第一个项目”，不显示虚构统计或样例项目。                                                                                     |
| 列表失败与重试      | 合成 503 显示安全失败提示与重试按钮；重试仍失败时不伪报成功。320px 无横向溢出。                                                                    |
| 登录/注册           | 桌面双栏；320px 保留用户名、密码等原字段与账号切换，注册页 scrollWidth=clientWidth=305。未创建真实账号。                                           |
| 键盘/导航           | “跳到主要内容”激活后 document.activeElement.id 为 main-content；从项目导航进入对话列表和聊天、退出返回登录正常。                                   |

原始 Vite 启动因沙箱阻止 esbuild 子进程而 EPERM，正常提升后只启动回环开发服务；没有改配置绕过网络或权限。浏览器截图仅含合成数据，不代表真实项目/模型质量或所有者真人评审。

截图：[项目工作台桌面](images/dev-035-projects-desktop.png)、[项目列表 320px](images/dev-035-projects-mobile-320.png)、[项目详情](images/dev-035-project-detail-desktop.png)、[对话列表](images/dev-035-conversations-desktop.png)、[聊天桌面](images/dev-035-chat-desktop.png)、[聊天 320px](images/dev-035-chat-mobile-320.png)、[登录桌面](images/dev-035-login-desktop.png)。

## 限制与回滚

浏览器验收针对当前 IAB 桌面与窄屏视口，不代表已穷尽所有浏览器、缩放和辅助技术。前端 API/表单与错误恢复的完整行为主要由 Mock 单元/组件测试覆盖，未借视觉优化连接真实后端或模型。

回滚本任务前端及文档提交即可，无迁移或服务端数据变更。后续可由所有者基于真实使用反馈细调密度；本任务不自动实施新功能、合并或部署。
