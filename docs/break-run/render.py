#!/usr/bin/env python3
"""把 breakrun.py 留下的 JSON 原始记录渲染成案卷。

分成两个脚本是为了「数字从哪来」可查：跑一次 ``breakrun.py`` 留一份 JSON，
案卷里的每个数都能在那份 JSON 里找到出处，而渲染这一步不接触被测代码、
也不产生任何新数字。

用法::

    docs/break-run/render.py docs/break-run/84e3358.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

CONTROL = "BreakRunRulerControlTest.gammaRedOnPurpose"

HEADER = """# 破坏跑案卷：{commit}

> 一条从没红过的判据，就还没有人证明过它拦得住什么。

本件由 `docs/break-run/breakrun.py` 实跑生成，原始记录在同目录 `{commit}.json`，
用 `docs/break-run/render.py` 渲染成本文。每个变体三样俱全：**改在哪里 / 红了哪几条
（含 surefire 报的原话）/ 时间戳**。

- 被测版本：**`{commit}`**。开跑前核过工作区干净；每个变体是一处单点替换，跑完
  `git checkout --` 还原，下一个变体从干净树重来
- 每次跑之前删掉 `starbot-core/target/surefire-reports`，**不让上一次的 XML 冒充这一次**
- 判据数口径：surefire 报告里的 `<testcase>` 元素数。**不能用 `tests="..."` 属性**——
  判据都写在 `@Nested` 里的类，那个属性恒为 0
- 每个变体跑的是**整个 starbot-core 模块**（{base_total} 条），不是只跑相关的几个类。
  这样才看得出「它只红了该红的，别的一条没动」
- 跑完时间：{finished}

## 一、验尺

数红的那把尺自己先在一个已知样本上量过：临时写入一个「三绿夹一红」的判据类
（红的排第三位，绿的在 surefire 里写成自闭合的 `<testcase .../>`），跑完即删。

| 项 | 值 |
|---|---|
| 样本里应当红的 | `{control}` |
| 尺子实际报的 | {ruler_reds} |
| 当时全量 | {ruler_total} |
| 结论 | {ruler_verdict} |

同一份 XML 交给上一轮那把正则尺（`<testcase[^>]*name="([^"]+)"[^>]*>(.*?)</testcase>`
配 `re.S`），它报的是 `com.starlwr.bot.core.BreakRunRulerControlTest` —— **两处都错**：
贪婪的 `[^>]*` 越过 `name="alphaGreen"` 抓到了 `classname="` 尾巴上的那个 `name="`，
而 `re.S` 让 `(.*?)` 从第一个自闭合的绿判据一路吞到第三条的 `</testcase>`。
**红的条数与名字全不可信，输出却看着完全正常。**

"""


def main() -> None:
    raw = Path(sys.argv[1])
    data = json.loads(raw.read_text(encoding="utf-8"))
    commit = data["提交号"]
    ruler = data["验尺"]

    out = [HEADER.format(
        commit=commit,
        base_total=data["基线条数"],
        finished=data["跑完"],
        control=CONTROL,
        ruler_reds="、".join(f"`{r}`" for r in ruler["样本红"]) or "（无）",
        ruler_total=f"{ruler['全量条数']} 条（基线 {data['基线条数']} + 样本 4）",
        ruler_verdict="✅ 数对了，名字没串行" if ruler["样本红"] == [CONTROL] else "❌ 没过",
    )]

    out.append("## 二、基线\n")
    base_red = data.get("基线红", [])
    if not base_red:
        out.append(f"没动过的树：**{data['基线条数']} 条，红 0 条。**\n")
    else:
        out.append(f"没动过的树：**{data['基线条数']} 条，红 {len(base_red)} 条**——")
        out.append("都在下面这份「已知不稳」名单里，**记账不拦路**，也不记在任何变体头上：\n")
        for r in base_red:
            out.append(f"- `{r['判据']}`（{r['耗时秒']}s）　{r['报的']}")
        out.append("")
        for name, why in data.get("已知不稳", {}).items():
            out.append(f"> `{name}`：{why}")
        out.append("")

    results = data["变体"]
    out.append("## 三、一览\n")
    out.append("| 变体 | 改在哪里 | 落点 | 红了几条 | 起 | 止 |")
    out.append("|---|---|---|---:|---|---|")
    for r in results:
        state = str(len(r["reds"])) if r["compiled"] else "❌编译失败"
        out.append("| **{key}** | {what} | `{path}` | {state} | {a} | {b} |".format(
            key=r["key"], what=r["what"], path=Path(r["path"]).name,
            state=state, a=r["started"][11:19], b=r["finished"][11:19]))

    out.append("\n## 四、逐个变体\n")
    for r in results:
        out.append(f"### 变体 {r['key']}　{r['what']}\n")
        out.append(f"- 落点：`{r['path']}`")
        out.append(f"- 开跑前预期打红：{r['expects']}")
        out.append(f"- 时间：{r['started']} → {r['finished']}")
        if not r["compiled"]:
            out.append("- 🔴 **编译失败** —— 这不是「一条都没红」，是这个变体本身没成立\n")
            out.append("```\n" + r["tail"] + "\n```\n")
            continue
        out.append(f"- 全量 {r['total']} 条，本批红 **{len(r['reds'])}** 条\n")
        if r["reds"]:
            for x in r["reds"]:
                tag = "" if x.get("本批新增") else "（既有判据，非本批新增）"
                out.append(f"  - `{x['判据']}`{tag}")
                out.append(f"    > {x['报的'] or '（surefire 未给 message）'}")
        else:
            out.append("  - 🔴 **一条都没红**")
        for a in r.get("外来红", []):
            out.append(f"\n  ⚠️ 本批之外还红了 `{a['判据']}`（{a['耗时秒']}s）：{a['报的']}")
            if a["已知不稳"]:
                out.append("  　已知不稳判据，机理已查明，**不记在本变体头上**")
            else:
                still = a.get("单跑复核", {}).get("仍红")
                out.append(f"  　单跑复核：{'仍红 ' + str(still) if still else '未复核'}")
        out.append("")

    silent = [r["key"] for r in results if r["compiled"] and not r["reds"]]
    broken = [r["key"] for r in results if not r["compiled"]]
    covered = sorted({x["判据"] for r in results for x in r["reds"]})
    out.append("## 五、合计\n")
    out.append(f"- 变体 **{len(results)}** 个")
    out.append(f"- 没能让任何判据变红的：**{len(silent)}** 个 {silent or '（无）'}")
    out.append(f"- 编译失败的：**{len(broken)}** 个 {broken or '（无）'}")
    fresh = sorted({x["判据"] for r in results for x in r["reds"] if x.get("本批新增")})
    out.append(f"- 这三个类里被红过的判据：**{len(covered)}** 条")
    out.append(f"- 其中**本批新增的 23 条**：被红过 **{len(fresh)}** 条"
               f"{'（全数）' if len(fresh) == 23 else '　🔴 有漏'}\n")
    if data.get("分类订正"):
        out.append(f"> **分类订正**：{data['分类订正']}\n")
    out.append("被红过的判据逐条（← 后面是把它弄红的变体）：\n")
    for name in covered:
        by = [r["key"] for r in results if any(x["判据"] == name for x in r["reds"])]
        tag = "" if name in fresh else "　（既有判据）"
        out.append(f"- `{name}` ← {'、'.join(by)}{tag}")
    out.append("")

    target = raw.with_suffix(".md")
    target.write_text("\n".join(out), encoding="utf-8")
    print(f"案卷写到 {target}")


if __name__ == "__main__":
    main()
