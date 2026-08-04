#!/usr/bin/env bash
#
# StarBot 3.0 构建脚本
#
# Maven 不支持在同一 reactor 内构建并使用同一个插件，因此需要分两步：
#   1. 先安装 build-tools/starbot-plugin-processor（各插件模块在 build 阶段会调用它）
#   2. 再构建主工程
#
# 用法:
#   ./build.sh              构建并运行测试，产物输出至 dist/build
#   ./build.sh --skip-tests 跳过测试
#   ./build.sh --clean      构建前先清理
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MAVEN_ARGS=(-B)
CLEAN=""

for arg in "$@"; do
    case "$arg" in
        --skip-tests) MAVEN_ARGS+=(-DskipTests) ;;
        --clean)      CLEAN="clean" ;;
        *)            echo "未知参数: $arg" >&2; exit 1 ;;
    esac
done

if ! command -v mvn > /dev/null 2>&1; then
    echo "未找到 mvn，请先安装 Maven 3.9 或更高版本" >&2
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
    echo "需要 Java 17 或更高版本，当前为 ${JAVA_MAJOR:-未知}" >&2
    exit 1
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

echo
echo "构建完成，产物位于 dist/build"
echo "首次运行前请编辑 $OUT/application.yml 与 $OUT/datasource.json"
