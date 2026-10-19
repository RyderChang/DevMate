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

## 2026-10-12：费用证据与关闭执行准备

从干净 9673b39 继续同分支/#53。所有者的一般继续指令仅用于离线下一步，真实模型调用 0 次。固定官方 V4.1 修订四份公开 JSON/参考源码已在本机下载并逐 SHA 核对，仅按 JSON/AST 数据读取，未执行源码、安装 tokenizer、下载模型权重或运行模型。已确认参考 256 字节 BPE 覆盖、空 normalizer、无额外 BOS/EOS、1048576 上下文与 80 字节非思考文本前缀；托管 API 等价性及 JSON mode framing 仍 UNPROVEN。证据规范 SHA 为 a7bdc556b1ae31ecbae157182e5d54e9510cf85ba10e40330b4ac85b38578173；完整[执行准备](../development/claim-citation-execution-preparation.md)明确本机来源复现与 CI 固定摘要验证的区别。

原估算计划、40 个 payload/组序、模型/v1/1024 输出、40 Chat / 0 Embedding、拟定 ¥2.00 cap 均未变。按官方完整上下文回退，每次保守预留 ¥2.105344、40 次 ¥84.213760，缺口 ¥82.213760；未增加预算、减题或用折扣填补。新 preparation SHA b11957ebaedeb61e4383fa7bf634be923d354cdfcec07f798a6bfa878f9efd7b，保持 BLOCKED_BUDGET_AND_AUTHORIZATION/NOT_GRANTED/liveEnabled=false。

新增 execution.py/token_evidence.py、两份证据/审查 JSON、test_execution.py、执行准备说明；更新任务书、原计划说明、工具 README、验收及 CI。live 门禁在网络/密钥/次数前固定拒绝；HTTP 只通过精确 MemoryHttpStub 模拟，没有 HTTPS 客户端或有效真实授权，也不替代已有排他 Stub 账本或宣称真实 fsync/跨进程锁已完成。真人与录制环境仍待所有者确认。

实际验证：

- bundled Python 3.12.14、JAVA_HOME=本机 JDK21、TEMP/TMP=仓库忽略目录：`-B -m unittest discover -s scripts/rag-claim-review -p 'test_execution.py' -v`，19 项通过。首次一项测试把内层 JSON 的转义串直接与 Unicode 正文比较而失败，改为测试解析合成内层 answer 的精确值后通过；没有修改生产 JSON 校验或降低断言。
- 新回归覆盖：40 次上下文保守费用/原 cap 与 payload 保持、证据 provenance/hash、输入边界及参考 profile 负例、环境状态不代签、默认 live 与批准环境变量拒绝/零 socket 与密钥访问、摘要漂移、未知模拟保存确认、同对象配对/复制/篡改、3xx/错误体不读/不跟随、timeout/disconnect/interrupt 无重试、全调用 deadline、大小/编码/mediaType/UTF-8 拒绝、输入/输出/费用超界保留 usage、provider 失败保留 usage、非 Stub 类型拒绝、stdout/stderr 哨兵不泄漏。
- `-B scripts/rag-claim-review/token_evidence.py`：四个本机官方文件重算 SHA 与 profile 摘要完全一致，TOKEN_REFERENCE_PASS、hostedApiEquivalence=UNPROVEN、paidRequests=0。CI 不下载、不执行这四个文件，不宣称重算过完整官方 tokenizer。
- `-B scripts/rag-claim-review/execution.py`：新 preparation 完全复算，准确输出 reserve=84213760/cap=2000000/shortfall=82213760 micros，NOT_GRANTED、ownerEnvironment=UNCONFIRMED、paidRequests=0。

完整 Python 回归、公开源/原计划预检、文档/空白检查与本轮新头 CI 在提交前后分别核验，不沿用旧头成功结论。生产源码、模板、校验器、依赖、数据库与原历史文件不改；真人语义评审、生产采纳、付费请求、合并及部署未实施。

