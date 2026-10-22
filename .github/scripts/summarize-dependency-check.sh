#!/usr/bin/env bash
#
# 汇总 dependency-check 的 JSON 报告进 job summary，守住一条底线：
# 报告坏了、缺了、判不动时，绝不输出「未发现高危」——那句话只留给
# 「该出的报告一份不缺、份份可判、判完确实没有」。
#
# 公开输出只有计数：漏洞数据来自 VulnCheck，其条款不许把数据免费转给他人，
# 而公开仓的 job summary 与日志谁都能看。所以这里只报扫没扫成、可判／应出
# 份数、高危几条、其余几条、缺 CVSSv3 几条，不列漏洞编号、分数、描述，也不列
# 带漏洞的依赖名；统计脚本逐条吐出的明细只留在变量里数行数，一个字也不打出去。
# 统计脚本失败时它的输出同样不转述（里面可能夹着已吐出的明细），只点名哪份
# 报告、哪一步没跑成。
#
# 为什么立成件而不写在 workflow 里：异常路径要逐份点名原因，逻辑塞进 yml
# 就没人核得动了；而它恰恰是要拿坏输入反复试的一段——立成件，CI 跑的与
# 本地能试的就是同一份，不存在「测的是抄本、跑的是另一份」。
#
# 环境变量（workflow 注入；本地自设同款即可离线复跑）：
#   GITHUB_STEP_SUMMARY  汇总写往的文件（缺省打到标准输出）
#   SCAN_OUTCOME         扫描步骤被 continue-on-error 豁免前的真实结果
#   VULNCHECK_API_TOKEN  只用来判空，绝不回显
#   SCAN_SOURCES         数据来源的一句话，由 workflow 里配扫描命令的地方给——
#                        配置与这句话同源，改一处不会漏另一处
#   SCAN_ATTRIBUTION     数据出处的署名，原样贴进 job summary
#
# 退出码：0＝汇总完成。「发现漏洞不阻断构建」照旧：报出高危也不让步骤失败；
# 1＝阳性对照失败——统计脚本自己坏了，那是工具故障，不是漏洞议题。
#
# 结构检查认两个字段：顶层 reportSchema（字符串）与 dependencies（数组）。
# 出处同 high-cvss.sh 开头注释（上游报告模板 jsonReport.vsl）：统计取值的
# 根都在这两个字段上，空对象 {} 或截断的半截 JSON 都过不了这道。
#
# 应出份数不写死：＝根 pom <modules> 清单里的模块数＋1（check goal 在
# reactor 的每个项目上各跑一遍、各出一份报告，packaging=pom 的聚合根也在内），
# 报告落在各项目的 target/ 下。模块清单变了，这里跟着 pom 走，不会漂。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
CONTROL_REPORT="$SCRIPT_DIR/../fixtures/dependency-check-control.json"

# 一份报告里的漏洞条目总数，口径与两个统计脚本相同（每个依赖下的每一条各算
# 一条）。「其余」＝总数－高危－缺 v3，三个数同一口径才减得出意义
count_all() {
  jq -r '[.dependencies[]? as $d | $d.vulnerabilities[]?] | length' "$1"
}

# ① 阳性对照：解析器抓不出对照报告里写死的那条高危、或数不对总条数＝检查
#    脚本本身失灵。失灵时给出的「未发现」比不给更危险，整步失败——这不是
#    漏洞不阻断的那个议题。对照报告的三条分别落在高危、其余、缺 v3 各一格
if ! CONTROL=$("$SCRIPT_DIR/high-cvss.sh" "$CONTROL_REPORT" 7 | wc -l | tr -d ' '); then
  printf '%s\n' "::error::漏洞统计的阳性对照没跑成——统计脚本自己报错，本次结果不可信"
  exit 1
fi
if [ "$CONTROL" != "1" ]; then
  printf '%s\n' "::error::漏洞统计的阳性对照失败（高危期望 1 条，实得 ${CONTROL}）——本次扫描结果不可信"
  exit 1
