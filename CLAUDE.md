# 项目开发规则

开始任何工作前按顺序读取：

1. `README.md`
2. `docs/01-需求与整体设计.md`
3. `docs/02-开发决策基线.md`
4. `docs/04-逐步开发提示词.md`
5. 当前步骤相关的 ADR、Schema、OpenAPI、公共规范和 R1-R8 专属规范

## 不可突破规则

- 技术基线固定为 Java 21 / Spring Boot 3.x / Maven、Python 3.11+ / FastAPI / LangGraph / Pydantic v2、Vue 3 / Vite / TypeScript / Pinia / Element Plus、MySQL 8、Redis、Chroma、Nginx。
- 只执行当前 Sxx 步骤；依赖 Gate 未通过时停止，不提前实现后续业务。
- Java 业务后端是 MySQL 唯一写入方；Python Agent 服务只经 REST/OpenAPI 回写。
- 路线、天气、票价、预约、景点和餐住事实只能来自规定数据源。LLM 不得生成、补写或改写事实。
- 无来源、过期、冲突、估算和失败必须显式呈现。
- Agent 服务输出遵守 `agents/` Schema；跨服务接口遵守 OpenAPI。先改契约、示例和测试，再改实现。
- 已发布行程版本不可原地修改；局部修改只重跑白名单范围，保护用户锁定项和未影响区块。
- 所有写操作校验身份、资源归属、状态、乐观锁和幂等键；不信任前端 user、role、balance、price、version 或 source。
- 积分流水只追加，预扣、确认和退款必须幂等守恒；不接真实支付。
- 角色仅有 USER 和 ADMIN；前端隐藏入口不等于授权，所有私有资源和管理操作必须由后端校验身份、角色与资源归属。
- 所有事实数据必须携带来源、取得时间、有效期、新鲜度和估算标识；超过规则时效的内容不得作为确定性硬约束。
- 不提交密钥、令牌、真实手机号、私密行程、原始 UGC、供应商原始响应、导出文件或备份。
- UGC、上传文件和供应商返回均按不可信输入处理；图片必须有合法来源并剥离 EXIF，日志必须脱敏且不得记录原始 Prompt、凭证或完整私密行程。
- 数据库变更只允许新增 Flyway 迁移；已发布迁移不得改写，时间统一使用 UTC `DATETIME(6)`。
- 提交必须保持单一职责和可回滚；提交前检查差异、测试结果和密钥，禁止自动合并或跳过 Gate。
- 每步必须更新 `docs/progress/Sxx.md`，记录实际测试、风险、回滚和 PENDING；不得虚报测试通过。

全局执行顺序只有 S00-S31。历史 25 步文档和 `.bak` 文件不是开发依据。
