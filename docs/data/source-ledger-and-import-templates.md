# S05/S06 数据模板字段

本文档定义后续导入前使用的合成/脱敏模板字段。模板不是事实发布集；真实来源、许可和双人复核完成前，记录必须保持 `draft` 或 `manual_queue`。

## 来源台账模板

```json
{
  "source_record_id": "src-synthetic-<id>",
  "source_url": "https://example.invalid/synthetic/<id>",
  "license": "synthetic-fixture-not-for-publication",
  "city_code": "CN-BJ",
  "poi_id": "poi-synthetic-<id>",
  "aliases": [],
  "entered_by": "synthetic-operator",
  "reviewed_by": "synthetic-review-pending",
  "retrieved_at": "2026-10-01T00:00:00Z",
  "valid_until": "2026-10-31T00:00:00Z",
  "freshness_status": "fresh",
  "is_estimated": false,
  "status": "draft",
  "content_hash": "sha256-synthetic-<id>",
  "notes": "synthetic record only"
}
```

## POI/别名模板

POI 条目至少包含 `poi_id`、`city_code`、规范名称、别名、坐标（含坐标系）、`source_record_id`、`retrieved_at`、`valid_until`、`freshness_status`、`is_estimated`、`status` 和 `conflict_flags`。同名、坐标冲突或来源冲突必须设置 `status: manual_queue`，禁止自动合并。

## 景点知识、开放时段与预约规则模板

知识条目必须引用来源记录和内部 POI ID，并将事实拆分为可单独核验的字段。开放时段、票价和预约规则应使用各自的 `retrieved_at`/`valid_until`；不能用模型生成内容补全缺失政策。缺失、过期或估算值必须显式标示，不得作为确定性硬约束。

## 人工队列模板

```json
{
  "queue_id": "queue-synthetic-<id>",
  "city_code": "CN-BJ",
  "candidate_poi_ids": ["poi-synthetic-a", "poi-synthetic-b"],
  "conflict_flags": ["same_name", "coordinate_conflict", "source_conflict"],
  "reason": "synthetic review required",
  "status": "manual_queue",
  "assigned_to": null,
  "reviewed_at": null
}
```

只有完成来源、许可、身份和双人复核后，条目才可按 `draft` → `review` → `published` 发布；发布集必须能追溯至来源快照且未超过适用有效期。