fi
if ! CONTROL_ALL=$(count_all "$CONTROL_REPORT"); then
  printf '%s\n' "::error::漏洞统计的阳性对照没跑成——总条数统计报错，本次结果不可信"
  exit 1
fi
if [ "$CONTROL_ALL" != "3" ]; then
  printf '%s\n' "::error::漏洞统计的阳性对照失败（总条数期望 3，实得 ${CONTROL_ALL}）——本次扫描结果不可信"
  exit 1
fi

# ② 收报告：各模块的报告都在自己 target/ 下，find 全收；排序让输出稳定
REPORTS=()
while IFS= read -r f; do
  if [ -n "$f" ]; then REPORTS+=("$f"); fi
done < <(find . -name 'dependency-check-report.json' | sort)

# 「没扫成」要亮在运行页上（::warning:: 注解），不能只沉在 job summary 里：
# summary 要点开才看得见，而没扫成的运行页和全绿的运行页长得一样。
# 两种情形都算没扫成：扫描步骤自己退非零（含到了步级上限被停），或跑完了
# 却一份报告都没落下来。只亮黄、不让 job 失败：这个 job 的职责是出报告，
# 「没扫成要不要拦推送」是另一个决定，不该在这里悄悄换掉
SCANNED=1
if [ "${SCAN_OUTCOME:-}" = "failure" ] || [ "${#REPORTS[@]}" -eq 0 ]; then
  SCANNED=0
  printf '%s\n' "::warning::依赖漏洞扫描这次没扫成（扫描步骤 outcome=${SCAN_OUTCOME:-未给出}，报告 ${#REPORTS[@]} 份）——别把结果栏的空白当成没有漏洞"
  if [ -z "${VULNCHECK_API_TOKEN:-}" ]; then
    printf '%s\n' "::warning::Actions 密钥 VULNCHECK_API_TOKEN 是空的——扫描拿不到令牌就取不到漏洞数据，请先在仓库设置里把它配好"
  fi
fi

# ③ 应出名单从根 pom 推：哪份该出而没出、哪份不在名单上却冒出来，都算
#    「结果不完整」，要与「没有漏洞」分开说
PROBLEMS=()
EXPECTED_LIST=""

read_expected_dirs() {
  python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('pom.xml').getroot()
ns = ''
if root.tag.startswith('{'):
    ns = root.tag[:root.tag.index('}') + 1]
mods = [m.text.strip() for m in root.findall(ns + 'modules/' + ns + 'module')
        if m.text and m.text.strip()]
if not mods:
    raise SystemExit('pom.xml 里解析不出 <modules>')
print('.')  # 聚合根：它自己也出一份报告
for m in mods:
    print(m)
PY
}

if EXPECTED_DIRS=$(read_expected_dirs); then
  while IFS= read -r d; do
    # 聚合根在清单里记作「.」：照模块那样拼「./$d/target/…」会得到「././target/…」，
    # 与 find 从仓库根给出的「./target/…」不是同一条路径，对账会永远多出两条假问题
    if [ "$d" = "." ]; then
      EXPECTED_LIST+="./target/dependency-check-report.json"$'\n'
    elif [ -n "$d" ]; then
      EXPECTED_LIST+="./$d/target/dependency-check-report.json"$'\n'
    fi
  done < <(printf '%s\n' "$EXPECTED_DIRS")
else
  PROBLEMS+=("应出份数推不出——根 pom.xml 解析不出模块清单，报告齐不齐没法核")
fi

# ④ 份份过结构检查：解析不了、结构不对的点名进 PROBLEMS，不进下面的统计。
#    jq 的报错不转述：它可能带出报告里的片段
VALID=()
if [ "${#REPORTS[@]}" -gt 0 ]; then
  for f in "${REPORTS[@]}"; do
    VERR=$(jq -r '(type == "object") and (.reportSchema | type == "string") and (.dependencies | type == "array") | tostring' "$f" 2>/dev/null) || VERR="error"
    if [ "$VERR" = "true" ]; then
      VALID+=("$f")
    elif [ "$VERR" = "false" ]; then
      PROBLEMS+=("\`$f\`——结构不对：顶层缺字符串 reportSchema 或数组 dependencies")
    else
      PROBLEMS+=("\`$f\`——不是可解析的 JSON")
    fi
  done
