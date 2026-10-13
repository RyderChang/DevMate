# 瞬时逐断言评审工具

DEV-034 的本机工具，默认只有公开来源预检、合成演示和内存 Stub 调用；已增加关闭的 HTTPS 传输实现与独立事务账本，仅 Stub 验证。没有密钥加载器、真实调用开关、正文文件读取入口或历史恢复功能。规则与限制见[核验流程](../../docs/development/claim-citation-review.md)及[驱动和账本说明](../../docs/development/claim-citation-https-ledger.md)。

## 离线使用

```powershell
python -B scripts/rag-claim-review/run.py
python -B scripts/rag-claim-review/run.py --demo
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
./devmate-server/mvnw.cmd -B -f devmate-server/pom.xml test-compile dependency:build-classpath '-Dmdep.outputFile=../tmp/dev-034/validator-classpath.txt'
python -B scripts/rag-claim-review/run.py --stub-demo
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
```

第一条仅核对冻结 SHA、七份 Git 公开源、十题四组及 50 个候选，输出固定预检摘要，不启动窗口。需要 Python 3.10+（含 tkinter）及冻结 Git 快照 `b0b58142af63963bc36ced05df064e4253475794`；浅克隆缺少该快照时固定失败，不自动联网补对象。Linux 窗口测试使用 tkinter 和 `xvfb-run`；CI 安装仅测试使用的系统 `python3-tk`，明确使用 `/usr/bin/python3`，不依赖 PATH 中缺少 Tk 的其他 Python，不跳过 GUI 测试。

完整测试与 `--stub-demo` 还需要 JDK 21、已编译的生产校验器/测试桥和 Maven 生成的 classpath；缺失时固定失败，不跳过或替换校验器。Linux 使用 `./devmate-server/mvnw`，其他 Maven 参数相同。CI 在 Backend 完整 `clean verify` 后生成 classpath 并运行全部 Python/Xvfb 测试；前端仍运行原有全部验证。

`--demo` 打开合成回答。选择答案区间，点击“加入当前断言”；一项可以添加多个不连续区间。选择分类、核心 E 编号、支持及类型；在 S 来源全文中按行核对后加入校核证据。选择 C 编号、对应判定和绑定；局部引用必须先把答案中的 `C1` 或 `[C1]` 选区设为标记。每个 C 对应的 S 编号/行区间以及核心证据行在窗口上方显示。

登记断言后清空草稿，按出现顺序继续；同一句多个事实、重复或矛盾表述分别登记。错误可撤回最后一项再重登。提交前分别确认核心出现/遗漏、每个返回引用相关性、Q10 弃答，以及两项全文检查。未审项或残留草稿阻止提交；`COMPLETED` 表示评审完整，不表示事实或绑定通过。

演示凭据写入忽略目录 `tmp/dev-034/demo/review-<本地随机编号>.json`，标为 `mode=SYNTHETIC`、`reviewer=SYNTHETIC_CHOICES`，usage 为 0；不能计入真实轮或原 40 样本。关闭窗口丢弃全部未提交断言，保存 `INTERRUPTED` 与原 usage/预留；没有付费重试。保存失败保留原未知凭据，窗口仍可重新提交脱敏凭据或中断；文件写入重试不发送模型请求。

## 离线调用适配与授权账本模拟

`adapter.py` 提供 `StubGrant`、`StubTransport`、`ProductionValidator` 与 `OfflineAdapter.review(context, transport)`。入口只接受精确的 Stub 类型和固定 `OFFLINE_STUB` 授权；LIVE、DEV-032 或任意其他模式均拒绝。该适配不接入 HTTPS 客户端、凭据读取或真实调用开关，不能用 Stub 授权发送网络请求。`provider.py` 只解码同次内存 Stub 信封。新 HTTPS 实现的公开入口仍固定关闭，真实授权与发送交接必须另行审查。

## 独立账本及 HTTPS 的离线验收

`authorization.py` 区分关闭的 `NewRoundAuthorization` 与仅模拟的 `RehearsalAuthorization`；`RoundLedger` 使用 SQLite FULL 事务及跨进程本机排他锁，发送前同时提交 UNKNOWN 次数/预留/未评审状态。`governed.py` 仅交接精确 MemoryHttpStub，合法 usage 在生产校验前保存；同轮、同授权和同题组不能恢复/重发/补额，所有失败停止。测试中的 `rehearsal_cap` 是模拟容量，不改变真实 ¥2.00。

`https_driver.py` 实现固定 HTTPS/TLS 校验、0 重定向/重试、DNS 至 EOF 总 deadline、含响应头/framing 的接收限制；公开 `exchange` 仍在任何密钥或网络前拒绝。内部 wire 实现只由测试以合成凭据/内存连接验证，没有 live CLI。`test_authorization.py` / `test_https_driver.py` 被现有 Foundation 自动发现；详细命令、存储合同与授权剩余条件见[说明](../../docs/development/claim-citation-https-ledger.md)。

原冻结文件和费用阻断保留，¥2.00 托管上界未证明，¥84.213760 仍只是历史保守回退。取得更紧证据或明确改变预算后才可重新冻结；真实调用另需所有者独立确认完整计划和评审环境。本轮不启动真实发送。

