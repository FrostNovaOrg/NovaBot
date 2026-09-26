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
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
UL = re.compile(r"^\s*[-*]\s+")
OL = re.compile(r"^\s*\d+\.\s+")
TABLE_SEP = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def link_html(label, target, links, kind):
    links.append((kind, target))
    if kind == "img":
        return '<img src="%s" alt="%s">' % (esc(target), esc(label))
    return '<a href="%s">%s</a>' % (esc(target), esc(label))


def inline_html(text, links):
    out, pos = [], 0
    for m in INLINE.finditer(text):
        out.append(esc(text[pos:m.start()]))
        tok = m.group(0)
        if tok.startswith("**"):
            out.append("<strong>%s</strong>" % esc(tok[2:-2]))
        elif tok.startswith("`"):
            out.append("<code>%s</code>" % esc(tok[1:-1]))
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
                items.append((ordered, text))
                i += 1
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
            html.append("<table><thead><tr>%s</tr></thead><tbody>%s</tbody></table>"
                        % (th, "".join(trs)))
        else:
            para = " ".join(block[1])
            text.append(para)
            html.append("<p>%s</p>" % inline_html(para, links))
    return "".join(html), used, "\n".join(text)


# ---------- 站点构建 ----------

def page_shell(title, toc, content, pager, cur):
    toc_html = toc_html_for(toc, cur)
    head_title = title if title == SITE_NAME else "%s · %s" % (title, SITE_NAME)
    search = ('<div class="search"><input type="search" class="q" '
              'placeholder="搜索手册…" autocomplete="off"><div class="hits"></div></div>')
    return """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<link rel="stylesheet" href="manual.css">
</head>
<body>
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
<script src="search-data.js"></script>
<script src="search.js"></script>
</body>
</html>""" % (esc(head_title), SITE_NAME, search, toc_html,
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
    for src in (ASSETS / "manual.css", ASSETS / "search.js"):
        shutil.copy(src, OUT / src.name)

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

    # 目录页（首页）
    items = "".join('<li><a href="%s.html">%s</a></li>' % (esc(s), esc(t)) for s, t in toc)
    home = """<article><h1>%s</h1>
<p>NovaBot 是一个哔哩哔哩直播与动态推送机器人：盯住你关心的 UP 主，
开播、下播、发动态时把消息推到 QQ 群或好友，下播后自动生成数据报告图。
这本手册讲怎么把它装起来、配好、用顺手。</p>
<p>不知道从哪看起就按顺序读；要找具体的东西，用左侧的搜索框。</p>
<nav class="sect"><span>全书目录</span><ul>%s</ul></nav></article>""" % (SITE_NAME, items)
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
