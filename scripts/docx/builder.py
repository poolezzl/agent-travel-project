# -*- coding: utf-8 -*-
"""Word 文档构建器：统一封面、目录域、中文标题/正文样式、表格与页码。

用法见 build_all.py。内容模块暴露 build(b: Builder) 即可。
"""

from __future__ import annotations

import re
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

SONG = "宋体"
HEI = "黑体"
KAI = "楷体"


def _set_run_font(run, cn=SONG, en="Times New Roman", size=12, bold=False, color=None):
    run.font.name = en
    run.font.size = Pt(size)
    run.font.bold = bold
    if color is not None:
        run.font.color.rgb = RGBColor(*color)
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), cn)
    rfonts.set(qn("w:ascii"), en)
    rfonts.set(qn("w:hAnsi"), en)


def _style_font(style, cn, en, size, bold=False):
    style.font.name = en
    style.font.size = Pt(size)
    style.font.bold = bold
    rpr = style.element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), cn)
    rfonts.set(qn("w:ascii"), en)
    rfonts.set(qn("w:hAnsi"), en)


_BOLD_RE = re.compile(r"(\*\*.+?\*\*)")


def _fill_runs(
    paragraph, text, cn=SONG, en="Times New Roman", size=12, base_bold=False, color=None
):
    """支持 **加粗** 内联标记与 \\n 换行。"""
    for i, line in enumerate(str(text).split("\n")):
        if i:
            paragraph.add_run().add_break()
        for part in _BOLD_RE.split(line):
            if not part:
                continue
            bold = base_bold
            if part.startswith("**") and part.endswith("**") and len(part) > 4:
                part = part[2:-2]
                bold = True
            run = paragraph.add_run(part)
            _set_run_font(run, cn=cn, en=en, size=size, bold=bold, color=color)