fi

# ⑤ 对账：应出没出（缺）、出了却不在名单上（多）
if [ -n "$EXPECTED_LIST" ] && [ "${#REPORTS[@]}" -gt 0 ]; then
  while IFS= read -r p; do
    if [ -n "$p" ] && ! printf '%s\n' "${REPORTS[@]}" | grep -Fxq -- "$p"; then
      PROBLEMS+=("\`$p\`——应出而未出（根 pom 模块清单推得）")
    fi
  done < <(printf '%s\n' "$EXPECTED_LIST")
  for f in "${REPORTS[@]}"; do
    if ! printf '%s\n' "$EXPECTED_LIST" | grep -Fxq -- "$f"; then
      PROBLEMS+=("\`$f\`——不在应出名单上（根 pom 模块清单推得），来源待查")
    fi
  done
fi

# ⑥ 统计只收可判的报告，且逐份接住统计的失败——把整轮循环接进一条
#    命令替换（HIGH=$(for ...; done | sort -u) 这种写法）时，中途某份解析
#    失败会被循环末轮的退码盖掉，空对象 {} 在 jq 的 .dependencies[]? 下安静
#    地判「无」：两条路都把「判不动」说成了「没有」。
#    一份报告的三项统计要么全成、要么整份不计：只计了一半的报告，「其余」
#    那个减法就减错了
COUNT=0
NOV3_COUNT=0
ALL_COUNT=0
JUDGED=0
if [ "${#VALID[@]}" -gt 0 ]; then
  for f in "${VALID[@]}"; do
    if ! H=$("$SCRIPT_DIR/high-cvss.sh" "$f" 7 2>/dev/null); then
      PROBLEMS+=("\`$f\`——高危统计没跑成")
      continue
    fi
    if ! N=$("$SCRIPT_DIR/no-cvssv3.sh" "$f" 2>/dev/null); then
      PROBLEMS+=("\`$f\`——无 v3 分数统计没跑成")
      continue
    fi
    if ! A=$(count_all "$f" 2>/dev/null); then
      PROBLEMS+=("\`$f\`——总条数统计没跑成")
      continue
    fi
    HC=0; NC=0
    if [ -n "$H" ]; then HC=$(printf '%s\n' "$H" | grep -c . || true); fi
    if [ -n "$N" ]; then NC=$(printf '%s\n' "$N" | grep -c . || true); fi
    if ! [ "$A" -ge $((HC + NC)) ] 2>/dev/null; then
      PROBLEMS+=("\`$f\`——各项统计对不上（总条数小于高危＋缺 v3）")
      continue
    fi
    COUNT=$((COUNT + HC))
    NOV3_COUNT=$((NOV3_COUNT + NC))
    ALL_COUNT=$((ALL_COUNT + A))
    JUDGED=$((JUDGED + 1))
  done
fi
OTHER_COUNT=$((ALL_COUNT - COUNT - NOV3_COUNT))

N_EXPECTED="?"
if [ -n "$EXPECTED_LIST" ]; then N_EXPECTED=$(printf '%s' "$EXPECTED_LIST" | grep -c . || true); fi

# 计数一行：job summary 与日志各一份，事后用 gh run view --log 也读得到。
# 一份可判报告都没有时三个数写「—」而不是 0：0 读起来就是「没有」
if [ "$SCANNED" = "1" ]; then
  STATE="扫成"
else
  STATE="没扫成（扫描步 outcome=${SCAN_OUTCOME:-未给出}）"
fi
if [ "$JUDGED" -gt 0 ]; then
  H_SHOW="$COUNT"; O_SHOW="$OTHER_COUNT"; N_SHOW="$NOV3_COUNT"
