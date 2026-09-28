# 本地开发与验收

DEV-016 的 SDK、MinIO 镜像及隔离运行组合见[实施前核验记录](storage-preflight.md)；
其核验工具独立运行。文档上传默认关闭；启用方式与完整验证见下方知识文档章节。

适用于认证、项目空间、项目内同步对话和原文件接入；常规开发不启动真实 AI、Redis、Qdrant 或对象存储。先阅读
[后端说明](../../devmate-server/README.md)、[前端说明](../../devmate-web/README.md)和
[验收记录](../testing/foundation-acceptance.md)。

## 运行环境

- JDK 21；设置 `JAVA_HOME` 后检查 `java -version`，不要使用系统里其他主版本。
- Node 使用 `devmate-web/.nvmrc` 的 22.19.0，npm 使用 `package.json` 的 10.9.3。
- Docker 引擎可用，`docker info` 成功；Windows Docker Desktop 使用 Linux containers。
- Maven 使用仓库 Wrapper（3.9.10）；Python 3 用于生成不含日志的测试摘要。

在干净 checkout 的任务分支执行，不修改依赖版本或 lockfile。POSIX 可用 `nvm use`
选择 Node；Windows 使用已有版本管理器或官方便携版。`npm --version` 应为 10.9.3，
必要时在选定的 Node 安装中执行 `npm install --global npm@10.9.3`。
PowerShell 下可使用 `npm.cmd` 避免执行策略拦截。

AI Gateway 默认关闭，因此本地构建和自动测试不需要 `OPENAI_API_KEY`，也不会访问公共模型服务。
如项目所有者选择单独执行真实模型冒烟测试，需在当前 shell 注入 `AI_ENABLED=true`、
`OPENAI_API_KEY` 和 `OPENAI_MODEL`；不得把值写入仓库、日志或命令记录。真实模型冒烟不是合并门禁。

## 先运行自动化检查

从仓库根目录执行：

```bash
docker info
./devmate-server/mvnw -B -f devmate-server/pom.xml clean verify
python3 scripts/summarize-tests.py
cd devmate-web
npm ci
npm run type-check
npm run lint
npm run format:check
npm run test
npm run build
npm audit
cd ..
node scripts/check-docs.mjs --format-changed origin/develop
node devmate-web/node_modules/prettier/bin/prettier.cjs --check .github/workflows/foundation.yml scripts/check-docs.mjs
git diff --check
```

Windows 将 `./devmate-server/mvnw` 替换为 `./devmate-server/mvnw.cmd`，
`python3` 替换为实际 Python 3 命令（通常是 `python`）。
Testcontainers 自动创建和回收隔离 MySQL 8.4.6，执行空库迁移；无需先创建下面的手工验收容器。
Docker 失败、依赖下载失败或漏洞审计失败都不是测试通过，不能跳过集成测试。
文档检查会扫描全部已跟踪及未忽略的新 Markdown 文件，并对相对 `origin/develop`
新增或修改的 Markdown 执行格式检查；执行前先确认该远端引用是任务分支的实际基线。

## 启动隔离的手工验收环境

以下示例占用本机 `13306`（MySQL）、`18080`（后端）和 `15173`（前端）。
先检查这些端口及容器名 `devmate-foundation-local` 未被占用；若已有同名容器，先确认归属，
不要删除或重用不明数据。数据库不挂载宿主机目录，只使用临时容器数据。

### PowerShell：数据库与后端

在仓库根目录的终端 A 执行。凭据运行时随机生成，只存于本终端环境中，不输出、不写入文件：

```powershell
$env:MYSQL_ROOT_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:MYSQL_PASSWORD = [guid]::NewGuid().ToString('N')
$env:MYSQL_DATABASE = 'devmate'
$env:MYSQL_USER = 'devmate'
docker run -d --name devmate-foundation-local -p 127.0.0.1:13306:3306 -e MYSQL_ROOT_PASSWORD -e MYSQL_PASSWORD -e MYSQL_DATABASE -e MYSQL_USER mysql:8.4.6 --default-time-zone=+00:00
docker logs --tail 10 devmate-foundation-local
```

