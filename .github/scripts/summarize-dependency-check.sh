#!/usr/bin/env bash
#
# 汇总 dependency-check 的 JSON 报告进 job summary，守住一条底线：
# 报告坏了、缺了、判不动时，绝不输出「未发现高危」——那句话只留给
# 「该出的报告一份不缺、份份可判、判完确实没有」。
#
# 为什么立成件而不写在 workflow 里：异常路径要逐份点名原因，逻辑塞进 yml
# 就没人核得动了；而它恰恰是要拿坏输入反复试的一段——立成件，CI 跑的与
# 本地能试的就是同一份，不存在「测的是抄本、跑的是另一份」。
#
# 环境变量（workflow 注入；本地自设同款即可离线复跑）：
#   GITHUB_STEP_SUMMARY  汇总写往的文件（缺省打到标准输出）
#   SCAN_OUTCOME         扫描步骤被 continue-on-error 豁免前的真实结果
#   NVD_API_KEY          只用来判空，绝不回显
#   SCAN_SOURCES         数据来源的一句话，由 workflow 里配扫描命令的地方给——
#                        配置与这句话同源，改一处不会漏另一处
#
# 退出码：0＝汇总完成。「发现漏洞不阻断构建」照旧：列出高危也不让步骤失败；
# 1＝阳性对照失败——统计脚本自己坏了，那是工具故障，不是漏洞议题。
#
# 结构检查认两个字段：顶层 reportSchema（字符串）与 dependencies（数组）。
# 出处同 high-cvss.sh 开头注释（上游报告模板 jsonReport.vsl）：两个统计脚本取值的
# 根都在这两个字段上，空对象 {} 或截断的半截 JSON 都过不了这道。
#
# 应出份数不写死：＝根 pom <modules> 清单里的模块数＋1（check goal 在
# reactor 的每个项目上各跑一遍、各出一份报告，packaging=pom 的聚合根也在内），
# 报告落在各项目的 target/ 下。模块清单变了，这里跟着 pom 走，不会漂。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

# ① 阳性对照：解析器抓不出对照报告里那条写死的 9.8＝检查脚本本身失灵。失灵
#    时给出的「未发现」比不给更危险，整步失败——这不是漏洞不阻断的那个议题
if ! CONTROL=$("$SCRIPT_DIR/high-cvss.sh" "$SCRIPT_DIR/../fixtures/dependency-check-control.json" 7 | wc -l | tr -d ' '); then
  printf '%s\n' "::error::漏洞统计的阳性对照没跑成——统计脚本自己报错，本次结果不可信"
  exit 1
fi
if [ "$CONTROL" != "1" ]; then
  printf '%s\n' "::error::漏洞统计的阳性对照失败（期望 1 条，实得 ${CONTROL}）——本次扫描结果不可信"
  exit 1
fi

# ② 收报告：各模块的报告都在自己 target/ 下，find 全收；排序让输出稳定
REPORTS=()
while IFS= read -r f; do
  if [ -n "$f" ]; then REPORTS+=("$f"); fi
done < <(find . -name 'dependency-check-report.json' | sort)

# 「没扫成」要亮在运行页上（::warning:: 注解），不能只沉在 job summary 里：
# summary 要点开才看得见，而没扫成的运行页和全绿的运行页长得一样。
# 两种情形都算没扫成：扫描步骤自己退非零，或跑完了却一份报告都没落下来。
# 只亮黄、不让 job 失败：这个 job 的职责是出报告，「没扫成要不要拦推送」
# 是另一个决定，不该在这里悄悄换掉
if [ "${SCAN_OUTCOME:-}" = "failure" ] || [ "${#REPORTS[@]}" -eq 0 ]; then
  printf '%s\n' "::warning::依赖漏洞扫描这次没扫成（扫描步骤 outcome=${SCAN_OUTCOME:-未给出}，报告 ${#REPORTS[@]} 份）——别把结果栏的空白当成没有漏洞"
  if [ -z "${NVD_API_KEY:-}" ]; then
    printf '%s\n' "::warning::Actions 密钥 NVD_API_KEY 是空的——扫描拿不到密钥会直接中止，请先在仓库设置里把它配好"
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

# ④ 份份过结构检查：解析不了、结构不对的点名进 PROBLEMS，不进下面的统计
VALID=()
if [ "${#REPORTS[@]}" -gt 0 ]; then
  for f in "${REPORTS[@]}"; do
    VERR=$(jq -r '(type == "object") and (.reportSchema | type == "string") and (.dependencies | type == "array") | tostring' "$f" 2>&1) || VERR="解析失败：$VERR"
    if [ "$VERR" = "true" ]; then
      VALID+=("$f")
    elif [ "$VERR" = "false" ]; then
      PROBLEMS+=("\`$f\`——结构不对：顶层缺字符串 reportSchema 或数组 dependencies")
    else
      PROBLEMS+=("\`$f\`——$VERR")
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

