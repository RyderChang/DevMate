# DEV-032：脱敏采集与输出约束实验验收

日期：2026-10-11。基线 `2cfa244b7478bb0fab463aa94ddfb96899e42542`；独立分支 `fix/dev-032-rag-output-experiment`。范围与本轮已授权付费额度见 [任务书](../tasks/DEV-032-rag-output-constraint-experiment.md)。

## 已完成的修正与离线实验

新版采集将来源集合覆盖、内联标记交集、语义结论分开保存。回归样例中答案无任何内联标记，但返回 C2/C4 片段分别覆盖 E16/E17：两者均为 `COVERED`，标记交集为空，语义与逐断言绑定为 `UNKNOWN`。不存在来源信息时覆盖为 `UNKNOWN`；明确空引用列表才为 `NOT_COVERED`。错误 SHA、仅部分证据行均不能算覆盖；输出按白名单构造，答案和来源正文的哨兵值不进入记录。

已冻结原十题、七来源与 50 条候选定位。四组离线预检共构建 **40 份**有界请求，所有来源/候选/片段 SHA 与冻结材料相同，Java 构建器没有移除候选。请求和正文只在内存管道中处理，没有保存 40 份完整提示词。夹具 SHA-256：`5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26`。

四组共有同一生产网关响应解析与严格 RAG 校验：JSON 长度、重复键、语法、尾随、形状、answer、引用 ID、内联标记负例全部继续拒绝。空 content 仍为 `PROVIDER_RESPONSE/MESSAGE_CONTENT`，截断为 `FINISH_REASON`；结构失败仍保留已经解析的 usage。这些 Stub 结果不证明真实模型的结构成功率或输出质量提升。

## 实际执行

选定 `JAVA_HOME=C:/Program Files/Java/jdk-21` 后：

```powershell
python -B -m unittest discover -s scripts/rag-output-experiment -p 'test_*.py'
devmate-server/mvnw.cmd -B -ntp -f devmate-server/pom.xml '-Dtest=RagOutputExperimentTest,RagPromptAndOutputTest,RagOutputDiagnosticTest,DeepSeekChatGatewayTest' test dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=../tmp/dev-032/test-classpath.txt'
python -B scripts/rag-output-experiment/run.py
```

- Python：**11 项通过**，覆盖来源判定、缺失/空值、固定语义枚举、正文不落盘、无重试、错误体不读取、响应/请求大小、未知费用预留及冻结文件漂移。Windows 沙箱临时目录首次阻断一个测试，获命令批准后在沙箱外重跑 11 项全部通过；CI 使用标准 Linux 临时目录。
- Java 21：**20 项通过**，失败/错误/跳过均 0。修正 Windows 管道输出编码后又执行新增 4 项，均通过；中文请求由 UTF-8 字节管道传输。
- 预检：`PASS`，十题、四组、40 份有界请求，按实际序列化字节及高峰单价累计预留 **¥1.397304**，**付费请求 0 次**。
- `node scripts/check-docs.mjs --format-changed origin/develop`：77 份 Markdown、297 个相对链接及本任务文档格式通过；`git diff --check` 通过。

CI 增加 Python 脱敏工具测试，Java 测试随原 Maven 套件执行；没有新依赖。无数据库或生产代码变更，未运行 MySQL 集成或全仓后端套件，不把已有数据库验收当作本轮运行结果。

## 已完成的真实轮

所有者于 2026-10-11 明确授权最多 40 次、费用不超过 ¥2，并确认任务书指定的外发范围。本轮 **40 次全部完成，无重试、补发、网络未知或 HTTP 失败**；30 条通过结构/引用校验的回答均由所有者在本机窗口提交固定判定。10 条结构失败没有答案语义采集，不能算作正确。密钥由所有者隐藏输入；工具没有将密钥、完整提示词、回答或来源正文写入终端、结果文件或 Git。历史 DEV-031 第 2、4 次逐项引用和全部语义仍不可恢复；本轮没有追加第六次 DEV-031 请求。

运行 ID 为 `338d4aaea73244e4980a71d33fc46076`。最终脱敏结果位于忽略目录 `tmp/dev-032/results-338d4aaea73244e4980a71d33fc46076.json`，文件 SHA-256 为 `ba267e58263b1de8efa7b9f8656ac6045c0a8a0804b0a857a880c803e727ef59`；只提交本验收摘要，不提交结果文件或旧缓存。先前 13:37 的首条进度快照已被本轮完整结果替代，不能继续当作当前状态。

### 结构、核心语义与来源覆盖

`SUCCEEDED` 只表示生产网关解析和严格 RAG 结构/引用校验通过，不表示全部事实正确。语义按冻结 E1–E17 的核心结论判断，额外事实只在 Q9 单独采集；其他题的额外断言仍待独立评审。核心语义与来源覆盖分别计数。

