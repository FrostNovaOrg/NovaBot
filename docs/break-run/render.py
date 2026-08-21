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

{ruler_extra}"""

LEGACY_RULER = """同一份 XML 交给上一轮那把正则尺（`<testcase[^>]*name="([^"]+)"[^>]*>(.*?)</testcase>`
配 `re.S`），它报的是 `com.starlwr.bot.core.BreakRunRulerControlTest` —— **两处都错**：
贪婪的 `[^>]*` 越过 `name="alphaGreen"` 抓到了 `classname="` 尾巴上的那个 `name="`，
而 `re.S` 让 `(.*?)` 从第一个自闭合的绿判据一路吞到第三条的 `</testcase>`。
**红的条数与名字全不可信，输出却看着完全正常。**

"""

# 尺子除了「数对不对」还得自证三件事。逐项从 JSON 读，不在文里写死结论。
STRUCT_RULER = """尺子另外三项自证（读数取自 `{commit}.json` 的 `验尺` 段）：

| 自证的是什么 | 读数 | 结论 |
|---|---|---|
| 分类器取的是真类名，不被 `@DisplayName` 带偏 | 报 `{classifier}` | {v_cls} |
| 单跑管道跑得起来（真类名） | 实跑 {pipe_n} 条 | {v_pipe} |
| 单跑管道分得清「断了」与「不红了」（展示名） | {pipe_display} | {v_disp} |

> 第三行是**失败闭合**那一条：`-Dtest=` 拿展示名去跑会匹配到 0 个类，
> 而 0 条判据**不算「全绿」**——不这么判，管道一断就会伪装成「都好着呢」。
>
> 夹具的 `@DisplayName` 是**故意**取得和类名不一样的，否则这两行自证等于没做。

"""


def main() -> None:
    raw = Path(sys.argv[1])
    data = json.loads(raw.read_text(encoding="utf-8"))
    commit = data["提交号"]
    ruler = data["验尺"]

    if "分类器" in ruler:
        ruler_extra = STRUCT_RULER.format(
            commit=commit,
            classifier=ruler['分类器'],
            v_cls="✅ 没被带偏" if ruler["分类器"] == CONTROL.split(".")[0] else "❌ 串行了",
            pipe_n=ruler["单跑管道"]["真类名实跑条数"],
            v_pipe="✅ 跑得起来" if ruler["单跑管道"]["真类名实跑条数"] else "❌ 跑不起来",
            pipe_display=ruler["单跑管道"]["展示名"],
            v_disp="✅ 认出「断了」" if "跑不起来" in ruler["单跑管道"]["展示名"] else "❌ 当成了全绿",
        )
    else:
        ruler_extra = LEGACY_RULER

    out = [HEADER.format(
        ruler_extra=ruler_extra,
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
    # 老 JSON 没记口径，退回它当时的值；措辞也照旧，好让重渲染逐字节可比
    # 附录路径先算出来：§五 判「有漏」时要知道本件带没带补跑读数
    extra = raw.with_name(raw.stem + ".附录.md")
    # 🔴 缺键一律拒出覆盖率格，不许拿默认值顶。
    #    这里原先写的是 data.get("本批新增判据数", 23) 与 "三" —— 缺键时会拿一个**编出来的分母**
    #    印出覆盖率，而印出来的东西和真读数长得一模一样。「缺」被读成了一个具体的数，
    #    正是共同纪律 34 的形状。旧案卷的键已按各自那一版脚本的实际取值补齐。
    #    🔴 拒的是**这一格**，不是整份案卷：这里原先写成 return，而它在 main() 里，
    #    等于缺一个键就整份渲不出来 —— 那是把「这一格不可信」办成了「什么都没有」。
    missing = [k for k in ("本批新增判据数", "本批类", "变体总数", "本跑变体数") if k not in data]
    if missing:
        out.append(f"> ⚠️ **本份 JSON 缺 {'、'.join(missing)}，覆盖率不予判定。**"
                   f"缺的不是「零」，是「没量」——这一格不给数。\n")
    else:
        n_new = data["本批新增判据数"]
        n_cls = f" {len(data['本批类'])} "
        out.append(f"- 这{n_cls}个类里被红过的判据：**{len(covered)}** 条")

        # 🔴 覆盖率只有整跑才判得了。挑着跑的时候「被红过的少」是因为变体没跑全，
        #    把它写成「有漏」就是拿一个假红去吓复核的人。
        # 🔴「整跑」是**机读判定**，不由人手写：变体总数 == 本跑变体数即整跑。
        #    手写的那两个字会和数据脱钩 —— 数据说挑跑而标题写着整跑，没有任何东西会拦住。
        total_v, ran_v = data["变体总数"], data["本跑变体数"]
        out.append(f"- 本跑口径：**{'整跑' if ran_v == total_v else '挑跑'}**"
                   f"（{ran_v}/{total_v} 个变体）"
                   f"　⚠️ 这里的「总数」是**落盘那一刻**的变体集大小，不是今天的；"
                   f"变体集会长大，已落盘的读数不会跟着长\n")
        if ran_v < total_v:
            out.append(f"- 其中**本批新增的 {n_new} 条**：被本跑的 {ran_v} 个变体红过 **{len(fresh)}** 条")
            out.append(f"\n> ⚠️ **本跑是挑着跑的**（{ran_v}/{total_v} 个变体），"
                       f"所以「新增判据被红过几条」这一格**不构成覆盖率结论**——"
                       f"没被红到的多半是这次压根没跑它的那个变体。整跑的覆盖率见整跑的案卷。\n")
        else:
            # 有漏而本件带附录时，指一下 —— 附录里多半就是补跑的读数。
            # 但**不许**因为附录存在就把「有漏」改写成「全数」：这一跑漏了就是漏了。
            gap = "" if len(fresh) == n_new else ("　🔴 有漏（补跑读数见附录）" if extra.exists()
                                                  else "　🔴 有漏")
            out.append(f"- 其中**本批新增的 {n_new} 条**：被红过 **{len(fresh)}** 条"
                       f"{'（全数）' if len(fresh) == n_new else gap}\n")
    if data.get("分类订正"):
        out.append(f"> **分类订正**：{data['分类订正']}\n")
    out.append("被红过的判据逐条（← 后面是把它弄红的变体）：\n")
    for name in covered:
        by = [r["key"] for r in results if any(x["判据"] == name for x in r["reds"])]
        tag = "" if name in fresh else "　（既有判据）"
        out.append(f"- `{name}` ← {'、'.join(by)}{tag}")
    out.append("")

    # 案卷是重渲染出来的，手写段落写进正文会被下一次渲染抹掉。
    # 凡是不出自这一跑 JSON 的读数（比如扫描器阳性对照），放同名 .附录.md，由这里挂进来。

    # 没有附录就什么都不加：已交付的老案卷重渲染后必须**逐字节相同，除非有显式记录的重立基线**，
    # 这是「改渲染脚本没改动历史读数」的对照项，一行提示也不许加。
    if extra.exists():
        out.append(extra.read_text(encoding="utf-8").rstrip() + "\n")

    target = raw.with_suffix(".md")
    target.write_text("\n".join(out), encoding="utf-8")
    print(f"案卷写到 {target}")


if __name__ == "__main__":
    main()