# ⑥ 统计只收可判的报告，且逐份接住统计脚本的失败——把整轮循环接进一条
#    命令替换（HIGH=$(for ...; done | sort -u) 这种写法）时，中途某份解析
#    失败会被循环末轮的退码盖掉，空对象 {} 在 jq 的 .dependencies[]? 下安静
#    地判「无」：两条路都把「判不动」说成了「没有」
HIGH_RAW=""
NOV3_RAW=""
if [ "${#VALID[@]}" -gt 0 ]; then
  for f in "${VALID[@]}"; do
    if ! OUT=$("$SCRIPT_DIR/high-cvss.sh" "$f" 7 2>&1); then
      PROBLEMS+=("\`$f\`——高危统计没跑成：$OUT")
      continue
    fi
    if [ -n "$OUT" ]; then HIGH_RAW+="$OUT"$'\n'; fi
  done
  for f in "${VALID[@]}"; do
    if ! OUT=$("$SCRIPT_DIR/no-cvssv3.sh" "$f" 2>&1); then
      PROBLEMS+=("\`$f\`——无 v3 分数统计没跑成：$OUT")
      continue
    fi
    if [ -n "$OUT" ]; then NOV3_RAW+="$OUT"$'\n'; fi
  done
fi

COUNT=0
NOV3_COUNT=0
N_EXPECTED=0
if [ -n "$HIGH_RAW" ]; then COUNT=$(printf '%s' "$HIGH_RAW" | grep -c . || true); fi
if [ -n "$NOV3_RAW" ]; then NOV3_COUNT=$(printf '%s' "$NOV3_RAW" | grep -c . || true); fi
if [ -n "$EXPECTED_LIST" ]; then N_EXPECTED=$(printf '%s' "$EXPECTED_LIST" | grep -c . || true); fi

{
  printf '%s\n' "## 依赖漏洞扫描"
  printf '%s\n' "（阳性对照已通过：解析器能在对照报告里抓出那条 9.8）"
  if [ -n "${SCAN_SOURCES:-}" ]; then
    printf '%s\n' "数据来源：$SCAN_SOURCES"
  fi
  if [ "${#REPORTS[@]}" -eq 0 ]; then
    printf '%s\n' "⚠️ **未找到报告文件**——扫描步骤可能根本没跑成。"
    printf '%s\n' "别把这一栏的空白当成「没有漏洞」。"
  else
    printf '%s\n' "报告 ${#REPORTS[@]} 份（应出 ${N_EXPECTED} 份）：$(printf '`%s` ' "${REPORTS[@]}")"
    if [ "${#PROBLEMS[@]}" -gt 0 ]; then
      printf '%s\n' ""
      printf '%s\n' "⚠️ **报告不完整或不可判（可判 ${#VALID[@]}/${#REPORTS[@]} 份）——下面的结论不覆盖全量**："
      for p in "${PROBLEMS[@]}"; do printf '%s\n' "- $p"; done
    fi
    if [ "$COUNT" = "0" ]; then
      if [ "${#PROBLEMS[@]}" -eq 0 ]; then
        printf '%s\n' "✅ 本次未发现 CVSSv3 ≥ 7 的条目。"
      else
        printf '%s\n' "可判的报告里未发现 CVSSv3 ≥ 7 的条目——但上面的 ⚠️ 未解除，这不等于本次没有高危。"
      fi
    else
      NOTE=""
      if [ "${#PROBLEMS[@]}" -gt 0 ]; then NOTE="（只覆盖可判的 ${#VALID[@]}/${#REPORTS[@]} 份）"; fi
      printf '%s\n' "🔴 **发现 $COUNT 条 CVSSv3 ≥ 7 的依赖漏洞**（不阻断构建，但请处理）${NOTE}："
      printf '%s\n' '```'
      printf '%s' "$HIGH_RAW" | head -40 || true
      printf '%s\n' '```'
      if [ "$COUNT" -gt 40 ]; then printf '%s\n' "（上面只列了前 40 条，共 $COUNT 条）"; fi
    fi

    # 只有 CVSSv2 的旧条目不参与上面的阈值判定。不报出来就等于
    # 把「没判到它」说成「它不高危」——同一个病的小号版本
    if [ "$NOV3_COUNT" != "0" ]; then
      NOTE2=""
      if [ "${#PROBLEMS[@]}" -gt 0 ]; then NOTE2="（只数了可判的报告）"; fi
      printf '%s\n' ""
      printf '%s\n' "ℹ️ 另有 **$NOV3_COUNT 条没有 CVSSv3 分数**（多为只有 CVSSv2 的旧条目）${NOTE2}，"
      printf '%s\n' "未参与上面的阈值判定，需要人工过目："
      printf '%s\n' '```'
      printf '%s' "$NOV3_RAW" | head -20 || true
      printf '%s\n' '```'
    fi
  fi
} >> "$SUMMARY_FILE"

# 报告的问题与「没扫成」同理：要亮在运行页上，不能只沉在要点开才看得见的
# job summary 里
if [ "${#PROBLEMS[@]}" -gt 0 ]; then
  printf '%s\n' "::warning::漏洞汇总：报告有 ${#PROBLEMS[@]} 项问题（不可判／缺失／多出，详见 job summary 的 ⚠️ 段）——结果不完整，别当成没有漏洞"
fi
