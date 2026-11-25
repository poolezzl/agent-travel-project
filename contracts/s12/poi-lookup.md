# POI 按 ID 查询内部契约（S12/S13 缺口关闭）

- 状态：**已冻结（2026-11-25，项目负责人授权评审）**。冻结前修正了三处实现与文档不一致：
  `source_record_id` 语义、非法 `city` 的错误码、限流窗口作用域。
- 适用范围：`agent-service` 路线适配器按 `poi_id` 回查权威 POI；不对外网暴露。
- 事实来源：Java 业务后端知识库（MySQL）中的 POI 当前快照（`poi` + `poi_snapshot` +
  `source_snapshot`）。Python 只读，不写库、不共享数据库连接。
- 机器可校验响应结构：`contracts/s12/poi-lookup.response.schema.json`。
- 示例：`contracts/examples/s12/poi-lookup-ok.json`、
  `contracts/examples/s12/poi-lookup-unknown-category.json`、
  `contracts/examples/s12/problem-poi-not-found.json`、
  `contracts/examples/s12/problem-rate-limited.json`。

## 1. 接口

```text
GET /internal/v1/knowledge/pois/{poiId}
可选查询参数：city=<标准城市 id，如 beijing；也接受“北京”>
```

- `poiId`：UUID（知识库 `poi.id` 的字符串形式）。
- 可用前提：仅在 `app.database.mode=mysql` 时注册；其他模式（如 mock）下该路径不存在。
- 调用方：仅限后端内网/同机 Agent 服务；沿用 `InternalKnowledgeController` 的“内网可信”
  边界，不读取外部 `user/role` 头，也不接受请求体。
- 只读：不产生审计写操作、不修改 POI/知识/来源状态。
- 幂等：同一时点的重复查询返回同一权威快照内容；`retrieved_at` 是本次响应时间。

## 2. 归属与限流约束

- **归属**：只返回归属于已登记城市（`city` 表）的 POI；提供 `city` 时，城市必须与 POI 的
  归属城市一致（空串视为未提供）。城市未登记或与 POI 不匹配一律返回 `404 POI_NOT_FOUND`，
  不区分“不存在”与“无权/跨城”，避免暴露其他城市 POI 的存在性。
- **权威性前置条件**：POI 必须存在、状态不是 `DISABLED`、有 `current_snapshot_id`，快照
  必须有经纬度与坐标系，且快照能回溯到 `source_snapshot`。任一条件不满足都返回
  `404 POI_NOT_FOUND`；**绝不**用请求时间、名称或默认值补齐坐标/来源。
- **限流**：进程内固定窗口限流，默认 `20 次/秒`，可用
  `INTERNAL_POI_LOOKUP_MAX_PER_SECOND`（后端配置项
  `app.internal.poi-lookup.max-per-second`）调整；超限返回 `429 RATE_LIMITED`。
  整个进程共享一个窗口（不区分调用方/IP），多实例部署时各自计数；跨实例总配额由
  部署层承担，不在本契约内。

## 3. 响应字段

| 字段 | 类型 | 必填 | 来源 / 说明 |
|---|---|---|---|
| `poi_id` | string(UUID) | 是 | `poi.id` |
| `city_id` | string | 是 | `city.city_code`（标准城市 id，如 `beijing`） |
| `name` | string | 是 | `poi.name` |
| `category` | string | 是 | `poi.category`；为空时**显式下发字面量 `"unknown"`**，不推断分类 |
| `latitude` | number | 是 | 当前 `poi_snapshot.latitude`，范围 `[-90, 90]` |
| `longitude` | number | 是 | 当前 `poi_snapshot.longitude`，范围 `[-180, 180]` |
| `coordinate_system` | string | 是 | 当前 `poi_snapshot.coordinate_system`，`GCJ02` 或 `WGS84` |
| `data_at` | string(date-time) 或 null | 是 | 当前 `poi_snapshot.created_at`；`null` 明确表示“数据时间未知”，不得用响应时间代替 |
| `source_metadata` | array | 是（至少 1 项） | 统一来源元数据，见第 4 节；来自当前快照的 `source_snapshot` |
| `retrieved_at` | string(date-time) | 是 | 后端组装本次响应的 UTC 时间 |

