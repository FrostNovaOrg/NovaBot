#!/usr/bin/env bash
#
# NovaBot 构建脚本
#
# Maven 不支持在同一 reactor 内构建并使用同一个插件，因此需要分两步：
#   1. 先安装 build-tools/starbot-plugin-processor（各插件模块在 build 阶段会调用它）
#   2. 再构建主工程
#
# 用法:
#   ./build.sh              构建并运行测试，产物输出至 dist/build
#   ./build.sh --skip-tests 跳过测试
#   ./build.sh --no-smoke   跳过收尾的起动冒烟。何时用：机器上没有可运行的 JDK（只出包不试起），
#                           或 CI 里把冒烟拆成单独一步、build.sh 只管出产物时
#   ./build.sh --clean      已是默认行为，保留只为兼容旧命令行（见下方「陈旧产物」一段）
#   ./build.sh --package    构建后把 dist/build 打成 dist/NovaBot-<版本>.tar.gz
#   ./build.sh --from-ref=<ref>
#                           从 git archive <ref> 导出的干净树里构建（发布必用，理由见下方注释）
#                           也可用环境变量：NOVABOT_BUILD_REF=<ref> ./build.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MAVEN_ARGS=(-B)
# ── 陈旧产物：为什么这里恒为 clean ──────────────────────────────────────
# 🔴 Maven 只往 target/ 里写，从不为「源码里已经没有的东西」做删除。源文件删掉或改名之后，
#    上一次构建留下的那一份照旧躺在 target/classes 里，照旧被打进 jar，而**包上看不出来**。
#
#    2026-09-03 实测的形状：某个界面文件已从源码树删除并改由插件提供，
#    未清理的 target/classes 里那份旧的仍在，进了包；而界面资源出口是「核心里有就用核心的，
#    没有才回落到插件」——于是旧文件静默压过插件那一份，服务端回的是一份源码里根本不存在的页面，
#    请求成功、状态码 200、长度也正常，只是内容来自上一个版本。
#
# 🔴 选 `mvn clean` 而不是「把那个目录删掉」：删目录只答得了今天这一例。
#    真正的形状是「产物目录里可以有源码里没有的任何东西」——删掉的类留下的 .class、
#    改过名的资源、上一版的 MANIFEST 条目，都是同一件事。一把只清一个目录的扫帚，
#    下次换个位置照样漏，而漏掉时的表现仍然是「一切正常」。
#
#    代价是每次构建都从零编译。这条链本来就是出包用的（要跑整测、要打 tar），
#    不是改一行看一眼的内循环；用它换「包里的东西都出自源码」这句话能当真，值。
#
# 🔴 清不到的地方要写明：`mvn clean` 走的是 reactor，而 build-tools/starbot-plugin-processor
#    与 templates/starbot-example-plugin 都不在模块列表里（理由见 pom.xml:30-35）。
#    前者由下面 [1/8] 用 -f 单独构建，那一步同样带上 clean；后者本脚本根本不构建，
#    它的 target/ 里有什么都进不了 dist/build。
#
# --clean 保留为空动作：README 与 docs/architecture.md 里写过它，敲了不该报「未知参数」。
CLEAN="clean"
PACKAGE=""
SMOKE="1"
BUILD_REF="${NOVABOT_BUILD_REF:-}"
# 转发给内层（干净树里那一次）构建的参数：--from-ref 自己不转发，否则会无限套娃
INNER_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --skip-tests) MAVEN_ARGS+=(-DskipTests); INNER_ARGS+=("$arg") ;;
        --clean)      : "已是默认";                INNER_ARGS+=("$arg") ;;
        --package)    PACKAGE="1";               INNER_ARGS+=("$arg") ;;
        --no-smoke)   SMOKE="0";                 INNER_ARGS+=("$arg") ;;
        --from-ref=*) BUILD_REF="${arg#--from-ref=}" ;;
        *)            echo "未知参数: $arg" >&2; exit 1 ;;
    esac
done

