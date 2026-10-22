# DEV-035：工作台视觉优化验收

日期：2026-10-19。任务见[DEV-035](../tasks/DEV-035-workspace-visual-polish.md)。从最新 develop `1a6dab6c67a7a200c08e78e975b4d3be5e0f1e93` 创建 `feature/dev-035-workspace-polish`，开始工作区干净。DEV-034 与 #53 留在原分支，不继承其未合并提交。

本文先保留 `4d522ee` 的首轮视觉验收，再记录本日接手后的真实隔离后端联调与 PR #54 收口；两轮使用的服务和证据分别说明。

## 首轮完成内容

- 全局主题变量统一蓝色主色、深色侧栏、浅色内容、边框、字体层级、表单、按钮、对话框、状态卡及键盘焦点；尊重减少动画的系统偏好，不加载外部字体或新增依赖。
- 工作台提供主导航、当前项目概览/对话入口、当前页面位置、账号与退出，以及可聚焦的“跳到主要内容”。非法项目 ID 不生成项目上下文链接。
- 项目卡片使用一致图标、描述、更新时间和底部操作区；长标题仍按纯文本换行。详情、表单及对话列表复用相同视觉语言。
- 聊天区区分作者、消息、引用和输入区域，保留发送/错误恢复；引用标签保持内容宽度，SHA 在窄屏可换行。320px 输入框跟随消息，避免吸底遮挡历史；桌面保留吸底输入。
- 登录/注册共享 AuthFrame，使用深色品牌区及简洁表单；窄屏收起辅助文案，保留原字段与校验。

API、权限、认证/会话存储、分页、删除确认、clientRequestId、未知结果手动确认、RAG 默认关闭及纯文本输出合同未改。没有真实 Chat/Embedding、付费试请求、模型凭据访问、生产开关或部署。

## 首轮自动化验证

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

## 首轮浏览器验收：内存夹具

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

## 首轮限制与回滚

浏览器验收针对当前 IAB 桌面与窄屏视口，不代表已穷尽所有浏览器、缩放和辅助技术。前端 API/表单与错误恢复的完整行为主要由 Mock 单元/组件测试覆盖，未借视觉优化连接真实后端或模型。

回滚本任务前端及文档提交即可，无迁移或服务端数据变更。后续可由所有者基于真实使用反馈细调密度；本任务不自动实施新功能、合并或部署。

## 隔离后端联调与 PR #54 收口

