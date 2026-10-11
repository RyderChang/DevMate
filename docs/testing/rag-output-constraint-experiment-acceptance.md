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

## 已授权的真实轮

所有者于 2026-10-11 明确授权最多 40 次、费用不超过 ¥2，并确认任务书指定的外发范围。本机交互入口已准备；密钥由所有者隐藏输入，不通过对话或文件传递。四组真实成功率、语义正确率、E16/E17 实测覆盖、token 对比和账单结果以本轮脱敏结果补记，不能用合成样例或预检代替。历史 DEV-031 第 2、4 次逐项引用和全部语义仍不可恢复；本轮也没有追加第六次 DEV-031 请求。

2026-10-11 13:37（北京时间）进度快照：本轮运行 ID `338d4aaea73244e4980a71d33fc46076`，脱敏结果位于忽略目录 `tmp/dev-032/results-338d4aaea73244e4980a71d33fc46076.json`。已记录一次 Q1 `baseline` 请求，UUID `a1dfc46a-8c3d-4172-83f6-13055d55a5a8`，结构/引用校验 `SUCCEEDED`，输入/输出/总 tokens 为 **3213 / 573 / 3786**，耗时 **4312 ms**，高峰价保守估费 **¥0.01101**，账单金额仍为 `null`。E1/E2 来源覆盖均为 `COVERED`，对应 C1；核心语义和逐断言绑定均为 `UNKNOWN`，尚无人工核验提交记录。其余三组及剩余题目尚无请求记录；这不是四组实验结论，也不能据此宣称改进。正在核对本机窗口是否仍等待核验，不启动第二个进程、不删除启动锁或重发首条请求。

本轮授权已满足。下面是已准备的本机交互命令，正在运行时不要重复启动。运行当日核对官方高峰单价未上涨，`YYYY-MM-DD` 替换为实际核价日期。工具隐藏读取密钥，人工语义核验只在瞬时本机窗口中进行；不要录屏或保存窗口正文。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
python -B scripts/rag-output-experiment/run.py --live --approved-requests 40 --approved-cny 2 --approved-scope-sha256 5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26 --price-checked YYYY-MM-DD
```

该命令仅在授权后使用。运行前须已编译测试类、生成 classpath，保留七份冻结源与历史显式检索缓存；GUI 使用 Python 自带 tkinter，无须安装外部包。工具在首个付费请求前核验窗口可用。结果写入忽略目录 `tmp/dev-032/results-<runId>.json`，不提交该文件或原始缓存。

下一步只补齐已授权的四组真实实验与账单核验；Q4 排序与全题逐断言引用继续独立安排，生产模板采纳须有单独决策。
