# DEV-034：逐断言核验流程与现有材料审查

日期：2026-10-11。基线 `1a6dab6c67a7a200c08e78e975b4d3be5e0f1e93`；首阶段文档分支 `docs/dev-034-claim-citation-review`，继续实现分支 `chore/dev-034-transient-claim-review`。[任务书](../tasks/DEV-034-claim-citation-review.md)与[核验流程](../development/claim-citation-review.md)分别定义范围和后续评审口径。

**已交付流程规范，审查全部 40 个题组材料，完成离线全断言评审工具及 Stub 调用适配；未完成原 40 份回答的逐断言核验。**正文没有保留，无法恢复全部核心/额外断言及引用位置。`claimCitationBinding` 仍为 `UNKNOWN`，不能报告全题事实或绑定通过。工具只用合成回答验证，HTTP 真实传输尚未接入；已准备仅限 Stub 的信封驱动与冻结计划，没有新增真实模型请求。

## 冻结输入与只读核验

输入来自未合并的 [PR #50](https://github.com/RyderChang/DevMate/pull/50) 头 `5f2862126ba4f553804d8946d5ecd5b6b4987fc8`：

| 输入                                                                   | SHA-256 / 冻结版本                                                 |
| ---------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 原脱敏结果 `tmp/dev-032/results-338d4aaea73244e4980a71d33fc46076.json` | `ba267e58263b1de8efa7b9f8656ac6045c0a8a0804b0a857a880c803e727ef59` |
| 该头的 `scripts/rag-output-experiment/frozen-inputs.json`              | `5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26` |
| 七份公开源的 Git 快照                                                  | `b0b58142af63963bc36ced05df064e4253475794`；逐文件 SHA 与夹具一致  |

本机只读审查使用忽略目录中的临时脚本，精确核验上述 SHA 后才读取固定元数据。不加载 DEV-026/027/029 的回答文件，不打开 live 入口；`git show` 取得冻结夹具与七份公开源，源内容仅用于内存中核对片段 SHA，不新增正文副本。原结果在审查后重新计算哈希，仍一致；原文件、结果统计及账单没有改写。

核验步骤可按以下合同复现；历史结果文件仅在所有者本机，干净克隆无法独立取得它，临时审查脚本也不作为新产品工具提交：

1. 断言原结果及夹具 SHA 等于上表；递归检查结果对象不存在 `answer`、`text`、`content`、`prompt` 或 `messages` 字段。
2. 核对 Q1–Q10 × 四组恰好 40 个唯一样本、40 个唯一请求 ID；输入与输出 tokens 相加等于总量。这里只检查 ID 唯一性，不把请求 ID 输出到公共文档。
3. 从固定 Git 快照重算七个 source SHA；按 50 个候选的字符边界重算 chunk SHA，核验 17 个目标证据行落在各源范围内。
4. 对 30 条成功采集的全部 108 个返回引用，核对 C1–C5、无重复、其 16 个来源字段与该题冻结候选一致。对每个 E 槽，以同一 source SHA 且引用行区间完整包含目标行重算覆盖；重算覆盖 ID 与全文标记集合交集，均与原记录一致。
5. 核对 30 条成功采集均为 `OWNER_MANUAL`；核心语义仅为合法固定枚举，所有已采集绑定仍 `UNKNOWN`。十条结构失败没有 collection，不能当作明确空引用或语义错误。

这些步骤验证的是历史字段一致性与可用性，**没有再次阅读瞬时答案进行语义复判**。原核心语义判定仍来自 DEV-032 所有者的当时人工提交，不能将本轮哈希核验称为独立语义确认。

## 十题 × 四组材料结果

下表成功单元格为“核心支持数/该题预期核心槽；直接来源覆盖数/该题预期核心槽”。核心支持沿用历史固定判定，来源覆盖已只读重算。失败单元格表示未采集；不填零分或“不覆盖”。Q10 没有 E1–E17 正向槽，弃答单列。

| 题  | A baseline     | B example      | C json_mode    | D combined     |
| --- | -------------- | -------------- | -------------- | -------------- |
| Q1  | 2/2；2/2       | 2/2；2/2       | 2/2；2/2       | 2/2；2/2       |
| Q2  | 2/2；2/2       | JSON_SYNTAX    | JSON_SYNTAX    | 2/2；2/2       |
| Q3  | 2/2；2/2       | 2/2；2/2       | 2/2；2/2       | 2/2；2/2       |
| Q4  | 2/2；1/2       | 2/2；1/2       | 2/2；1/2       | 2/2；1/2       |
| Q5  | 1/1；1/1       | 1/1；1/1       | 1/1；1/1       | 1/1；1/1       |
| Q6  | 1/2；2/2       | 2/2；2/2       | JSON_SHAPE     | 2/2；2/2       |
| Q7  | 2/2；2/2       | JSON_SYNTAX    | 2/2；2/2       | 2/2；2/2       |
| Q8  | JSON_SHAPE     | JSON_SHAPE     | JSON_SYNTAX    | 2/2；2/2       |
| Q9  | JSON_SHAPE     | 2/2；2/2       | JSON_SHAPE     | JSON_SHAPE     |
| Q10 | 弃答 SUPPORTED | 弃答 SUPPORTED | 弃答 SUPPORTED | 弃答 SUPPORTED |

汇总为结构通过 30/40（8、7、6、9），68 个预期核心槽中 48 个已采集：47 个 `SUPPORTED`，1 个 `CONTRADICTED`，来源覆盖 44/48；另外 20 个核心槽因结构失败未采集。不能只用 47/48 分母宣布全题正确。Q10 四条弃答支持不纳入 68 槽。

- Q4 四组 E7 均为语义 `SUPPORTED`、来源 `NOT_COVERED`；E8 均由 C1/C2 覆盖。[PR #51](https://github.com/RyderChang/DevMate/pull/51) 的新候选方案不能回写这些原样本。
- baseline Q6/E11 为 `CONTRADICTED`，虽然 C1 覆盖 E11；有充分来源不代表答案表述正确，不能将原矛盾改成笔误或通过。
- Q9 仅 example 成功：E16/C2、E17/C4 都覆盖，历史核心与额外事实汇总为 `SUPPORTED`。没有逐项额外断言清单，因此额外事实的数量、具体对应及就近绑定仍未知。
- 只有 baseline/Q1 和 example/Q6 的 `inlineCitationIds` 非空；另 28 条成功记录为空。交集非空不能推出 `LOCAL`；空标记也不能在缺少正文时自动判为 `GLOBAL_ONLY`，因为未保留其他表达及位置。

## 全断言评审可用性

| 对象                        | 当前可用材料                                          | 本轮可给出的结论                                                        |
| --------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------- |
| 30 条结构通过回答的核心要求 | 48 个 E 槽固定语义、来源映射与标记交集                | 历史核心判定/覆盖可保留；实际原子断言清单及逐项绑定不可恢复             |
| 30 条成功回答的额外事实     | Q9/example 有一条汇总 SUPPORTED；其他 29 条为 UNKNOWN | 30 条均不能得到完整逐项额外断言、引用对应和绑定；汇总支持不等于逐项完成 |
| 十条结构失败                | JSON_SYNTAX ×4、JSON_SHAPE ×6、usage                  | 无合法 parsed answer 或 collection 可用于本轮评审；保持未评审           |
| Q10 四条弃答                | 固定弃答 SUPPORTED                                    | 保留核心弃答；不能确认回答没有夹带无依据的额外事实                      |
| 完整断言数、遗漏、引用精度  | 未保存正文与断言区间                                  | UNKNOWN；断言数为未知，不能记 0，也不能以 68 个 E 槽代替                |
| 所有题组的逐断言就近绑定    | 已采集 48 个核心槽均 UNKNOWN；其余未采集              | 原状态不变，全题绑定没有完成证据                                        |

完整逐断言评审样本为 **0/40**，表示没有可完成本规范全文评审的原样本；这不是 40 份答案均错误的评分。结构失败 10 条、正文未保留的成功样本 30 条分别保留，所有断言支持率、局部充分引用率与额外事实数量都不能计算。

DEV-029 的 Q1 未知字段、Q2 incomplete 与其他流程状态、Q4 旧活动代前提、Q5 多余引用、Q10 默认关闭范围等旧案例，只用于流程中的负例与优先核查提示。它们不是 DEV-032 的新增判定；不能将不同轮正文、旧结论或人的记忆补成原 40 份答案。

## 首阶段验证、完成状态与剩余条件

实际执行：

```powershell
python -B tmp/dev-034/audit_materials.py
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

首阶段临时 Python 审查：`PASS`，七源、50 候选、108 个返回引用及 48 个核心槽字段一致；40 条 usage 合计 156015 tokens（四组分别 38964、38711、39343、38997），没有新模型调用。语义不重新评分，原账单仍为所有者确认的 ¥0.20 / 40 次 / 156015 tokens。文档检查：83 份 Markdown、376 个相对链接及改动格式通过；`git diff --check` 通过。当时没有运行后端、前端或数据库套件，也未实现或运行全断言工具测试；[PR #52 的 Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/38139524744)前后端通过。

已完成：独立任务边界、正文不落盘规范、全题材料可用性审查、历史字段一致性与逐题矩阵。**未完成：原回答的全断言拆分、额外事实逐项评估、引用对应与就近绑定。**阻断原因是原正文不存在，不能靠现有脱敏字段补回。

新轮输出是新样本，不能解除原 DEV-032 的 UNKNOWN 或补改其账单/统计。当前没有新轮授权，不启动调用；不能进入生产输出约束采纳决策，也不自动合并、部署。草稿 PR 的最终合并由所有者确认。

## 继续阶段：瞬时工具与实际离线验证

所有者要求继续后，使用符合工程工具命名的分支承接首阶段文档，仍只处理 DEV-034。[工具说明](../../scripts/rag-claim-review/README.md)列出入口与后续调用适配要求。

- `review.py`：固定上下文与凭据白名单，分离整体事实支持、校核证据、直接来源覆盖、逐引用对应及绑定；只保存编号/区间和判定。提交前及保存前都校验，未知键、非法枚举、ID、坐标或不完整全文检查拒绝。
- `window.py`：本机 tkinter 只读正文/来源，选择区间登记核心、额外和弃答断言；手动确认遗漏及所有返回引用。无复制、备注、导出或截图入口；中断丢弃全部未提交选择，异常不打印正文或 traceback。
- `run.py`：默认只做公开源预检，`--demo` 仅合成演示；没有网络模型客户端、密钥参数或 live 模式。内存接口必须由后续适配层提供同一请求的已验证答案/来源与授权账本，当前没有接入真实调用。
- `frozen-inputs.json`：去掉原夹具中的 point/document/processing/index ID 和 score，只保留公开问题、S 来源、50 个候选定位与 E1–E17。SHA-256 `2ff08dc4060f74fe18cc10112b9988e3fa4bf2c11439699407b65ba3b2f5a506`；保留原 basis scope SHA，明确是新的评审输入 scope，不冒称原 DEV-032 夹具字节相同。来源与原五候选不变，没有应用 PR #51 的新选择。
- Foundation CI 增加 Linux Xvfb 窗口测试和公开源预检；不调用模型、不跳过窗口测试、没有新增生产依赖或数据库变更。首轮 [CI](https://github.com/RyderChang/DevMate/actions/runs/38144359794) 的窗口测试因 PATH Python 缺少 tkinter 而失败，随后显式安装仅 CI 测试所需的系统 `python3-tk`，并使用 `/usr/bin/python3`；没有降低断言。

实际执行：

```powershell
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
python -B scripts/rag-claim-review/run.py
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

Windows Python（本机 miniconda，TEMP/TMP 指向忽略目录 `tmp/dev-034/test-temp`）：**31 项通过**，含 24 项凭据/异常/文件边界与七项隐藏 Tk 窗口测试。覆盖序列化/终端/临时文件正文哨兵、任意字段与 ID、布尔/坐标、冻结漂移、跨样本引用成员、多断言与矛盾选择保留、核心遗漏、正确弃答与额外不足分离、引用位置和支持分离、完整性、用量保留、原子保存失败、真实 Tk 事件循环提交/异常及 Unicode 区间。测试使用合成人工选择，不自动判定语义，也不证明评审者不会漏断言或误判。未进行可见窗口的人工视觉验收，不把隐藏窗口测试冒称视觉验收。

默认预检：**PASS**，七份冻结 Git 源及 50 片段 SHA、十题四组均通过，**付费请求 0 次**。文档检查：84 份 Markdown、382 个相对链接及改动格式通过；工作流/JSON 的 Prettier 和 Git 空白检查通过。最新 Foundation 结果以工具 PR 当前头为准。

应用层不落盘与日志边界通过合成测试；不承诺操作系统换页、第三方录屏或物理擦除。保存完成后原文销毁，凭据不能让他人恢复断言；需要独立复核时须在同一内存生命周期中另行安排。目前工具为单评审者窗口，没有实现双人并行评审。

上述为首轮离线工具验收时的状态；随后调用适配验证见下节。原 40 份回答依旧不可恢复，完整评审仍为 0/40。没有进入生产输出约束采纳。回滚撤回工具、测试桥、离线 CI 步骤与本任务文档，不改原文件、账单或生产行为。

## 2026-10-12：离线调用适配与合成可见窗口

本轮复用 `chore/dev-034-transient-claim-review` 与草稿 [PR #53](https://github.com/RyderChang/DevMate/pull/53)，开始 HEAD 为 `c67861d4453023ff5875264079a68c5915e23dff`。中断前改动保留并续做；#50/#51 仍为未合并草稿，#52 关闭但未合并。开始头的 [Foundation CI](https://github.com/RyderChang/DevMate/actions/runs/38144859528) 成功不代表本轮新改动已经通过 CI；本轮当前头状态另行核验。

交付文件与行为：

- `adapter.py`：限定 OFFLINE_STUB 授权与 Stub 类型，按固定题组/scope 预留次数和模拟费用；发送前保存未知账本/凭据，同题组不可补发，同目录同 round 不可恢复。返回内存 Ticket 的对象身份及 round/attempt/题组/scope/候选全部逐项核对，之后接入同响应 usage 与生产校验结果。实际 C 顺序/数量与每个 source/chunk SHA、ordinal、字符/行定位和类型必须精确匹配，拒绝跨请求或候选混用。
- `ClaimReviewValidationBridge.java`：测试源中的内存管道桥直接复用生产 `RagOutputValidator`；生产代码、JSON 合同及模板未变，Python 没有另一套输出校验。正文只通过被捕获的 stdin/stdout 管道流转，不进入 argv、终端或文件；stderr/异常不回显，禁止 JVM 核心转储。
- `review.py`、`window.py`、`run.py`：固定题干也须匹配；新增 OFFLINE_STUB 模式与合成 reviewer，窗口显示题组/mode/scope/sample；新增 `--stub-demo` 合成全链路入口，默认仍预检，没有网络入口或密钥参数。
- `test_adapter.py` 与 Foundation 工作流：增加真实 Java 校验器 → Stub 配对/账本 → Tk → 脱敏凭据的回归。测试移至 Backend 完整验证之后，复用已编译类并生成 classpath，仍用系统 Python/Tk/Xvfb；没有新增生产依赖或数据库改动。

实际执行（JDK 21 路径为本机安装）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
./devmate-server/mvnw.cmd -B -o -f devmate-server/pom.xml '-Dtest=RagOutputDiagnosticTest' test dependency:build-classpath '-Dmdep.outputFile=../tmp/dev-034/validator-classpath.txt'
New-Item -ItemType Directory -Force tmp/dev-034/test-temp | Out-Null
$env:TEMP = (Resolve-Path tmp/dev-034/test-temp).Path
$env:TMP = $env:TEMP
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
python -B scripts/rag-claim-review/run.py
python -B scripts/rag-claim-review/run.py --stub-demo
```

Windows **49 项测试通过，零跳过**：原 31 项加 18 项适配回归。生产 Java `RagOutputDiagnosticTest` **2 项通过，零失败/错误/跳过**，离线 Maven 构建成功。八类生产结构失败、所有请求/引用定位字段错配、复制 Ticket、篡改 Ticket、布尔定位、异题候选、题干漂移、非法授权、次数/费用耗尽、已完成同题重发、排他 round 创建、发送前未知预留、失败/未知 usage、超预留、回包保存失败、窗口/传输中断、桥故障及非 Stub 类型均覆盖。正文/源摘录与异常哨兵的 stdout、stderr、日志及临时文件检查通过，调用结束 Stub/Session 内容引用清理通过。该结果不证明跨机器真实授权治理、真实提供商响应或人工语义质量。

默认预检 **PASS**：七源、50 候选 SHA、十题四组不变，`paidRequests=0`。本轮只读重算原脱敏结果 SHA 仍为 `ba267e58263b1de8efa7b9f8656ac6045c0a8a0804b0a857a880c803e727ef59`，评审 manifest SHA 仍为 `2ff08dc4060f74fe18cc10112b9988e3fa4bf2c11439699407b65ba3b2f5a506`；没有使用 #51 的 Q4 新候选，也未重新评判任何历史样本。

`node scripts/check-docs.mjs --format-changed origin/develop`：84 份 Markdown、383 个相对链接及改动格式通过；工作流 Prettier 和 `git diff --check` 通过。本机未运行后端/前端/数据库完整套件，完整 Foundation 回归由推送后 CI 核验，不用开始头 CI 的成功替代新提交结果。

可见验收由代理使用 computer-use 完成，所有者尚未执行真人语义验收：`--stub-demo` 的合成 Q1/baseline 经过生产校验桥显示于本机可见 Tk 窗口，OFFLINE_STUB/题组/scope/sample、全文与行号、五个 C 对应区间、两行全引用相关性、核心与全篇检查及提交/中断控件均完整可见。实际拖选合成答案，添加后显示半开 Unicode 定位 `[0,10)`；未完成提交显示 `INCOMPLETE_REVIEW`，有选区草稿时显示 `UNSAVED_DRAFT`。点击中断后进程退出且窗口消失；凭据为 `INTERRUPTED`、`claimCount=null`、`claims=[]`，保留模拟 usage 10/5/15 与 1000 micros 预留，账本停止且不可再发。只查看合成正文/公开源，没有截图真实正文，也不将可见检查称为真实答案已评审。

本轮新增真实 Embedding/Chat **0 次**。原账单仍为所有者确认 **¥0.20、40 次、156015 tokens**；原完整评审 **0/40**，所有历史 `claimCitationBinding` **UNKNOWN**。新增可审查的新轮提议写在任务书中，尚未授权/执行：最多 40 Chat、0 Embedding、总上限 ¥2.00，外发限定每题公开题干/原五候选/固定模板。剩余是新真实驱动与信封/usage 配对的审查、精确 payload/正式价格/总预留冻结、所有者独立授权及本机人工评审安排；本次不实现真实调用或生产采纳、不合并或部署。

## 2026-10-12：提供商信封离线回归与冻结计划

复用干净起点 dbe2a25 与 #53。所有者确认仅将离线计划模型改为 deepseek-flash、关闭思考；新增真实 Embedding/Chat 仍为 0，没有付费授权、真人语义评审或生产采纳。最新[冻结计划](../development/claim-citation-new-round-plan.md)和机器计划的完整 scope 为 `c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750`，40 个精确 payload SHA、四组配置、固定输出 1024 与逐次/总预留均可复算。官方人民币高峰/缓存未命中报价已核验；¥1.397304 是字节加 framing 假设下的预留，输入上界尚未得到提供商合同证明，机器状态为 BLOCKED_INPUT_BOUND_PROOF，不能直接启动。

新增文件：生产 v1 请求构建测试桥、公开完整请求元数据夹具、plan.py/new-round-plan.json、provider.py/test_provider.py 和新轮计划文档。adapter.py 增加同内存信封、payload/plan SHA 与 usage 交接；Foundation 增加冻结计划预检。仍严格限定精确 Stub 类型，不添加 HTTP 客户端、凭据读取或 live 参数；生产源码、Schema、依赖、数据库及默认配置未改。

实际验证：

- JDK 21 的离线 Maven：`./devmate-server/mvnw.cmd -B -o -f devmate-server/pom.xml '-Dtest=RagOutputDiagnosticTest' test dependency:build-classpath '-Dmdep.outputFile=../tmp/dev-034/validator-classpath.txt'`，新请求桥编译成功，生产诊断 2 项通过，零失败/错误/跳过。
- 本机 PATH 的 conda python.exe 无法找到真实位置，改用已配置的 bundled Python 3.12.14（含 Tk），没有安装依赖或升级版本。默认临时目录在 Windows 沙箱中不可写，最初测试因此失败；设置 TEMP/TMP 为仓库忽略目录 tmp/dev-034/test-temp 后重跑。命令为该 Python 执行 `-B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'`，最终 67 项通过，零失败/错误/跳过（原 49 + 新 18 项）；最后运行耗时 80.824 秒。
- 新回归覆盖：40 个请求重算、跨 Java 进程稳定规范序列化、四组 v1 配置、plan/题干漂移、发送前未知计划绑定、同信封答案/usage/实际 C2,C1 顺序、复制/篡改 Ticket 与替换信封、模型/finish/role/content/工具/隐藏思考/缓存/choice 错误、缺失/非法 usage、输入/输出超界保留 usage、生产 JSON_SYNTAX、非法编码/大小/重复键/流式数据、正文/来源/异常不进入终端和凭据。
- 原评审 manifest、完整公开请求夹具及本机原结果 SHA 只读重算，与交接的三个 SHA 完全一致；历史文件不改写，原 0/40、全部 UNKNOWN、账单 ¥0.20 / 40 次 / 156015 tokens 均保持。不读取或展示历史正文，不应用 #51。

没有新增 UI 控件，此轮不重新截图窗口；原可见验收仍仅是代理执行的合成测试，不能作为真人评审。完整 Foundation 回归以本轮发布后新头 CI 为准。未完成：输入费用上界证明、独立真实授权/传输治理、本机不录制环境确认与所有者逐断言评审。合并、部署与自动启动新轮均未实施。

本轮默认公开源预检 PASS、paidRequests=0；完整计划预检精确复算 40 个 payload 并返回上述 scope、NOT_GRANTED、paidRequests=0。`node scripts/check-docs.mjs --format-changed origin/develop` 检查 85 份 Markdown、390 个相对链接及格式通过；冻结 JSON/工作流 Prettier 与 Git 空白检查通过。