本轮全部 `test_*.py` 共 86 项通过，零失败/错误/跳过（原 67 + 新 19），耗时 83.087 秒。默认七源/50候选预检和原 40 payload 计划复算仍通过；执行准备预检成功复算预算不足并拒绝授权，不将审查成功解释为可启动。文档检查为 86 份 Markdown、399 个相对链接及格式通过；两份新增 JSON、工作流 Prettier 和 Git 空白检查通过。完整 Maven/Frontend/数据库验证由发布后的新头 CI 执行，本机复用原已编译生产校验桥，不宣称此轮重跑完整 Maven。

## 2026-10-13：关闭 HTTPS 实现与独立授权账本

从干净 `5d0bfa4` 继续同分支/草稿 #53；远端 develop 仍为 `1a6dab6`，前一头 CI 成功。新增 `https_driver.py`、`authorization.py`、`governed.py` 与两个测试文件；更新任务、README/导航、执行说明及本记录，新增[驱动和账本合同](../development/claim-citation-https-ledger.md)与 Proposed [ADR 0009](../adr/0009-local-transient-review-authorization-ledger.md)。现有 CI 的 `test_*.py` 自动包含新回归，无需改变生产配置或 CI 网络政策。

本轮只用内存 Stub/合成凭据，没有外部 DNS/TLS/HTTP 模型请求、密钥查找或正文截图。公开及底层连接入口在 TLS/DNS/socket/额度前固定拒绝；真实授权载体也不能创建账本。模拟账本使用完整保守预留作 rehearsal_cap，与真实 ¥2.00 cap 分开，不重新冻结或提高预算。两份旧冻结计划、证据及历史文件保持不变，¥2.00 托管上界未证明，¥84.213760 仅为冻结审查回退。真人完整计划/环境确认仍待所有者独立完成。

新增回归验证：固定端点/方法、证书/主机名/CA/TLS 最低版本、禁代理/重定向/重试、DNS 单地址、有界等待、写入/响应头/framing/正文/EOF 共用 120 秒 deadline、总线流/正文大小、类型/编码/长度冲突与截断、套接字及响应句柄生命周期；SQLite FULL 提交的 UNKNOWN 在 Stub 交接前可被另一连接读到、失败或提交确认丢失不发、结果保存失败保留 usage、进程排他、授权/round/题组唯一、预留后强制进程退出保留 UNKNOWN 且不能恢复、配对错乱不归入 usage、提供商/生产结构失败及超界保留合法 usage、线程重入/旧授权/非 Stub 拒绝、正文/payload/异常哨兵不进入 SQLite/journal 或终端。

贯通测试包含真实标准库 HTTPConnection/HTTPResponse 解析与未修改的生产 Java 校验器，但 IO 为内存 wire Stub；不代表真实服务端证书、网络可用性或费用验收。Windows 初次新增账本测试发现测试读连接未显式关闭及合成引用顺序预期错误，已关闭连接并按原返回 C2/C1 精确断言；未降低断言。贯通测试也发现 Connection: close 的套接字读取句柄需保留至文件关闭，已修复并回归。

本机命令使用 bundled Python 3.12.14（Tk）、JAVA_HOME=本机 JDK21、TEMP/TMP=仓库忽略目录。完整回归及固定源/计划预检、文档/空白检查在最终提交前核验；新头 CI 在发布后独立核对，不沿用旧头成功。Maven/Frontend/数据库完整验证由本轮新头 Foundation 执行，本机只复用已编译生产测试桥。局限为本机权威目录、文件锁/SQLite 合同，未验证物理断电、网络盘或多主机治理；新协调层不代签真人评审，返回内存内容需由调用者 clear。未合并、部署或启动真实新轮。

最终本机实际结果：`-B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'` 共 118 项通过（原 86 + 新 32），96.244 秒，零失败/错误/跳过；Tk 销毁后出现一次固定 ThemeChanged 诊断，无正文/凭据，测试退出 0。`run.py` 七源/50 候选预检 PASS；`plan.py` 原 40 payload/SHA 复算 PASS；`execution.py` 原 scope、84213760/2000000/82213760 micros 与 BLOCKED_BUDGET_AND_AUTHORIZATION/NOT_GRANTED/ownerEnvironment=UNCONFIRMED/paidRequests=0 保持。`node scripts/check-docs.mjs --format-changed origin/develop` 检查 88 份 Markdown、413 个相对链接及格式通过，`git diff --check` 通过。旧五份冻结 JSON 无 diff。本机未重跑完整 Maven/Frontend。