等待日志显示数据库已可接受连接，再在同一终端继续。不要把数据库日志上传到 PR。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:DB_URL = 'jdbc:mysql://localhost:13306/devmate'
$env:DB_USERNAME = 'devmate'
$env:DB_PASSWORD = $env:MYSQL_PASSWORD
$env:JWT_SECRET = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SERVER_PORT = '18080'
./devmate-server/mvnw.cmd -f devmate-server/pom.xml spring-boot:run
```

`JAVA_HOME` 应替换为本机实际 JDK 21 路径。数据库访问始终使用应用账户，不使用 root。

### POSIX shell：数据库与后端

终端 A 从仓库根目录执行，使用 Python 3 生成临时凭据：

```bash
export MYSQL_ROOT_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export MYSQL_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export MYSQL_DATABASE=devmate MYSQL_USER=devmate
docker run -d --name devmate-foundation-local -p 127.0.0.1:13306:3306 -e MYSQL_ROOT_PASSWORD -e MYSQL_PASSWORD -e MYSQL_DATABASE -e MYSQL_USER mysql:8.4.6 --default-time-zone=+00:00
docker logs --tail 10 devmate-foundation-local
```

等待数据库 ready 后，在同一终端执行（预先设置有效的 JDK 21 `JAVA_HOME`）：

```bash
export PATH="$JAVA_HOME/bin:$PATH"
export DB_URL='jdbc:mysql://localhost:13306/devmate'
export DB_USERNAME=devmate DB_PASSWORD="$MYSQL_PASSWORD"
export JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export SPRING_PROFILES_ACTIVE=dev SERVER_PORT=18080
./devmate-server/mvnw -f devmate-server/pom.xml spring-boot:run
```

### 前端与浏览器

终端 B 切换到 `devmate-web/`，已执行 `npm ci` 后启动。
PowerShell：

```powershell
$env:VITE_DEV_PROXY_TARGET = 'http://127.0.0.1:18080'
npm.cmd run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

POSIX：

```bash
VITE_DEV_PROXY_TARGET=http://127.0.0.1:18080 npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

访问 `http://127.0.0.1:15173`。Axios 默认 `/api`，Vite 代理移除 `/api` 前缀，
转发到后端 `/auth/*` 和 `/projects*`。`http://127.0.0.1:18080/health` 应匿名返回健康响应。
无需更改服务端 CORS，也不要把数据库密码或 JWT 密钥放进 `VITE_` 变量。

按[任务书验收矩阵](../tasks/DEV-012-foundation-acceptance.md)使用两个临时普通用户验收。
使用独立浏览器会话分别登录；注册凭据仅本地使用，截图避开密码、令牌和开发者工具中的请求头。
对过期令牌场景，可在隔离环境启动后端时将 `JWT_EXPIRATION` 设为 `PT30S`，完成后恢复默认值。
网络失败可临时停止本任务后端或使用浏览器离线功能；不得给生产接口添加故障注入开关。

## 常见故障

第二阶段的独立容器、模型 Stub 和故障注入步骤见本文末尾“AI 对话隔离验收”。

Windows 宿主 JVM 若报告 `Unable to establish loopback connection`，不要修改业务安全配置。
可以在 `clean verify` 已生成当前 JAR 后，使用 Java 21 Linux 容器做本地联调。
以下 PowerShell 命令在已创建本文临时 MySQL 的同一终端运行，先停止原后端：

```powershell
docker network create devmate-foundation-net
docker network connect devmate-foundation-net devmate-foundation-local
$env:DB_URL = 'jdbc:mysql://devmate-foundation-local:3306/devmate'
$env:SERVER_PORT = '8080'
$taskJar = (Resolve-Path devmate-server/target/devmate-server-0.0.1-SNAPSHOT.jar).Path
docker run --rm --name devmate-foundation-app --network devmate-foundation-net -p 127.0.0.1:18080:8080 -v "${taskJar}:/app.jar:ro" -e DB_URL -e DB_USERNAME -e DB_PASSWORD -e JWT_SECRET -e SPRING_PROFILES_ACTIVE -e SERVER_PORT eclipse-temurin:21-jre java -jar /app.jar
```

