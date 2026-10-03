#!/usr/bin/env python3
# 用户手册静态站生成器。只依赖 python3 标准库，用法：
#   python3 manual/build.py
# 源文件在本目录（章节顺序读 chapters.txt，写法见本目录 README.md），
# 产物生成到 manual/dist/。生成时检查全部站内链接、页内锚点与图片引用，
# 有断的就逐条报出并以退码 1 结束，不产出残缺的站。
import json
import re
import shutil
import sys
import urllib.parse
from pathlib import Path

MANUAL = Path(__file__).resolve().parent
OUT = MANUAL / "dist"
ASSETS = MANUAL / "assets"
SITE_NAME = "NovaBot 用户手册"
EXTERNAL = ("http://", "https://", "mailto:")

# ---------- Markdown 子集渲染（支持的写法见 manual/README.md） ----------

INLINE = re.compile(r"\*\*.+?\*\*|`[^`]+`|!\[[^\]]*\]\([^)]+\)|\[[^\]]+\]\([^)]+\)")
# 粗体里面再认一层：行内代码与链接；图片不必支持，照旧当文字
INLINE_STRONG = re.compile(r"`[^`]+`|!\[[^\]]*\]\([^)]+\)|\[[^\]]+\]\([^)]+\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
UL = re.compile(r"^\s*[-*]\s+")
OL = re.compile(r"^\s*\d+\.\s+")
CONT = re.compile(r"^ {2,}\S")
LATIN = re.compile(r"[A-Za-z0-9]")
TABLE_SEP = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# 行内代码在这些符号后面允许折行。整段放得进容器一行时不拆，由样式表决定。
_CODE_BREAK = re.compile(r"([/._=?&-])")


def inline_code_html(raw):
    parts = _CODE_BREAK.split(raw)
    bits = []
    for i, part in enumerate(parts):
        if part == "":
            continue
        piece = esc(part)
        if i % 2 == 1:
            piece += "<wbr>"
        bits.append(piece)
    return "<code>%s</code>" % "".join(bits)


def link_html(label, target, links, kind):
    links.append((kind, target))
    if kind == "img":
        return '<img src="%s" alt="%s">' % (esc(target), esc(label))
    return '<a href="%s">%s</a>' % (esc(target), esc(label))


def inline_html(text, links, in_strong=False):
    """转行内写法。in_strong=True 是在粗体里面：认行内代码与链接，图片当文字。"""
    out, pos = [], 0
    for m in (INLINE_STRONG if in_strong else INLINE).finditer(text):
        out.append(esc(text[pos:m.start()]))
        tok = m.group(0)
        if tok.startswith("**"):
            out.append("<strong>%s</strong>" % inline_html(tok[2:-2], links, True))
        elif tok.startswith("`"):
            out.append(inline_code_html(tok[1:-1]))
        elif in_strong and tok.startswith("!"):
            out.append(esc(tok))
        else:
            mm = re.match(r"(!?)\[([^\]]*)\]\(([^)]+)\)", tok)
            img, label, target = mm.groups()
            out.append(link_html(label, target, links, "img" if img else "a"))
        pos = m.end()
    out.append(esc(text[pos:]))
    return "".join(out)


def slugify(text, used):
    s = re.sub(r"[^\w一-鿿]+", "-", text.strip()).strip("-")
    base, i = s, 1
    while s in used:
        i += 1
        s = "%s-%d" % (base, i)
    used.add(s)
    return s


def split_row(line):
    line = line.strip().strip("|")
    return [c.strip() for c in line.split("|")]


def is_marker(line):
    return bool(HEADING.match(line) or line.startswith("```")
                or line.startswith(">") or UL.match(line) or OL.match(line))


def is_continuation(lines, i):
    """列表项折行：紧跟在项后、行首至少两个空格，且不是新的一项、也不起别的块。"""
    line = lines[i]
    if not CONT.match(line) or UL.match(line) or OL.match(line):
        return False
    if is_marker(line.lstrip()):
        return False
    return not ("|" in line and i + 1 < len(lines) and TABLE_SEP.match(lines[i + 1]))


def join_continuation(text, cont):
    """按中文排版接上续行：接缝一侧（隔着 ` 与 ** 看）是拉丁字母或数字才留空格。"""
    text = text.rstrip()
    left, right = text.rstrip("`*")[-1:], cont.lstrip("`*")[:1]
    sep = " " if LATIN.match(left) or LATIN.match(right) else ""
    return text + sep + cont


def parse_blocks(lines):
    blocks, i, n = [], 0, len(lines)
    while i < n:
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("```"):
            lang = line[3:].strip()
            buf = []
            i += 1
            while i < n and not lines[i].startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            blocks.append(("code", lang, buf))
            continue
        m = HEADING.match(line)
        if m:
            blocks.append(("h", len(m.group(1)), m.group(2).strip()))
            i += 1
            continue
        if line.startswith(">"):
            buf = []
            while i < n and lines[i].startswith(">"):
                buf.append(lines[i].lstrip("> ").rstrip())
                i += 1
            blocks.append(("quote", buf))
            continue
        if UL.match(line) or OL.match(line):
            items = []
            while i < n and (UL.match(lines[i]) or OL.match(lines[i])):
                ordered = bool(OL.match(lines[i]))
                text = re.sub(r"^\s*(?:[-*]|\d+\.)\s+", "", lines[i])
                i += 1
                while i < n and is_continuation(lines, i):
                    text = join_continuation(text, lines[i].strip())
                    i += 1
                items.append((ordered, text))
            blocks.append(("list", items))
            continue
        if "|" in line and i + 1 < n and TABLE_SEP.match(lines[i + 1]):
            header = split_row(line)
            i += 2
            rows = []
            while i < n and "|" in lines[i] and lines[i].strip():
                rows.append(split_row(lines[i]))
                i += 1
            blocks.append(("table", header, rows))
            continue
        buf = [line]
        i += 1
        while (i < n and lines[i].strip() and not is_marker(lines[i])
               and not ("|" in lines[i] and i + 1 < n and TABLE_SEP.match(lines[i + 1]))):
            buf.append(lines[i])
            i += 1
        blocks.append(("p", buf))
    return blocks


ALERT = {"NOTE": ("说明", "note"), "TIP": ("提示", "tip"), "WARNING": ("注意", "warn")}


def render_page(blocks, links):
    """渲染正文 HTML，收集 H2/H3 锚点与纯文本。"""
    used, html, text = set(), [], []

    def heading(line):
        m = HEADING.match(line)
        return m.group(2).strip() if m else line

    for block in blocks:
        kind = block[0]
        if kind == "h":
            _, level, line = block
            text.append(line)
            if level == 1:
                html.append("<h1>%s</h1>" % inline_html(line, links))
            else:
                slug = slugify(line, used)
                html.append('<h%d id="%s">%s</h%d>'
                            % (level, esc(slug), inline_html(line, links), level))
        elif kind == "code":
            _, lang, buf = block
            text.extend(buf)
            cls = ' class="lang"' if lang else ""
            html.append("<pre%s><code>%s</code></pre>" % (cls, esc("\n".join(buf))))
        elif kind == "quote":
            _, buf = block
            m = re.match(r"\[!(NOTE|TIP|WARNING)\]\s*(.*)", buf[0]) if buf else None
            if m:
                label, cls = ALERT[m.group(1)]
                body = [m.group(2)] + [b for b in buf[1:] if b]
                text.append(" ".join(body))
                inner = "<br>".join(inline_html(b, links) for b in body if b)
                html.append('<div class="alert %s"><span class="al">%s</span>%s</div>'
                            % (cls, label, inner))
            else:
                text.extend(buf)
                html.append("<blockquote>%s</blockquote>"
                            % "<br>".join(inline_html(b, links) for b in buf if b))
        elif kind == "list":
            ordered = block[1][0][0]
            tag = "ol" if ordered else "ul"
            items = []
            for _, item in block[1]:
                text.append(item)
                items.append("<li>%s</li>" % inline_html(item, links))
            html.append("<%s>%s</%s>" % (tag, "".join(items), tag))
        elif kind == "table":
            _, header, rows = block
            text.extend(header)
            th = "".join("<th>%s</th>" % inline_html(c, links) for c in header)
            trs = []
            for row in rows:
                text.extend(row)
                trs.append("<tr>%s</tr>"
                           % "".join("<td>%s</td>" % inline_html(c, links) for c in row))
            html.append('<div class="table-wrap"><table><thead><tr>%s</tr></thead>'
                        "<tbody>%s</tbody></table></div>" % (th, "".join(trs)))
        else:
            para = " ".join(block[1])
            text.append(para)
            html.append("<p>%s</p>" % inline_html(para, links))
    return "".join(html), used, "\n".join(text)


# ---------- 站点构建 ----------

# 顶栏仓库标志。打开页面前先把深浅色写到 html 上，样式表才不会先闪一下另一种颜色。
_GH_ICON = (
    '<svg class="gh-ico" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">'
    '<path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59'
    '.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23'
    '-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87'
    ' 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15'
    '-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2'
    ' .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07'
    '-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38'
    'A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"></path></svg>'
)
_THEME_BOOT = (
    "<script>\n"
    "(function () {\n"
    "  var mode = \"system\";\n"
    "  try {\n"
    "    var saved = localStorage.getItem(\"novabot-manual-theme\");\n"
    "    if (saved === \"dark\" || saved === \"light\" || saved === \"system\") mode = saved;\n"
    "  } catch (e) {}\n"
    "  var dark = mode === \"dark\";\n"
    "  if (mode === \"system\" && window.matchMedia) {\n"
    "    dark = window.matchMedia(\"(prefers-color-scheme: dark)\").matches;\n"
    "  }\n"
    "  document.documentElement.setAttribute(\"data-theme\", dark ? \"dark\" : \"light\");\n"
    "  document.documentElement.setAttribute(\"data-mode\", mode);\n"
    "})();\n"
    "</script>"
)
_SVG_SUN = (
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.2" '
    'fill="none" stroke="currentColor" stroke-width="1.8"/>'
    '<path d="M12 3.2v2.2M12 18.6v2.2M3.2 12h2.2M18.6 12h2.2M6 6l1.6 1.6M16.4 16.4L18 18'
    'M18 6l-1.6 1.6M7.6 16.4L6 18" fill="none" stroke="currentColor" '
    'stroke-width="1.8" stroke-linecap="round"/></svg>'
)
_SVG_MOON = (
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.6 3.8A7.4 7.4 0 1 0 19 15.8'
    ' 5.8 5.8 0 0 1 15.6 3.8z" fill="none" stroke="currentColor" stroke-width="1.8" '
    'stroke-linejoin="round"/></svg>'
)
_SVG_SYSTEM = (
    '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4.5" width="17" height="11" '
    'rx="1.6" fill="none" stroke="currentColor" stroke-width="1.8"/>'
    '<path d="M8 19.5h8M12 15.5v4" fill="none" stroke="currentColor" stroke-width="1.8" '
    'stroke-linecap="round"/></svg>'
)

# 首页章节入口的小图标，按章节顺序取。
_ICON_PATHS = (
    "M12 3.2l1.9 4.7 5 .6-3.8 3.4 1.1 5-4.2-2.5-4.2 2.5 1.1-5L5.1 8.5l5-.6z",
    "M5 7h14M5 12h14M5 17h8",
    "M12 4v9.5M8.5 10.5L12 14l3.5-3.5M6 19h12",
    "M5 5h14v14H5zM5 9h14",
    "M12 4.5a7.5 7.5 0 1 0 .01 0zM12 8v4.2l2.8 1.8",
    "M12 11.2a3.1 3.1 0 1 0 0-6.2 3.1 3.1 0 0 0 0 6.2zM6.2 19.2c.8-2.8 2.8-4.2 5.8-4.2s5 1.4 5.8 4.2",
    "M8 11a2.4 2.4 0 1 0 0-4.8A2.4 2.4 0 0 0 8 11zM16 11a2.4 2.4 0 1 0-.01 0zM4.5 18.5c.6-2.2 2-3.3 3.5-3.3s2.9 1.1 3.5 3.3M12.5 18.5c.6-2.2 2-3.3 3.5-3.3s2.9 1.1 3.5 3.3",
    "M6 16.5V8.2A2.2 2.2 0 0 1 8.2 6h7.6A2.2 2.2 0 0 1 18 8.2V14a2.2 2.2 0 0 1-2.2 2.2H9.2L6 18.5z",
    "M5 16V9M10 16V6M15 16v-4M20 16V8",
    "M15.5 4.8A6.4 6.4 0 1 0 16 15.2 5.2 5.2 0 0 1 15.5 4.8z",
    "M12 7v5l3 2M12 4.5a7.5 7.5 0 1 0 .01 0z",
    "M5 8h14M5 12h14M5 16h14",
    "M12 19V6M8 10l4-4 4 4M6 19h12",
    "M12 5.5l6.5 3v5.2c0 3.4-2.6 5.6-6.5 6.8-3.9-1.2-6.5-3.4-6.5-6.8V8.5z",
    "M6 18l4-12h4l4 12M8.2 14h7.6",
    "M7 7h4v4H7zM13 13h4v4h-4zM13 9h4M7 15h4",
    "M12 4.5l6 2.2v5.2c0 3.6-2.5 6-6 7.6-3.5-1.6-6-4-6-7.6V6.7z",
    "M8 8h5a3 3 0 0 1 0 6H8zM16 16H11a3 3 0 0 1 0-6",
)


def _icon(i):
    d = _ICON_PATHS[i] if i < len(_ICON_PATHS) else _ICON_PATHS[-1]
    return ('<svg class="ci" viewBox="0 0 24 24" aria-hidden="true">'
            '<path d="%s" fill="none" stroke="currentColor" stroke-width="1.8" '
            'stroke-linecap="round" stroke-linejoin="round"/></svg>' % d)


_THEME_HINTS = {
    "dark": "当前：深色，点一下换成浅色",
    "light": "当前：浅色，点一下换成跟随系统",
    "system": "当前：跟随系统，点一下换成深色",
}


def _theme_button():
    def opt(mode, label, svg):
        return ('<span class="theme-opt opt-%s" data-hint="%s">%s<span>%s</span></span>'
                % (mode, _THEME_HINTS[mode], svg, label))
    hint = _THEME_HINTS["system"]
    return ('<button type="button" class="theme" aria-live="polite" '
            'title="%s" aria-label="%s">' % (hint, hint)
            + opt("dark", "深色", _SVG_MOON)
            + opt("light", "浅色", _SVG_SUN)
            + opt("system", "跟随系统", _SVG_SYSTEM)
            + "</button>")


def home_html(toc):
    """首页：抬头卡片、从哪开始、分组卡片。分组按现有章节顺序切，不改文件名。"""
    bands = (
        ("认识与安装", 0, 4),
        ("日常使用", 4, 12),
        ("升级与排障", 12, 14),
        ("附录", 14, len(toc)),
    )
    chunks = []
    for label, a, b in bands:
        cards = []
        for i, (stem, title) in enumerate(toc[a:b], start=a):
            cards.append(
                '<a class="card" href="%s.html"><span class="ci-wrap">%s</span>'
                '<span class="ct">%s</span></a>'
                % (esc(stem), _icon(i), esc(title)))
        if cards:
            chunks.append('<p class="band">%s</p><div class="cards">%s</div>'
                          % (esc(label), "".join(cards)))
    start = toc[0][0] if toc else "index"
    lead = ("NovaBot 是一个哔哩哔哩直播与动态推送机器人：盯住你关心的 UP 主，"
            "开播、下播、发动态时把消息推到 QQ 群或好友，下播后自动生成数据报告图。")
    return ('<article class="home"><div class="hero"><h1>%s</h1>'
            '<p class="lead">%s</p>'
            '<a class="start" href="%s.html">从哪开始</a></div>%s</article>'
            % (esc(SITE_NAME), esc(lead), esc(start), "".join(chunks)))


def page_shell(title, toc, content, pager, cur):
    toc_html = toc_html_for(toc, cur)
    head_title = title if title == SITE_NAME else "%s · %s" % (title, SITE_NAME)
    search = ('<div class="search"><input type="search" class="q" '
              'placeholder="搜索手册…" autocomplete="off"><div class="hits"></div></div>')
    topbar = (
        '<header class="topbar">'
        '<a class="top-brand" href="index.html">'
        '<span class="dot" aria-hidden="true"></span>NovaBot</a>'
        '<div class="top-actions">'
        '<a class="gh" href="https://github.com/FrostNovaOrg/NovaBot" '
        'target="_blank" rel="noopener noreferrer">%s<span>GitHub</span></a>'
        '%s</div></header>'
    ) % (_GH_ICON, _theme_button())
    return """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
%s
<link rel="stylesheet" href="manual.css">
</head>
<body>
%s
<details class="nav-toggle"><summary>目录</summary>
<nav class="toc toc-in-toggle">
<div class="brand"><a href="index.html">%s</a></div>
%s
%s
</nav>
</details>
<div class="layout">
<nav class="toc">
<div class="brand"><a href="index.html">%s</a></div>
%s
%s
</nav>
<main>
%s
%s
</main>
</div>
<script src="theme.js"></script>
<script src="search-data.js"></script>
<script src="search.js"></script>
</body>
</html>""" % (esc(head_title), _THEME_BOOT, topbar,
              SITE_NAME, search, toc_html,
              SITE_NAME, search, toc_html, content, pager)


def toc_html_for(toc, cur):
    out = []
    for stem, title in toc:
        cls = ' class="cur"' if stem == cur else ""
        out.append('<a href="%s.html"%s>%s</a>' % (esc(stem), cls, esc(title)))
    return "\n".join(out)


def href(target):
    """把 Markdown 链接目标转成站内 URL：.md 换成 .html，锚点转义。"""
    if target.startswith(EXTERNAL):
        return target
    if ".md" in target:
        path, _, anchor = target.partition("#")
        target = path[:-3] + ".html"
        if anchor:
            target += "#" + urllib.parse.quote(slug_of(anchor))
    elif target.startswith("#"):
        target = "#" + urllib.parse.quote(slug_of(target[1:]))
    return target


def slug_of(text):
    return re.sub(r"[^\w一-鿿]+", "-", text.strip()).strip("-")


def main():
    manifest = MANUAL / "chapters.txt"
    if not manifest.exists():
        sys.exit("找不到 %s（章节清单），无法生成。" % manifest)
    listed = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            listed.append(line)

    errors = []
    on_disk = sorted(p.name for p in MANUAL.glob("*.md") if p.name != "README.md")
    for name in listed:
        if not (MANUAL / name).exists():
            errors.append("chapters.txt: [%s](%s) — 清单列了这一章，但文件不存在"
                          % (name, name))
    for name in on_disk:
        if name not in listed:
            errors.append("chapters.txt: [%s](%s) — 文件在目录里，但清单没列它"
                          % (name, name))
    if errors:
        report(errors)

    pages = []   # (stem, title, blocks)
    for name in listed:
        lines = (MANUAL / name).read_text(encoding="utf-8").splitlines()
        h1 = [b for b in parse_blocks(lines) if b[0] == "h" and b[1] == 1]
        if len(h1) != 1:
            errors.append("%s: 需要恰好一个一级标题（# ），实有 %d 个" % (name, len(h1)))
            title = name
        else:
            title = h1[0][2]
        pages.append((name[:-3], title, parse_blocks(lines)))
    if errors:
        report(errors)

    # 渲染各页，收集锚点与「每页各自」的站内链接清单（报错要能说出是哪一页）
    rendered, anchors, per_page_links = {}, {}, []
    for stem, title, blocks in pages:
        links = []
        body, used, text = render_page(blocks, links)
        rendered[stem] = (title, body, used, text)
        anchors[stem] = used
        per_page_links.append((stem, links))

    toc = [(stem, title) for stem, title, _ in pages]
    valid = {"index"} | set(anchors)
    errors2 = []
    for stem, links in per_page_links:
        for kind, target in links:
            if target.startswith(EXTERNAL):
                if kind == "img":
                    errors2.append("%s.md: ![](%s) — 图片不许引站外地址，"
                                   "放 manual/images/ 里" % (stem, target))
                continue
            if kind == "img":
                if not (MANUAL / target).exists():
                    errors2.append("%s.md: 图片 %s — 文件不存在" % (stem, target))
                continue
            path, _, anchor = target.partition("#")
            if path:
                if not path.endswith(".md"):
                    errors2.append("%s.md: [%s](%s) — 站内链接请指向 .md 文件"
                                   % (stem, path, target))
                    continue
                dst = path[:-3]
                if dst not in valid:
                    errors2.append("%s.md: [%s](%s) — 没有这一页" % (stem, path, target))
                    continue
                if anchor and dst in anchors and slug_of(anchor) not in anchors[dst]:
                    errors2.append("%s.md: [%s](%s) — 目标页没有这个锚点"
                                   % (stem, path, target))
            elif anchor:
                if slug_of(anchor) not in anchors[stem]:
                    errors2.append("%s.md: [#%s](%s) — 本页没有这个锚点"
                                   % (stem, anchor, target))
    if errors2:
        report(errors + errors2)

    # 写产物
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    for name in ("manual.css", "search.js", "theme.js"):
        shutil.copy(ASSETS / name, OUT / name)

    entries = []
    for i, (stem, title, blocks) in enumerate(pages):
        _, body, used, text = rendered[stem]
        h2s = [b[2] for b in blocks if b[0] == "h" and b[1] == 2]
        sect = ""
        if len(h2s) >= 2:
            items = "".join('<li><a href="#%s">%s</a></li>'
                            % (urllib.parse.quote(slug_of(h)), esc(h)) for h in h2s)
            sect = '<nav class="sect"><span>本页内容</span><ul>%s</ul></nav>' % items
        pager = ['<div class="pager">']
        if i > 0:
            prev = toc[i - 1]
            pager.append('<a class="prev" href="%s.html">← 上一页：%s</a>'
                         % (prev[0], esc(prev[1])))
        else:
            pager.append('<a class="prev" href="index.html">← 目录页</a>')
        if i + 1 < len(toc):
            nxt = toc[i + 1]
            pager.append('<a class="next" href="%s.html">下一页：%s →</a>'
                         % (nxt[0], esc(nxt[1])))
        pager.append("</div>")
        # 正文里的 .md 链接换成 .html；页内目录插在 H1 之后
        body = rewrite_links(body)
        if sect:
            body = body.replace("</h1>", "</h1>" + sect, 1)
        (OUT / (stem + ".html")).write_text(
            page_shell(title, toc, "<article>%s</article>" % body,
                       "".join(pager), stem), encoding="utf-8")
        entries.append({"u": stem + ".html", "t": title, "c": title + "\n" + text})

    # 目录页（首页）：抬头、从哪开始、分组卡片。分组不改各章文件名。
    home = home_html(toc)
    (OUT / "index.html").write_text(
        page_shell(SITE_NAME, toc, home, '<div class="pager"></div>', "index"),
        encoding="utf-8")

    # 搜索索引：search-data.js 是纯 JSON 前缀包一层 const，python 与浏览器都好读
    (OUT / "search-data.js").write_text(
        "const SEARCH_DATA = %s;\n" % json.dumps(entries, ensure_ascii=False),
        encoding="utf-8")

    # 手册引用的本地图片整目录带过去
    img_root = MANUAL / "images"
    if img_root.exists():
        shutil.copytree(img_root, OUT / "images")

    print("生成 %d 页 + 目录页 → %s" % (len(pages), OUT))


def rewrite_links(body):
    """把正文里指向 .md 的 href 换成 .html（锚点按标题转写）。"""
    def repl(m):
        return 'href="%s"' % href(m.group(1))
    return re.sub(r'href="([^"]+)"', repl, body)


def report(errors):
    for e in errors:
        print("断链: %s" % e, file=sys.stderr)
    sys.exit("共 %d 处问题，未生成。" % len(errors))


if __name__ == "__main__":
    main()