## 2026-10-13：追加托管费用证据与离线审查

所有者要求自行执行下一步；从干净 `0196def` 复用同分支和 #53，远端 develop `1a6dab6` 未变。官方价格页仍为 Flash 高峰输入未命中 ¥2/百万、输出 ¥8/百万；固定 recipe 六份文件与官方四份页面已只读取得并逐 SHA 记录，源码未运行、构建或 import。新增 `cost_contract.py`、`test_cost_contract.py`、两份来源/审查 JSON 与[费用合同说明](../development/claim-citation-hosted-cost-audit.md)，同步任务、README/导航、CI 与本记录。

发现参考 JSON 模式另追加 105 UTF-8 bytes，20 个组需计入该开销。同原 40 payload 的固定参考输入合计 474284 tokens，参考费用 ¥1.276248；托管 tokenizer 绑定、内容预处理及 JSON framing 仍 UNPROVEN，托管 input/reservation 仍 null。共享上下文候选算术为 ¥84.131840，仍超 ¥2.00；原冻结回退 ¥84.213760 不改。新审查 SHA `37e71d794d657379c732a6d186699651c0e86a963cecb259cdbc8dd08584d43d` 是证据摘要，不是新冻结授权；状态为 BLOCKED_HOSTED_BOUND/NOT_GRANTED/liveEnabled=false，环境仍 UNCONFIRMED。任何参考估算或低于预算的算术结果均不得自动升级为可发送的授权。

本机 bundled Python 3.12.14/Tk、JDK21、TEMP/TMP=仓库忽略目录，实际命令/结果：

- `-B -m unittest discover -s scripts/rag-claim-review -p 'test_cost_contract.py'`：新增 10 项通过；来源/形状/预算阈值/原 scope 与冻结回退、缺口/null 不升级及哨兵检查。随后增加数值闭合校验并运行完整套件。
- `-B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'`：**128 项通过**（原 118 + 新 10），121.934 秒，零失败/错误/跳过；原 Tk 销毁后的固定 ThemeChanged 诊断仍无正文/凭据，退出 0。
- `-B scripts/rag-claim-review/cost_contract.py --verify-sources`：十份本机官方来源 bytes/SHA 复现通过，sourceExecuted=false、hostedBound=UNPROVEN、paidRequests=0。CI 不重抓或重算这十份未提交全文。
- `-B scripts/rag-claim-review/cost_contract.py`：同 40 payload 完全复算，新审查与预算阈值 836160、参考 1276248、候选 84131840、旧回退 84213760 micros 一致，NOT_GRANTED/liveEnabled=false/UNCONFIRMED/paidRequests=0 保持。

官方用量页链接的 tokenizer ZIP 两次只读检查申请均因自动审批服务“所选模型容量不足”未执行；这是审批失败，不是安全否决。该包不在已验证的十份来源中；未绕过审批，不能宣称包内版本/合同已核验。恢复审批后才能补做此项，仍不获付费权限。原计划/执行审查/四文件证据/请求夹具/历史全部不改；无付费探测或向提供商发消息。本机未重跑完整 Maven/Frontend，完整套件与新头 CI 在提交前后分别核验。

`node scripts/check-docs.mjs --format-changed origin/develop`：89 Markdown、420 个相对链接与格式通过；新 JSON/工作流 Prettier、`git diff --check` 通过。旧冻结文件与生产实现无 diff。新头 CI 结果在 #53 记录，不以 0196def 的已通过结果代替本轮验证。

## 2026-10-13：恢复提交与官方 tokenizer ZIP 补查

所有者明确要求继续上述任务。开始工作区仍为原十个未提交文件，无额外用户改动，HEAD 与 #53 均为 0196def，草稿目标 develop。本次只读 ZIP 审批通过；从官方用量页当前链接取得 1911504-byte 包，SHA `e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495`。仅 ZIP/JSON/AST 数据审查，未执行、import 或安装包内演示/transformers，也未使用 trust_remote_code。

