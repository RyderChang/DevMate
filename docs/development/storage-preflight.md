# DEV-016 实施前依赖与环境核验

关联 [DEV-016](../tasks/DEV-016-knowledge-document-storage.md) 与
[ADR 0003](../adr/0003-knowledge-document-storage-and-recovery.md)。本记录只核验依赖与隔离运行条件，
不代表文档接入功能、失败恢复、权限隔离或生产部署已实现。

## 基线与范围

2026-09-27 刷新远端后，`origin/develop` 为 `0fc422907f2792dc557ae6bd73b899257c2a51c5`；
本地已审查的任务书提交为 `a5db4bfb695cdf5a27d9f1bb61291b6092cfced5`。
生产后端 POM、V1–V5 migration、业务代码和 Foundation 工作流保持原有版本。
核验工程位于 [scripts/storage-preflight](../../scripts/storage-preflight/pom.xml)，不加入后端模块或打包产物。

## SDK 与依赖锁定

首个 MinIO S3 兼容适配器采用 `software.amazon.awssdk:s3:2.55.6`，HTTP 实现为同版本
`url-connection-client`，通过 `software.amazon.awssdk:bom:2.55.6` 对齐 SDK 模块。
该版本已核对 [AWS 发布记录](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.55.6)
与 Maven Central 发布 POM；本次是首次选择存储 SDK，不升级已有业务依赖。

选择依据是同步 `S3Client`、明确的操作总超时和重试上限，以及 JDK HTTP 实现可满足当前 PUT/GET/HEAD/DELETE。
排除默认 Apache、Apache5 和 Netty HTTP 客户端，不加入 AWS CRT native 客户端、Transfer Manager 或异步上传。
SDK 类型仍只允许进入未来 knowledge infrastructure，应用服务接口保持 ADR 0003 的同步边界。

核验工程沿用 Spring Boot `3.5.6` 的 dependency management。58 项完整解析结果（包括测试依赖）记录于
[dependencies.lock.json](../../scripts/storage-preflight/dependencies.lock.json)，逐项固定版本、作用域及 JAR SHA-256。
执行 [校验脚本](../../scripts/storage-preflight/check-dependencies.py)会拒绝增删依赖、版本、作用域或字节变更，
不会自动改写锁文件。主要非 SDK 依赖包括 `reactive-streams:1.0.4`、`eventstream:1.0.1`；
JUnit `5.12.2`、Testcontainers `1.21.3`、MySQL JDBC `9.4.0`、SLF4J `2.0.17` 沿用当前父 POM 的解析结果。
AWS 自带的 shaded Jackson 不替换后端 Jackson；没有强制升级 Spring Boot 管理的已有库。
`verify.py` 还在忽略目录生成包含完整后端依赖的候选 POM 并对比依赖树：本次原有 143 项的版本和作用域
均未变化，仅新增 30 项 SDK 相关依赖。JUnit launcher 显式固定为父 POM 的 `1.12.2`，提前解析 Surefire
provider `3.5.4`，让空缓存 CI 的离线测试容器也具备完整运行依赖。

SDK 构造约束：显式 endpoint、region `us-east-1`、path-style、静态的运行时注入凭据；不使用默认账户/凭据链。
HTTP 连接超时 2 秒、socket 超时 10 秒、API 总超时与单次尝试上限均为 30 秒，`maxAttempts=1`。
本地请求不继承系统/环境代理，依赖下载代理与对象存储连接配置分开。
总超时并不能证明远端 PUT 已取消；后续实现仍须保留 ADR 规定的不确定状态和迟到写入保护。
参考 [AWS HTTP 客户端说明](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html)
与 [操作超时说明](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)。

## MinIO 测试镜像来源与锁定方式