if ! command -v mvn > /dev/null 2>&1; then
    echo "未找到 mvn，请先安装 Maven 3.9 或更高版本" >&2
    exit 1
fi

# 不用 head -1：它读够一行就关闭管道，上游 java 随即 SIGPIPE，
# 而本脚本开了 pipefail，会把整条管道判为失败
JAVA_MAJOR="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
    echo "需要 Java 17 或更高版本，当前为 ${JAVA_MAJOR:-未知}" >&2
    exit 1
fi

# 🔴 上界。这里原先只有下界，于是 JDK 26 一路放行 —— 而在它上面 Lombok 会静默失效，
#    报出来的是一串**指向别处的普通编译错误**，一个字都不提 Lombok：
#
#      AlertService.java:[91,13] cannot find symbol   symbol: variable log
#      AlertService.java:[89,34] cannot find symbol   symbol: method getAlert()
#
#    （2026-08-21 在 JDK 26.0.2 上实测的原文。）照着这种错去查，会一路查到
#    「谁把 @Slf4j 删了」上面去，而那边根本没问题。**报错指向哪里，和毛病在哪里，是两件事。**
#
# 🔴 这个上界不是「Lombok 在哪一版坏掉」——那个数我没量过，不写没量过的数。
#    它是**「这个工程验过哪一版」**：pom 里 java.version=17，实测 17 通过、26 失败，
#    17 与 26 之间一版都没试过。所以默认只放行验过的那一版。
#    要在别的版本上试，显式打开：NOVABOT_ALLOW_UNTESTED_JDK=1 ./build.sh
#    试通了就把 JAVA_VERIFIED_MAX 抬上去，并把新读数补进这段注释 —— 抬数之前先跑一次整测。
JAVA_VERIFIED_MAX=17
if [ "${JAVA_MAJOR}" -gt "${JAVA_VERIFIED_MAX}" ] && [ -z "${NOVABOT_ALLOW_UNTESTED_JDK:-}" ]; then
    echo "当前 Java ${JAVA_MAJOR}，而本工程只在 Java ${JAVA_VERIFIED_MAX} 上验过（pom 的 java.version 也是它）。" >&2
    echo "高版本上 Lombok 会静默失效，报错会伪装成一串「cannot find symbol: variable log」，" >&2
    echo "指向的位置与真实原因无关 —— 那种错查起来很贵，所以这里直接拦住。" >&2
    echo "" >&2
    echo "改用 17：export JAVA_HOME=<jdk17 路径> && export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
    echo "（PATH 也要改：本脚本查的是 PATH 上的 java，只设 JAVA_HOME 不算数）" >&2
    echo "确要在未验版本上试：NOVABOT_ALLOW_UNTESTED_JDK=1 $0" >&2
    exit 1
fi

# ── 构建来源：BUILD-INFO 与工作区闸 ─────────────────────────────────────
# 产物里放一份 BUILD-INFO，记下它是从哪一次提交、哪一棵树构建出来的。
# 没有这两行，拿到一个包之后就无法回答「它出自哪份源码」——只能靠回忆，
# 而回忆答不了这个问题。tree 那一行尤其关键：它是可以拿去比对的事实。
#
# 默认要求工作区干净。从脏工作区构建出来的产物，其内容与任何一次提交都不对应，
# 而**包本身看不出这一点**——它和干净构建出来的包长得一模一样。
#
# 确需从脏工作区构建，两个变量都要给：
#   NOVABOT_ALLOW_DIRTY_BUILD=1 NOVABOT_DIRTY_REASON="为什么" ./build.sh
# 只给开关不给理由会被拒绝。开了覆盖之后，dirty=true 与理由照写进 BUILD-INFO：
# 一道被关掉的闸，产物上必须看得出来它是关着的——否则「关掉」和「一切正常」
# 长得一样，那这道闸等于不存在。
#
# 未跟踪的文件同样算脏：用 status --porcelain -uall，不是 diff --quiet。
# diff --quiet 看不见未跟踪文件，而未跟踪文件照样会被打进产物。
if [ -n "${NOVABOT_ARCHIVE_BUILD:-}" ]; then
    # 这是内层：当前目录是 git archive 导出的树，里面没有 .git，问不出来源。
    # 来源由外层解析好之后传进来 —— 内层不去猜，猜出来的来源和没有来源是一回事。
    BUILD_COMMIT="$NOVABOT_ARCHIVE_BUILD"
    BUILD_TREE="$NOVABOT_ARCHIVE_TREE"
    BUILD_SOURCE="archive:${NOVABOT_ARCHIVE_REF}"
    IS_DIRTY="false"