包内三个文件/一目录，配置 model_max_length=16384、LlamaTokenizerFast，无已审查 V4.1 System 标记、thinking/JSON 模式分支；tokenizer SHA `89085f...` 与固定 V4.1 发布文件不同。演示仅文本 encode。该配置不构成托管输入/上下文保证，不用于降低原回退或启用发送。独立摘要 SHA `74b9f3a369c63a370457919ebfe204a0d0aa52a367f4cb8adfe0537f577e84d5` 保留 UNPROVEN/NOT_GRANTED/liveEnabled=false；此前十份来源与费用审查 SHA 完全不变。

新增 `tokenizer_archive.py`、`test_tokenizer_archive.py` 与 `tokenizer-archive-review.json`，同步既有费用说明/任务/README/验收及 CI。`-B scripts/rag-claim-review/tokenizer_archive.py --replay-local` 实际复现包/三个成员 SHA 与 JSON/AST 观察通过，sourceExecuted=false、paidRequests=0；`-B -m unittest discover -s scripts/rag-claim-review -p 'test_tokenizer_archive.py'` 五项通过。CI 仅运行固定摘要及合成负例，不联网下载/运行 ZIP，也不冒称复现该未提交归档。原 16384 值不能替代托管 1048576/预算回退的回归通过。

最终本机完整 `-B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'`：**133 项通过**（原 118 + 费用审查 10 + ZIP 5），142.203 秒，零失败/错误/跳过。十份来源的 `cost_contract.py --verify-sources` 复现与默认费用/ZIP 审查、ZIP `--replay-local`、原 `execution.py` 均通过；三份新摘要及两份旧 scope 不变，NOT_GRANTED/UNCONFIRMED/paidRequests=0 保持。89 Markdown、422 相对链接及格式检查、新 JSON/工作流 Prettier 和 Git 空白检查通过。原五份冻结 JSON、生产源码和依赖无 diff。本机未重跑完整 Maven/Frontend，提交的新头 CI 独立核验后登记在 #53；不沿用原 0196def 的成功 CI。

## 2026-10-13：提供商计数/费用控制合同补查

从干净 `cec57ad` 继续当前分支和草稿 #53。所有者要求继续，补查官方 Chat、Anthropic 兼容、余额/错误、并发文档及 sitemap；五份官方 HTML/XML 只读下载并记录 SHA，其中 Chat SHA 与上轮相同，sitemap 解析为 52 URL，未见 count_tokens/tokenize 文档路径。并发文档直接由官方网页工具读取，未宣称保存该页快照或 CI 重抓。部分初次抓取超时及搜索无结果不作为功能不存在证据。

补查未取得冻结 Chat 的发送前保证计数/强输入/费用 cap；余额/402、并发/user_id、SDK 方法、被忽略的 budget_tokens 与客户端超时不能替代保证。新增[合同补查及证据验收条件](../development/claim-citation-provider-contract-followup.md)、[DRAFT/UNSENT 询证正文](../development/claim-citation-provider-inquiry-draft.md)，同步导航/任务/费用说明/工具 README/本记录，共七份 Markdown；没有改脚本、测试、配置、依赖、原冻结 JSON 或生产行为。未查询账户、尝试猜测端点、读取密钥、调用模型或向他人发送消息。

本阶段纯文档，执行格式/相对链接和 Git 空白检查，不重复未改变的本机 133 项回归。原 40 payload、¥2.00、¥84.213760 回退、NOT_GRANTED/UNCONFIRMED/真实发送关闭保持。上一头 133 项离线测试/Maven 271 项与前后端完整 CI 实际成功；本轮新头状态在发布后单独核验并登记 #53，不沿用旧头结论。继续到真实授权需要提供商有效合同或所有者明确预算/scope 决策，询证仍未发送。

实际 `node scripts/check-docs.mjs --format-changed origin/develop`：91 Markdown、435 相对链接与格式通过；`git diff --check` 通过。脚本/摘要、工作流、依赖及生产实现无 diff，不宣称本轮重跑未改变的本机套件。