class Builder:
    def __init__(self, title, subtitle, meta_rows, out_path):
        self.title = title
        self.subtitle = subtitle
        self.meta_rows = meta_rows
        self.out_path = Path(out_path)
        self.doc = Document()
        self._setup_page()
        self._setup_styles()

    # ---------- 基础样式 ----------
    def _setup_page(self):
        sec = self.doc.sections[0]
        sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)
        sec.top_margin = sec.bottom_margin = Cm(2.54)
        sec.left_margin = sec.right_margin = Cm(3.0)
        # 页脚页码
        footer_p = sec.footer.paragraphs[0]
        footer_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        run = footer_p.add_run("— ")
        _set_run_font(run, size=9)
        self._add_field(footer_p, "PAGE")
        run = footer_p.add_run(" —")
        _set_run_font(run, size=9)

    def _setup_styles(self):
        normal = self.doc.styles["Normal"]
        _style_font(normal, SONG, "Times New Roman", 12)
        normal.font.color.rgb = RGBColor(0, 0, 0)
        pf = normal.paragraph_format
        pf.line_spacing = 1.5
        pf.space_after = Pt(0)
        pf.first_line_indent = Pt(24)

        h1 = self.doc.styles["Heading 1"]
        _style_font(h1, HEI, "Arial", 16, bold=True)
        h1.font.color.rgb = RGBColor(0, 0, 0)
        h1.paragraph_format.space_before = Pt(18)
        h1.paragraph_format.space_after = Pt(12)
        h1.paragraph_format.first_line_indent = Pt(0)
        h1.paragraph_format.line_spacing = 1.3

        h2 = self.doc.styles["Heading 2"]
        _style_font(h2, HEI, "Arial", 14, bold=True)
        h2.font.color.rgb = RGBColor(0, 0, 0)
        h2.paragraph_format.space_before = Pt(12)
        h2.paragraph_format.space_after = Pt(8)
        h2.paragraph_format.first_line_indent = Pt(0)
        h2.paragraph_format.line_spacing = 1.3

        h3 = self.doc.styles["Heading 3"]
        _style_font(h3, HEI, "Arial", 12, bold=True)
        h3.font.color.rgb = RGBColor(0, 0, 0)
        h3.paragraph_format.space_before = Pt(8)
        h3.paragraph_format.space_after = Pt(6)
        h3.paragraph_format.first_line_indent = Pt(0)
        h3.paragraph_format.line_spacing = 1.3

    def _add_field(self, paragraph, instr):
        run = paragraph.add_run()
        f1 = OxmlElement("w:fldChar")
        f1.set(qn("w:fldCharType"), "begin")
        it = OxmlElement("w:instrText")
        it.set(qn("xml:space"), "preserve")
        it.text = instr
        f2 = OxmlElement("w:fldChar")
        f2.set(qn("w:fldCharType"), "end")
        run._r.append(f1)
        run._r.append(it)
        run._r.append(f2)
        return run

    # ---------- 结构 ----------
    def cover(self):
        for _ in range(3):
            self.doc.add_paragraph()
        p = self.doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Pt(0)
        _fill_runs(p, self.title, cn=HEI, en="Arial", size=26, base_bold=True)

        p = self.doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Pt(0)
        p.paragraph_format.space_before = Pt(18)
        _fill_runs(p, self.subtitle, cn=HEI, en="Arial", size=18, base_bold=True)

        self.doc.add_paragraph()
        self.doc.add_paragraph()

        table = self.doc.add_table(rows=0, cols=2)
        table.style = "Table Grid"
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        for k, v in self.meta_rows:
            row = table.add_row()
            for idx, text in enumerate((k, v)):
                cell = row.cells[idx]
                cell.width = Cm(3.6 if idx == 0 else 9.4)
                para = cell.paragraphs[0]
                para.paragraph_format.first_line_indent = Pt(0)
                para.alignment = WD_ALIGN_PARAGRAPH.CENTER if idx == 0 else WD_ALIGN_PARAGRAPH.LEFT
                _fill_runs(para, text, cn=HEI if idx == 0 else SONG, size=12, base_bold=(idx == 0))
        self.page_break()

    def toc(self):
        p = self.doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Pt(0)
        _fill_runs(p, "目  录", cn=HEI, en="Arial", size=16, base_bold=True)
        p = self.doc.add_paragraph()
        p.paragraph_format.first_line_indent = Pt(0)
        p.paragraph_format.line_spacing = 1.5
        run = p.add_run()
        f1 = OxmlElement("w:fldChar")
        f1.set(qn("w:fldCharType"), "begin")
        f1.set(qn("w:dirty"), "true")
        it = OxmlElement("w:instrText")
        it.set(qn("xml:space"), "preserve")
        it.text = 'TOC \\o "1-3" \\h \\z \\u'
        f2 = OxmlElement("w:fldChar")
        f2.set(qn("w:fldCharType"), "separate")
        t = OxmlElement("w:t")
        t.text = "（在 Word 中右键此处选择“更新域”即可生成目录）"
        f3 = OxmlElement("w:fldChar")
        f3.set(qn("w:fldCharType"), "end")
        for el in (f1, it, f2, t, f3):
            run._r.append(el)
        self.page_break()

    def h1(self, text):
        self.doc.add_heading(text, level=1)

    def h2(self, text):
        self.doc.add_heading(text, level=2)

    def h3(self, text):
        self.doc.add_heading(text, level=3)

    def p(self, text):
        para = self.doc.add_paragraph()
        para.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        _fill_runs(para, text)
        return para

    def lead(self, label, text):
        """加粗引导段，如 lead('功能定义：', '...')。"""
        para = self.doc.add_paragraph()
        para.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        _fill_runs(para, f"**{label}**{text}")
        return para

    def bullets(self, items, indent=24):
        for item in items:
            para = self.doc.add_paragraph()
            para.paragraph_format.first_line_indent = Pt(0)
            para.paragraph_format.left_indent = Pt(indent)
            para.paragraph_format.line_spacing = 1.5
            _fill_runs(para, f"● {item}")

    def numbers(self, items, indent=24):
        for i, item in enumerate(items, 1):
            para = self.doc.add_paragraph()
            para.paragraph_format.first_line_indent = Pt(0)
            para.paragraph_format.left_indent = Pt(indent)
            para.paragraph_format.line_spacing = 1.5
            _fill_runs(para, f"（{i}）{item}")

    def note(self, text):
        para = self.doc.add_paragraph()
        para.paragraph_format.first_line_indent = Pt(0)
        para.paragraph_format.left_indent = Pt(12)
        para.paragraph_format.line_spacing = 1.3
        _fill_runs(para, text, cn=KAI, size=10.5, color=(0x40, 0x40, 0x40))
        return para

    def table(self, headers, rows, widths=None, font_size=10.5):
        table = self.doc.add_table(rows=1, cols=len(headers))
        table.style = "Table Grid"
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        hdr = table.rows[0].cells
        for i, h in enumerate(headers):
            para = hdr[i].paragraphs[0]
            para.paragraph_format.first_line_indent = Pt(0)
            para.alignment = WD_ALIGN_PARAGRAPH.CENTER
            _fill_runs(para, h, cn=HEI, en="Arial", size=font_size, base_bold=True)
            tc_pr = hdr[i]._tc.get_or_add_tcPr()
            shd = OxmlElement("w:shd")
            shd.set(qn("w:val"), "clear")
            shd.set(qn("w:fill"), "E8EEF7")
            tc_pr.append(shd)
        for row_data in rows:
            cells = table.add_row().cells
            for i, text in enumerate(row_data):
                para = cells[i].paragraphs[0]
                para.paragraph_format.first_line_indent = Pt(0)
                para.paragraph_format.line_spacing = 1.15
                _fill_runs(para, text, size=font_size)
        if widths:
            for row in table.rows:
                for i, w in enumerate(widths):
                    row.cells[i].width = Cm(w)
        self.doc.add_paragraph().paragraph_format.first_line_indent = Pt(0)
        return table

    def caption(self, text):
        para = self.doc.add_paragraph()
        para.alignment = WD_ALIGN_PARAGRAPH.CENTER
        para.paragraph_format.first_line_indent = Pt(0)
        _fill_runs(para, text, cn=HEI, en="Arial", size=10.5)
        return para

    def quote(self, text):
        para = self.doc.add_paragraph()
        para.paragraph_format.first_line_indent = Pt(0)
        para.paragraph_format.left_indent = Pt(24)
        para.paragraph_format.right_indent = Pt(12)
        para.paragraph_format.line_spacing = 1.3
        _fill_runs(para, text, cn=KAI, size=10.5)

    def ascii_figure(self, text):
        """预格式化图形块（架构图等）。"""
        para = self.doc.add_paragraph()
        para.paragraph_format.first_line_indent = Pt(0)
        para.paragraph_format.left_indent = Pt(6)
        para.paragraph_format.line_spacing = 1.0
        para.alignment = WD_ALIGN_PARAGRAPH.LEFT
        for i, line in enumerate(text.strip("\n").split("\n")):
            if i:
                para.add_run().add_break()
            run = para.add_run(line)
            _set_run_font(run, cn=SONG, en="Consolas", size=8.5)
        self.doc.add_paragraph().paragraph_format.first_line_indent = Pt(0)

    def image(self, path, width_cm=15.0):
        """插入居中图片（建议配套 caption()）。"""
        para = self.doc.add_paragraph()
        para.alignment = WD_ALIGN_PARAGRAPH.CENTER
        para.paragraph_format.first_line_indent = Pt(0)
        para.paragraph_format.space_before = Pt(6)
        para.paragraph_format.space_after = Pt(2)
        run = para.add_run()
        run.add_picture(str(path), width=Cm(width_cm))
        return para

    def page_break(self):
        self.doc.add_page_break()

    def save(self):
        self.out_path.parent.mkdir(parents=True, exist_ok=True)
        self.doc.save(str(self.out_path))
        return self.out_path