此方式只用于本地隔离验收，不是部署配置。前端代理保持 `http://127.0.0.1:18080`。
完成后 Ctrl+C；若容器仍在运行，用 `docker stop devmate-foundation-app` 停止本任务容器。
网络名须未被占用；完成数据库容器清理后，用 `docker network rm devmate-foundation-net` 清理此临时网络。
仍须单独保留 JDK 21/Testcontainers 完整测试结果，不能把启动成功当作测试通过。

| 症状                         | 检查与处理                                                                                 |
| ---------------------------- | ------------------------------------------------------------------------------------------ |
| Docker pipe/socket 不可用    | 启动 Docker Desktop/Linux daemon，确认 Linux containers 与 `docker info`，不跳过数据库测试 |
| Java 编译版本错误            | 核对 `JAVA_HOME`、`java -version` 与 Wrapper 输出，使用 JDK 21                             |
| npm 版本仍旧                 | 用 `where.exe npm` 或 `command -v npm` 检查 PATH，避免旧 npm 抢占                          |
| DB_PASSWORD 或 JWT 配置错误  | 在启动后端的同一终端设置；JWT 至少 32 字节，不提供源码默认密钥                             |
| 数据库连接被拒绝             | 等待数据库 ready，核对 13306 端口及 DB_URL；不连接共享数据库救急                           |
| /api 返回 404 或代理失败     | 确认 VITE_DEV_PROXY_TARGET 设置后重启 Vite，后端健康接口使用 `/health`                     |
| 端口被占用                   | Windows 用 Get-NetTCPConnection、POSIX 用 ss/lsof 查归属，修改本任务端口而非终止未知进程   |
| npm audit 网络失败或发现漏洞 | 分开记录网络错误与漏洞，禁止自动 audit fix；漏洞按独立任务处理                             |
| 构建 chunk 超过 500 kB       | 记录现有体积告警，不视为隐藏失败，也不在本任务重构打包                                     |

## 停止与清理

先在终端 B、A 分别 Ctrl+C 停止本任务前端和后端。确认容器是本次创建的临时验收容器后：

```bash
docker stop devmate-foundation-local
docker rm -v devmate-foundation-local
```

这会删除该容器及其匿名数据库卷，验收数据无法恢复；不得用于保留数据的容器。
不执行全局 `docker system prune` 或删除其他容器。关闭设置凭据的终端，清除浏览器测试会话。
`target/`、`dist/`、`tmp/` 是忽略的本地输出，不纳入提交。

## CI 与合并检查

[Foundation 工作流](../../.github/workflows/foundation.yml)在 PR 目标为 `develop`/`main`、
`develop` push 和手动触发时运行。稳定检查名为 `Foundation Backend`、`Foundation Frontend`。
项目所有者可在 GitHub 分支规则中把这两个检查设为 required checks；本任务不更改保护规则。
手动触发需工作流已存在于默认分支；当前 PR 通过 `pull_request` 事件验证。

后端上传 7 天保留的统计摘要，不上传 Surefire XML 中的系统属性、日志、密码或数据库文件。
前端审计访问公共 npm registry；所有检查必须成功，不以历史运行替代最新提交结果。

## AI 对话隔离验收

任务范围见 [DEV-015](../tasks/DEV-015-ai-conversation-acceptance.md)，实际证据见
[第二阶段验收记录](../testing/ai-conversation-acceptance.md)。以下辅助工具只用于本机合成数据验收，
不属于生产服务。先完成上述构建与测试，使用固定的 Node 22.19.0、npm 10.9.3、Python 3 和 Docker。

### 容器和模型 Stub

在根目录执行：

