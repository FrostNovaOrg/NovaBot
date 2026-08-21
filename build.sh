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
#   ./build.sh --clean      构建前先清理
#   ./build.sh --package    构建后把 dist/build 打成 dist/NovaBot-<版本>.tar.gz
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MAVEN_ARGS=(-B)
CLEAN=""
PACKAGE=""

for arg in "$@"; do
    case "$arg" in
        --skip-tests) MAVEN_ARGS+=(-DskipTests) ;;
        --clean)      CLEAN="clean" ;;
        --package)    PACKAGE="1" ;;
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
if ! git -C "$ROOT" rev-parse --git-dir > /dev/null 2>&1; then
    echo "这里不是 git 仓库，无法记录构建来源。" >&2
    echo "确要继续：NOVABOT_ALLOW_DIRTY_BUILD=1 NOVABOT_DIRTY_REASON=\"...\" $0" >&2
    [ -n "${NOVABOT_ALLOW_DIRTY_BUILD:-}" ] && [ -n "${NOVABOT_DIRTY_REASON:-}" ] || exit 1
    BUILD_COMMIT="unknown"; BUILD_TREE="unknown"; IS_DIRTY="true"
else
    BUILD_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
    BUILD_TREE="$(git -C "$ROOT" rev-parse "HEAD^{tree}")"
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

echo "==> [1/4] 安装构建插件 starbot-plugin-processor"
mvn "${MAVEN_ARGS[@]}" -f build-tools/starbot-plugin-processor/pom.xml ${CLEAN} install

# starbot-core 有两种产物形态：
#   install profile —— 普通库 jar，供各插件模块编译期依赖
#   package profile —— Spring Boot 重打包后的可运行 jar，类位于 BOOT-INF/classes
# 后者无法作为依赖被下游模块解析，因此必须先以 install 形态构建整个工程，最后再单独打发行包。
echo "==> [2/4] 构建全部模块（库形态）"
mvn "${MAVEN_ARGS[@]}" -Pinstall ${CLEAN} install

echo "==> [3/4] 打包可运行的 StarBotCore"
mvn "${MAVEN_ARGS[@]}" -f starbot-core/pom.xml -Ppackage package

echo "==> [4/4] 汇总产物至 dist/build"
OUT="$ROOT/dist/build"
PLUGIN_MODULES=(starbot-onebot-adapter starbot-onebot-adapter-napcat-extension starbot-bilibili)

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

# 不吞错误：模板拷贝失败时产物里会没有 application.yml，
# 而那要到运行时才暴露成一句莫名其妙的启动失败
cp -R dist/templates/. "$OUT/"

# BUILD-INFO 只进产物，不进仓库
{
    echo "commit=$BUILD_COMMIT"
    echo "tree=$BUILD_TREE"
    echo "dirty=$IS_DIRTY"
    echo "dirty_reason=$DIRTY_REASON"
    echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OUT/BUILD-INFO"

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
echo "首次运行前请编辑 $OUT/application.yml 与 $OUT/datasource.json"