## 2026-10-13：托管合同取证与计划再审查

从干净 `505d94e` 继续当前分支/#53。所有者选择直接提供官方书面答复后要求自行推进；目前未收到专项答复或新预算/范围。本轮只读重新取得官方价格、Chat、用量、错误码、并发和 sitemap 六份快照，bytes/SHA 及[证据逐项判定](../development/claim-citation-provider-contract-followup.md#2026-10-13托管合同取证与授权计划再审查)已记录。只解析 HTML/XML 数据，快照在忽略目录，不改前轮证据或提交全文。Git 网络读取曾连接失败，后经 GitHub API 成功核验 #53 为 OPEN/DRAFT、目标 develop、头 505d94e，develop 仍为 1a6dab6；不将失败读取当作核验成功。

公开目录仍为 52 URL，Chat 快照没有列出保证预计数/输入/费用 cap；本日高峰价未变，但价格页允许变价。完整托管 framing、失败/未知计费上界、期限与全 40 payload 适用性仍缺失。没有重新冻结授权计划或验收真实评审环境，询证仍未发送；付费、账户/计数/模型请求新增均为 0。¥84.213760 仍为保守回退，不能解释为预算批准。

本机 Python 3.13.5、已有生产 Java 校验桥，仅 Stub/离线验证。实际命令：