```bash
node --test scripts/ai-stub.test.mjs
python -B scripts/conversation_env.py start
python -B scripts/check-conversation-api.py
```

`start` 创建带 `devmate.acceptance=dev015` 标签的 `devmate015-net`、`devmate015-db`、
`devmate015-stub`、`devmate015-app`。已有同名资源时拒绝覆盖。镜像为 `mysql:8.4.6`、
`eclipse-temurin:21-jre` 和 `node:22.19.0-alpine`，缺少时需下载；每次 Docker 命令最多等待 180 秒。
数据库不发布端口、不挂载宿主数据；Stub 仅在专用容器网络监听，后端只发布 `127.0.0.1:18085`。
Docker Desktop 下使用专用 bridge 网络，而非无法从宿主访问映射端口的 internal 网络。

数据库、JWT 和 Stub 测试认证值运行时随机生成，仅存在进程及容器环境，不写入仓库或回显。
后端显式使用 `http://devmate015-stub:19090/v1` 与合成模型 `acceptance-model`，不回退到公共模型。
验收环境的读取超时为 5 秒、租约为 10 秒，生产默认值不变。
仅在验收容器中抑制 Spring 自动配置生成的开发密码提示；不改变认证过滤器或业务审计日志。

接口脚本创建随机合成账号和项目，验证所有权、重放、52 条消息分页、故障状态和调用计数，
最后软删除自己的测试项目并恢复成功 Stub。重复执行会留下合成历史，最终随临时数据库清理。
`-B` 防止辅助模块导入生成 Python 缓存。

也可以在宿主直接运行 `node scripts/ai-stub.mjs success 19090`。默认只监听 loopback；
只有在不发布端口的专用容器内才能设置 `STUB_ISOLATED_CONTAINER=1`。
不要把容器后端的地址误设为 `127.0.0.1`，那表示后端容器自身。

Stub 仅接受 `POST /v1/responses` 和计数用 `GET /stats`。请求体最多 1 MiB、同时处理最多 8 个生成请求、
连接最多 32 个、请求接收超时 10 秒；延迟最多 150 秒，CLI 运行 2 小时后关闭。
只接受合成模型和无状态、非流式、无工具契约，不匹配即失败；不记录请求头、正文或响应正文。

切换故障会重建本任务 Stub，计数归零，不应在正常请求仍在执行时切换：

```bash
python -B scripts/conversation_env.py stub --scenario rate-limit
python -B scripts/conversation_env.py stub --scenario unavailable
python -B scripts/conversation_env.py stub --scenario invalid
python -B scripts/conversation_env.py stub --scenario delay --delay-ms 6500
python -B scripts/conversation_env.py stub --scenario success
```

分别验证 503、503、502、504 与成功。并发浏览器测试用 4500ms 延迟，在两个已登录标签中立即发送同一对话，
第二个请求应显示生成冲突；5 秒读取超时内第一个请求应成功。自动化租约过期测试使用屏障和显式数据库时间，
不依靠浏览器操作速度判定租约安全性。

### 浏览器与响应丢失

前端终端在 `devmate-web/` 中设置 `VITE_DEV_PROXY_TARGET=http://127.0.0.1:18085` 后执行：

```bash
npm run dev -- --host 127.0.0.1 --port 15175 --strictPort
```

PowerShell 使用 `$env:VITE_DEV_PROXY_TARGET='http://127.0.0.1:18085'` 和 `npm.cmd`。
浏览器访问 `http://127.0.0.1:15175`，注册合成用户并创建项目。为该用户生成可读的分页固定数据：

```bash
python -B scripts/seed-conversation-browser.py dev015-browser
```

将用户名替换为刚创建的测试用户。脚本仅操作带任务标签的临时库，为第一个活动项目新增 52 条合成消息的对话
和 12 个列表项目；不会触发模型调用。这只准备 UI 数据，真实发送与分页接口另由接口脚本验证。

验证响应丢失时，在仓库根目录的另一终端运行：

```bash
node scripts/conversation-proxy.mjs drop-send-once 15175 15176
```