| 组          | 结构通过/尝试 | 失败类别频数                  | 核心支持/已判定 | 核心支持/全部 17 槽 | 未采集核心槽 | 来源覆盖/已采集槽 |
| ----------- | ------------- | ----------------------------- | --------------- | ------------------- | ------------ | ----------------- |
| A baseline  | 8/10          | JSON_SHAPE ×2                 | 12/13           | 12/17               | 4            | 12/13             |
| B example   | 7/10          | JSON_SYNTAX ×2；JSON_SHAPE ×1 | 11/11           | 11/17               | 6            | 10/11             |
| C json_mode | 6/10          | JSON_SYNTAX ×2；JSON_SHAPE ×2 | 9/9             | 9/17                | 8            | 8/9               |
| D combined  | 9/10          | JSON_SHAPE ×1                 | 15/15           | 15/17               | 2            | 14/15             |

四组预期共 68 个核心槽，48 个已判定，47 个 `SUPPORTED`、1 个 `CONTRADICTED`；20 个因结构失败未采集，不从分母剔除后宣称全题正确。唯一核心矛盾在 A/Q6/E11：关于自动重发的直接回答与后文和冻结证据冲突，未擅自作为笔误修正。结构校验通过因此不能代替语义判定。

以下逐题表仅表示结构/引用校验结果；`PASS` 不表示完整答案事实通过。

| 题  | A baseline | B example   | C json_mode | D combined |
| --- | ---------- | ----------- | ----------- | ---------- |
| Q1  | PASS       | PASS        | PASS        | PASS       |
| Q2  | PASS       | JSON_SYNTAX | JSON_SYNTAX | PASS       |
| Q3  | PASS       | PASS        | PASS        | PASS       |
| Q4  | PASS       | PASS        | PASS        | PASS       |
| Q5  | PASS       | PASS        | PASS        | PASS       |
| Q6  | PASS       | PASS        | JSON_SHAPE  | PASS       |
| Q7  | PASS       | JSON_SYNTAX | PASS        | PASS       |
| Q8  | JSON_SHAPE | JSON_SHAPE  | JSON_SYNTAX | PASS       |
| Q9  | JSON_SHAPE | PASS        | JSON_SHAPE  | JSON_SHAPE |
| Q10 | PASS       | PASS        | PASS        | PASS       |

### Q4、Q9 与弃答

- **Q4**：四组 E7 核心语义均为 `SUPPORTED`，但 E7 来源覆盖均为 `NOT_COVERED`，返回候选/引用没有直接覆盖冻结证据行；E8 均由 C1/C2 覆盖。语义正确没有修复 Q4 的排序缺口。本轮不改候选、topK、查询或排序。
- **Q9**：仅 B 通过；E16 由 C2、E17 由 C4 完整覆盖，核心语义和额外事实均为 `SUPPORTED`。两槽的 `inlineEvidenceIds=[]`：工具只统计 `[Cn]` 格式标记；空交集没有被误判为缺失来源。A/C/D 的 `JSON_SHAPE` 没有来源或语义采集，均应理解为未知，不能记为“不覆盖”或“语义错误”。
- **Q10**：四组均提交 `abstentionVerdict=SUPPORTED`，明确无法确定生产向量点总数，没有编造数值；这一槽不纳入 E1–E17 分母。
- 所有已采集核心槽的 `claimCitationBinding=UNKNOWN`，Q9 的额外事实支持也不证明逐断言就近绑定。其他题存在需要独立核验的额外范围推断，不把核心槽通过当作全答案通过。

### 用量、耗时与费用

下表包含成功和失败样本，每组 10 条均有 usage。`durationMs` 从真实请求开始到响应评估完成，排除人工核验窗口等待；仍包含网络与本机桥接处理，不能当作模型纯推理耗时。

| 组          | 输入 tokens | 输出 tokens | 总 tokens | 平均/中位耗时 ms | 最小–最大 ms | 高峰价保守估费 ¥ |
| ----------- | ----------- | ----------- | --------- | ---------------- | ------------ | ---------------- |
| A baseline  | 33783       | 5181        | 38964     | 4142.8 / 4353.5  | 2274–5227    | 0.109014         |
| B example   | 34613       | 4098        | 38711     | 3693.1 / 3462    | 2783–5704    | 0.102010         |
| C json_mode | 34008       | 5335        | 39343     | 4276.3 / 4337    | 3386–5266    | 0.110696         |
| D combined  | 34848       | 4149        | 38997     | 3901.8 / 4185    | 2456–5305    | 0.102888         |

合计输入 **137252**、输出 **18763**、总 **156015 tokens**。按全部输入未命中高峰价 ¥2/百万及输出 ¥8/百万计算，保守估费合计 **¥0.424608**，低于授权 ¥2。每次发送前的最坏额度预留也未突破预算。该估计不作为实际账单金额。

所有者于 2026-10-11 在本会话补充终端结束输出，确认 Q10 最后一条已保存。首次账单快照为消费 ¥0.18 CNY、34 次请求、133227 tokens，与执行记录不一致；随后所有者再次刷新并报告 **消费 ¥0.20 CNY、40 次请求、156015 tokens**。刷新后的请求数与总 tokens 均与本轮完整执行记录一致，按所有者报告确认本轮实际消费为 **¥0.20**，低于授权 ¥2。未直接访问账户或取得逐请求费用，不能分摊四组真实费用。