本次实查 Docker Hub `minio/minio:RELEASE.2025-09-07T16-13-09Z` 无法匿名拉取，
Quay 的该 tag 及 `RELEASE.2025-10-15T17-29-55Z` 查询返回 401；不能将历史 tag 当作可复现镜像。
[MinIO 官方仓库](https://github.com/minio/minio)已归档，
[2025-10-15 安全修复发布](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
要求从源码构建容器。

因此只为本任务的合成数据测试，从官方 commit `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`
构建本地镜像；不使用第三方重打包镜像，不发布镜像，不借此指定生产存储方案。
源码归档 SHA-256 为 `45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841`。
Go 固定为本机已安装且可在 [Go 官方发行页](https://go.dev/dl/)获取的 `1.26.5`；
`GOTOOLCHAIN=local` 禁止隐式下载/切换编译器，保留官方 `go.mod`/`go.sum`，`-mod=readonly`、checksum database 校验开启。
固定 `linux/amd64`、`GOAMD64=v1`、`CGO_ENABLED=0`、`-trimpath`、编译元数据和文件时间，构建并发限制为 4。
Dockerfile 显式固定文件属主 `0:0`、二进制权限 `755` 与许可证权限 `644`，不依赖 Windows/Linux 宿主权限语义。
镜像显式使用未压缩 Docker 归档和 `rewrite-timestamp=true`，每次无缓存构建后校验摘要再加载。
构建脚本创建按平台摘要锁定的 BuildKit `0.33.0` 临时容器构建器，完成或失败后回收其容器与缓存卷，
不切换用户默认构建器。此方式采用 Docker 官方的
[docker-container 驱动](https://docs.docker.com/build/builders/drivers/docker-container/)，
避免 CI Docker 28 默认驱动不支持归档导出的问题；BuildKit 镜像摘要也纳入构建锁文件。
不依赖 BuildKit 的默认压缩或缓存；先前一次 CI 已通过二进制校验，但默认导出产生不同 manifest，因而失败。
Docker Desktop 的直接 unpack 与时间戳重写冲突，故采用“归档导出 → 摘要校验 → 本地加载”。

构建及摘要见 [build-lock.json](../../scripts/storage-preflight/build-lock.json) 与
[Minio.Dockerfile](../../scripts/storage-preflight/Minio.Dockerfile)。摘要分别表示源码归档、Linux 二进制、
镜像 manifest 和 image config，不能相互替代。Docker 不同 image store 返回的本地 ID 可能是后两者之一；
构建脚本单独校验 manifest，测试前核对本地 ID。镜像 tag 仅便于本地引用，禁止发现缺失后退回其他 tag 或 `latest`。
`FROM scratch` 运行层只包含二进制与许可证，关闭浏览器控制台，不含 shell、CA 或生产运维工具。
源码使用 AGPL-3.0；该归档项目及历史传递依赖不作“无漏洞”或“生产可维护”承诺，生产选型需单独评估。

## 固定的运行组合

| 项目          | 核验/配置值                                                                                      |
| ------------- | ------------------------------------------------------------------------------------------------ |
| 宿主          | Windows 11 x64，系统版本 `10.0.26200`                                                            |
| 编译 JDK      | 本机 Oracle `21.0.7+8-LTS-245`；显式 JAVA_HOME，默认 PATH 的 Java 22 不可用作本任务编译器        |
| 测试 JVM      | Linux/amd64 Temurin `21.0.12+8`，镜像按平台 digest 固定                                          |
| Maven         | 仓库 Wrapper `3.9.10`；compiler `3.14.0`、Surefire `3.5.4`、dependency plugin `3.8.1`            |
| Docker        | Desktop `4.64.0`，客户端/引擎 `29.2.1`，Linux/amd64，API 范围 `1.44..1.53`                       |
| Linux kernel  | `6.18.33.2-microsoft-standard-WSL2`；引擎可见 20 CPU、约 15.45 GiB 内存                          |
| Docker API    | 独立核验 POM 将 docker-java 请求版本固定为 `1.44`，不修改 daemon 或用户全局配置                  |
| 构建辅助      | Go `1.26.5`；本机 Python `3.13.5`，脚本要求 Python 3.12+；Buildx `0.31.1-desktop.1`              |
| MySQL         | `8.4.6`，平台 manifest `sha256:c296d65ee6ab3ce2f608c1d1b2bdd3c08b087a5834101d76a6db2e00875216cc` |
| Java 测试镜像 | 平台 manifest `sha256:677919d2f5cfc06a966b17d7b1b06c177fdf31928c60bcf20ac3016bca8a90b8`          |
| CI            | `ubuntu-24.04`、Temurin `21.0.12+8` 编译、Go `1.26.5`，测试仍使用上述 Java 镜像                  |

运行引用集中在 [environment.properties](../../scripts/storage-preflight/environment.properties)。
Docker daemon 与宿主内核版本是本次证据，不声称托管 CI runner 的内核永远固定；每次 CI 输出实际平台版本。
新机器或引擎版本必须重跑核验，不能只靠版本号推断兼容。

MinIO 与 MySQL 都由 Testcontainers 创建，无固定容器名、复用开关或宿主数据目录。
MinIO 9000、MySQL 3306 仅映射到宿主 `127.0.0.1` 的随机端口，控制台不发布。
Windows 容器测试通过 `host.docker.internal` 访问宿主映射；Linux 测试 runner 使用 host network 和 loopback。
测试 runner 挂载 Docker socket 仅用于创建/回收这些已知测试容器，不是生产应用的运行方式。
凭据每次在 JVM 内随机生成，仅保留于测试进程/容器环境；不读云账户或真实 bucket，不记录值。
本任务环境准备代码创建私有 bucket；未来应用适配器不得因此获得自动创建 bucket 的职责。
原文件只使用 32 字节和 5 MiB 合成文本；测试结束关闭容器并删除临时数据。
源码、二进制、构建日志存于忽略的 `tmp/dev-016/`；Maven 产物和报告在独立工程 `target/`。
不上传原始 Surefire XML 或容器日志。

## 复现命令

先启动 Docker，安装上述 JDK、Go 和 Python，确认公共 Maven Central、GitHub codeload、Go module proxy、
checksum database 和固定 Java/MySQL 镜像可访问。需要代理时只在当前进程配置，不把本机代理端口写进共享配置。

PowerShell（在仓库根目录，JAVA_HOME 换成本机已核对的 JDK 21）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
java -version
go version
docker version
python -B scripts/storage-preflight/build-minio.py
python -B scripts/storage-preflight/verify.py
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

POSIX（预先设置有效的 JDK 21 JAVA_HOME）：

```bash
export PATH="$JAVA_HOME/bin:$PATH"
java -version
go version
docker version
python3 -B scripts/storage-preflight/build-minio.py
python3 -B scripts/storage-preflight/verify.py
```

`build-minio.py` 校验源码哈希、工具链、二进制及镜像摘要，不自动接受变化。
首次下载 Go 模块较慢；网络中断时保留缓存，解决网络后重跑，不能关闭 checksum 校验或升级依赖绕过。
`verify.py` 清理本工程旧产物，解析并校验依赖、编译测试，再用固定 Java 容器执行四项测试。
脚本同时校验进程退出码、Maven 成功标记和新生成的测试报告，任何失败、跳过或缺失都以非零退出。
本机 Windows Wrapper 有丢失 Maven 失败退出码的问题，不能单独用其退出码判定验证通过；本任务未扩大到修复 Wrapper。

CI 定义见 [Storage Preflight](../../.github/workflows/storage-preflight.yml)，只在相关核验文件变更的 PR 或手动启动时执行。
`setup-java` 使用发行元数据的完整版本标识 `21.0.12+8.0.LTS`；省略 `.0.LTS` 的首次 CI 安装失败，未进入测试。
它从相同官方源码自行构建测试镜像并校验 digest，不依赖本机镜像缓存或已不可拉取的 MinIO 官方旧镜像。
不会推送镜像、部署服务或调用付费服务。

## 本次结果与实施边界

本地前置核验已通过，CI 尚待本准备分支的最新提交运行结果；功能实施尚未启动。

| 实际执行                                           | 结果                                                                                                                   |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| JDK 21 `mvnw.cmd --version`、`docker version/info` | 核对到上表版本；Docker 启动后 Linux engine 可用                                                                        |
| Maven 依赖解析及 `check-dependencies.py`           | 58 项版本、作用域与 JAR SHA-256 匹配                                                                                   |
| 候选后端 POM 依赖树对比                            | 原有 143 项无版本/作用域变化，新增 30 项 SDK 依赖                                                                      |
| 固定源码构建及 `build-minio.py` 重跑               | 二进制与镜像摘要一致，镜像实际输出正确 release、commit 和 Go 版本                                                      |
| `verify.py`                                        | 4 项测试通过，0 失败、0 错误、0 跳过；在 Linux Java 容器运行                                                           |
| SDK 边界                                           | 503 只有 1 次请求；不响应的本地 Stub 被 API 总超时中断                                                                 |
| MinIO 合同冒烟                                     | 私有 bucket；32 字节/5 MiB PUT、GET 原字节/SHA-256、HEAD；匿名和错误凭据均为 403；重复 DELETE 成功、删除后 HEAD 为 404 |
| MySQL/Testcontainers                               | 摘要锁定的 8.4.6 启动、JDBC 查询、UTC 配置与自动回收通过                                                               |
| GitHub CI                                          | 待本准备 PR 最新提交执行，不引用 DEV-015 的旧 CI 代替                                                                  |

锁定的 MinIO 二进制 SHA-256 为 `2788cd3f1082a789905b3da10f61106849e31ddfc1443d11c89d16ff7ae0e36e`，
镜像 manifest 为 `sha256:22d886b8a16cea295dcbbca55aea28fd8354e72a4e829eb269678ef07d07c923`，
image config 为 `sha256:5b7b9313d03bef5e8c4ac8bb9e538380c80ad80b4cdc4a0f072f3d41742d348f`。

已复现并处理的环境问题：系统默认 Java 22、Docker 最初未启动、Windows Oracle JDK 21 loopback 失败
（显式 IPv4 仍失败）、Testcontainers 默认 Docker API 过旧，以及公共源码依赖下载 EOF。
首次测试夹具使用不存在的 `PullPolicy.neverPull()` 导致编译失败，已改用当前版本支持的 pull policy 后编译通过。
失败记录不计作通过；后续只以本次重跑的真实结果为准。

本准备任务不验证文档 API、数据库 migration、额度/幂等、租约/恢复、迟到 PUT、项目删除或权限业务。
这些必须在单独启动 DEV-016 功能实施后按任务书验证，不能以本次 SDK 合同冒烟替代。
