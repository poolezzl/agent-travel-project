# Word 交付文档

本目录存放项目正式 Word 交付文档，由脚本生成，内容基线为仓库内 Markdown 文档（`docs/01-需求与整体设计.md`、`docs/02-开发决策基线.md`、`docs/03-多智能体系统规范.md`、`docs/04-逐步开发提示词.md`、`docs/architecture/`、`docs/progress/`）。

## 文档清单

| 文档 | 覆盖内容 |
|---|---|
| 多智能体协作文旅规划平台-需求分析文档.docx | 研究目标、研究内容、用户与用例、功能/非功能/数据与接口需求、验收 Gate 与 REQ 追踪、风险 |
| 多智能体协作文旅规划平台-总体架构设计文档.docx | 技术路线、分层/多智能体架构、任务状态机、技术栈、数据架构、契约体系、关键机制、部署与安全、ADR 索引 |
| 多智能体协作文旅规划平台-项目研究计划与可行性论证.docx | 研究目标、研究内容、技术路线、可行性分析、项目研究计划（M0-M4/S00-S31/R1-R8）、研发流程框架、预期成果、特色和创新点、风险 |

## 重新生成

```bash
python scripts/docx/build_all.py
```

依赖：`python-docx`、`Pillow`（生成环境已装）。内容修改入口：`scripts/docx/content_requirements.py`、`content_architecture.py`、`content_plan.py`；样式统一封装在 `scripts/docx/builder.py`。

插图（分层架构图、生成/修改 DAG、研发流程框架图）由 `scripts/docx/diagrams.py` 绘制为高分辨率 PNG（输出到 `docs/word/assets/`）并嵌入 Word，`build_all.py` 会自动重绘。不要手改 assets 下的 PNG——重新生成会覆盖；要改图请改 `diagrams.py`。

若目标 docx 正被 Word 打开，脚本会跳过该文件并提示，关闭后重跑即可。

## 使用说明

- 目录为 Word 域：打开文档后全选更新域（或右键目录 → 更新域）即可生成带页码的目录。
- 文档内数字、口径如与 `docs/` 下 Markdown 基线冲突，以 Markdown 基线为准并重新生成 Word 文件。
