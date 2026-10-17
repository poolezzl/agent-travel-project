# R3 来源台账规范

## 目的

source_registry 是 R3 所有景点知识、POI 和预约规则事实的来源索引。它记录“事实从哪里来、何时取得、能否使用和何时需要重新核验”，不保存密钥、完整供应商响应或受限平台原文。

## 来源类型

| source_type | 允许用途 | 例子 |
|---|---|---|
| knowledge_base | 景点资讯事实 | 官方景区公开页面、人工审核资料 |
| reservation_rule | 预约政策事实 | 官方预约页面、人工核验政策 |
| poi | POI 名称、坐标和类别 | 合法 POI 适配器结果 |
| user_input | 用户当次输入 | 仅作为用户约束，不转为公共事实 |
| system | 系统计算或固定枚举 | 不承载外部事实 |

## 许可与审核

来源必须同时具备以下信息才可以被引用：

1. source_url 或受控内部引用；
2. license_type 和 license_reference；
3. permitted_use；
4. collected_at、retrieved_at 和 content_hash；
5. operator_id；进入 review 或 published 时还必须有 reviewer_id 和 reviewed_at。

source_status 的流转为 pending_review → approved，或者 pending_review → rejected/revoked。被拒绝或撤销的来源不能被新版本引用；历史版本的引用记录必须保留。

## 新鲜度计算

- 事实的 valid_until 已过期时，freshness_status 为 stale。
- 预约规则距 last_verified_at 超过 30 天时，stale 为 true。
- 没有足够时间信息时，freshness_status 为 unknown。
- stale 或 unknown 内容可以作为提示性信息展示，但不能作为确定性硬约束。

## 外链与隐私

- 外链只能经安全白名单和跳转审计后展示。
- 日志只保留 source_record_id、状态、摘要哈希和错误码，不记录完整 URL 查询参数中的密钥或用户信息。
- 示例文件使用 example.invalid 和合成 ID，不代表真实景点、价格或政策。

## 导入前检查

导入工具必须检查来源记录存在、许可字段完整、时间格式正确、状态合法和 content_hash 非空。任何一个事实找不到 source_record_id 时，整条事实保持 draft 并进入错误清单。