`python -B scripts/rag-claim-review/cost_contract.py` 重算[追加托管费用审查](../../docs/development/claim-citation-hosted-cost-audit.md)：同 40 payload 的固定参考 JSON 开销、预算阈值与共享上下文候选费用。参考 ¥1.276248 不作为托管证明，候选 ¥84.131840 不替换旧冻结回退；`hostedInputTokenBound/hostedReservedMicros=null`、BLOCKED_HOSTED_BOUND/NOT_GRANTED/liveEnabled=false 保留。`--verify-sources` 仅复现本机十份已下载官方快照的 bytes/SHA，不下载/执行源码；CI 只运行默认审查及固定摘要/合成负例。

`python -B scripts/rag-claim-review/tokenizer_archive.py` 校验官方 tokenizer ZIP 的独立固定摘要；`--replay-local` 可只读复现已下载包/三个成员 SHA 与 JSON/AST 观察。包内 16384 配置不能当作托管输入限制，V4.1 tokenizer/framing 绑定仍未证明；演示的 trust_remote_code 不执行。CI 不下载/运行 ZIP，只核对摘要与合成负例，详见上述费用说明。

预计数、输入/费用 cap、余额/402 和兼容 SDK 的[官方合同补查](../../docs/development/claim-citation-provider-contract-followup.md)仍未取得可解除阻断的依据；[询证草稿](../../docs/development/claim-citation-provider-inquiry-draft.md)已准备但未发送。工具没有新增计数探测、密钥读取或对外消息入口。

`context` 由 `load_context(..., returned=[])` 创建，必须是本夹具的 FROZEN 上下文。适配先校验题干、题号、组别、scope、全部来源和原五候选，再创建只用于该次请求的内存 Ticket。返回必须持有同一个 Ticket 对象；逐项比对 round/attempt 编号、题组、scope 与完整候选定位，复制出值相等的 Ticket 也不能冒充本次请求。之后将同一响应的合法 usage、生产校验结果和实际引用接入同一个 Session；引用数量/顺序、C 编号、source/chunk SHA、ordinal、字符与行定位及类型全部精确比对，布尔不能冒充整数。

生产 JSON 只交给现有 `RagOutputValidator`。测试目录中的 `ClaimReviewValidationBridge` 通过捕获的 stdin/stdout 内存管道传递原始 JSON 与已解析答案，不在命令参数、终端或文件中传递正文；Python 不重写 JSON 合同。捕获 stderr 后不回显，异常只返回固定类别，JVM 禁止创建核心转储。桥缺失、失败或协议异常会停止，不降级到另一套解析器。

每个 Stub round 显式给出题组白名单、次数、模拟费用上限和每次预留；同题组只可尝试一次。发送前先持久化 `UNKNOWN` 账本及未知评审凭据；次数与预留一旦登记就保留，失败也不释放。返回 usage 在定位/结构检查与窗口之前保存；跨请求响应被拒绝时，其 usage 不归到本请求。超过预留的 usage 保留并停止；缺失 usage 记 null 并保持全部预留。固定模拟费率仅用于 Stub 超限测试，`stubPriceMicros` 不代表当前报价、已获付费授权或实际账单。

结构失败保存生产固定类别、usage 与未评审状态，不打开窗口；允许继续另一个已列出的题组，不补发失败题组。提供商失败、未知网络、校验桥故障、配对/费用/保存失败和窗口中断均停止本 round。关闭窗口丢弃所有未提交断言；若最终保存失败，保留最后一份成功保存的未知凭据及账本。已有 round 文件使用排他创建，拒绝恢复、补额或重新构造同目录同 round；没有重试/续跑接口。该模拟不提供跨机器真实授权治理。

`--stub-demo` 仅生成合成 Q1/baseline 答案与五个引用，经真实生产校验器和适配进入可见窗口。脱敏记录写到 `tmp/dev-034/stub-demo`，模式 `OFFLINE_STUB`，完成时 reviewer 为 `SYNTHETIC_CHOICES`；使用公开冻结来源校核和模拟 usage。它与 `--demo` 互斥，两者都不能计入新真实轮或 DEV-032。默认入口仍只做预检。

适配结束清理 Session、响应、解析答案、Stub 正文与窗口引用；调用者仍须清理自己持有的内容和来源缓存。真实正文严禁截图；当前可见验收只展示合成正文及公开源，不证明真实回答质量或所有者已完成语义评审。

## 底层瞬时评审接口

`review.py` 的 `load_context(blob, read_source, number, arm, returned)` 只接受本目录固定 manifest SHA。`read_source` 返回指定 Git 快照/固定路径的 bytes，工具逐文件和片段校验 SHA。`returned` 必须来自同一次经过生产校验的响应，且实际返回来源定位必须由调用适配层逐项与本题候选核对；不能只把另一次请求的 C 编号交给本接口。整个冻结源仅供本机校核，后续外发只能包含实际必要候选。

