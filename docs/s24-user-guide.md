# S24 使用说明书：历史行程与偏好画像

本文档面向接口调用方，包含功能范围、接口参数与响应、请求示例及相关规则说明。文档采用结构化标题组织，便于人工查阅与智能体按小节定位。

## 功能范围

S24 提供历史行程管理与偏好画像两类能力。用户生成的行程保留为历史记录，系统依据历史记录统计偏好，在后续行程生成时参考。模块对外提供五个功能：查看历史行程、再来一单、版本演进对比、查看偏好画像、清空偏好画像。

## 接口速查

所有接口均需登录，请求 Header 携带 Bearer Token。基础路径为 `/api/v1`。

### 查看历史行程

- 方法：GET
- 路径：`/history/trips`
- 可选查询参数：
  - `from`、`to`：日期范围，格式 YYYY-MM-DD
  - `city`：城市名
  - `days`：行程天数
  - `status`：DRAFT（草稿）、ACTIVE（已定稿）、ARCHIVED（已归档）
  - `q`：标题关键字
  - `limit`、`offset`：分页参数
- 返回：行程数组。每项含 id、标题、起止日期、状态、城市列表与版本数
- 数据范围：仅返回当前登录用户自身的行程

### 再来一单

- 方法：POST
- 路径：`/trips/{tripId}/reuse`
- 必需 Header：`Idempotency-Key`，用于防止重复提交生成多份草稿
- 返回：新草稿的 tripId、versionId，以及被清理的私密字段列表
- 处理规则：复制行程时移除精确住宿地址与私密偏好字段，原字段值不出现在返回结果中

### 版本演进对比

- 方法：GET
- 路径：`/trips/{tripId}/versions/compare`
- 可选查询参数：
  - `from`：起始版本 ID
  - `to`：结束版本 ID
  - 两者均省略时，对比最早版本与最新版本
- 返回字段：
  - `from`、`to`：起止版本摘要，含版本 ID、版本号、状态、创建时间、结构版本
  - `changes`：字段差异数组，每项含 `path`（字段路径）、`changeType`（ADDED/REMOVED/MODIFIED）、`before`、`after`
- 字段路径示例：`days[0].spots`、`budget`，数组用方括号加下标表示
- 数据范围：仅本人行程可见，归属校验在查询时完成
- 前置条件：行程至少有两个版本，否则返回错误；差异最多返回 200 条

### 查看偏好画像

- 方法：GET
- 路径：`/preferences/summary`
- 返回字段：
  - `sampleCount`：参与统计的历史行程数
  - `eligible`：是否满足画像生成条件，历史行程不少于 3 条时为 true
  - `dimensions`：统计维度数组，每项含 key、valueJson、sourceType、weight、reason
  - `reason`：可解释理由，说明该维度由何种历史数据统计得出
  - `narrative`：画像文字，由语言模型依据统计维度改写生成
- 响应头：`Cache-Control: no-store`
- 数据范围：仅返回当前登录用户自身的画像

历史行程少于 3 条时，`eligible` 为 false，`dimensions` 与 `narrative` 均为空。

### 清空偏好画像

- 方法：DELETE
- 路径：`/preferences/summary`
- 必需 Header：`Idempotency-Key`
- 返回状态码：204
- 处理规则：将当前画像标记为已清除并保留历史版本，不删除历史行程与既有画像版本

## 请求示例

### 查询已定稿行程

```http
GET /api/v1/history/trips?status=ACTIVE&limit=20
Authorization: Bearer <token>
```

### 复制历史行程

```http
POST /api/v1/trips/9f1c.../reuse
Authorization: Bearer <token>
Idempotency-Key: reuse-20261124-001
```

### 对比行程版本

```http
GET /api/v1/trips/9f1c.../versions/compare
Authorization: Bearer <token>
```

典型响应：

```json
{
  "tripId": "65710000-0000-4000-8000-000000000001",
  "from": { "versionId": "6571...11", "versionNo": 1, "versionStatus": "DRAFT" },
  "to": { "versionId": "6571...12", "versionNo": 2, "versionStatus": "ACTIVE" },
  "changes": [
    { "path": "budget", "changeType": "MODIFIED", "before": 3000, "after": 3500 },
    { "path": "theme", "changeType": "ADDED", "before": null, "after": "海滨" }
  ]
}
```

### 读取偏好画像

```http
GET /api/v1/preferences/summary
Authorization: Bearer <token>
```

典型响应：

```json
{
  "summaryVersion": 2,
  "sampleCount": 7,
  "eligible": true,
  "dimensions": [
    { "key": "city", "valueJson": ["青岛", "厦门"], "sourceType": "HISTORY_ANALYSIS", "weight": 4, "reason": "统计历史行程中出现次数最多的城市" },
    { "key": "theme", "valueJson": {"亲子": 5, "美食": 3}, "sourceType": "HISTORY_ANALYSIS", "weight": 5, "reason": "从行程偏好标签中统计高频主题" },
    { "key": "pace", "valueJson": {"适中": 5, "紧凑": 2}, "sourceType": "HISTORY_ANALYSIS", "weight": 5, "reason": "按每日景点数量划分行程节奏" },
    { "key": "budget", "valueJson": {"舒适": 6, "高端": 1}, "sourceType": "HISTORY_ANALYSIS", "weight": 6, "reason": "按行程预算金额划分消费档位" },
    { "key": "season", "valueJson": {"春季": 3, "夏季": 4}, "sourceType": "HISTORY_ANALYSIS", "weight": 4, "reason": "按出行日期所属季节统计" }
  ],
  "narrative": "你偏好海滨城市，行程节奏偏轻松。",
  "generatedAt": "2026-11-24T10:00:00Z"
}
```

## 规则说明

### 画像生成的样本门槛

样本量不足时统计结果不稳定，容易误导后续行程生成。历史行程少于 3 条时不生成画像。数据库约束限定画像为可用状态时样本数必须不少于 3，接口按同一规则返回空画像。

### 历史偏好的作用边界

历史偏好作为软约束参与行程生成。用户在本次输入中明确指定城市、预算或景点时，以本次输入为准，历史偏好不覆盖显式需求。

### 画像的数据隔离

画像数据按用户隔离。接口校验登录用户身份，仅返回当前用户自身的画像，不返回其他用户数据。

### 清空操作的数据处理

清空操作将画像状态标记为已清除并保留版本记录。历史行程与既有画像版本均不删除，后续可依据历史记录重新生成画像。