`category` 是**来源原值**：知识库路径下为 `poi.category`（如 `attraction`），供应商回退路径
下为高德 `type` 文本（如 `风景名胜`）。它不是跨来源统一枚举，消费方不得据此推断事实；
需要 `attraction` 等知识库分类的调用方必须只使用知识库 POI（UUID），不得把供应商分类值
当枚举使用。

## 4. 统一来源元数据（`source_metadata[]`）

沿用 `KnowledgeModels.SourceMetadata` 与 Agent Schema 的 `source_metadata` 结构：

| 字段 | 说明 |
|---|---|
| `source_type` | 来源快照的实际类型小写值；POI 通常为 `poi` 或 `knowledge_base` |
| `source_record_id` | `source_snapshot.source_record_id`（已登记的来源记录 ID，字符串 UUID；不是 `source_snapshot.id` 快照行 ID） |
| `retrieved_at` | `source_snapshot.retrieved_at`（来源取得时间） |
| `valid_until` | `source_snapshot.valid_until`，可为 null |
| `freshness_status` | `fresh` / `stale` / `unknown`；`valid_until` 已过则强制 `stale` |
| `is_estimated` | 固定 `false`；POI 坐标不是估算值 |

`data_at` 与 `source_metadata[].retrieved_at` 语义不同：前者是“这份 POI 快照写入知识库的
时间”，后者是“原始来源被取得的时间”。两者都缺失时不得互相顶替。

## 5. 错误码

错误体沿用 `contracts/examples/s08/problem-detail.json` 的 RFC7807 形状
（`type/title/status/detail/code/traceId/timestamp`）。

| HTTP | `code` | 触发条件 | Python 侧处理 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | `poiId` 不是合法 UUID | 非 UUID 的供应商 ID（如 `B000A1`）不请求本接口，直接回退地图详情；UUID 收到 400 与 404 同义（本端不认该 ID），可回退地图详情；仍不得伪造坐标 |
| 404 | `POI_NOT_FOUND` | POI 不存在、`city` 未知/不匹配、`DISABLED`、缺当前快照、缺坐标/坐标系、缺来源快照 | 视为“后端无此权威 POI”，可回退地图详情；不回退进程缓存 |
| 429 | `RATE_LIMITED` | 超过进程内限流 | 显式降级，`get` 返回 `None`，不重试风暴 |
| 5xx | `INTERNAL_ERROR` | 后端未预期异常 | 显式降级，`get` 返回 `None` |

## 6. Python `PoiPort.get` 语义（配套实现约束）

- 先查**新鲜**进程内缓存；缓存命中且记录坐标完整才可直接返回。
- 未命中或缓存过期：知识库 POI（UUID）必须查询本契约接口；进程内索引只是缓存，不是事实来源。
- 高德/供应商 ID（非 UUID）不请求本接口，直接走供应商按 ID 详情，避免被误判为非法 UUID。
- 后端返回 404 或 400：允许回退到地图供应商按 ID 查询（外部 POI 场景），但不得使用过期缓存。
- 后端超时/不可用/响应非法：**显式降级**，`get` 返回 `None`，由路线层产生
  `DIRTY`/“缺权威坐标”结构化错误；不得把缺坐标或缓存中的旧记录当权威。
- `category` 缺失：使用 `"unknown"`；`data_at` 缺失：`None`。两者都不是 Python 自造默认值。

## 7. 非目标

- 不定义 POI 名称检索（沿用 `PoiPort.lookup` 与现有高德/目录实现）。
- 不把 POI 状态 `PUBLISHED` 作为坐标权威条件：知识条目发布状态与 POI 坐标快照是两件事；
  `DISABLED` 才阻断。
- 不新增数据库迁移；复用现有 `poi` / `poi_snapshot` / `source_snapshot` 表。