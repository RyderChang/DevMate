# 本地开发与检查脚本

脚本默认不读取私人或生产账户。不要输出凭据、上传原始日志、执行未知用户仓库代码或做全局 prune。

- `check-docs.mjs`：Markdown 链接、代码围栏和改动格式检查。
- `summarize-tests.py`：仅输出后端测试类名与计数；摘要本身不代表通过。
- `conversation_env.py`、`ai-stub.mjs`：隔离对话联调与 Stub，使用方式见[开发指南](../docs/development/local-development.md)。
- `storage-preflight/`：SDK/hash 锁、固定源码 MinIO 构建和真实存储/数据库预检，见[准备记录](../docs/development/storage-preflight.md)。
- `verify-knowledge-backend.py`：JDK 21 编译、固定 Linux JRE 执行完整后端；失败/缺失/跳过均拒绝。
- `check-knowledge-test-reports.py`：Foundation CI 的完整报告门禁，要求八组存储和三组解析分块验收，所有测试零跳过。
- `retry-knowledge-document.sql`：经授权运维会话恢复已审查 ID 的核对/清理；不改变终态或释放未知写入的容量。

知识文档的配置、隔离测试、清理和人工处理步骤见[开发指南](../docs/development/local-development.md)，
实际命令与结果见[验收记录](../docs/testing/knowledge-document-storage-acceptance.md)。
DEV-017 新增验证见[解析与分块验收记录](../docs/testing/document-processing-acceptance.md)。
