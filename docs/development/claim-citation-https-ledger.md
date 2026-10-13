# DEV-034：关闭的 HTTPS 驱动与独立授权账本

日期：2026-10-13。复用干净 `5d0bfa4` 的 `chore/dev-034-transient-claim-review` 与[草稿 #53](https://github.com/RyderChang/DevMate/pull/53)，目标仍为 develop。远端 develop 为 `1a6dab6`，#53 前一头前后端 CI 成功。此阶段只开发并以 Stub 验证，不发送真实请求、不读取密钥、不确认所有者环境。

## HTTPS 实现与关闭入口

[https_driver.py](../../scripts/rag-claim-review/https_driver.py) 使用 Python 标准库，不新增依赖。唯一公开发送入口 `HttpsDriver.exchange` 调用现有关闭门禁，固定拒绝；底层 `connection` 在 TLS/DNS/socket 构造前也重查同一门禁，直接调用内部 wire 方法同样不能真实发送。没有 live CLI、环境开关或密钥加载器。内部 `_exchange_once` 是传输实现，测试仅以合成凭据及内存连接替换底层 IO；它不是授权入口，不应直接调用。未来真实调用还必须独立审查完整计划、账本至传输的授权交接和真人评审环境，不能仅移除门禁或绕过协调层。

- 固定 `POST https://api.deepseek.com:443/chat/completions`，不接受 URL/端口参数，不使用环境代理。请求必须匹配旧 preparation scope、题组及冻结 payload SHA，最大 98304 bytes；只发该题原五候选对应的已冻结请求。
- 使用系统 CA 的 `ssl.create_default_context`，要求 `CERT_REQUIRED`、主机名校验、TLS 至少 1.2，SNI 固定为 api.deepseek.com，ALPN 为 HTTP/1.1；不提供自定义 CA、忽略证书或 HTTP 回退参数。
- 单次 DNS 后只连接第一个地址，不做地址回退；单次 POST、零重试、零重定向。任何非 200（含 3xx、429、5xx）都不读 Location 或错误正文，连接关闭。
- DNS、TCP、TLS、写入、响应头/分块 framing、正文和 EOF 共用单调时钟的 120 秒绝对 deadline。每次底层 recv/send 使用剩余时间并在返回后重核，慢速响应头或分块不能续期。DNS 无可移植的同步超时，使用有界等待的 daemon；超时后可能仍有 DNS 线程，但它没有凭据/正文/socket，不能晚发请求。
- 整个接收线流（含头和分块 framing）最多 1 MiB，正文也有限；只接受 JSON 与 identity 编码。拒绝重复类型/长度、冲突 Content-Length 与 Transfer-Encoding、非 chunked 转移编码、超限或截断正文。关闭套接字时保留 HTTPResponse 读取句柄，待响应文件关闭才真正释放，覆盖 `Connection: close`。
- 返回同一请求对象；重新比对 scope、题组、SHA/bytes。正文只在内存；异常转换为固定代码，不输出响应、错误体、凭据或上游 reason/id。

Stub 验证证明配置及控制流，不证明真实服务端证书、DNS、网络可用性或付费账单；本轮没有做外部握手。

## 独立授权与可靠预留

[authorization.py](../../scripts/rag-claim-review/authorization.py) 的 `NewRoundAuthorization` 与旧 `StubGrant`、DEV-032 额度独立。真实类型须先经过关闭门禁，当前任何实例都在创建目录、读密钥、网络或次数消费前拒绝；计划/环境确认 SHA 字段仅为待审授权载体，不能当成已经确认或有效签名。新 plan 及环境证据如何接受必须另行审查，此阶段没有签发真实授权。

离线精确类型 `RehearsalAuthorization` 仅允许 `OFFLINE_LEDGER_STUB`，绑定 preparation scope、独立 authorizationId/roundId。测试用全部 40 个冻结样本、正式保守预留作**模拟容量**：数据库字段 `rehearsal_cap=84213760`，不是批准 ¥84.213760，更不是把真实 ¥2.00 cap 提高。旧计划 JSON 不变，真实费用仍阻断。

`RoundLedger` 在唯一指定的本机目录使用 `authorization.sqlite3` 与永久锁文件。SQLite DELETE journal / synchronous FULL，初始化完整性检查；POSIX 首次创建后同步目录，Windows 使用 SQLite FULL 的 FlushFileBuffers 合同。Windows `msvcrt` 和 POSIX `flock` 均非阻塞排他，持有到该轮关闭；SQLite 唯一约束阻止同 authorizationId、roundId 或同题组重复。进程内非阻塞锁禁止重入。关闭句柄释放锁，但不删除锁、授权或预留记录；没有恢复、补额、清空或解锁后重建同轮接口。

同事务写入 attemptId、sampleId、题组、精确 payload SHA、`UNKNOWN` usage/status、固定预留和 `review_status=UNKNOWN` 后才返回发送能力。其他连接可在 Stub 交接时读到已提交 UNKNOWN。事务或提交确认失败不发送；提交成功但确认丢失也保留预留。发送后进程崩溃仍留 UNKNOWN，重新创建同轮拒绝。不能用调用方传入的 durableUnknown 布尔代替账本提交。

该合同仅针对一个本机权威目录和支持 SQLite/文件锁的本地文件系统；未做断电/硬件故障测试，不承诺网络盘、多主机、复制/删除账本或恶意本机文件改写后的全局防重放。未来真实授权必须固定唯一存储位置；目前没有可换目录重放的付费入口。数据库及 journal 只包含闭合元数据，不含答案、候选摘录、完整 payload、密钥或异常正文。

## 同请求配对与失败停止

[governed.py](../../scripts/rag-claim-review/governed.py) 的 `LedgerStubCoordinator` 只接受精确内存 Stub，不接受 HTTPS 客户端或旧授权；同次对象身份及 frozen payload 在预留、交接、保存前核对。信封合法 usage 先可靠保存，再由同一信封的 assistant JSON 调用未修改的生产 `RagOutputValidator`，实际引用 ID 顺序不改写。返回答案/ID 只驻留内存，调用者须 `clear()`；新账本没有自动真人评审或质量通过状态。

任何 HTTP/提供商/结构/配对/usage/保存失败、中断或未知都停止本轮，保留次数和完整预留，不再发送别题，不重发失败题组，不补额。合法 usage 在后续提供商或生产结构失败前已保存；跨请求 usage 不归到本题。保存失败保留最后成功提交的 UNKNOWN/usage。成功题组也不能再次发送。

旧 `OfflineAdapter` 与可见窗口继续维持原精确 Stub 类型及历史行为；新协调层的所有失败停止规则单独验证。完整链路测试以 SQLite 预留、实际 stdlib HTTP 头/正文解析、同响应信封、生产 Java 校验器贯通，但底层仍为内存 Stub。新增测试由现有 Foundation `test_*.py` 自动发现。

## 费用、环境与剩余边界

**¥2.00 内的托管费用上界仍未证明。** ¥84.213760 只是[已冻结执行审查](claim-citation-execution-preparation.md)的保守回退值，不是已批准额度、实际账单或新冻结授权。原计划 SHA `c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750` 与审查 SHA `b11957ebaedeb61e4383fa7bf634be923d354cdfcec07f798a6bfa878f9efd7b` 保留；其中 `NO_LIVE_CLIENT/liveClientPresent=false` 是冻结时的历史准备状态，此阶段新增代码不重新冻结或将该审查升级为授权。

取得更紧的托管 tokenizer/framing 证据，或所有者明确改变预算/scope 后，才能重新冻结完整授权计划；执行日价格/模型仍须另核。本轮不以历史价格假定新付费上界，不访问提供商取得新样本。

真实调用还需所有者独立确认完整计划与私密桌面、录制、剪贴板、调试/崩溃捕获和真人评审安排。一般继续指令及合成 GUI 不代签确认。原 0/40 完整审查、全部历史 UNKNOWN、¥0.20 / 40 次 / 156015 tokens 账单及 SHA 不变，真实 Chat/Embedding 新增均为 0；不应用 #51，不改变生产配置、Schema、依赖、数据库或模板，不合并/部署。回滚撤回此阶段五个工具/测试文件和文档，保留历史账本与冻结材料。
