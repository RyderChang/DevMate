# 第三方模型资产来源

本目录的词表与配置来自 Qwen 官方
[Qwen3-Embedding-0.6B](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/tree/97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3)，
固定修订 `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3`。
官方模型卡声明 Apache-2.0；许可正文见 [LICENSE](LICENSE)。本仓库尚未指定的项目许可不替代这些资产的上游许可。

`tokenizer.json.gz` 是上游 `tokenizer.json` 的无损 gzip 存档，时间戳固定为 0；
`1_Pooling-config.json` 仅将上游路径 `1_Pooling/config.json` 改为平面文件名。
其余配置按上游字节保存。未改写 tokenizer 的 NFC、特殊标记或后处理规则。
原文件和归档摘要、大小、来源修订见 [model.lock.json](model.lock.json)，每次测试先核对摘要。
这是为离线合同核验保留的第三方输入资产，不是应用构建产物。

仓库不包含模型权重、原始 vocab/merges、账户凭据或真实项目文档。
`../tokenizer-cases.json` 是 DevMate 合成输入及官方 fast/slow tokenizer 的对照期望值，
可用 `verify_goldens.py` 独立复现；测试不会更新期望值。
