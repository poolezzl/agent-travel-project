# S07 演示动线与截图说明

本目录保存 S07「开题演示前端」的截图与演示动线证据。

- 截图来源：`frontend/e2e/s07-demo.spec.ts`（Playwright，`desktop` 1440×900 与 `mobile` Pixel 5 两个项目）。
- 运行方式：`npm --prefix frontend run dev:mock` 之后执行 `npm --prefix frontend run test:e2e`。
- 截图只代表**当前合成 UI 的证据**，不构成真实接口、地图服务或 S14 端到端验收。

## 文件清单

| 文件 | 视口 | 状态 | 说明 |
|---|---|---|---|
| `desktop-success.png` | 1440×900 | 成功 | 默认演示态：输入表单 + 模拟进度 + 契约信封 + 时间轴 + 地图占位 + 方案对比 + 社区样张 |
| `desktop-loading.png` | 1440×900 | 加载 | 骨架屏 + 模拟进度处于「采集来源」阶段 |
| `desktop-empty.png` | 1440×900 | 空 | 无时间轴/无方案，空态文案明确不使用占位数字 |
| `desktop-partial.png` | 1440×900 | 部分完成 | 数据可展示但 `degraded_fields` 可见 |
| `desktop-error.png` | 1440×900 | 错误 | 演示错误码 `AGENT_UNAVAILABLE` 与后续阶段「未执行」 |
| `mobile-*.png` | Pixel 5（393×851） | 同上五种状态 | 单列布局，无横向溢出 |

## 演示动线

1. **启动**：`npm --prefix frontend run dev:mock`，打开 `http://127.0.0.1:5173/`。
   页面顶部常驻横幅「演示数据，未接入真实接口」，不随状态切换消失。
2. **默认成功态**：左侧为 Mock 状态选择器（仅 mock profile 可见）、行程输入表单（预填北京 2026-10-10→10-12 的合成输入）、
   模拟进度（6 个阶段全部完成）、契约信封（S02 公共信封字段 + 来源与时效）；
   右侧为当前输入摘要、结果状态、时间轴（Day 1-3，含每个节点的交通/时长/天气/预约/费用与来源徽标）、
   地图占位、方案对比表、社区静态样张（只读）。
3. **键盘提交**：`Tab` 聚焦「生成演示行程」→ `Enter` 触发本地模拟。
   模拟约 3.6 秒推进 6 个阶段（排队 → 解析需求 → 采集来源 → 生成路线 → 整合方案 → 完成），
   期间出现骨架屏、提交按钮禁用（单元测试覆盖），结束后自动回到成功态并渲染完整时间轴。
   > 该动线不发起任何网络请求：可在浏览器 Network 面板确认仅有 Vite 模块与 HMR 请求。
4. **状态切换**（Mock 状态选择器）：依次点击「加载中 / 空状态 / 部分完成 / 错误 / 成功」，
   分别得到骨架屏、空态文案、部分完成 + `degraded_fields=provider_fields`、`AGENT_UNAVAILABLE` 错误码、完整成功态。
5. **来源与降级检查**：任一状态下都可以在「契约信封（S02 形状）」区块读到
   `schema_version / task_id / trace_id / request_id / generated_at / status`，
   以及 `message`、`error_code`、`degraded_fields` 与 `source_metadata` 条目；空集合显示为空态说明，不用占位事实补齐。
6. **S03 骨架回归页**：访问 `/scaffold` 可复核 S03 骨架（不属于 S07 演示流程，仅在真实 REST/SSE 接入前保留回归价值）。

## 验收对应关系

| S07 要求 | 证据 |
|---|---|
| 输入表单、模拟进度、时间轴、地图占位、方案对比、社区静态样张 | `*-success.png` 与 `e2e/s07-demo.spec.ts` 第 1 个用例 |
| 持续显示「演示数据，未接入真实接口」 | `demo-notice` 元素在五种状态与两种视口下均可见（e2e 断言） |
| 空 / 错 / 加载 / 部分状态 | `*-empty.png`、`*-error.png`、`*-loading.png`、`*-partial.png` 与状态切换用例 |
| 桌面与移动视口无溢出、遮挡 | 「桌面与 320px 窄屏都没有横向溢出」用例（含 320px 视口断言） |
| 键盘操作 | 「键盘可以聚焦并激活提交按钮」用例 + `PlanningForm` 单元测试 |
| Mock 开关不可在生产配置误启用 | 单元测试「状态选择器只在显式 mock profile 下渲染」；`build`（production）构建产物中不含该入口 |