从 `http://127.0.0.1:15176` 登录并进入对话。代理先等真实后端完成第一次消息发送，再向浏览器返回截断的响应体；
浏览器显示网络不确定提示，手动“重试确认结果”后消息恢复，Stub 计数与数据库消息数不得增加。
代理不记录正文或 UUID，只截断首次消息响应；其他请求正常转发。
不要只把代理放在 Vite 上游：Vite 会将断连转换成 HTTP 错误；只在响应头前断连又可能触发 Chromium 透明重试。

Ctrl+C 停止该代理后，可用同样参数改为 `fail-read`，在已经加载消息的页面点击刷新，验证失败提示且保留消息；
停止后用 `pass` 启动，点击重试恢复。代理固定仅支持本任务本机端口、1 MiB 请求上限、32 个连接、
125 秒上游超时和 2 小时进程寿命，不是通用代理，也不在生产环境使用。

切换 AI 关闭或短会话测试不删除数据库，保持 JWT 密钥，需重新登录才能取得新的短有效期令牌：

```bash
python -B scripts/conversation_env.py app --disabled --short-session
python -B scripts/conversation_env.py app
```

关闭 AI 后仍可创建和读取对话，发送返回 503 并保留草稿；短会话为 30 秒，到期触发请求后应清理页面并跳转登录。
恢复 `app` 默认设置后会启用本地 Stub 和 2 小时会话，不会启用真实模型。

### 清理与检查

先 Ctrl+C 停止本任务前端与代理，关闭测试浏览器标签，然后执行：

```bash
python -B scripts/conversation_env.py stop
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

`stop` 逐一验证标签后删除上述本任务容器、匿名数据库卷及专用网络；合成数据将不可恢复。
不用于需保留的环境，不执行全局 prune。关闭设置了前端代理变量的终端，或恢复原值。
若 npm 默认镜像不支持审计，使用 `npm audit --registry=https://registry.npmjs.org`，不修改用户全局配置或 lockfile。

## 知识文档接入与存储验收

上传默认关闭。完整自动测试先从锁定源码构建隔离 MinIO 镜像；详情见[准备记录](storage-preflight.md)，
SDK 2.55.6、Go 工具链、镜像摘要和既有依赖不因本次功能实施而升级。

```bash
python3 -B scripts/storage-preflight/build-minio.py
python3 -B scripts/storage-preflight/verify.py
./devmate-server/mvnw -B -f devmate-server/pom.xml clean verify
python3 -B scripts/check-knowledge-test-reports.py
python3 scripts/summarize-tests.py
```

Foundation Backend 在 Linux JDK 21 上执行上述源码构建与完整 Maven 测试，必需的八组 knowledge 测试缺失、失败或跳过
均不通过。数据库、对象存储、端口、bucket 和凭据均由测试隔离创建；不调用私人账户。
测试密码与 JWT secret 运行时生成，报告仅发布类名和计数，不上传原始 XML、SQL、Docker inspect 或 SDK 日志。

