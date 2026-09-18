# 第一阶段本地开发与验收

适用于认证与项目空间；不启动 AI、Redis、Qdrant 或对象存储。先阅读
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
node scripts/check-docs.mjs
node devmate-web/node_modules/prettier/bin/prettier.cjs --check .github/workflows/foundation.yml scripts/check-docs.mjs README.md devmate-server/README.md devmate-web/README.md docs/README.md docs/development/local-development.md docs/testing/foundation-acceptance.md
git diff --check
```

Windows 将 `./devmate-server/mvnw` 替换为 `./devmate-server/mvnw.cmd`，
`python3` 替换为实际 Python 3 命令（通常是 `python`）。
Testcontainers 自动创建和回收隔离 MySQL 8.4.6，执行空库迁移；无需先创建下面的手工验收容器。
Docker 失败、依赖下载失败或漏洞审计失败都不是测试通过，不能跳过集成测试。

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