`review_transient(context, answer, receipt, directory)` 接收内存中的已解析答案，不接收原始提供商信封或正文路径；`receipt` 严格限定 `status`、`usage`（inputTokens/outputTokens/totalTokens 或 null）、`reservedMicros`（整数，百万分之一人民币元）。它先保存未知评审及用量，再打开窗口；返回脱敏凭据路径。FAILED/UNKNOWN 必须没有答案和返回引用，来源覆盖为 UNKNOWN；成功且明确无引用才为 NOT_COVERED。

该底层接口自身不校验生产 JSON 合同、调用授权、余额或响应真实性；离线适配已用 Stub 验证这些边界，HTTP 真实传输与付费授权尚未接入。离线信封驱动与冻结计划见下文。`FROZEN` 仅表示来源 scope 已冻结，不自动证明内容来自真实模型；不能据凭据格式或合法 usage 认定实际调用/费用。旧轮额度、账单与 UNKNOWN 不改变。

答案及源文只在 Session/窗口内存中驻留。接口结束会清理自身正文引用并销毁窗口；调用者必须清理自己持有的响应、答案与来源引用。窗口禁止复制/剪切/粘贴和选区导出，没有备注/截图/导出功能，但不控制操作系统换页、第三方录屏或物理内存擦除。

记录白名单与闭合枚举校验在保存前再次执行；只保存断言编号与 Unicode 字符区间、事实判定、冻结证据定位、逐引用对应/绑定、完整性及独立弃答。校核源可只保存 source SHA 与行范围，引用关系另保存冻结 chunk SHA。未知键、非法 ID、布尔冒充整数、越界坐标与缺少全文检查都拒绝，错误不回显原值。

原 DEV-032 正文仍不可恢复。离线适配完成后，新真实轮仍需独立次数、预算和外发范围授权；提议与剩余条件见[任务书](../../docs/tasks/DEV-034-claim-citation-review.md)。本工具不实施生产输出约束采纳、合并或部署。

## 新轮冻结计划与提供商信封 Stub

`python -B scripts/rag-claim-review/plan.py` 只重新生成并比较全部 40 个精确 payload，输出固定 scope SHA 与 paidRequests=0。模型为所有者确认的 `deepseek-flash`、非思考、输出 1024 tokens；正式高峰/缓存未命中价下，按字节加 framing 的保守假设预留 ¥1.397304。输入费用上界仍须在授权前核实；不是提供商账单硬保证。完整外发范围、四组配置、逐次 SHA/预留、官方价格来源与剩余条件见[新轮计划](../../docs/development/claim-citation-new-round-plan.md)及 [new-round-plan.json](new-round-plan.json)。

`StubTransport(None, envelope=<合成 bytes>)` 保持精确 Stub 类型与 OFFLINE_STUB 限制。发送前固定 payload/plan SHA 随 Ticket 和 UNKNOWN 账本保存；信封同请求配对通过后提取合法 usage 并先保存，再核验提供商模型/choice/finish/message 和生产 JSON。实际引用 ID 由同信封经真实生产校验器得到，不使用另一份调用的 ID。缺失 usage 或提供商异常停止；未知、费用/输入/输出超界、保存失败与中断仍不重试。该入口没有 HTTP 客户端，不可用于付费调用。

本机受限环境中若默认临时目录不可写，先将 TEMP/TMP 指向仓库忽略目录 `tmp/dev-034/test-temp`，并使用可用的 Python 3.10+（含 Tk）与 JDK 21；不能通过跳过文件/窗口测试解决环境问题。

## 费用证据与关闭执行审查

`python -B scripts/rag-claim-review/execution.py` 输出固定 execution preparation SHA、预算不足、环境未确认、NOT_GRANTED 与 paidRequests=0。原 ¥1.397304 仍是输入假设下的估算；官方完整上下文回退预留为 40 次 ¥84.213760，超过拟定 ¥2.00 cap，未提高额度或改变题组。固定官方参考 tokenizer/源码的只读证据、机器审查和政策演练见[执行准备](../../docs/development/claim-citation-execution-preparation.md)、[token-evidence.json](token-evidence.json)、[execution-preparation.json](execution-preparation.json)。

`token_evidence.py` 是可选本机复现：需要四份固定官方文件已放在忽略目录 tmp/dev-034/token-evidence；仅解析 JSON/AST 字面量、重算 SHA 并比对摘要，不 import/exec 下载源码、不自动下载或安装 tokenizer。CI 使用固定摘要和合成 profile 测试，不重新下载官方文件，也不冒称重算过完整 tokenizer。

`require_live_ready()` 与 `OfflineHttpHarness.live()` 固定拒绝，环境变量和 JSON 编辑不能开启。MemoryHttpStub 只模拟同请求 HTTP 状态、deadline、读入上界与 usage 保留；没有 socket、密钥、HTTPS 客户端或有效真实授权，不能证明真实 TLS/代理/网络验收、fsync 或跨进程锁。现有 OfflineAdapter 的精确 Stub 类型与排他账本限制保留。真人评审、无录制桌面及系统捕获限制仍需所有者确认；本轮不代签、不扫描或关闭私人软件。