本次 Windows Oracle JDK 21 的 loopback 不可用，因此本机使用以下替代命令，未跳过实际集成测试：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = $env:JAVA_HOME + '/bin;' + $env:PATH
python -B scripts/verify-knowledge-backend.py
```

脚本先用宿主 JDK 21 `clean package -DskipTests` 编译主程序及测试，再在准备记录固定的 Linux Temurin 21.0.12
**JRE** 容器内执行离线 `surefire:test`，检查所有报告零失败、零错误、零跳过。编译阶段的 `-DskipTests` 只是推迟执行，
不是验收豁免。该镜像没有 javac，不能宣称在其中执行了 `clean verify`。CI 仍使用 JDK 的完整 `clean verify`。
可用 `--tests DocumentLifecycleIntegrationTest,DocumentHttpIntegrationTest` 做开发局部检查，但不能代替完整验收。
忽略目录 `tmp/dev-016` 的原始日志仅供本地排错。

### 启用隔离手工上传

继续使用上文隔离 MySQL 与 JWT 配置。先由受控管理会话在隔离 MinIO 创建私有 bucket，核对匿名 GET 被拒绝；
应用账户仅获该 bucket 的 `s3:PutObject`、`s3:GetObject`、`s3:DeleteObject` 及 bucket HEAD 所需 `s3:ListBucket`，
对象权限限制在 `users/*`。应用不拥有 bucket 创建或策略修改权限。测试镜像仅用于隔离测试，不指定生产存储方案。

在当前 shell 注入 `KNOWLEDGE_ENABLED=true`、endpoint、bucket、region 和运行时生成的存储凭据；变量表见
[后端说明](../../devmate-server/README.md)。隔离 loopback HTTP 显式设置 `KNOWLEDGE_ALLOW_LOCAL_HTTP=true`；
生产必须使用 `prod` profile、HTTPS 和正确证书，不能关闭证书校验或把该开关用于公共 endpoint。
凭据不放入请求、前端、配置文件、终端输出或 Git。重启后用有效 JWT 调用[文档 API](../api/knowledge-documents.md)。
知识文件不会进入现有对话上下文。

临时目录应为专用本地目录，限制磁盘和所有者权限；默认总预算 256 MiB。每请求在解析前预留 servlet spool 与
校验副本的最坏容量；多个实例共享目录时用文件锁协调预算。实例拥有锁定私有子目录、随机文件名；结束请求清理，
每次扫描至多检查 50 个实例目录、每目录 500 个条目并清理至多 50 个识别文件。十分钟后仅回收已失去实例锁的
遗留目录，不跟随 symlink、不递归删除未知文件。目录异常或预算不足安全返回 503，应由运维检查占用而非无限扩容。

### 持久化恢复、人工处理与回滚

扫描器默认每 60 秒最多领取 50 条，上传租约 2 分钟，总 SDK 操作超时 30 秒、连接 2 秒/读取 10 秒。
SDK 每个操作只尝试一次，扫描不重新 PUT。项目锁、操作版本与持久化租约防止多实例重复领取和旧响应覆盖删除。
失败后最多五次自动重试，退避 1/2/4/8/16 分钟；耗尽时保留状态、定位、容量并置 `needs_manual=1`。
租约过期只允许调查；`remote_phase=POSSIBLE` 且对象缺失不能证明 PUT 已停止，必须保留。
完整且带原写入 token 的原子对象或可信 PUT receipt 才确认该唯一 PUT 已结束。

经授权的数据库运维会话可以只查看安全列：

```sql
SELECT id, storage_state, error_code, retry_count, needs_manual
FROM knowledge_documents
WHERE needs_manual = 1
ORDER BY id
LIMIT 50;
```

确认目标 ID 和存储故障恢复后，在同一 UTC 数据库会话设置数值 `@devmate_document_id`，执行
[人工重试 SQL](../../scripts/retry-knowledge-document.sql)。脚本只恢复核对/清理、增加操作版本，不改变终态、写入阶段或额度；
活动租约不能被抢占。若唯一 PUT 结果始终未知，再次重试仍可能进入人工清单；不能将 `POSSIBLE` 手动改为 `FINISHED`，
不能删除定位或强行释放额度。进一步处理须取得远端不再写入的明确证据并单独审查修复方案。
无公开重试、故障注入或清理 API。

回滚优先设置 `KNOWLEDGE_ENABLED=false` 并重启兼容应用；上传拒绝、远端清理暂停，但授权元数据与删除标记继续可用。
保留 V6 表和原始对象，故障修复后重新启用，持久化待处理记录继续扫描。不得修改已执行 migration、清空 bucket 或数据库。
失败/删除完成物理清理后仅保留请求终态 24 小时；已删除原文件无法通过应用回滚恢复。
本任务未部署任何环境，也未建立备份。上线前所有者须另行确定 MySQL/对象存储一致备份、保留期及删除履约政策；
备份中的副本可能超过应用物理删除时间，不能把一分钟扫描间隔当成删除 SLA。