| 统计来源           | 请求数 | 总 tokens | 金额                         |
| ------------------ | ------ | --------- | ---------------------------- |
| 本轮最终脱敏记录   | 40     | 156015    | 高峰价保守估费 ¥0.424608     |
| 所有者首次账单快照 | 34     | 133227    | 页面显示消费 ¥0.18           |
| 所有者刷新后的账单 | 40     | 156015    | 实际消费 ¥0.20（所有者报告） |

首次快照少 6 次请求、22788 tokens，已由刷新后完整统计替代，不将 ¥0.18 当作全轮费用；未核实首次差异产生的具体原因。原始脱敏执行结果文件保持不变，保留上述 SHA；其中 `actualChargeCny=null`、`actualChargeStatus=ACCOUNT_BILLING_REQUIRES_REVIEW` 描述采集结束时尚未获得账单的状态。最终账单补充以本验收记录中的所有者报告为准：**¥0.20 / 40 次 / 156015 tokens，计数对齐**。不为对账新增真实请求，不把估费与实际金额的差值归因为未经核实的缓存或时段折扣。

### 实验结论与剩余事项

D 本轮结构通过数最高（9/10），但仍在 Q9 出现 `JSON_SHAPE`；B/C 的语法失败也说明示例或 JSON 参数不能代替严格校验。每题每组只有一次，未做重复轮或统计显著性分析；不能宣称稳定、因果提升、参数效果独立可加或直接选定生产方案。C 未补齐官方推荐的示例条件，属于参数诊断对照，不能据此泛化官方完整 JSON 用法的效果。

脱敏采集修正、本轮真实实验及所有者报告的账单总量核对已完成。未完成的是独立安排的 Q4 排序、全题逐断言引用和生产模板采纳决策；未取得四组逐请求实际费用。本轮不实施这些后续工作，不改生产默认值，不自动合并或部署。

## PR #50 收口复核

2026-10-11 在原任务分支继续收口，工作区初始干净；远端 `develop` 仍为上述基线。PR #50 保持草稿、目标为 `develop`。前置 PR #49 仍未合并；#50 不依赖其代码或本地文档，前置记录使用 GitHub PR 链接，不合并该分支。

审查发现采集入口依赖上游校验合法 ID，自身会接受带任意文本的引用 ID 或 `[C…]` 标记；核心语义枚举还多接受 `NOT_APPLICABLE`，与任务书合同不一致。先新增负例复现，再收紧到冻结 `C1`–`C5` 白名单和四种核心语义判定。异常只含固定文字，不回显非法值。生产网关和 RAG 校验器不变，本轮已采集结果中的 ID 与判定均合法，不改写原始文件、SHA 或实验统计。

实际复核：

- `python -B -m unittest discover -s scripts/rag-output-experiment -p 'test_*.py'`：**13 项通过**；包括新增的非法 ID/内联标记及核心 `NOT_APPLICABLE` 回归。Windows 本轮将 `TEMP`/`TMP` 指向忽略目录 `tmp/dev-032/test-temp`，在沙箱内通过。
- 按上文 Java 21 Maven 命令重跑四个测试类并生成 classpath：**20 项通过**，失败/错误/跳过均 0。
- `python -B scripts/rag-output-experiment/run.py`：仅离线预检；40 份有界请求、冻结 SHA 核对通过，费用预留 **¥1.397304**，**付费请求 0 次**。
- 本机只读重新计算原结果 SHA 与四组摘要，核对 40 个唯一请求/题组、完整 usage、结构类别、核心语义与来源覆盖计数、耗时统计，均与本记录一致。用已保存的 ID、来源与固定判定重建 30 条合法采集记录，各采集字段完全一致；所有逐断言绑定仍为 `UNKNOWN`。结果对象无 `answer`、`text`、`content` 等正文字段；这不等于已核验全部事实或逐断言绑定。
- `node scripts/check-docs.mjs --format-changed origin/develop`：77 份 Markdown、297 个相对链接及本任务文档格式通过；工作流和冻结 JSON 的 Prettier 检查、`git diff --check` 通过。

收口没有新增真实调用，未实施 Q4 排序、全题逐断言核验或生产方案采纳。最终 CI 须以 PR 当前头提交对应的 Foundation 运行结果为准；合并由所有者确认。修复仅影响实验采集器，回滚可撤回本次收口提交，不涉及数据库、生产配置或实验账单。

## 本轮启动方式（额度已用完）

以下记录本轮使用的命令结构，**40 次额度已经用完，不得重复启动、删除启动锁或沿用本轮授权追加请求**。任何新真实轮仍须单独明确次数、预算和外发范围。运行日核对官方高峰单价未上涨，并填写实际核价日期。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
python -B scripts/rag-output-experiment/run.py --live --approved-requests 40 --approved-cny 2 --approved-scope-sha256 5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26 --price-checked YYYY-MM-DD
```

运行前已编译测试类、生成 classpath，并保留七份冻结源与历史显式检索缓存；GUI 使用 Python 自带 tkinter。工具在首个付费请求前核验窗口可用。结果写入忽略目录 `tmp/dev-032/results-<runId>.json`，不提交该文件或原始缓存。