elif ! git -C "$ROOT" rev-parse --git-dir > /dev/null 2>&1; then
    echo "这里不是 git 仓库，无法记录构建来源。" >&2
    echo "确要继续：NOVABOT_ALLOW_DIRTY_BUILD=1 NOVABOT_DIRTY_REASON=\"...\" $0" >&2
    [ -n "${NOVABOT_ALLOW_DIRTY_BUILD:-}" ] && [ -n "${NOVABOT_DIRTY_REASON:-}" ] || exit 1
    BUILD_COMMIT="unknown"; BUILD_TREE="unknown"; IS_DIRTY="true"; BUILD_SOURCE="worktree"
else
    BUILD_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
    BUILD_TREE="$(git -C "$ROOT" rev-parse "HEAD^{tree}")"
    BUILD_SOURCE="worktree"
    if [ -n "$(git -C "$ROOT" status --porcelain -uall)" ]; then
        IS_DIRTY="true"
    else
        IS_DIRTY="false"
    fi
fi

DIRTY_REASON="${NOVABOT_DIRTY_REASON:-}"

if [ "$IS_DIRTY" = "true" ]; then
    if [ -z "${NOVABOT_ALLOW_DIRTY_BUILD:-}" ]; then
        echo "工作区有未提交的改动，已停止构建，未产出任何文件。" >&2
        echo "" >&2
        git -C "$ROOT" status --porcelain -uall >&2
        echo "" >&2
        echo "提交或清理后重试；确要照此构建：" >&2
        echo "  NOVABOT_ALLOW_DIRTY_BUILD=1 NOVABOT_DIRTY_REASON=\"为什么\" $0 $*" >&2
        exit 1
    fi
    if [ -z "$DIRTY_REASON" ]; then
        echo "已开启 NOVABOT_ALLOW_DIRTY_BUILD，但没有给 NOVABOT_DIRTY_REASON。" >&2
        echo "理由会写进产物的 BUILD-INFO，用来说明这个包为什么不对应任何一次提交。" >&2
        echo "不写理由就不产出。" >&2
        exit 1
    fi
    echo "注意：正从脏工作区构建，BUILD-INFO 将记 dirty=true"
    echo "      理由：$DIRTY_REASON"
fi