```powershell
Push-Location scripts/rag-claim-review
python -B -m unittest test_execution.ExecutionTest.test_live_gate_rejects_before_network_key_lookup_or_attempt test_execution.ExecutionTest.test_environment_is_owner_unconfirmed_not_promoted_by_synthetic_tests test_https_driver.HttpsDriverTest.test_public_entry_fails_before_key_dns_socket_or_connection test_https_driver.HttpsDriverTest.test_private_wire_also_cannot_construct_real_connection test_cost_contract.CostContractTest.test_reference_result_does_not_promote_hosted_proof_or_authorization
Pop-Location
python -B scripts/rag-claim-review/execution.py
python -B scripts/rag-claim-review/cost_contract.py
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

五项针对性回归通过，45.270 秒，零失败/错误/跳过：公开/底层 live 入口在密钥/DNS/socket 前拒绝、环境不由合成测试确认、参考费用不升级为托管证明/授权。`execution.py` 复算 preparation scope、84213760/2000000/82213760 micros、BLOCKED_BUDGET_AND_AUTHORIZATION/NOT_GRANTED/UNCONFIRMED/paidRequests=0；`cost_contract.py` 重建同 40 payload，保持 BLOCKED_HOSTED_BOUND、836160 阈值、1276248/84131840/84213760 micros 和 liveEnabled=false。Python 启动器有已有路径定位警告，两入口及测试均退出 0，不将警告当作跳过测试的理由。

本轮仅三份 Markdown 改动；生产源码、脚本/测试、冻结 JSON、依赖、工作流及历史资料无 diff。本机不重跑完整 Maven/Frontend 或 GUI；匹配文档的格式/相对链接与 Git 空白检查通过，新提交的完整 CI 在发布后独立核验并登记 #53。回滚仅撤回本轮三份文档新增段落，保留原证据/计划/账本。下一步仍待官方书面答复或所有者明确预算/scope；只说明，不自动启动付费执行。

## 2026-10-19：模型版本漂移与历史计划适用性

所有者要求确定并自行执行下一步。从干净 `4c9af14` 检查当前分支、任务/合同材料、#53 及最新官方资料；PR 仍为 OPEN/DRAFT、目标 develop、同一头，无 comments/reviews，develop 仍为 `1a6dab6`。原 4c9af14 的 CI 为 success，仅作为原提交历史结果，不冒称本轮新提交已通过。

本轮发现官方 2026-10-17 更新日志和当前价格页均将 `deepseek-flash` 对应版本列为 DeepSeek-V4.1-Flash-1017，公告称调用方式/价格不变。已只读下载七份官方快照到独立忽略目录，bytes/SHA 及[适用性判定](../development/claim-citation-provider-contract-followup.md#2026-10-19官方模型版本漂移与旧证据适用性)已记录，不覆盖前轮证据。XML 解析为 54 URL，较前轮增加 `/api/fetch` 和 `/news/news261017`；Chat 快照未列出四个被检查的预计数/cap 字段。没有运行网页脚本/下载代码、探测猜测端点或发起账户/计数/模型请求。

五份文档更新：在旧冻结计划和未发送询证草稿前标明版本适用性，补充合同漂移审查、任务及本验收记录。没有修改脚本/测试、冻结 JSON、scope、payload、原参考文件、1024 输出、拟定 ¥2.00、¥84.213760 历史回退或生产配置。旧预检仍可复现，但不在线检查模型路由，不能证明当前托管版本或新版费用上界；本轮未新增机器门禁状态或接受新版模型，没有重新冻结授权或代签环境，真实发送继续关闭。

实际验证使用 Python 3.13.5 和已有生产 Java 测试桥，仅合成 Stub：

```powershell
Push-Location scripts/rag-claim-review
python -B -m unittest test_cost_contract.CostContractTest.test_full_40_payload_regeneration_matches_review_and_both_old_scopes test_cost_contract.CostContractTest.test_reference_result_does_not_promote_hosted_proof_or_authorization test_execution.ExecutionTest.test_live_gate_rejects_before_network_key_lookup_or_attempt test_https_driver.HttpsDriverTest.test_public_entry_fails_before_key_dns_socket_or_connection test_https_driver.HttpsDriverTest.test_private_wire_also_cannot_construct_real_connection
Pop-Location
python -B scripts/rag-claim-review/execution.py
python -B scripts/rag-claim-review/cost_contract.py
node scripts/check-docs.mjs --format-changed origin/develop
git diff --check
```

五项回归通过，24.989 秒，零失败/错误/跳过；覆盖全部 40 payload 与两份旧 scope 的精确复现、参考证据不升级为授权，以及公开/底层发送入口先于密钥/DNS/socket 拒绝。`execution.py` 保持原 preparation SHA 与 84213760/2000000/82213760 micros、BLOCKED_BUDGET_AND_AUTHORIZATION/NOT_GRANTED/UNCONFIRMED/paidRequests=0；`cost_contract.py` 保持 BLOCKED_HOSTED_BOUND、836160 阈值、1276248/84131840/84213760 micros、liveEnabled=false 和 paidRequests=0。这些结果仅验证旧材料，不宣称实测或验证了 1017 模型。

文档检查首次因沙箱内 Node 无法派生 Git 而 EPERM；只读检查经正常提升后实际成功，未跳过、修改检查器或将失败当作通过。网络 CLI 首次受沙箱代理限制失败，提升后 GitHub 只读核验成功。格式/相对链接与 Git 空白检查通过；本机未重跑完整 Maven/Frontend/GUI，新头 CI 发布后单独核验并登记 #53。

所有者随后明确选择“接受 1017，仅继续离线取证”。继续只读取得官方 Hugging Face 模型库公开元数据（不含 Token），固定修订 `1bae0e1088993b9b82de08f59de5be5a34e42170`，下载 README/config/tokenizer/tokenizer_config 与 encoding README/源码六文件，未下载权重。全部 bytes/SHA 已记入合同说明；用 hashlib 与旧 token-evidence.json 比较，三个 config/tokenizer 文件 SHA 完全一致；JSON 的 text_config.max_position_embeddings=1048576，ast.parse 新 encoding/encoding.py 成功。只解析数据，未执行/import 下载源码、运行包内测试、调用推理或使用第三方部署。新参考的可追溯性增强，但不证明托管预处理/完整 JSON framing 或计费保证；没有更新旧机器 JSON/scope 或将历史费用升级为新版保证。

回滚仅撤回本次五份文档新增段落，不删除原证据/计划/账本。1017 离线待审范围已获确认；后续仍需适用费用合同和明确预算，之后才能重新冻结并独立审查授权交接与环境。本轮不发询证、不开启付费执行或实施其他任务。
