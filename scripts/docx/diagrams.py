# -*- coding: utf-8 -*-
"""渲染文档插图为高分辨率 PNG（分层架构图 / 任务 DAG / 流程框架图）。

ASCII 字符画在 Word 里必然错位（汉字全角宽度），因此插图统一改为绘图生成：
设计按 1x 逻辑坐标布局，输出 2x 位图，Word 中缩放后边缘平滑。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SCALE = 2
FONT_DIR = Path("C:/Windows/Fonts")

# Office 风格配色：header / body / 边框深色
PALETTE = {
    "blue": {"head": (44, 84, 140), "body": (222, 231, 245), "line": (44, 84, 140)},
    "green": {"head": (84, 130, 53), "body": (230, 241, 221), "line": (84, 130, 53)},
    "purple": {"head": (112, 48, 160), "body": (236, 227, 246), "line": (112, 48, 160)},
    "orange": {"head": (197, 90, 17), "body": (253, 233, 217), "line": (197, 90, 17)},
    "slate": {"head": (68, 84, 106), "body": (234, 236, 239), "line": (68, 84, 106)},
    "red": {"head": (168, 47, 47), "body": (250, 226, 226), "line": (168, 47, 47)},
}
ARROW = (96, 96, 96)
TEXT = (38, 38, 38)
MUTED = (110, 110, 110)

_FONTS: dict = {}


def font(size: float, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key not in _FONTS:
        name = "msyhbd.ttc" if bold else "msyh.ttc"
        _FONTS[key] = ImageFont.truetype(str(FONT_DIR / name), int(round(size * SCALE)))
    return _FONTS[key]


def S(v: float) -> int:
    return int(round(v * SCALE))


# ---------------- 基础图元 ----------------

def text_center(draw, cx, cy, s, fnt, fill=TEXT):
    l, t, r, b = fnt.getbbox(s)  # 字体像素盒
    draw.text((S(cx) - (l + r) / 2, S(cy) - (t + b) / 2), s, font=fnt, fill=fill)


def text_left(draw, x, cy, s, fnt, fill=TEXT):
    _, t, _, b = fnt.getbbox(s)
    draw.text((S(x), S(cy) - (t + b) / 2), s, font=fnt, fill=fill)


def text_width(s, fnt) -> float:
    """文本宽度（逻辑单位）。"""
    l, _, r, _ = fnt.getbbox(s)
    return (r - l) / SCALE


def text_height(s, fnt) -> float:
    """文本高度（逻辑单位）。"""
    _, t, _, b = fnt.getbbox(s)
    return (b - t) / SCALE


def chip(draw, cx, cy, s, fnt, fill=(255, 255, 255), border=(120, 130, 150), pad=16, radius=8, height=None):
    """白底圆角小卡片（组件芯片），水平居中于 cx。"""
    w = text_width(s, fnt) + pad * 2
    h = height or text_height(s, fnt) + 18
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    draw.rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(radius), fill=fill, outline=border, width=S(1.2))
    text_center(draw, cx, cy, s, fnt)
    return w, h


def chip_row(draw, center_x, cy, items, fnt, gap=16, **kw):
    """一行等高芯片，整体水平居中，返回总宽。"""
    pad = kw.get("pad", 16)
    widths = [text_width(s, fnt) + pad * 2 for s in items]
    total = sum(widths) + gap * (len(items) - 1)
    x = center_x - total / 2
    for s, w in zip(items, widths):
        chip(draw, x + w / 2, cy, s, fnt, **kw)
        x += w + gap
    return total


def layer_box(draw, x, y, w, h, title, scheme, radius=12, header_h=46, title_size=17):
    """带标题栏的分层容器：圆角外框 + 平底色带标题栏。"""
    head, body, line = PALETTE[scheme]["head"], PALETTE[scheme]["body"], PALETTE[scheme]["line"]
    draw.rounded_rectangle([S(x), S(y), S(x + w), S(y + h)], radius=S(radius), fill=body, outline=line, width=S(1.6))
    # 标题栏：圆角顶 + 矩形补平底
    draw.rounded_rectangle([S(x), S(y), S(x + w), S(y + header_h + radius)], radius=S(radius), fill=head)
    draw.rectangle([S(x), S(y + header_h), S(x + w), S(y + header_h + radius)], fill=head)
    # 重新描边（覆盖标题栏溢出的描边像素）并画分隔线
    draw.rounded_rectangle([S(x), S(y), S(x + w), S(y + h)], radius=S(radius), outline=line, width=S(1.6))
    draw.line([S(x), S(y + header_h), S(x + w), S(y + header_h)], fill=line, width=S(1.2))
    text_center(draw, x + w / 2, y + header_h / 2, title, font(title_size, bold=True), fill=(255, 255, 255))
    return y + header_h


def arrow_v(draw, x, y1, y2, color=ARROW, width=2.4, head=9, double=False):
    """垂直箭头；double=True 时双向。"""
    top = y1 + head if double else y1
    draw.line([S(x), S(top), S(x), S(y2 - head)], fill=color, width=S(width))
    draw.polygon([S(x - head), S(y2 - head), S(x + head), S(y2 - head), S(x), S(y2)], fill=color)
    if double:
        draw.polygon([S(x - head), S(y1 + head), S(x + head), S(y1 + head), S(x), S(y1)], fill=color)


def arrow_h(draw, y, x1, x2, color=ARROW, width=2.2, head=8):
    draw.line([S(x1), S(y), S(x2 - head), S(y)], fill=color, width=S(width))
    draw.polygon([S(x2 - head), S(y - head), S(x2 - head), S(y + head), S(x2), S(y)], fill=color)


def flow_box(draw, cx, cy, w, h, lines, scheme="blue", radius=10, name_size=15, sub_size=11.5, fill=None):
    """流程图节点：首行为主文本，可带次行说明。"""
    head, body, line = PALETTE[scheme]["head"], PALETTE[scheme]["body"], PALETTE[scheme]["line"]
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    draw.rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(radius), fill=fill or body, outline=line, width=S(1.5))
    if len(lines) == 1:
        text_center(draw, cx, cy, lines[0], font(name_size, bold=True), fill=head)
    else:
        gap = 6
        f1, f2 = font(name_size, bold=True), font(sub_size)
        h1, h2 = text_height(lines[0], f1), text_height(lines[1], f2)
        total = h1 + gap + h2
        text_center(draw, cx, cy - total / 2 + h1 / 2, lines[0], f1, fill=head)
        text_center(draw, cx, cy + total / 2 - h2 / 2, lines[1], f2, fill=MUTED)
    return (x0, y0, x1, y1)


def side_note(draw, x, y, lines, scheme="orange", w=None, name_size=12.5):
    """链路旁的分支/说明盒（左上角定位）。"""
    fnt = font(name_size)
    max_w = max(text_width(s, fnt) for s in lines)
    w = w or max_w + 28
    h = 16 * len(lines) + 14
    head, body, line = PALETTE[scheme]["head"], PALETTE[scheme]["body"], PALETTE[scheme]["line"]
    draw.rounded_rectangle([S(x), S(y), S(x + w), S(y + h)], radius=S(8), fill=body, outline=line, width=S(1.2))
    cy = y + h / 2
    if len(lines) == 1:
        text_center(draw, x + w / 2, cy, lines[0], fnt, fill=head)
    else:
        for i, s in enumerate(lines):
            text_center(draw, x + w / 2, y + 12 + i * 16 + 8, s, fnt, fill=head)
    return h


def dashed_arrow_h(draw, y, x1, x2, color=ARROW, width=1.8, head=7):
    """指向旁注的虚线水平箭头。"""
    step = 8
    x = x1
    while x < x2 - head - step:
        draw.line([S(x), S(y), S(min(x + step, x2 - head)), S(y)], fill=color, width=S(width))
        x += step * 2
    draw.polygon([S(x2 - head), S(y - head), S(x2 - head), S(y + head), S(x2), S(y)], fill=color)


def save(img: Image.Image, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(str(path), dpi=(220, 220))
    return path


# ---------------- 图 1：分层架构图 ----------------

def layered_architecture(out_dir: Path) -> Path:
    W = 1560
    M = 30
    box_w = W - 2 * M
    header_h = 46
    inner = 24
    gap = 76  # 层间连线区
    f_title = font(16.5, bold=True)
    f_chip = font(14)
    f_chip_s = font(11.5)
    f_link = font(13)

    layers = [
        {
            "scheme": "blue",
            "title": "前端交互层（Vue 3 Web）",
            "rows": [["参数表单", "对话修正", "行程时间轴 / 地图", "榜单社区", "导出 / 提醒"]],
        },
        {
            "scheme": "green",
            "title": "业务服务层（Spring Boot · 业务数据唯一写方）",
            "rows": [
                ["账号鉴权（JWT）", "钱包积分", "行程 / 版本", "任务状态机", "榜单社区"],
                ["导出 / 提醒", "管理后台", "审计留痕", "事务 Outbox"],
            ],
        },
        {"scheme": "purple", "title": "智能体编排层（FastAPI + LangGraph）", "agent": True},
        {
            "scheme": "orange",
            "title": "第三方 API / 数据源适配层（限流 · 缓存 · 降级 · Mock）",
            "rows": [["地图 / POI（高德）", "天气（和风）", "LLM 网关（OpenAI 兼容）", "已发布知识 / 规则"]],
        },
        {
            "scheme": "slate",
            "title": "数据存储层",
            "rows": [
                ["MySQL（业务事实）", "Redis（队列 / 缓存 / 限流）", "Chroma（知识检索）", "受控对象目录（导出 / 图片）"],
                ["来源快照", "积分流水", "任务事件", "行程版本"],
            ],
        },
    ]
    links = [
        "REST 命令  ·  SSE 流式进度",
        "Redis Streams 投递  ·  REST 上下文与结果回写",
        "经适配层调用外部服务（Agent 不直连供应商）",
        "缓存与事实数据读写（业务数据经业务服务层落库）",
    ]

    # 1) 估算高度
    chip_h = 42
    row_gap = 14
    heights = []
    for lay in layers:
        if lay.get("agent"):
            h = header_h + 16 + 64 + 12 + 22 + 12 + 72 + 20
        else:
            n = len(lay["rows"])
            h = header_h + 16 + n * chip_h + (n - 1) * row_gap + 20
        heights.append(h)
    H = 2 * M + sum(heights) + gap * (len(layers) - 1) + 8

    img = Image.new("RGB", (S(W), S(H)), "white")
    d = ImageDraw.Draw(img)
    cx = W / 2
    y = M

    for idx, lay in enumerate(layers):
        h = heights[idx]
        layer_box(d, M, y, box_w, h, lay["title"], lay["scheme"], header_h=header_h, title_size=16.5)
        if lay.get("agent"):
            # 总控 + 交互修正
            in_x0, in_y0 = M + 40, y + header_h + 16
            in_x1 = M + box_w - 40
            in_y1 = in_y0 + 64
            d.rounded_rectangle([S(in_x0), S(in_y0), S(in_x1), S(in_y1)], radius=S(10),
                                fill=(255, 255, 255), outline=PALETTE["purple"]["line"], width=S(1.3))
            orch_w = 300
            flow_box(d, cx, (in_y0 + in_y1) / 2, orch_w, 48, ["01 总控调度 Agent"], "purple", name_size=15)
            rev_cx = cx + 330
            flow_box(d, rev_cx, (in_y0 + in_y1) / 2, 260, 48, ["07 交互修正 Agent"], "purple", name_size=14)
            arrow_h(d, (in_y0 + in_y1) / 2, rev_cx - 130 - 12, cx + orch_w / 2 + 6, color=PALETTE["purple"]["head"])
            text_left(d, cx - 380, (in_y0 + in_y1) / 2, "唯一面向用户", f_chip_s, fill=MUTED)
            # 专业 Agent 说明
            cap_cy = in_y1 + 12 + 11
            text_center(d, cx, cap_cy, "专业 Agent（无状态 · 彼此不直连 · 只受总控调度）", f_chip_s, fill=MUTED)
            # 5 个专业 Agent 双行芯片
            agents = [
                ("02 路线规划", "算法层 TSP / 2-opt"),
                ("03 气象服务", "天气 API"),
                ("04 景点资讯", "知识库 / RAG"),
                ("05 餐饮住宿", "POI 检索"),
                ("06 预约规则", "人工规则库"),
            ]
            a_w, a_h, a_gap = 258, 72, 22
            total = len(agents) * a_w + (len(agents) - 1) * a_gap
            ax = cx - total / 2
            ay = cap_cy + 12 + a_h / 2
            for name, sub in agents:
                flow_box(d, ax + a_w / 2, ay, a_w, a_h, [name, sub], "purple", name_size=14, sub_size=11)
                ax += a_w + a_gap
        else:
            n = len(lay["rows"])
            block_h = n * chip_h + (n - 1) * row_gap
            ry = y + header_h + 16 + chip_h / 2
            for row in lay["rows"]:
                chip_row(d, cx, ry, row, f_chip, gap=18, pad=20, height=chip_h, border=PALETTE[lay["scheme"]]["line"])
                ry += chip_h + row_gap
        y += h
        if idx < len(layers) - 1:
            arrow_v(d, cx, y + 6, y + gap - 16, double=True, width=2.6, head=10)
            text_left(d, cx + 22, y + gap / 2 - 4, links[idx], f_link, fill=(70, 70, 70))
            y += gap

    return save(img, out_dir / "fig-3-1-layered-architecture.png")


# ---------------- 图 2：首次生成 DAG ----------------

def generation_dag(out_dir: Path) -> Path:
    W = 1560
    M = 30
    cx = 780
    box_w, box_h = 700, 62
    vgap = 30
    y = M
    rows = []

    def node(cy, lines, scheme="blue", w=box_w, h=box_h):
        return flow_box(d, cx, cy, w, h, lines, scheme, name_size=15, sub_size=11.5)

    # 高度预算
    spec = [
        ("box", ["[0] 鉴权与服务端报价", "幂等预扣本次方案对应积分"], "blue"),
        ("box", ["[1] 用户提交需求", "结构化参数 + 自由文本偏好"], "blue"),
        ("box", ["[2] 总控：需求解析 + 可行性预检"], "purple", ["不可行 / 冲突", "返回用户澄清"]),
        ("box", ["[3] 总控：任务拆解（生成子任务 DAG）"], "purple"),
        ("parallel", None),
        ("box", ["[5] 总控：日级分配", "基于资讯 / 天气 / 预约约束决定哪天去哪几个点"], "purple"),
        ("box", ["[6] 路线规划：逐日顺序优化 + 路径计算", "确定性贪心 + 2-opt，各日可并行"], "green"),
        ("box", ["[7] 餐饮住宿：注入每餐候选与住宿建议", "顺路性优先于绝对评分"], "green"),
        ("box", ["[8] 总控：整合 + 冲突消解 + 质量校验"], "purple", ["硬冲突无法自动消解", "AWAITING_CONFIRMATION 携选项询问"]),
        ("box", ["[9] Java 原子创建不可变版本 V1", "结构化 JSON + 摘要，流式推送前端"], "blue"),
    ]
    # 估算总高
    total_h = M
    for kind, *_ in spec:
        if kind == "box":
            total_h += box_h + vgap
        else:
            total_h += 26 + 16 + 78 + vgap
    total_h += 40 + 30  # 终态条
    H = total_h + M

    img = Image.new("RGB", (S(W), S(H)), "white")
    d = ImageDraw.Draw(img)

    for item in spec:
        if item[0] == "box":
            lines, scheme = item[1], item[2]
            cy = y + box_h / 2
            node(cy, lines, scheme)
            if len(item) > 3:
                note = item[3]
                h = side_note(d, cx + box_w / 2 + 60, cy - 18, note, "orange")
                dashed_arrow_h(d, cy, cx + box_w / 2 + 8, cx + box_w / 2 + 52)
            y += box_h
        else:
            # 并行采集容器
            text_center(d, cx, y + 12, "══  并行阶段一（彼此无依赖，共享最小上下文）══", font(13.5, bold=True), fill=PALETTE["green"]["head"])
            y += 26
            p_h = 78
            p_w, p_gap = 360, 40
            total = 3 * p_w + 2 * p_gap
            px = cx - total / 2
            for name, sub in [("04 景点资讯", "必去 + 候选景点"), ("03 气象服务", "目的地逐日天气"), ("06 预约规则", "预约政策与时间线")]:
                flow_box(d, px + p_w / 2, y + p_h / 2, p_w, p_h, [name, sub], "green", name_size=14.5, sub_size=11.5)
                px += p_w + p_gap
            y += p_h
        y += vgap
        arrow_v(d, cx, y - vgap + 4, y - 6, width=2.4, head=9)

    # 终态条
    y += 10
    seg_w, seg_h, seg_gap = 330, 56, 30
    segs = [
        ("SUCCEEDED / PARTIAL", "确认预扣，缺失项显式可见", "green"),
        ("AWAITING_CONFIRMATION", "24h 确认期限，可取消", "orange"),
        ("FAILED / CANCELLED → REFUNDED", "幂等全额退款", "red"),
    ]
    total = len(segs) * seg_w + (len(segs) - 1) * seg_gap
    sx = cx - total / 2
    for name, sub, scheme in segs:
        flow_box(d, sx + seg_w / 2, y + seg_h / 2, seg_w, seg_h, [name, sub], scheme, name_size=13, sub_size=11)
        sx += seg_w + seg_gap
    text_left(d, cx - total / 2, y - 12, "任务终态（幂等结算，重复回调不产生第二次结算）", font(12.5), fill=MUTED)

    return save(img, out_dir / "fig-4-1-generation-dag.png")


# ---------------- 图 3：局部修改 DAG ----------------

def revision_dag(out_dir: Path) -> Path:
    W = 1560
    M = 30
    cx = 780
    box_w, box_h = 700, 62
    vgap = 28
    y = M

    spec = [
        ("box", ["[1] 读取当前不可变版本与 ETag"], "blue"),
        ("box", ["[2] 交互修正：意图分类"], "purple", ["query → 只读回答", "不建版本 · 不扣费"], ["ambiguous → 返回澄清问题"]),
        ("box", ["[3] 计算影响范围与锁定项", "affected_days / agents / locked_paths（含必去点）"], "purple"),
        ("box", ["[4] 需用户确认？"], "purple", ["是 → AWAITING_CONFIRMATION", "拒绝 / 超时 → CANCELLED · 退款"]),
        ("box", ["[5] 服务端报价与预扣（局部重规划 2 积分）"], "blue"),
        ("box", ["[6] 仅调度白名单 Agent", "复用未影响区块与新鲜来源快照"], "green"),
        ("box", ["[7] 确定性质量校验"], "purple", ["ETag 冲突 → 409", "不写版本 · 退款预扣"]),
        ("box", ["[8] 原子创建子版本 + change_set", "结构化 diff + 终态结算（确认预扣）"], "blue"),
    ]

    total_h = M
    for item in spec:
        total_h += box_h + vgap
    total_h += 20
    H = total_h + M

    img = Image.new("RGB", (S(W), S(H)), "white")
    d = ImageDraw.Draw(img)

    for item in spec:
        lines, scheme = item[1], item[2]
        cy = y + box_h / 2
        flow_box(d, cx, cy, box_w, box_h, lines, scheme, name_size=15, sub_size=11.5)
        notes = item[3:] if len(item) > 3 else []
        nx = cx + box_w / 2 + 60
        ny = cy - 32
        for i, note in enumerate(notes):
            nh = 16 * len(note) + 14
            side_note(d, nx, ny, note, "orange" if i == 0 else "slate")
            dashed_arrow_h(d, ny + nh / 2, cx + box_w / 2 + 8, nx - 8)
            ny += nh + 12
        y += box_h + vgap
        arrow_v(d, cx, y - vgap + 4, y - 6, width=2.4, head=9)

    return save(img, out_dir / "fig-4-2-revision-dag.png")


# ---------------- 图 4：研发流程框架 ----------------

def process_framework(out_dir: Path) -> Path:
    W = 1560
    M = 30
    cx = 780
    y = M

    img = Image.new("RGB", (S(W), S(1080)), "white")
    d = ImageDraw.Draw(img)

    # 顶层输入
    flow_box(d, cx, y + 34, 560, 68, ["需求与决策基线（docs/01 · docs/02）"], "slate", name_size=15)
    y += 68
    arrow_v(d, cx, y + 8, y + 34, width=2.4, head=9)
    y += 42
    flow_box(d, cx, y + 34, 760, 72, ["架构与契约冻结（S01 / S02）", "ADR · 任务状态机 · Schema · OpenAPI · 示例 · 契约测试"], "slate",
             name_size=15, sub_size=11.5)
    y += 72
    arrow_v(d, cx, y + 8, y + 34, width=2.4, head=9)
    y += 46

    # 循环容器
    loop_title = "单步执行循环（S03 – S31，每步以真实证据收口）"
    steps = ["读取规范\n与依赖 Gate", "只做\n当前步骤", "测试与\n边界验证", "更新\nprogress 证据", "PENDING 处理\n建议提交（可回滚）"]
    n = len(steps)
    s_w, s_h, s_gap = 248, 88, 26
    total = n * s_w + (n - 1) * s_gap
    pad = 28
    loop_x0, loop_x1 = cx - total / 2 - pad, cx + total / 2 + pad
    loop_y0 = y
    loop_h = 46 + s_h + 56
    d.rounded_rectangle([S(loop_x0), S(loop_y0), S(loop_x1), S(loop_y0 + loop_h)], radius=S(12),
                        fill=(236, 243, 251), outline=PALETTE["blue"]["line"], width=S(1.6))
    text_center(d, cx, loop_y0 + 22, loop_title, font(14, bold=True), fill=PALETTE["blue"]["head"])
    sx = cx - total / 2
    for i, s in enumerate(steps):
        w = s_w
        x0, y0 = sx, loop_y0 + 46 + 8
        x1, y1 = sx + w, loop_y0 + 46 + 8 + s_h
        scheme = "blue" if i in (0, len(steps) - 1) else "green"
        head, body, line = PALETTE[scheme]["head"], PALETTE[scheme]["body"], PALETTE[scheme]["line"]
        d.rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(10), fill=body, outline=line, width=S(1.4))
        parts = s.split("\n")
        if len(parts) == 1:
            text_center(d, (x0 + x1) / 2, (y0 + y1) / 2, s, font(13, bold=True), fill=head)
        else:
            text_center(d, (x0 + x1) / 2, (y0 + y1) / 2 - 11, parts[0], font(13, bold=True), fill=head)
            text_center(d, (x0 + x1) / 2, (y0 + y1) / 2 + 12, parts[1], font(11), fill=MUTED)
        if i < n - 1:
            arrow_h(d, (y0 + y1) / 2, x1 + 2, sx + w + s_gap - 6, width=2, head=7)
        sx += w + s_gap
    # 回环说明：容器底边内侧（避免画布外文字）
    back_y = loop_y0 + 46 + 8 + s_h + 28
    bx = cx + total / 2
    # 右侧下折回到入口的回环线
    d.line([S(bx), S(loop_y0 + 46 + 8 + s_h + 4), S(bx + pad - 14), S(loop_y0 + 46 + 8 + s_h + 4)],
           fill=PALETTE["blue"]["head"], width=S(1.8))
    d.line([S(bx + pad - 14), S(loop_y0 + 46 + 8 + s_h + 4), S(bx + pad - 14), S(loop_y0 + loop_h - 6)],
           fill=PALETTE["blue"]["head"], width=S(1.8))
    d.line([S(bx + pad - 14), S(loop_y0 + loop_h - 6), S(loop_x0 + 40), S(loop_y0 + loop_h - 6)],
           fill=PALETTE["blue"]["head"], width=S(1.8))
    d.polygon([S(loop_x0 + 40), S(loop_y0 + loop_h - 13), S(loop_x0 + 40), S(loop_y0 + loop_h + 1),
               S(loop_x0 + 26), S(loop_y0 + loop_h - 6)], fill=PALETTE["blue"]["head"])
    text_center(d, cx, back_y, "依赖未过 / 步骤未完成 → 回到循环入口，重复直至步骤完成并留证", font(11.5),
                fill=PALETTE["blue"]["head"])

    y = loop_y0 + loop_h + 26
    arrow_v(d, cx, y + 8, y + 34, width=2.4, head=9)
    y += 42

    # Gate 节点
    flow_box(d, cx, y + 36, 560, 72, ["阶段 Gate 复核（M1 / M2 / M3 / M4 出口）"], "orange", name_size=15)
    gate_cy = y + 36
    y += 72
    # 未通过回环
    nx = cx + 280 + 70
    side_note(d, nx, gate_cy - 32, ["未通过 → 修复并重跑", "不得跳阶进入下一阶段"], "red")
    dashed_arrow_h(d, gate_cy - 18, cx + 280 + 8, nx - 8)
    arrow_v(d, cx, y + 8, y + 34, width=2.4, head=9)
    text_left(d, cx + 16, y + 20, "通过", font(12.5), fill=PALETTE["green"]["head"])
    y += 42

    flow_box(d, cx, y + 36, 720, 72, ["下一阶段推进 / 最终答辩与真实演示"], "green", name_size=15)
    text_center(d, cx, y + 36 + 22, "只陈述实际通过验收的能力，不以 Mock 或原型冒充最终能力", font(11.5), fill=MUTED)
    y += 72 + M

    # 裁剪画布到实际高度
    img = img.crop((0, 0, S(W), S(y + 10)))
    return save(img, out_dir / "fig-6-1-process-framework.png")


def render_all(out_dir: Path) -> list:
    return [
        layered_architecture(out_dir),
        generation_dag(out_dir),
        revision_dag(out_dir),
        process_framework(out_dir),
    ]


if __name__ == "__main__":
    out = Path(__file__).resolve().parents[2] / "docs" / "word" / "assets"
    for p in render_all(out):
        print("OK ", p, f"{p.stat().st_size:,}B")
