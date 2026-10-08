# DEV-023 前端文档问答验收记录

日期：2026-10-08。分支 `feature/dev-023-frontend-rag-citations`，基线 `f3d6e5b7b63e8b51d9fe5cdd711a2bded0b6b11f`。

## 实现与验证范围

历史消息 GET 增加只读 `evidence`，只映射成功 RAG 助手消息保存的摘要与引用；当前可用性按完整来源定位和项目归属批量核验。原 POST 响应未改，不新增迁移或依赖。
前端入口 `VITE_RAG_ENABLED=false` 默认关闭；普通聊天保留，显式文档问答使用独立 API、690 秒超时、手动同 UUID 确认及纯文本引用组件。

实际命令与结果：

| 命令或检查                                                                                                                          | 结果                                                                                                   |
| ----------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `python -B scripts/verify-knowledge-backend.py --tests RagConversationIntegrationTest`                                              | 真实 MySQL 定向 21 项通过，失败/错误/跳过均 0。                                                        |
| `python -B scripts/verify-knowledge-backend.py`                                                                                     | 固定 Linux Java 21 与真实 MySQL/MinIO/Qdrant 完整后端 262 项通过，失败/错误/跳过均 0；无跳过门禁通过。 |
| `./node_modules/.bin/vue-tsc.cmd --build --force`、`./node_modules/.bin/eslint.cmd .`、`./node_modules/.bin/prettier.cmd --check .` | 类型、lint、前端格式检查通过。                                                                         |
| `./node_modules/.bin/vitest.cmd run`                                                                                                | 14 个文件、97 项通过。                                                                                 |
| `./node_modules/.bin/vite.cmd build`                                                                                                | 生产构建成功；共享 JS chunk 约 1,043 kB，保留现有大包告警。                                            |
| `node scripts/check-docs.mjs --format-changed f3d6e5b7b63e8b51d9fe5cdd711a2bded0b6b11f`                                             | 69 个 Markdown、274 个相对链接及改动格式通过（最终文档修改后需重跑）。                                 |
| `git diff --check`                                                                                                                  | 通过。                                                                                                 |

测试网络中的 Chat 均为 Stub，Embedding 为合成数据；没有真实付费模型调用或真实项目资料传输。

## 未通过与未执行的验收

依赖审计 **未通过**。本机 `npm` 默认解析到旧版 6.14.10，无法读取当前 lockfile；改用已安装的 npm 11.11.0 后，默认镜像的 audit API 返回 `NOT_IMPLEMENTED`。以 `--registry=https://registry.npmjs.org` 只读审计报告 **10 项漏洞（8 high、2 moderate）**，涉及 Vue 的服务端渲染依赖及若干构建工具传递依赖。未修改锁定依赖、未关闭或降低 CI 的 `npm audit` 门禁；PR 的安全检查预计会失败，待单独确定依赖修复范围。

隔离浏览器验收未完成。已启动仅返回合成数据的本机 Stub 与 Vite，但当前应用内浏览器无法访问 `127.0.0.1`，且没有可用的 Chrome 浏览器连接；因此没有桌面/窄屏截图，也不宣称浏览器交互通过。两个本机服务已停止。前端组件测试覆盖默认关闭、成功/退役引用纯文本、未知结果手动确认、终态 409、路由往返陈旧响应和 UTF-16 边界。

真实项目质量、生产启用、付费模型及部署不属于本次验收。历史引用事实支撑需由项目所有者基于实际项目资料另行评估。数据库全面不可写时既有 RAG 用量缺口仍需按提供商记录人工核对。

最终 commit、PR 与远端 CI 在创建后补充；安全审计和浏览器限制须在合并前明确处理。
