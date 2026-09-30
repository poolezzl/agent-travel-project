# S06 城市与来源数据模板

## 十城清单

| 城市代码 | 城市 | 本阶段状态 | 事实读取条件 |
|---|---|---|---|
| CN-BJ | 北京 | `pilot-template` | 仅已发布且来源完整的数据 |
| CN-SH | 上海 | `planned` | 待 S15 建设 |
| CN-HZ | 杭州 | `planned` | 待 S15 建设 |
| CN-NJ | 南京 | `planned` | 待 S15 建设 |
| CN-SZ | 苏州 | `planned` | 待 S15 建设 |
| CN-CD | 成都 | `planned` | 待 S15 建设 |
| CN-XA | 西安 | `planned` | 待 S15 建设 |
| CN-CQ | 重庆 | `planned` | 待 S15 建设 |
| CN-GZ | 广州 | `planned` | 待 S15 建设 |
| CN-XM | 厦门 | `planned` | 待 S15 建设 |

## 来源台账字段

来源记录必须包含：`source_record_id`、`source_url`、许可/使用范围、城市代码、内部 POI ID、别名、录入人、复核人、录入/核验时间、有效期、状态、内容哈希和备注。事实快照另行记录 `retrieved_at`、`valid_until`、`freshness_status`、`is_estimated`。

允许来源为已确认许可的官方公开资料、人工运营记录、合法 POI 结果和获得许可的 UGC。禁止未授权抓取、许可不明图片、原始供应商响应、真实用户数据和模型生成政策。URL 使用 `example.invalid` 的合成样例不代表真实事实来源。

状态仅允许：`draft` → `review` → `published`，或 `manual_queue`/`retired`。同名、坐标冲突、来源冲突和许可缺失进入 `manual_queue`；未完成复核不得发布。发布数据必须能追溯到来源记录，且不超过默认 30 天核验阈值；开放时间、票价和预约规则可有更短有效期。

## 北京最小集模板

`tests/fixtures/data/beijing/` 仅放合成记录，包含：

- 一个可演示引用链的 published POI；
- 一个 draft 条目，证明未核验数据不可读取；
- 一个带同名/坐标冲突标记的人工队列条目；
- 一个过期/`stale` 条目，证明过期内容不可作为确定性硬约束。

这些 fixture 不声称对应真实景点，也不含图片或模型生成政策。S15 导入前必须以 R3/R7 双人复核替换/确认每一条真实来源，并保留历史快照。