# ── 打包源：从 git archive 出干净树 ────────────────────────────────────────
# 🔴 上面那道脏闸拦的是「工作区有改动」。它拦不住另一件事：**被忽略的文件照样进包**。
#
#    忽略件不出现在 status --porcelain -uall 里（-uall 只补列未跟踪的，不列被忽略的），
#    所以闸看过去是干净的；而下面「汇总产物」那一步是 `cp -R dist/templates/. "$OUT/"`，
#    照目录拷，目录里有什么就拷什么。dist/templates/datasource.json 正是一个被忽略的
#    未跟踪文件，它就这样进了 dist/build/datasource.json。
#
#    2026-08-24 实测：工作区 status --porcelain -uall 一个字都没输出（＝干净），
#    而 dist/build/datasource.json 已经在那里了。**这不是闸没关，是闸量的那个量不含它。**
#    ——一把只量「改动」的尺，永远读不出「忽略」；它的「都对」和它看不见，长得一样。
#
# 所以发布构建的打包源不许是工作目录，只许是一棵从 git 导出的树：
#
#     ./build.sh --from-ref=origin/main --package
#
# git archive 只吐已跟踪文件。忽略件**结构上进不去**，不依赖任何一处「记得检查」——
# 这是与「加一条检查」的区别：检查会漏，导出的树里根本没有那个文件可漏。
# 脏闸保留为前置：它拦不住忽略件，但它仍然是「你看的和你建的不是一份」的唯一提示。
if [ -n "$BUILD_REF" ] && [ -z "${NOVABOT_ARCHIVE_BUILD:-}" ]; then
    if ! git -C "$ROOT" rev-parse --git-dir > /dev/null 2>&1; then
        echo "--from-ref 需要 git 仓库，而这里不是。" >&2
        exit 1
    fi
    if ! REF_SHA="$(git -C "$ROOT" rev-parse --verify --quiet "${BUILD_REF}^{commit}")"; then
        echo "解不出 ref：$BUILD_REF" >&2
        exit 1
    fi
    REF_TREE="$(git -C "$ROOT" rev-parse "${REF_SHA}^{tree}")"

    STAGE="$(mktemp -d "${TMPDIR:-/tmp}/novabot-archive-XXXXXX")"
    trap 'rm -rf "$STAGE"' EXIT

    echo "==> [0/8] 从 $BUILD_REF 导出干净树"
    echo "    commit=$REF_SHA"
    echo "    tree=$REF_TREE"
    echo "    导出至 $STAGE"
    git -C "$ROOT" archive --format=tar "$REF_SHA" | tar -x -C "$STAGE"

    if [ ! -x "$STAGE/build.sh" ]; then
        echo "导出的树里没有可执行的 build.sh，已停止。" >&2
        exit 1
    fi

    # 外层工作区脏不脏，不影响内层产物的字节（内层建的是 $REF_SHA 那棵树），
    # 所以 BUILD-INFO 的 dirty= 记 false 是照实记。但「脏闸曾被打开」这件事也要写下来：
    # 另记一行 outer_worktree_dirty，不混进 dirty=。过程和结论分开写，两个都不失真。
    # source= 记解析后的 40 位，不记 --from-ref 收到的那个名字：
    # HEAD、main、v4.3.0 这些名字会挪，明天再解一次可能落到另一次提交上。
    # 拿到包的人要能凭这一行找回**当时那一棵树**，所以这里传的是已经解开的 commit。
    # 人当初敲的是哪个名字另记一行，两件事都留着，谁也不冒充谁。
    NOVABOT_ARCHIVE_BUILD="$REF_SHA" \
    NOVABOT_ARCHIVE_TREE="$REF_TREE" \
    NOVABOT_ARCHIVE_REF="$REF_SHA" \
    NOVABOT_ARCHIVE_REF_ASKED="$BUILD_REF" \
    NOVABOT_ARCHIVE_OUTER_DIRTY="$IS_DIRTY" \
        "$STAGE/build.sh" ${INNER_ARGS[@]+"${INNER_ARGS[@]}"}

    echo
    echo "==> 取回产物"
    rm -rf "$ROOT/dist/build"
    mkdir -p "$ROOT/dist"
    cp -R "$STAGE/dist/build" "$ROOT/dist/build"
    echo "    $ROOT/dist/build"
    for tb in "$STAGE"/dist/*.tar.gz; do
        [ -e "$tb" ] || continue
        cp "$tb" "$ROOT/dist/"
        echo "    $ROOT/dist/$(basename "$tb")"
    done
    exit 0
fi

echo "==> [1/8] 安装构建插件 starbot-plugin-processor"
mvn "${MAVEN_ARGS[@]}" -f build-tools/starbot-plugin-processor/pom.xml ${CLEAN} install

# starbot-core 有两种产物形态：
#   install profile —— 普通库 jar，供各插件模块编译期依赖
#   package profile —— Spring Boot 重打包后的可运行 jar，类位于 BOOT-INF/classes
# 后者无法作为依赖被下游模块解析，因此必须先以 install 形态构建整个工程，最后再单独打发行包。
echo "==> [2/8] 构建全部模块（库形态）"
mvn "${MAVEN_ARGS[@]}" -Pinstall ${CLEAN} install

echo "==> [3/8] 打包可运行的 StarBotCore"
# 这一步不带 clean：[2/8] 刚把 starbot-core/target 清空并重建过，此刻目录里只有那一次的产物。
# 在这里再清一次，等于把上一步刚编好的东西删掉重编一遍，清掉的却是同一批文件。
mvn "${MAVEN_ARGS[@]}" -f starbot-core/pom.xml -Ppackage package

echo "==> [4/8] 汇总产物至 dist/build"
OUT="$ROOT/dist/build"
PLUGIN_MODULES=(starbot-onebot-adapter starbot-onebot-adapter-napcat-extension starbot-bilibili starbot-novabot-console starbot-report)

rm -rf "$OUT"
mkdir -p "$OUT/plugins" "$OUT/lib" "$OUT/plugins-lib"

cp starbot-core/target/dist/StarBotCore.jar "$OUT/"
cp starbot-core/target/lib/*.jar "$OUT/lib/"

for module in "${PLUGIN_MODULES[@]}"; do
    cp "$module"/target/"$module"-*.jar "$OUT/plugins/"

    # 插件自身的运行期依赖放入 plugins-lib（启动参数 -Dloader.path=lib,plugins-lib 会加载此目录）。
    # 若缺失，StarBot 会在启动后检测到依赖不全并触发一次自动下载与重启，推送接口也就无法及时注册。
    mvn "${MAVEN_ARGS[@]}" -f "$module/pom.xml" dependency:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory="$OUT/plugins-lib" \
        -q
done

# 去除与核心 lib 目录重复的依赖，避免同一个 jar 被加载两次
for jar in "$OUT"/plugins-lib/*.jar; do
    [ -e "$jar" ] || continue
    if [ -e "$OUT/lib/$(basename "$jar")" ]; then
        rm -f "$jar"
    fi
done
# 插件模块自身的 jar 已在 plugins 目录，无需在 plugins-lib 中重复
for module in "${PLUGIN_MODULES[@]}"; do
    rm -f "$OUT"/plugins-lib/"$module"-*.jar
done
rm -f "$OUT"/plugins-lib/starbot-core-*.jar

# 不吞错误：模板拷贝失败时产物里会缺掉启动脚本与示例配置，
# 而那要到运行时才暴露成一句莫名其妙的启动失败
cp -R dist/templates/. "$OUT/"

# ── 包里不带 application.yml，只带 application.example.yml ────────────────────
# 程序在第一次保存设置时自己写出一份完整的 application.yml，键与说明取自它自己的配置面。
# 包里再塞一份手写的，就有了两份互不钉住的「完整配置」——2026-09-04 现算，手写那份
# 缺了配置面 80 项里的 24 项，而缺项的表现是「设置页上有、配置文件里没有」，
# 使用者照着文件改，改不到那一项，程序照常启动、什么也不报。
#
# 🔴 无条件删，不是「有才删」。理由与下面 datasource.json 那一条同族：archive 那条路上
#    本来就没有这个文件，而 worktree 那条路上，打包那台机器 dist/templates/ 里若还躺着
#    改名前留下的 application.yml，`cp -R` 已经原样把它拷进来了。
#    闸要落在「谁都拦不住的位置」：不问它在不在，一律删。
rm -f "$OUT/application.yml"

# datasource.json 被 .gitignore 忽略（免得谁把自己的真配置提交上来），因此导出的树里没有它。
# 🔴 上一版包里那份 datasource.json 是上面 `cp -R dist/templates/.` 从**打包那台机器的
#    本地文件**拷来的：它不在仓库里，内容取决于谁来打包，而包本身看不出这一点。
#
# 🔴 **无条件覆盖，不是「没有才写」。** 写成 `if [ ! -e ]` 只堵住了 archive 那条路
#    ——那条路上本来就没有这个文件；而 worktree 那条路上它**已经被 cp 拷进来了**，
#    条件不成立，于是本机件原样进包。这正是 v5.0.0-beta2 的病灶形状：
#    **值只活在打包那一刻的工作区里**，仓库全历史干净，包里却带着它。
#    覆盖要落在「谁都拦不住的位置」：不问它在不在，一律动手。
#
# 🔴 5.1 起从「写成空数组」改为「删掉」：这个文件由控制台在加第一位主播时生成，
#    包里带一份空的等于替使用者做了一半的事——而它与 application.yml 一样，
#    一旦存在就归使用者所有，程序不会再去动它。删的语义比写空数组准：
#    「还没配」与「配成了空的」在一个 [] 上长得一样，而只有前者是真的。
rm -f "$OUT/datasource.json"

# template-defaults.json 同族：第一次改默认模板时由程序写出，不随包。
# 无条件删，理由与上面两条相同——worktree 那条路上打包机的 dist/templates/
# 里若躺着一份本机改过的覆盖，`cp -R` 已经把它拷进来了。
rm -f "$OUT/template-defaults.json"

# BUILD-INFO 只进产物，不进仓库
# source= 这一行是给拿到包的人看的：worktree 表示打包源是某人的工作目录
# （那么包里可能有仓库里没有的文件），archive:<40 位 commit> 表示打包源是从那一次提交
# 导出的一棵树。两种包长得一样，不写这一行就分不出来。
# 记 40 位而不记 HEAD 一类的名字：名字会挪，凭一个会挪的名字回不到当时那棵树。
# source_ref_asked= 记人当初敲的是哪个名字，只在它与 commit 不同时出现。
{
    echo "commit=$BUILD_COMMIT"
    echo "tree=$BUILD_TREE"
    echo "source=$BUILD_SOURCE"
    if [ -n "${NOVABOT_ARCHIVE_REF_ASKED:-}" ] && [ "$NOVABOT_ARCHIVE_REF_ASKED" != "$BUILD_COMMIT" ]; then
        echo "source_ref_asked=$NOVABOT_ARCHIVE_REF_ASKED"
    fi
    echo "dirty=$IS_DIRTY"
    echo "dirty_reason=$DIRTY_REASON"
    if [ -n "${NOVABOT_ARCHIVE_OUTER_DIRTY:-}" ]; then
        echo "outer_worktree_dirty=$NOVABOT_ARCHIVE_OUTER_DIRTY"
    fi
    echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OUT/BUILD-INFO"

# ── [5/8] 产物守卫 ──────────────────────────────────────────────────────
# 上面那道恒 clean 答的是「构建有没有从空目录开始」；这一格答的是另一个问题：
# 「打出来的包里有没有源码里不存在的界面资源」。前者管编译输出，管不着从别处拷进 dist/build 的东西，
# 也管不住将来有谁把 clean 改回去。**只装一道就是把另一个问题悄悄结掉**，而它下次出事时
# 表现仍然是「BUILD SUCCESS」。判据与理由写在尺里。
#
# 放在打包之前：脏产物不许被压进 tar.gz——包一旦成形就会被拿去发，那时再发现已经晚一步。
echo
echo "==> [5/8] 校验产物界面资源"
"$ROOT/tools/artifact-ui-resource-check.sh" "$OUT"

# ── [6/8] 界面视图模型 ──────────────────────────────────────────────────
# 名单写死一处：少写一把，那一页的模型从此只靠人手跑，
# 而「人手跑过」和「没跑」在构建日志上长得一样。任一红即本构建红。
# 本树已有主播页那一把，名单十三把（首页／推送／连接／主播／日志／初始设置／模板／今日卡／
# 设置／登录／告警／确认／只读口令）。
echo
echo "==> [6/8] 校验界面视图模型"
MODEL_CHECKERS=(
    home-model-check.sh
    push-model-check.sh
    links-model-check.sh
    streamers-model-check.sh
    log-model-check.sh
    setup-model-check.sh
    template-model-check.sh
    today-model-check.sh
    settings-model-check.sh
    login-model-check.sh
    alert-model-check.sh
    confirm-model-check.sh
    tokens-model-check.sh
)
for checker in "${MODEL_CHECKERS[@]}"; do
    bash "$ROOT/tools/$checker"
done

# ── [7/8] 测试夹具隐私 ──────────────────────────────────────────────────
# 尺已经在 tools/ 里，但只靠人手跑时，「跑过」和「没跑」在构建日志上长得一样。
# 接进构建：退码非 0 即本构建红。它扫的是源码树 src/test（头像哈希须在允许名单、
# 邮箱须用保留域），不依赖产物，放在打包前后皆可——按现有顺序放在冒烟之前。
# 这一步不被 --no-smoke 跳过：冒烟量的是「包起不起得来」，夹具隐私是另一件事。
echo
echo "==> [7/8] 校验测试夹具隐私"
bash "$ROOT/tools/fixture-privacy-check.sh"

# ── [8/8] 起动冒烟 ──────────────────────────────────────────────────────
# 上面七步答的是「编译过、测过、包里的文件都出自源码、视图模型对得上、测试夹具隐私过了」；这一步答的是另一句：
# 这堆 jar 摆在一起，在一台没有任何配置文件的机器上，起不起得来。
# 单元测试里每个类都是自己 new 出来的，谁也不经过容器；容器到启动那一刻才第一次
# 按类型去凑构造参数，凑不齐当场退出——所以「整测全绿而包起不来」在结构上可能，
# 且此前两件事之间没有任何一处把对方钉住（2026-09-04 实测到的正是：
# 同一类型在容器里有两个候选，注入点要一个，程序当场退出）。
# 判据与做法写在尺里：tools/boot-smoke.sh。
#
# 红也照留产物与 BUILD-INFO，不抹：这一步量的是产物，「出过一个起不来的包」这件事
# 本身就该留在盘上给事后看；抹掉只会把红变成「什么都没发生过」。
# 冒烟端口与超时可用环境变量 BOOT_SMOKE_PORT／BOOT_SMOKE_TIMEOUT 调（尺内定义），
# 例如 7827 已被别的进程占着时：BOOT_SMOKE_PORT=17827 ./build.sh
echo
if [ "$SMOKE" = "1" ]; then
    echo "==> [8/8] 起动冒烟"
    # bash 调用而不是直接执行：这把尺在仓库里不带执行位，且 CI 的 runner 按 git 记录的
    # 权限 checkout——直接执行在「谁chmod过谁的环境」上绿、在干净 checkout 上炸，
    # 而那种炸与「装配坏了」的红同形。不依赖盘上权限位的调法在哪都一样。
    bash "$ROOT/tools/boot-smoke.sh" "$OUT"
else
    echo "==> [8/8] 起动冒烟：已按 --no-smoke 跳过"
fi

if [ -n "$PACKAGE" ]; then
    VERSION="$(mvn -B -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1)"
    if [ -z "$VERSION" ]; then
        echo "取不到版本号，未打包。" >&2
        exit 1
    fi
    TARBALL="$ROOT/dist/NovaBot-${VERSION}.tar.gz"
    # COPYFILE_DISABLE=1：macOS 的 tar 默认会为带扩展属性的文件另塞一个 ._ 边车条目，
    # 而 tar tzvf 不显示它——列一遍看不出来，它却真的在包里，跟着一起发出去。
    COPYFILE_DISABLE=1 tar -czf "$TARBALL" -C "$OUT" .
    echo
    echo "已打包：$TARBALL"
    echo "  sha256=$(shasum -a 256 "$TARBALL" | awk '{print $1}')"
    echo "  条目数=$(python3 -c "import tarfile,sys;print(sum(1 for m in tarfile.open(sys.argv[1]).getmembers() if m.isfile()))" "$TARBALL")"
fi

echo
echo "构建完成，产物位于 dist/build"
echo "直接起就行，不必先写配置：起来之后打开控制台按初始设置走一遍，"
echo "程序会自己写出 application.yml 与 datasource.json（想先手写就照 application.example.yml 抄）"
