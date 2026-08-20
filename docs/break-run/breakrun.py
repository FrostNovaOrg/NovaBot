#!/usr/bin/env python3
"""破坏跑：把判据一条一条弄红，证明它们各自拦得住什么。

一条从没红过的判据，就还没有人证明过它拦得住什么 —— 它可能是恒真绿，
可能盯错了对象，也可能压根没被执行。所以每写一批判据，就照着它们各自
声称要挡的那件事，往被测代码里种一个对应的缺陷，看它红不红。

用法::

    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home  # macOS，Linux 按自己的路径改
    docs/break-run/breakrun.py                 # 全部变体
    docs/break-run/breakrun.py A F R           # 只跑这几个
    docs/break-run/breakrun.py --ruler-only    # 只验尺

输出：``docs/break-run/<提交号>.md`` 案卷，含每个变体的「改在哪里 / 红了哪几条 /
时间戳」，以及一份 JSON 原始记录。

🔴 三条纪律，都是栽过才写下来的：

1. **数红的那把尺自己要先过阳性对照。** 上一轮用正则数红，而通过的判据在
   surefire 里写成自闭合的 ``<testcase .../>``，``(.*?)`` 一路吞到下一个真正的
   ``</testcase>``，把后面那条的失败记到了前面那条名下 —— 名字全错，输出却
   看着完全正常。现在改用 XML 解析，并且每次开跑前先在一个「三绿夹一红」的
   已知样本上验一遍，验不过就不跑。
2. **每次跑之前先删掉上一次的 surefire 报告。** 不删的话，一个没被重跑的类会
   把上一次的 XML 留在原地，于是旧产物冒充新跑。
3. **不许为了让破坏变体编译得过去而改被测对象。** 那一刻被测的已经不是要交付
   的东西了。变体必须是自带声明的单点替换。
4. **落在本批之外的红，先当成「跟这个变体无关」处理。** 一个只动了 js 文件的
   变体不可能让口令校验的判据变红。碰到这种红，就把那个类单独再跑一遍：
   单跑还红说明它本来就红，单跑就绿说明它跟机器当时的忙闲有关 —— 两种都不该
   记在变体头上。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = "starbot-core"
REPORTS = ROOT / MODULE / "target" / "surefire-reports"
TEST_ROOT = ROOT / MODULE / "src/test/java/com/starlwr/bot/core"

WITNESS = f"{MODULE}/src/main/java/com/starlwr/bot/core/config/ui/napcat/NapCatRouteWitness.java"
CREDENTIAL = f"{MODULE}/src/main/java/com/starlwr/bot/core/config/ui/napcat/NapCatCredentialService.java"
RESUME_JS = f"{MODULE}/src/main/resources/config-ui/napcat-resume.js"


@dataclass
class Variant:
    """一个变体 = 一处单点替换 + 它意在打红的那条判据。

    ``expects`` 只是写下开跑前的预期，**不参与判定**：实测红了哪几条以实跑为准，
    对不上时案卷里照记，不回头改预期。
    """

    key: str
    what: str
    path: str
    old: str
    new: str
    expects: str


VARIANTS: list[Variant] = [
    Variant(
        "A", "见证找不到路由时也判「认得出」",
        WITNESS,
        "        return fetchedAny ? Verdict.ROUTE_GONE : Verdict.ASSET_UNREACHABLE;",
        "        return fetchedAny ? Verdict.RECOGNISED : Verdict.ASSET_UNREACHABLE;",
        "找不到时必须报警",
    ),
    Variant(
        "B", "入口脚本连 modulepreload 与样式表一起抓",
        WITNESS,
        '            Pattern.compile("<script[^>]*\\\\ssrc=[\\"\']([^\\"\']+)[\\"\']", Pattern.CASE_INSENSITIVE);',
        '            Pattern.compile("<(?:script|link)[^>]*\\\\s(?:src|href)=[\\"\']([^\\"\']+)[\\"\']", Pattern.CASE_INSENSITIVE);',
        "只认入口脚本",
    ),
    Variant(
        "C", "把处数口径换成行数口径",
        WITNESS,
        """        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(literal, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + literal.length();
        }""",
        """        return (int) text.lines().filter(line -> line.contains(literal)).count();""",
        "数处数不数行数",
    ),
    Variant(
        "D", "签发凭据时根本不跑见证",
        CREDENTIAL,
        "            routeWitness.witness();",
        "            // 破坏跑 D：这里本该跑一次见证",
        "阳性对照：签发时见证确实跑过",
    ),
    Variant(
        "E", "签发时不给见证兜底（守卫拆掉）",
        CREDENTIAL,
        """        try {
            routeWitness.witness();
        } catch (RuntimeException e) {
            // 🔴 见证是来报信的，不是来把门关上的（边界②：失败回落到现状）。
            // 它自己出了岔子，最坏的结果应当是「这次没见证成」，
            // 而不是「凭据签不出来了」——后者会让使用者被关在 NapCat 门外
            log.warn("NapCat 路由见证过程中出错, 不影响本次凭据签发: {}", e.getMessage());
        }""",
        "        routeWitness.witness();",
        "见证抛异常时凭据照样签得出",
    ),
    Variant(
        "F", "页面与服务端用不同的路由字面量",
        RESUME_JS,
        "    var LOGIN_ROUTE = 'web_login';",
        "    var LOGIN_ROUTE = 'web_signin';",
        "两边同值",
    ),
    Variant(
        "G", "「取不到资产」与「路由没了」混为一谈",
        WITNESS,
        "        return fetchedAny ? Verdict.ROUTE_GONE : Verdict.ASSET_UNREACHABLE;",
        "        return Verdict.ROUTE_GONE;",
        "两种失败分开报",
    ),
    Variant(
        "H", "拆掉续登防抖（落到登录页就一直换）",
        RESUME_JS,
        """        if (state.renewed) {
            // 边界⑤：换过一次还是回到登录页，说明不是凭据过期那么简单。
            // 再换只会重复同一个结果，并且把请求打向 NapCat 的登录接口
            return Action.GIVE_UP;
        }
        return Action.RENEW;""",
        "        return Action.RENEW;",
        "至多续登一次",
    ),
    Variant(
        "I", "认登录页改用子串匹配",
        RESUME_JS,
        "        return clean.slice(clean.lastIndexOf('/') + 1) === LOGIN_ROUTE;",
        "        return clean.indexOf(LOGIN_ROUTE) >= 0;",
        "最后一段全等，不用子串",
    ),
    Variant(
        "J", "续登后的重载还没落地就下判断",
        RESUME_JS,
        """        if (state.reloading) {
            // 续登之后那次重载还在路上。此时内层多半还停在登录页，
            // 若不等它落地就判，会把「正在救」误判成「救不回来」，于是一次机会都不给
            return Action.WAIT;
        }""",
        "        // 破坏跑 J：不等那次重载落地",
        "等重载落地再判",
    ),
    Variant(
        "K", "离开登录页之后不归零",
        RESUME_JS,
        """            // 内层回到了正常页面，说明上一次续登真的救回来了 —— 下一次失效可以再救一次。
            // 🔴 归零的条件是「确实离开过登录页」，不是「过了多久」：
            //    靠时间归零的话，一个一直卡在登录页的内层会被反复重试
            state.renewed = false;""",
        "            // 破坏跑 K：这里本该归零",
        "确实离开过才归零",
    ),
    Variant(
        "L", "速率闸永远放行",
        CREDENTIAL,
        """    synchronized boolean tryAcquireMint(Instant now) {
        if (mintRefilledAt == null) {""",
        """    synchronized boolean tryAcquireMint(Instant now) {
        if (true) { return true; }
        if (mintRefilledAt == null) {""",
        "闸确实拦得住",
    ),
    Variant(
        "M", "桶不随时间回满（做成锁定而不是限流）",
        CREDENTIAL,
        """        double elapsedSeconds = Duration.between(mintRefilledAt, now).toMillis() / 1000.0;
        if (elapsedSeconds > 0) {
            mintTokens = Math.min(MINT_BURST,
                    mintTokens + elapsedSeconds * MINT_BURST / MINT_WINDOW.toSeconds());
            mintRefilledAt = now;
        }""",
        "        // 破坏跑 M：桶不回满",
        "回满不锁定",
    ),
    Variant(
        "N", "见证永远判「路由没了」",
        WITNESS,
        """            if (occurrences(asset, ROUTE_LITERAL) >= MINIMUM_OCCURRENCES) {
                return Verdict.RECOGNISED;
            }""",
        """            if (false) {
                return Verdict.RECOGNISED;
            }""",
        "找得到时判「认得出」",
    ),
    Variant(
        "O", "非 2xx 的响应也当成取到了",
        WITNESS,
        """            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }""",
        "            // 破坏跑 O：不看状态码",
        "外壳取不到时报警",
    ),
    Variant(
        "P", "抽不出入口脚本时自己编一个",
        WITNESS,
        """        List<String> scripts = entryScripts(shell);
        if (scripts.isEmpty()) {
            return Verdict.NO_ENTRY_SCRIPT;
        }""",
        """        List<String> scripts = entryScripts(shell);
        if (scripts.isEmpty()) {
            scripts = List.of("/webui/assets/index.js");
        }""",
        "抽不出入口脚本时报警",
    ),
    Variant(
        # 🔴 这一版是自带声明的单点替换。上一轮的第一版需要往被测类里加一个
        #    局部变量才编译得过去 —— 为了让破坏脚本跑起来而改被测对象是本末倒置
        "Q", "除了主包还去扫别的分块",
        WITNESS,
        """        boolean fetchedAny = false;
        for (String src : scripts) {""",
        """        boolean fetchedAny = false;
        scripts = new ArrayList<>(scripts);
        scripts.add(0, "/webui/assets/react-dom-CwaHFgt6.js");
        for (String src : scripts) {""",
        "正常只两跳",
    ),
    Variant(
        "R", "速率闸的额度收到只剩一次",
        CREDENTIAL,
        "    private static final int MINT_BURST = 3;",
        "    private static final int MINT_BURST = 1;",
        "3 不是拍的：收到 1 会打到正常用法",
    ),
]

# ---------------------------------------------------------------- 尺子

RULER_CONTROL = TEST_ROOT / "BreakRunRulerControlTest.java"

RULER_CONTROL_SOURCE = """package com.starlwr.bot.core;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验尺用的一次性样本，由 docs/break-run/breakrun.py 临时写入、跑完即删。
 *
 * 形状是照着上一轮那个 bug 摆的：三绿夹一红，红的排在第三位。
 * 通过的判据在 surefire 里写成自闭合的 &lt;testcase .../&gt;，
 * 用正则去数红的话，会把这里的 gamma 记到 beta 名下。
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class BreakRunRulerControlTest {
    @Test
    void alphaGreen() {
        assertEquals(1, 1);
    }

    @Test
    void betaGreen() {
        assertEquals(2, 2);
    }

    @Test
    void gammaRedOnPurpose() {
        assertEquals(1, 2, "验尺用的必红样本");
    }

    @Test
    void deltaGreen() {
        assertEquals(4, 4);
    }
}
"""


# 本批新增判据所在的三个类。落在这以外的红一律先当成与变体无关（纪律 4）
#
# 🔴 判断「是不是这三个类」只能看 **surefire 的文件名**，不能看 `classname` 属性：
#    判据用了 @DisplayName，于是 XML 里 classname="挂在凭据签发上" —— 是展示名，
#    不是类名。第一版拿它去比，18 个变体全被判成「本批之外」，
#    汇总打出「本批被红过的判据 0 条」。这次是数字荒唐才露的馅。
OWN_CLASSES = ("NapCatRouteWitnessTest", "NapCatResumeDebounceTest", "NapCatCredentialServiceTest")

# 本批新增的那 23 条判据（见证 12 + 续登 7 + 速率闸 4）。
# 上面三个类里还住着一批既有判据，它们红不红是另一回事，要分开数。
NEW_JUDGMENTS = {
    # 见证层 12
    "recognisesTheRouteInTheMainBundle", "shoutsWhenTheRouteIsGone",
    "shoutsWhenTheShellIsUnreachable", "shoutsWhenTheShellHasNoEntryScript",
    "distinguishesUnreachableAssetFromMissingRoute", "countsOccurrencesNotLines",
    "takesOnlyTheEntryScript", "fetchesExactlyTwoThings",
    "mintingActuallyRunsTheWitness", "aThrowingWitnessNeverBlocksMinting",
    "aFailingWitnessNeverBlocksMinting", "theLiteralMatchesTheOneInTheResumeScript",
    # 续登层 7
    "theRulerLoadsTheRealScript", "renewsExactlyOnceThenStops", "waitsForTheReloadToLand",
    "resetsOnlyAfterActuallyLeavingTheLoginPage", "doesNothingWhenTheInnerPathIsUnreadable",
    "recognisesTheRealLoginPath", "doesNotMatchBySubstring",
    # 速率闸 4
    "normalUseNeverHitsTheGate", "refusesWhenHammered",
    "theBucketRefillsSoThereIsNoLockout", "aRefusedAttemptSendsNothing",
}

# 🔴 已知会随机器忙闲变红的判据。**不是消音，是记账**：
# 它每一跑报了什么都照录进 JSON，只是不拿它拦住破坏跑、也不记在任何变体头上。
#
# 名下这条的机理是量出来的（2026-08-18）：它先做三次口令校验（每次 600 000 轮
# PBKDF2），再把全局桶抽干，期望恰好少三个令牌。可那三次校验是拿真实时刻去问桶的，
# 而桶按 20 次/分钟回满 —— 也就是每 3 秒回一个。机器慢到单次校验超过 1.5 秒，
# 桶就在循环里回上一整个令牌，判据于是变成「这台机器有多快」的函数。
# 判据自己的注释说这一族已经被 before 那一手挡住了，但 before 挡的是**循环之后**
# 的回满，循环之内的三次没挡住 —— 判据自洽，前提没被检查过。
# 实测：本机空闲时它绿；24 路 CPU 占满时报 expected 17 but was 18；
# 改用电池供电后单跑三次连红，该判据自身耗时 14.2 / 14.3 / 14.8 秒，报 was 19。
UNSTABLE = {
    "ConfigUiAuthServiceTest.failedChecksSpendTheGlobalBudget":
        "机器慢到单次口令校验超过 1.5 秒就会红；机理见上方注释，已报 pm 待裁",
}


def unstable(name: str) -> bool:
    return name in UNSTABLE


def read_reds(only: str | None = None) -> tuple[list[dict], int]:
    """从 surefire 报告里读出「红了哪几条」「各自报了什么」和「一共跑了几条」。

    口径与全量计数一致：判据数 = ``<testcase>`` 元素数（``tests="..."`` 属性对
    判据都在 @Nested 里的类恒为 0，不能用）。红 = 带 failure 或 error 子元素。

    判据名取 **文件名里的类名** ＋ 方法名。不用 ``classname`` 属性：判据带
    @DisplayName 时它是展示名（``挂在凭据签发上``），不是类名。
    上一轮那把正则尺则更早一步就错了——它从 ``name="…"`` 抓，而 ``classname="…"``
    的尾巴也长着 ``name="``，贪婪匹配会抓到展示名去。
    """
    reds: list[dict] = []
    total = 0
    pattern = f"TEST-*{only}.xml" if only else "TEST-*.xml"
    for xml in sorted(REPORTS.glob(pattern)):
        cls = xml.name[len("TEST-"):-len(".xml")].split(".")[-1]
        for case in ET.parse(xml).getroot().iter("testcase"):
            total += 1
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is None:
                continue
            method = case.get("name")
            reds.append({
                "判据": f"{cls}.{method}",
                "展示名": f"{case.get('classname')}.{method}",
                "外层类": cls,
                "本批": cls in OWN_CLASSES,
                "本批新增": method in NEW_JUDGMENTS,
                "已知不稳": unstable(f"{cls}.{method}"),
                "耗时秒": case.get("time"),
                "报的": (bad.get("message") or "").strip(),
                "类型": bad.get("type") or "",
            })
    reds.sort(key=lambda r: r["判据"])
    return reds, total


def run_tests(only: str | None = None) -> tuple[bool, str]:
    """跑一次模块测试。返回 (编译并跑起来了吗, mvn 输出尾巴)。"""
    shutil.rmtree(REPORTS, ignore_errors=True)  # 纪律 2：不让旧产物冒充新跑
    command = ["mvn", "-B", "-pl", MODULE, "test"]
    if only:
        command += [f"-Dtest={only}", "-DfailIfNoTests=false"]
    proc = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    tail = "\n".join(proc.stdout.splitlines()[-40:])
    compiled = REPORTS.exists() and any(REPORTS.glob("TEST-*.xml"))
    return compiled, tail


def verify_ruler() -> dict:
    """阳性对照：尺子得先在一个已知样本上数对。

    数不对就地停手 —— 一把没验过的尺子量出来的 18 行数字，一行都不能信。
    """
    print("==> 验尺：三绿夹一红的已知样本")
    RULER_CONTROL.write_text(RULER_CONTROL_SOURCE, encoding="utf-8")
    try:
        compiled, tail = run_tests()
        if not compiled:
            raise SystemExit("验尺跑不起来（编译失败？）：\n" + tail)
        reds, total = read_reds()
        names = [r["判据"] for r in reds]
        control = [n for n in names if n.startswith("BreakRunRulerControlTest.")]
        expected = ["BreakRunRulerControlTest.gammaRedOnPurpose"]
        print(f"    共 {total} 条，红 {len(reds)} 条；样本里红的是 {control}")
        if control != expected:
            raise SystemExit(f"❌ 验尺不过：样本里应当只有 {expected} 红，实得 {control}")
        print("    ✅ 尺子数对了，名字也没串行")
        return {"样本红": control, "全量条数": total, "全量红数": len(reds),
                "全量红": names}
    finally:
        RULER_CONTROL.unlink(missing_ok=True)


# ---------------------------------------------------------------- 跑


def stamp() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %z")


def ensure_clean() -> str:
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                           capture_output=True, text=True).stdout
    tracked = [line for line in dirty.splitlines() if not line.startswith("??")]
    if tracked:
        raise SystemExit("工作区不干净，破坏跑要求从被交付的那一版开跑：\n" + "\n".join(tracked))
    return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                          capture_output=True, text=True).stdout.strip()


def apply_variant(v: Variant) -> None:
    target = ROOT / v.path
    text = target.read_text(encoding="utf-8")
    hits = text.count(v.old)
    if hits != 1:
        raise SystemExit(f"变体 {v.key} 的落点在 {v.path} 里命中 {hits} 次，应当恰好 1 次")
    target.write_text(text.replace(v.old, v.new), encoding="utf-8")


def restore(v: Variant) -> None:
    subprocess.run(["git", "checkout", "--", v.path], cwd=ROOT, check=True)


def recheck(outer: str) -> dict:
    """把一个类单独再跑一遍，看那条红还在不在（纪律 4）。

    这一步不动源码 —— 此时变体已经还原，跑的是交付版。
    还红＝它本来就红；变绿＝它跟机器当时的忙闲有关。两种都不该记在变体头上。
    """
    compiled, tail = run_tests(only=outer)
    if not compiled:
        return {"跑起来了": False, "仍红": None, "报的": ""}
    reds, _ = read_reds(only=outer)
    return {"跑起来了": True, "仍红": [r["判据"] for r in reds],
            "报的": [r["报的"] for r in reds]}


def run_variant(v: Variant) -> dict:
    started = stamp()
    apply_variant(v)
    try:
        compiled, tail = run_tests()
    finally:
        restore(v)

    if not compiled:
        return {"key": v.key, "what": v.what, "path": v.path, "expects": v.expects,
                "started": started, "finished": stamp(), "compiled": False,
                "reds": [], "外来红": [], "total": 0, "tail": tail}

    reds, total = read_reds()
    own = [r for r in reds if r["本批"]]
    alien = [r for r in reds if not r["本批"]]
    for a in alien:
        # 变体已还原，这一跑量的是交付版本身
        a["单跑复核"] = {"跳过": "已知不稳，机理已查明"} if a["已知不稳"] else recheck(a["外层类"])
    return {"key": v.key, "what": v.what, "path": v.path, "expects": v.expects,
            "started": started, "finished": stamp(), "compiled": True,
            "reds": own, "外来红": alien, "total": total, "tail": ""}


def reclassify(path: Path) -> None:
    """把一份已有 JSON 的「本批/外来」标签按正确规则重贴一遍。

    🔴 用在什么场合：2026-08-18 那一跑的分类器拿 ``classname`` 属性当类名，
    而判据带 @DisplayName，于是 18 个变体的红全被贴成「本批之外」，
    汇总打出「本批被红过的判据 0 条」—— 荒唐得一眼看得出，所以才没混过去。

    **测量本身没受影响**：红了哪几条、各自报什么、几点跑的，都是实测记下来的。
    重贴标签是纯后处理，不重跑、不改任何一个测出来的数。
    连带作废原跑的「单跑复核」——它把展示名当类名传给 ``-Dtest=``，
    一个测试都没跑起来，却报成了「变绿」。**那是管道断了，不是判据绿了。**
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    for v in data["变体"]:
        merged = list(v.get("reds", [])) + list(v.get("外来红", []))
        for r in merged:
            method = r["判据"].split(".")[-1]
            r.setdefault("展示名", r["判据"])
            r["本批新增"] = method in NEW_JUDGMENTS
            r["本批"] = method in NEW_JUDGMENTS or method in PRE_EXISTING_IN_OWN
            r["已知不稳"] = method in {k.split(".")[-1] for k in UNSTABLE}
            r["判据"] = f"{owner(method)}.{method}"
            r.pop("单跑复核", None)
        merged.sort(key=lambda r: r["判据"])
        v["reds"] = [r for r in merged if r["本批"]]
        v["外来红"] = [r for r in merged if not r["本批"]]
    for r in data.get("基线红", []):
        method = r["判据"].split(".")[-1]
        r["判据"] = f"{owner(method)}.{method}"
        r["已知不稳"] = method in {k.split(".")[-1] for k in UNSTABLE}
    data["分类订正"] = (
        "原跑的分类器把 surefire 的 classname 属性当成了类名，而判据带 @DisplayName，"
        "于是每一条红都被贴成「本批之外」，并且触发了一轮把展示名传给 -Dtest= 的"
        "「单跑复核」——那一轮一个测试都没跑起来，结果作废已删。"
        "本次只重贴标签，未重跑，测出来的数一个没动。"
    )
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已重贴标签：{path}")


# 那三个类里的既有判据（不是本批新增）。列出来是为了让「本批新增 23 条」这个数
# 与「这三个类里红过的判据」分得开
PRE_EXISTING_IN_OWN = {
    "matchesNapCatAlgorithm", "plainTokenIsHashedAndCleared", "existingHashIsNotRewritten",
    "notConfigured", "bodyFieldIsHashNotToken", "sendsTotpCodeWhenSecretConfigured",
    "omitsTotpCodeWhenNoSecret", "secondCallUsesCache", "renewForcesFreshLogin",
    "errorInsideHttp200IsNotSuccess", "require2FaIsNotACredential", "failureIsNotCached",
    "connectionFailureIsHandled",
}

_WITNESS_METHODS = {
    "recognisesTheRouteInTheMainBundle", "shoutsWhenTheRouteIsGone",
    "shoutsWhenTheShellIsUnreachable", "shoutsWhenTheShellHasNoEntryScript",
    "distinguishesUnreachableAssetFromMissingRoute", "countsOccurrencesNotLines",
    "takesOnlyTheEntryScript", "fetchesExactlyTwoThings",
    "mintingActuallyRunsTheWitness", "aThrowingWitnessNeverBlocksMinting",
    "aFailingWitnessNeverBlocksMinting", "theLiteralMatchesTheOneInTheResumeScript",
}

_RESUME_METHODS = {
    "theRulerLoadsTheRealScript", "renewsExactlyOnceThenStops", "waitsForTheReloadToLand",
    "resetsOnlyAfterActuallyLeavingTheLoginPage", "doesNothingWhenTheInnerPathIsUnreadable",
    "recognisesTheRealLoginPath", "doesNotMatchBySubstring",
}


def owner(method: str) -> str:
    if method in _WITNESS_METHODS:
        return "NapCatRouteWitnessTest"
    if method in _RESUME_METHODS:
        return "NapCatResumeDebounceTest"
    if method in NEW_JUDGMENTS or method in PRE_EXISTING_IN_OWN:
        return "NapCatCredentialServiceTest"
    if method in {k.split(".")[-1] for k in UNSTABLE}:
        return "ConfigUiAuthServiceTest"
    return "?"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("keys", nargs="*", help="只跑这几个变体，缺省全跑")
    parser.add_argument("--ruler-only", action="store_true")
    parser.add_argument("--reclassify", metavar="JSON",
                        help="不重跑，只把一份已有记录的本批/外来标签重贴一遍")
    args = parser.parse_args()

    if args.reclassify:
        reclassify(Path(args.reclassify))
        return

    if not os.environ.get("JAVA_HOME"):
        print("⚠️  JAVA_HOME 没设。build.sh 只查 PATH 里的 java >= 17，"
              "本机默认是 JDK 26，Lombok 会静默失效并伪装成一串编译错误。", file=sys.stderr)

    head = ensure_clean()
    print(f"破坏跑，被测版本 {head}")

    ruler = verify_ruler()

    print("==> 基线：没动过的树，红应当是 0 条")
    compiled, tail = run_tests()
    if not compiled:
        raise SystemExit("基线跑不起来：\n" + tail)
    baseline, base_total = read_reds()
    blocking = [r for r in baseline if not r["已知不稳"]]
    print(f"    共 {base_total} 条，红 {len(baseline)} 条 {[r['判据'] for r in baseline]}")
    for r in baseline:
        if r["已知不稳"]:
            print(f"    ⚠️ 已知不稳，记账不拦路：{r['判据']}（{r['耗时秒']}s）{r['报的']}")
    if blocking:
        raise SystemExit("基线就有红的，先把它修好再跑破坏：" + str([r["判据"] for r in blocking]))

    if args.ruler_only:
        return

    chosen = [v for v in VARIANTS if not args.keys or v.key in args.keys]
    results = []
    for v in chosen:
        print(f"==> 变体 {v.key}：{v.what}")
        r = run_variant(v)
        results.append(r)
        if not r["compiled"]:
            print("    ❌ 编译失败 —— 这不是「一条都没红」，是变体本身没成立")
            continue
        print(f"    红 {len(r['reds'])} 条 / 共 {r['total']} 条："
              f"{[x['判据'] for x in r['reds']]}")
        for a in r["外来红"]:
            if a["已知不稳"]:
                print(f"    ⚠️ 本批之外的已知不稳判据又红了，不记在本变体头上："
                      f"{a['判据']}（{a['耗时秒']}s）")
                continue
            still = a["单跑复核"]["仍红"]
            print(f"    ⚠️ 本批之外还红了 {a['判据']}（{a['报的']}）；"
                  f"单跑复核：{'仍红' if still else '变绿'} {still}")

    out = {"提交号": head, "基线条数": base_total, "验尺": ruler,
           "基线红": baseline, "已知不稳": UNSTABLE,
           "跑完": stamp(), "变体": results}
    raw = Path(__file__).with_name(f"{head}.json")
    raw.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n原始记录：{raw.relative_to(ROOT)}")

    silent = [r["key"] for r in results if r["compiled"] and not r["reds"]]
    broken = [r["key"] for r in results if not r["compiled"]]
    covered = sorted({x["判据"] for r in results for x in r["reds"]})
    alien = sorted({x["判据"] for r in results for x in r["外来红"]})
    print(f"变体 {len(results)} 个；没能让任何判据变红的 {len(silent)} 个 {silent}；"
          f"编译失败的 {len(broken)} 个 {broken}")
    print(f"本批被红过的判据 {len(covered)} 条")
    if alien:
        print(f"⚠️ 本批之外还红过 {len(alien)} 条，不记在变体头上：{alien}")


if __name__ == "__main__":
    main()