2026-10-19 接手时重新核验：工作区干净，当前分支和远端 PR 头均为 `4d522ee8a676f31d5df9476c8e6bab6d93ecc479`；
[PR #54](https://github.com/RyderChang/DevMate/pull/54) 为 open/draft，目标 `develop`，基线仍为 `1a6dab6`，可合并且无未解决的审查线程。
继续原任务分支，没有引入 DEV-034/#53 的提交。最终头 SHA 与对应 CI 结果登记在 PR，不使用旧头的成功结果替代。

### 环境与可重现输入

先用当前后端源码在本机 JDK 21 执行打包，再启动仓库已有的隔离环境：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = $env:JAVA_HOME + '/bin;' + $env:PATH
./devmate-server/mvnw.cmd -B -ntp -f devmate-server/pom.xml package -DskipTests
python -B scripts/conversation_env.py start --scenario delay --delay-ms 1500
```

打包成功，11.954 秒；本命令明确跳过测试，不能算后端完整测试。没有修改后端源码、配置默认值或 migration。
后端为实际 Spring Boot 应用，MySQL 8.4.6 从临时空库迁移；容器与网络均带 `devmate.acceptance=dev015` 标签，启动前没有运行中的容器。
MySQL 不发布端口，后端仅发布 `127.0.0.1:18085`，AI 只访问专用网络中的 `devmate015-stub:19090`，模型为 `acceptance-model`。
数据库、JWT 和 Stub 认证值由脚本运行时随机生成，没有读取真实模型凭据或输出凭据。

首次普通联调使用 Vite `15180` 直接代理后端。响应丢失验收改用[开发指南](../development/local-development.md)规定的拓扑：

```powershell
# 在 devmate-web/：
$env:VITE_DEV_PROXY_TARGET = 'http://127.0.0.1:18085'
$env:VITE_RAG_ENABLED = 'false'
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15175 --strictPort
# 在仓库根目录：
node scripts/conversation-proxy.mjs drop-send-once 15175 15176
```

浏览器通过 `127.0.0.1:15176` 登录。未知结果恢复完成后，仅停止本任务代理，依次以 `fail-read`、`pass` 模式重启。
最初误将截断代理放在 Vite 上游，未得到可用的未知结果证据；调整到上述浏览器侧拓扑后重新验收，未把该次尝试计为通过。
AI/RAG/知识库默认合同保持，文档问答入口关闭。没有发送新的 RAG 请求、执行 Embedding、启用生产开关、真实付费请求或部署。

账号由浏览器注册，用户名为运行时生成的 `dev035_` 前缀，密码随机生成，仅用于本临时环境。
创建名称 100 个字符的中英文项目，将描述编辑为 1000 个字符；内容包含无空格英文与 `<img src=x onerror=alert(1)>` 纯文本样例。
创建 200 字符对话标题，用 Ctrl+Enter 发送合成消息。另建短名项目检查取消和确认删除，长名项目仅取消删除。

使用下列可提交脚本准备历史数据，将用户名替换为刚创建的合成账号：

```powershell
python -B scripts/seed-workspace-citations.py dev035_<随机后缀>
python -B scripts/seed-conversation-browser.py dev035_<随机后缀>
```

[引用种子脚本](../../scripts/seed-workspace-citations.py)只允许 `dev035_` 合成账号，并检查固定本任务 app/db/stub 容器的归属标签，
在首个活动项目中事务性写入一组成功历史回答、两条引用和一条来源的资格元数据。C1 的元数据匹配当前来源，C2 不匹配；历史 GET 经实际后端核验后分别返回可用与不可用。
**这不是上传、解析、索引或 RAG 生成的验收**：没有真实对象或向量，`STORED/CHUNKED/SUCCEEDED` 仅是临时库的展示夹具，不能把“当前可用”当成对象存储或答案事实的验证。
第二个既有脚本准备 52 条消息和 12 个列表项，不调用 AI。所有夹具最终随任务临时库清理，不用于共享环境。

### 可复现问题与修复

1. 320px 下，聊天标题上方的项目标签包含长连续英文时，文档 `scrollWidth=346`、`clientWidth=305`，出现横向滚动。
   先加入[只读布局回归检查](../../scripts/check-workspace-layout.mjs)，在原页面运行，明确抛出 `Horizontal overflow` 并定位到 `p.eyebrow`。
   为页面标题段落补充 `overflow-wrap: anywhere` 后，同一项目、对话和视口复验为 `305/305`，展开引用后仍通过。
2. 项目详情与列表的删除按钮在确认弹窗出现前已进入 loading，被 Element Plus 禁用；取消后失去键盘焦点。
   先新增两项组件回归，原实现两项均在“确认前按钮应可聚焦”断言失败；随后将实际删除的 loading 与确认期间的操作锁分开。
   确认前不发删除请求、重复激活不创建第二个弹窗；确认后才 loading。修复后的 23 项定向测试通过，浏览器确认 Escape/取消后焦点返回原按钮。

布局检查读取真实 DOM 的视口、文档宽度和溢出节点；需要在加载完成的浏览器页面运行，不能用 jsdom 的零尺寸结果代替。
本轮用浏览器工具读入脚本，将 `export function` 转为局部函数，在只读 IIFE 中调用 `checkWorkspaceLayout()`；
每次检查文档宽度超过客户区 1px 即抛错。脚本加入前后的同一输入均实际执行，不仅依靠截图目测。

### 浏览器验收结果

IAB，桌面 1440 × 900 与窄屏 320 × 800；有竖向滚动条时客户区分别为 1425px、305px，无滚动条或弹窗锁滚动时为 1440px、320px。

| 场景               | 实际结果                                                                                                                                                                                             |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 注册、登录与认证页 | 真实后端注册合成账号成功；错误密码显示失败，正确密码以 Enter 登录。320px 登录/注册没有横向滚动，字段及账号切换可操作。                                                                               |
| 项目增删改查       | 空项目显示真实空状态；空名称被前端拦截。100 字符项目名创建、1000 字符描述保存及刷新恢复成功；短名测试项目确认删除后回到列表且不再显示，取消不删除长名项目。                                          |
| 长文本与纯文本     | 桌面/320px 项目列表、详情、表单、对话与引用布局检查通过。项目描述、消息、引用文件名中的 HTML 字符串按文本展示，`main img=0`。                                                                        |
| 加载与生成中       | 暂停本任务 app 16 秒并在 finally 自动恢复，实际请求等待时桌面与 320px 均见一组 skeleton，宽度检查通过；恢复后加载成功。1.5 秒延迟 Stub 发送期间输入和发送操作锁定，成功后草稿清空。                  |
| 弹窗与键盘         | 320px 的 200 字符创建对话弹窗及长项目名删除确认框可操作；Escape/取消关闭，焦点返回触发按钮；Enter 创建或确认删除正常。跳到主要内容后焦点为 `main-content`，原生引用详情支持 Enter 展开。             |
| 普通聊天与恢复     | 真实后端保存用户消息和本机 Stub 固定回答，刷新后仍有记录。浏览器侧截断一次完成响应后显示未知结果提示，锁定原输入；手动确认恢复结果、清空草稿。                                                       |
| 重放计数           | 手动确认前后 Stub 请求数均为 3，确认后 `active=0/rejected=0`；对话 1 的消息为 6、调用为 3、不同请求 UUID 为 3，没有新增 AI 调用或重复消息。计数含一次作废拓扑尝试产生的合成成功发送。                |
| 失败与重试         | `fail-read` 时刷新显示安全失败与重试入口，并保留原 6 条消息；再次重试仍失败，不伪报成功。恢复 `pass` 后手动重试成功，错误提示消失。320px 布局通过。                                                  |
| 历史与引用         | 52 条合成历史初次显示最后一页 2 条，向前加载后 52 条；引用经实际历史 GET 返回 C1 当前可用/C2 不可用，关闭 RAG 入口仍可读。展开完整 SHA、定位和长文件名后无横向滚动；退出、重新登录及刷新后引用恢复。 |
| 导航与退出         | 项目概览/对话路径与 `aria-current` 随路由变化；跳转正文正常。退出后访问原受保护聊天 URL 返回带 redirect 的登录页，重新登录仍可读取服务端历史。                                                       |

截图均只含合成资料，不含密码、令牌或请求头：

- 项目：[桌面列表](images/dev-035-integration-list-desktop.jpg)、[320px 列表](images/dev-035-integration-list-mobile-320.jpg)、[空项目](images/dev-035-integration-empty-desktop.jpg)、[桌面详情](images/dev-035-integration-project-desktop.jpg)、[320px 详情](images/dev-035-integration-project-mobile-320.jpg)、[320px 编辑](images/dev-035-integration-form-mobile-320.jpg)。
- 状态与键盘：[加载](images/dev-035-integration-loading-mobile-320.jpg)、[创建对话弹窗](images/dev-035-integration-dialog-mobile-320.jpg)、[长名删除确认](images/dev-035-integration-delete-dialog-mobile-320.jpg)、[退出后的登录页](images/dev-035-integration-logout-desktop.jpg)。
- 对话与引用：[修复前横向溢出](images/dev-035-integration-chat-overflow-before.jpg)、[未知结果](images/dev-035-integration-unknown-desktop.jpg)、[320px 读取失败](images/dev-035-integration-read-failure-mobile-320.jpg)、[桌面引用](images/dev-035-integration-citations-desktop.jpg)、[320px 完整定位](images/dev-035-integration-citations-mobile-320.jpg)。

### 本轮检查与边界

本机仍使用 Node.js 24.14.1 和已有依赖，无依赖或 lockfile 改动。最终在 `devmate-web/` 执行：

```powershell
node node_modules/vue-tsc/bin/vue-tsc.js --build --force
node node_modules/eslint/bin/eslint.js .
node node_modules/prettier/bin/prettier.cjs --check .
node node_modules/vitest/vitest.mjs run
node node_modules/vite/bin/vite.js build
```

最终类型、lint、全部前端格式检查均通过；Vitest **15 个文件、102 项通过**（10.13 秒），包含新增两项删除确认行为测试。
最终生产构建成功（8.12 秒），共享 JS 为 1053.30 kB / gzip 350.35 kB，既有大 chunk 告警保留。
仓库根目录执行 `node scripts/check-docs.mjs --format-changed origin/develop`：82 份 Markdown、399 个相对链接与格式通过；
`node --check scripts/check-workspace-layout.mjs`、该脚本 Prettier、引用种子 Python AST 语法检查和 `git diff --check` 通过。
初次沙箱 Vitest worker 因映射临时路径 `ENOENT` 无法读取模块，正常进程权限下复跑成功，不通过删测试或改断言规避。
新增测试的 resolver 类型在类型检查中发现不匹配，按已有 Element Plus 返回类型修正，行为断言保持。
后端本机仅打包及运行实际服务，完整后端与标准 Node/npm、npm audit 由最终新头 Foundation CI 验证。

引用样例不代表真实 RAG 答案质量；未覆盖全部浏览器、缩放或辅助技术。既有大 chunk 告警保留。
回滚本轮前端修复和验收提交即可；无服务端或迁移变更。合并由项目所有者确认，不自动合并或部署。
DEV-034 继续等待适用于 1017 的官方证据；本任务不修订或冻结其计划，¥85.00 预算不构成真实发送授权。
