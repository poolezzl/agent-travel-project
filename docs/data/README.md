# R3 数据交接目录

本目录保存 R3 在 S08 之前交付的逻辑数据规范、来源台账规范、导入模板、合成样例和交接接口说明。

| 文件 | 用途 | 步骤 |
|---|---|---|
| knowledge-data-dictionary.md | 定义 POI、景点知识、预约规则和来源字段 | R3-00 / S04 配合 |
| source_registry.md | 定义来源许可、审核和新鲜度规则 | R3-00 / S06 |
| city-coverage.md | 首批十城清单和覆盖状态 | S06 |
| r3-interface-table.md | 给 R1、R2、R5、R7 对接的接口表 | S02 / S06 |
| r3-evaluation-cases.md | 提供 R7 S05 的 R3 异常案例 | S05 配合 |
| templates/*.csv | 空导入模板，不包含生产数据 | S06 |
| samples/*.jsonl | 合成脱敏样例，仅用于契约和校验 | S06 |
| samples/tianapi_beijing_scenic.jsonl | 天聚真实北京景点派生样例（课程用途，2 条，未提交原始响应） | S06 |

R3 不在本目录实现数据库迁移、Java 写入服务、Chroma 运行时索引或 S15 之后的 Agent 业务逻辑。模板通过 scripts/validate_r3_data_templates.py 校验后，仍需经过 S04 数据模型评审和 R7 数据质量验收。
