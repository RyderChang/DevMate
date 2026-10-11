# 瞬时逐断言评审工具

DEV-034 的本机离线工具，只有公开来源预检、合成演示和内存调用接口；没有模型客户端、密钥参数、真实调用开关、正文文件读取入口或历史恢复功能。规则与限制见[核验流程](../../docs/development/claim-citation-review.md)。

## 离线使用

```powershell
python -B scripts/rag-claim-review/run.py
python -B scripts/rag-claim-review/run.py --demo
python -B -m unittest discover -s scripts/rag-claim-review -p 'test_*.py'
```

第一条仅核对冻结 SHA、七份 Git 公开源、十题四组及 50 个候选，输出固定预检摘要，不启动窗口。需要 Python 3.10+（含 tkinter）及冻结 Git 快照 `b0b58142af63963bc36ced05df064e4253475794`；浅克隆缺少该快照时固定失败，不自动联网补对象。Linux 窗口测试使用 tkinter 和 `xvfb-run`；CI 安装仅测试使用的系统 `python3-tk`，明确使用 `/usr/bin/python3`，不依赖 PATH 中缺少 Tk 的其他 Python，不跳过 GUI 测试。

`--demo` 打开合成回答。选择答案区间，点击“加入当前断言”；一项可以添加多个不连续区间。选择分类、核心 E 编号、支持及类型；在 S 来源全文中按行核对后加入校核证据。选择 C 编号、对应判定和绑定；局部引用必须先把答案中的 `C1` 或 `[C1]` 选区设为标记。每个 C 对应的 S 编号/行区间以及核心证据行在窗口上方显示。

登记断言后清空草稿，按出现顺序继续；同一句多个事实、重复或矛盾表述分别登记。错误可撤回最后一项再重登。提交前分别确认核心出现/遗漏、每个返回引用相关性、Q10 弃答，以及两项全文检查。未审项或残留草稿阻止提交；`COMPLETED` 表示评审完整，不表示事实或绑定通过。

演示凭据写入忽略目录 `tmp/dev-034/demo/review-<本地随机编号>.json`，标为 `mode=SYNTHETIC`、`reviewer=SYNTHETIC_CHOICES`，usage 为 0；不能计入真实轮或原 40 样本。关闭窗口丢弃全部未提交断言，保存 `INTERRUPTED` 与原 usage/预留；没有付费重试。保存失败保留原未知凭据，窗口仍可重新提交脱敏凭据或中断；文件写入重试不发送模型请求。

## 后续已授权调用的内存接口

`review.py` 的 `load_context(blob, read_source, number, arm, returned)` 只接受本目录固定 manifest SHA。`read_source` 返回指定 Git 快照/固定路径的 bytes，工具逐文件和片段校验 SHA。`returned` 必须来自同一次经过生产校验的响应，且实际返回来源定位必须由调用适配层逐项与本题候选核对；不能只把另一次请求的 C 编号交给本接口。整个冻结源仅供本机校核，后续外发只能包含实际必要候选。

`review_transient(context, answer, receipt, directory)` 接收内存中的已解析答案，不接收原始提供商信封或正文路径；`receipt` 严格限定 `status`、`usage`（inputTokens/outputTokens/totalTokens 或 null）、`reservedMicros`（整数，百万分之一人民币元）。它先保存未知评审及用量，再打开窗口；返回脱敏凭据路径。FAILED/UNKNOWN 必须没有答案和返回引用，来源覆盖为 UNKNOWN；成功且明确无引用才为 NOT_COVERED。

该接口自身不校验生产 JSON 合同、调用授权、余额或响应真实性；这些必须由后续调用适配层执行。当前尚未接入 DEV-032 live runner，也没有新轮授权。`FROZEN` 仅表示来源 scope 已冻结，不自动证明内容来自真实模型；不能据凭据格式或合法 usage 认定实际调用/费用。旧轮额度、账单与 UNKNOWN 不改变。

答案及源文只在 Session/窗口内存中驻留。接口结束会清理自身正文引用并销毁窗口；调用者必须清理自己持有的响应、答案与来源引用。窗口禁止复制/剪切/粘贴和选区导出，没有备注/截图/导出功能，但不控制操作系统换页、第三方录屏或物理内存擦除。

记录白名单与闭合枚举校验在保存前再次执行；只保存断言编号与 Unicode 字符区间、事实判定、冻结证据定位、逐引用对应/绑定、完整性及独立弃答。校核源可只保存 source SHA 与行范围，引用关系另保存冻结 chunk SHA。未知键、非法 ID、布尔冒充整数、越界坐标与缺少全文检查都拒绝，错误不回显原值。

原 DEV-032 正文仍不可恢复。新真实轮需另行明确次数、预算和外发范围，且要先完成调用适配层的授权/配对与离线验证；本工具不实施生产输出约束采纳、合并或部署。