else
  H_SHOW="—"; O_SHOW="—"; N_SHOW="—"
fi
COUNTS_LINE="依赖漏洞扫描：${STATE}；可判报告 ${JUDGED}/${N_EXPECTED} 份（实收 ${#REPORTS[@]} 份）；CVSSv3 ≥ 7 高危 ${H_SHOW} 条；其余 ${O_SHOW} 条；缺 CVSSv3 ${N_SHOW} 条"

{
  printf '%s\n' "## 依赖漏洞扫描"
  printf '%s\n' "（阳性对照已通过：解析器在对照报告里数出高危 1 条、共 3 条，与预期相符）"
  if [ -n "${SCAN_SOURCES:-}" ]; then
    printf '%s\n' "数据来源：$SCAN_SOURCES"
  fi
  if [ -n "${SCAN_ATTRIBUTION:-}" ]; then
    printf '%s\n' "$SCAN_ATTRIBUTION"
  fi
  printf '%s\n' ""
  printf '%s\n' "$COUNTS_LINE"
  if [ "${#REPORTS[@]}" -eq 0 ]; then
    printf '%s\n' ""
    printf '%s\n' "⚠️ **未找到报告文件**——扫描步骤可能根本没跑成。"
    printf '%s\n' "别把这一栏的空白当成「没有漏洞」。"
  else
    if [ "${#PROBLEMS[@]}" -gt 0 ]; then
      printf '%s\n' ""
      printf '%s\n' "⚠️ **报告不完整或不可判（可判 ${JUDGED}/${#REPORTS[@]} 份）——下面的结论不覆盖全量**："
      for p in "${PROBLEMS[@]}"; do printf '%s\n' "- $p"; done
    fi
    printf '%s\n' ""
    if [ "$COUNT" = "0" ]; then
      if [ "${#PROBLEMS[@]}" -eq 0 ] && [ "$SCANNED" = "1" ]; then
        printf '%s\n' "✅ 本次未发现 CVSSv3 ≥ 7 的条目。"
      else
        printf '%s\n' "可判的报告里未发现 CVSSv3 ≥ 7 的条目——但这次没扫成或报告有 ⚠️，这不等于本次没有高危。"
      fi
    else
      NOTE=""
      if [ "${#PROBLEMS[@]}" -gt 0 ]; then NOTE="（只覆盖可判的 ${JUDGED}/${#REPORTS[@]} 份）"; fi
      printf '%s\n' "🔴 **发现 $COUNT 条 CVSSv3 ≥ 7 的依赖漏洞**（不阻断构建，但请处理）${NOTE}。"
      printf '%s\n' "明细不在这里公开：在本机对同一提交跑一遍扫描看报告。"
    fi

    # 没有 CVSSv3 分数的条目不参与上面的阈值判定。不报出来就等于
    # 把「没判到它」说成「它不高危」——同一个病的小号版本
    if [ "$NOV3_COUNT" != "0" ]; then
      NOTE2=""
      if [ "${#PROBLEMS[@]}" -gt 0 ]; then NOTE2="（只数了可判的报告）"; fi
      printf '%s\n' ""
      printf '%s\n' "ℹ️ 另有 **$NOV3_COUNT 条没有 CVSSv3 分数**（多为只有 CVSSv2 的旧条目）${NOTE2}，"
      printf '%s\n' "未参与上面的阈值判定，需要人工过目。"
    fi
  fi
} >> "$SUMMARY_FILE"

# 同一行计数也打进日志。缺省汇总已打到标准输出时不重复
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '%s\n' "$COUNTS_LINE"
fi

# 报告的问题与「没扫成」同理：要亮在运行页上，不能只沉在要点开才看得见的
# job summary 里
if [ "${#PROBLEMS[@]}" -gt 0 ]; then
  printf '%s\n' "::warning::漏洞汇总：报告有 ${#PROBLEMS[@]} 项问题（不可判／缺失／多出，详见 job summary 的 ⚠️ 段）——结果不完整，别当成没有漏洞"
fi
