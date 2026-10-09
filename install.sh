#!/usr/bin/env bash
#
# NovaBot 一键安装脚本（Linux）
#
# 用法：
#   ./install.sh                        从当前源码构建并安装到 /opt/starbot
#   ./install.sh --dir /srv/starbot     指定安装目录（须为绝对路径）
#   ./install.sh --port 7827            指定服务端口
#   ./install.sh --user starbot         指定运行服务的系统用户
#   ./install.sh --no-service           跳过 systemd 服务创建
#   ./install.sh --no-switch            只装、不换（有别的实例在跑时不自动换到本版）
#   ./install.sh --no-packages          不经包管理器装任何东西（缺 Java 17 就停下，缺字体只提醒）
#   ./install.sh --keep-syslog          系统日志（rsyslog）里照旧多记一份 NovaBot 的输出
#
# 安装目录若还是旧的扁平布局（程序在根上），先把旧程序搬进 releases/旧版本号/ 再装。
#
# 脚本会依次完成：检查并安装 Java 17、构建、安装到目标目录、生成配置、
# 创建 systemd 服务、输出配置界面地址。
#
set -euo pipefail

INSTALL_DIR="/opt/starbot"
SERVICE_USER="starbot"
PORT="7827"
CREATE_SERVICE="yes"
NO_SWITCH="no"
NO_PACKAGES="no"
KEEP_SYSLOG="no"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

info()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m警告:\033[0m %s\n' "$*"; }
die()   { printf '\033[31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

# 用法说明取自文件头的注释块，读到第一行非注释为止。
# 不写死行号：脚本增删几行后，写死的范围会把 set -euo pipefail 之类的代码当成用法打印出来
usage() { sed -n '2,/^[^#]/p' "$0" | sed -n 's/^# \{0,1\}//p'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)        INSTALL_DIR="$2"; shift 2 ;;
        --port)       PORT="$2"; shift 2 ;;
        --user)       SERVICE_USER="$2"; shift 2 ;;
        --no-service) CREATE_SERVICE="no"; shift ;;
        --no-switch)  NO_SWITCH="yes"; shift ;;
        --no-packages) NO_PACKAGES="yes"; shift ;;
        --keep-syslog) KEEP_SYSLOG="yes"; shift ;;
        -h|--help)    usage; exit 0 ;;
        *)            die "未知参数: $1（可用 --help 查看用法）" ;;
    esac
done

# 以下取值会被拼进 rm -rf 与 sed，先卡住明显危险的输入。
# --dir "" 会让后面的清理变成 rm -rf /lib，以 root 运行时足够毁掉整个系统
case "$INSTALL_DIR" in
    /)     die "--dir 不能是根目录" ;;
    /?*)   ;;
    *)     die "--dir 需为绝对路径，当前为「${INSTALL_DIR}」" ;;
esac
case "$INSTALL_DIR" in
    *'#'*) die "--dir 不能包含 # 号（生成 systemd 单元时以 # 作 sed 分隔符）" ;;
    *'&'*) die '--dir 不能包含 & 号（生成文件时替换串里的 & 代表匹配到的原文，目录会指错）' ;;
    *'\'*) die '--dir 不能包含反斜杠（生成文件时替换串里的反斜杠会吞掉后面的字符）' ;;
    *'"'*) die '--dir 不能包含双引号（生成的文件里这一段包在双引号中，引号会提前收尾）' ;;
    *"'"*) die '--dir 不能包含单引号（生成的文件里引号会配不上对）' ;;
    *'$'*) die '--dir 不能包含 $ 号（装出的系统命令每次换版都会把它当变量或命令替换）' ;;
    *'`'*) die '--dir 不能包含反引号（装出的系统命令每次换版都会把它当命令替换）' ;;
    *'}'*) die '--dir 不能包含 } 号（生成的文件里这一段落在 ${…} 中，花括号会提前收尾）' ;;
    *'%'*) die '--dir 不能包含 % 号（systemd 单元里 % 是占位符，路径会被换掉）' ;;
    *'
'*) die '--dir 不能包含换行（生成的文件会被拆成两半）' ;;
    *' '*) die '--dir 不能包含空格（生成的服务单元会按空白把路径拆开，服务起不来）' ;;
    *'	'*) die '--dir 不能包含制表符（生成的服务单元会按空白把路径拆开，服务起不来）' ;;
esac
case "$PORT" in
    ''|*[!0-9]*) die "--port 需为数字，当前为「${PORT}」" ;;
esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "--port 取值需在 1-65535 之间，当前为 $PORT"
case "$SERVICE_USER" in
    ''|*[!a-zA-Z0-9_-]*) die "--user 只能包含字母、数字、下划线与连字符，当前为「${SERVICE_USER}」" ;;
esac

[ "$(uname -s)" = "Linux" ] || die "本脚本仅适用于 Linux。macOS 与 Windows 请参考手册第 3 章的手动安装：https://frostnovaorg.github.io/NovaBot/03-install.html"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    command -v sudo > /dev/null 2>&1 || die "需要 root 权限，且未找到 sudo"
    SUDO="sudo"
fi

# 目录名要像 5.7.9：至少两段数字，中间用点连。别的名字留在 releases/ 里。
release_name_is_version() {
    case "$1" in
        *[!0-9.]*|.*|*.|*..*) return 1 ;;
        *.*) return 0 ;;
        *) return 1 ;;
    esac
}

# 根上 NovaBot.jar 里的 build.version。没有 unzip、不是 jar、或读不到时打印空。
flat_jar_build_version() {
    local jar="$1" line
    command -v unzip > /dev/null 2>&1 || return 0
    [ -f "$jar" ] || return 0
    line="$(unzip -p "$jar" META-INF/build-info.properties 2>/dev/null | sed -n 's/^build\.version=//p' | sed -n '1p' || true)"
    line="${line//$'\r'/}"
    printf '%s' "$line"
}

# 根上 lib/novacore-<版本>.jar，恰好一个、且版本号能当目录名，才打印它。
# 根上一个都没有时，再认 releases/<版本>/lib/novacore-<同一个版本>.jar，仍是恰好一个才算。
flat_novacore_version() {
    local f base ver found="" count=0 dir
    for f in "$INSTALL_DIR"/lib/novacore-*.jar; do
        [ -f "$f" ] || continue
        base="$(basename "$f")"
        ver="${base#novacore-}"
        ver="${ver%.jar}"
        release_name_is_version "$ver" || continue
        count=$((count + 1))
        found="$ver"
    done
    if [ "$count" -eq 1 ]; then
        printf '%s' "$found"
        return 0
    fi
    # 根上已经有不止一个，分不清是哪一版，不再到版本目录里认。
    [ "$count" -eq 0 ] || return 0
    found=""
    count=0
    for dir in "$INSTALL_DIR"/releases/*; do
        [ -d "$dir" ] || continue
        ver="$(basename "$dir")"
        release_name_is_version "$ver" || continue
        f="$dir/lib/novacore-${ver}.jar"
        [ -f "$f" ] || continue
        # 目录里已经有程序，这一版已经在，不是搬到一半。
        [ -f "$dir/NovaBot.jar" ] && continue
        count=$((count + 1))
        found="$ver"
    done
    if [ "$count" -eq 1 ]; then
        printf '%s' "$found"
    fi
}

# 程序和数据还堆在安装目录根上时，先定旧版本号，再往下装 Java。
# 定不出版本号就在这里停下，一个文件都不改。
FLAT_LAYOUT=no
OLD_FLAT_VERSION=""
MIGRATED=no
LEGACY_UNITS_FOUND=""
LEGACY_UNITS_RUNNING=""
LEGACY_DROPINS=""
KEPT_USER_JARS=""
if [ -f "$INSTALL_DIR/NovaBot.jar" ] || [ -f "$INSTALL_DIR/StarBotCore.jar" ]; then
    FLAT_LAYOUT=yes
    if [ -f "$INSTALL_DIR/NovaBot.jar" ]; then
        OLD_FLAT_VERSION="$(flat_jar_build_version "$INSTALL_DIR/NovaBot.jar")"
    fi
    if ! release_name_is_version "${OLD_FLAT_VERSION:-}"; then
        OLD_FLAT_VERSION="$(flat_novacore_version)"
    fi
    if ! release_name_is_version "${OLD_FLAT_VERSION:-}"; then
        die "取不到旧版本号，这一次没有改安装目录里的文件。
     根上是旧的扁平布局。本脚本要先把旧程序搬进 releases/旧版本号/ 再装，
     但没能从 NovaBot.jar 里的 build.version（需要 unzip），
     也没能从恰好一个 lib/novacore-版本.jar 读出能当目录名的版本号。
     请确认这是 NovaBot 的安装目录；若程序包不完整，请自行把旧程序放进 releases/旧版本号/ 后再运行。"
    fi
fi

# ---------------------------------------------------------------- 依赖检查

detect_pkg_manager() {
    for pm in apt-get dnf yum pacman apk zypper; do
        if command -v "$pm" > /dev/null 2>&1; then
            echo "$pm"
            return
        fi
    done
}

# $1 为 JDK 或 JRE，$2 为包管理器
java_package() {
    case "$1:$2" in
        JDK:apt-get)                echo openjdk-17-jdk-headless ;;
        JRE:apt-get)                echo openjdk-17-jre-headless ;;
        JDK:dnf|JDK:yum|JDK:zypper) echo java-17-openjdk-devel ;;
        JRE:dnf|JRE:yum|JRE:zypper) echo java-17-openjdk-headless ;;
        JDK:pacman)                 echo jdk17-openjdk ;;
        JRE:pacman)                 echo jre17-openjdk-headless ;;
        JDK:apk)                    echo openjdk17-jdk ;;
        JRE:apk)                    echo openjdk17-jre-headless ;;
    esac
}

install_java() {
    local pm pkg kind="$1"
    pm="$(detect_pkg_manager)"
    [ -n "$pm" ] || die "未识别的包管理器，请手动安装 $kind 17 后重新运行"
    pkg="$(java_package "$kind" "$pm")"

    info "正在安装 $kind 17（${pm}：${pkg}）"
    case "$pm" in
        apt-get)        $SUDO apt-get update -qq && $SUDO apt-get install -y "$pkg" ;;
        dnf|yum|zypper) $SUDO "$pm" install -y "$pkg" ;;
        pacman)         $SUDO pacman -Sy --noconfirm "$pkg" ;;
        apk)            $SUDO apk add --no-cache "$pkg" ;;
    esac
}

# 取不到版本号时返回 0 而非原样输出：否则调用处的数值比较会报
# "integer expression expected"，把「java 装坏了」显示成一句看不懂的 shell 报错
java_major() {
    local version
    command -v java > /dev/null 2>&1 || { echo 0; return; }
    # 不用 head -1：它读够一行就关闭管道，上游随即 SIGPIPE，pipefail 会把整条管道判为失败。
    # sed 会读完全部输入，只对带 version " 的那一行做替换并打印
    version="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
    echo "${version:-0}"
}

# 找一把主版本恰为 17 的 JDK 安装目录，构建要指名用它。
# 各包管理器装完的落点不一样，写死一个路径必然在别的发行版上扑空：
#   apt-get（Debian、Ubuntu）：/usr/lib/jvm/java-17-openjdk-<架构>
#   dnf、yum（Fedora、RHEL）：/usr/lib/jvm/java-17-openjdk（软链，实体目录带完整版本号）
#   zypper（openSUSE）：/usr/lib64/jvm/java-17-openjdk
#   pacman（Arch）：/usr/lib/jvm/java-17-openjdk
#   apk（Alpine）：/usr/lib/jvm/java-17-openjdk
# 所以逐个目录问 bin/java 报的主版本号，不看目录名：同一台机器上 17 与 21 并存时，
# 照名字找会把两把一起端上来。构建要用 javac，只认带 bin/javac 的目录。
# 装在别处的自己指：NOVABOT_JVM_ROOTS="<它的上层目录> [<它的上层目录>…]"
find_jdk17() {
    local root dir major
    for root in ${NOVABOT_JVM_ROOTS:-/usr/lib/jvm /usr/lib64/jvm}; do
        [ -d "$root" ] || continue
        for dir in "$root"/*; do
            [ -x "$dir/bin/java" ] || continue
            [ -x "$dir/bin/javac" ] || continue
            major="$("$dir/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
            [ "$major" = "17" ] || continue
            echo "$dir"
            return 0
        done
    done
    return 1
}

# 给了 --no-packages 时，缺运行环境就在动机器之前停下。
stop_without_packages() {
    die "$1这一次什么都没改。"
}

# 从源码构建要用 javac，只跑现成产物则 JRE 足够（JDK 多占约 150 MB）
NEED_JAVA="JRE"
if [ -f "$ROOT/build.sh" ]; then
    NEED_JAVA="JDK"
fi

info "检查运行环境"
BUILD_JAVA_HOME=""
# 从源码构建只在 Java 17 上验过本工程，比它高的版本构建会当场停下（见 build.sh「上界」那一段）。
# 所以现有 java 高于 17 时另找一把 17 专供这次构建：只让构建那一趟用它，
# 机器上默认的 java 不动，服务运行用哪把 java 也不归这里管
if [ "$NEED_JAVA" = "JDK" ] && [ "$(java_major)" -gt 17 ]; then
    info "构建要另用 Java 17：这台机器现在的 Java 主版本是 $(java_major)"
    info "原因：本工程只在 Java 17 上验过，版本高过 17 时构建会停下（见 build.sh「上界」那一段）"
    if ! BUILD_JAVA_HOME="$(find_jdk17)"; then
        if [ "$NO_PACKAGES" = yes ]; then
            stop_without_packages "缺 Java 17。从源码构建需要 JDK 17，请先装好 JDK 17 后再运行。"
        fi
        info "机器上没有现成的 Java 17，现在装一把（只给这次构建用）"
        install_java JDK
        BUILD_JAVA_HOME="$(find_jdk17)" || die "装好 JDK 17 后仍没找到它（在 ${NOVABOT_JVM_ROOTS:-/usr/lib/jvm、/usr/lib64/jvm} 下没找到主版本为 17 的目录）。
     请装好 JDK 17 再跑一次本脚本；装在别处的，用 NOVABOT_JVM_ROOTS=<它的上层目录> 指给我。
     不会改用现在的 Java $(java_major) 继续构建——那样构建会在中途停下"
    fi
    info "构建将使用 Java 17：${BUILD_JAVA_HOME}（只作用于这一次构建）"
elif [ "$(java_major)" -lt 17 ]; then
    if [ "$NO_PACKAGES" = yes ]; then
        if [ "$NEED_JAVA" = "JDK" ]; then
            stop_without_packages "缺 Java 17。从源码构建请先装好 JDK 17 后再运行。"
        fi
        stop_without_packages "缺 Java 17。请先装好 Java 17 后再运行。"
    fi
    install_java "$NEED_JAVA"
    [ "$(java_major)" -ge 17 ] || die "$NEED_JAVA 17 安装后仍不可用，请手动检查"
fi
# 机器上可能已有 JRE 17 但没有 javac，此时版本检查会通过，构建却会失败
if [ "$NEED_JAVA" = "JDK" ] && [ -z "$BUILD_JAVA_HOME" ] && ! command -v javac > /dev/null 2>&1; then
    if [ "$NO_PACKAGES" = yes ]; then
        stop_without_packages "缺 Java 17。从源码构建需要 javac，请先装好 JDK 17 后再运行。"
    fi
    install_java JDK
    command -v javac > /dev/null 2>&1 || die "从源码构建需要 javac，安装 JDK 后仍未找到"
fi
if [ -n "$BUILD_JAVA_HOME" ]; then
    info "构建用的 Java 版本：$("$BUILD_JAVA_HOME/bin/java" -version 2>&1 | sed -n '/version "/p')"
else
    info "Java 版本：$(java -version 2>&1 | sed -n '/version "/p')"
fi

# 用 grep -c 而非 grep -q：本脚本开了 pipefail，而 grep -q 一匹配到就退出并关闭管道，
# 上游的 fc-list 随即收到 SIGPIPE 以 141 结束，pipefail 便把整条管道判为失败——
# 于是字体明明装好了也会被判成没装（实测 45 条 CJK 字体仍返回 141）。
# grep -c 会读完全部输入，不会产生 SIGPIPE
has_cjk_font() {
    local matches
    matches="$(fc-list 2>/dev/null | grep -ciE 'cjk|noto sans sc|wqy|source han' || true)"
    [ "${matches:-0}" -gt 0 ]
}

# 中文字体的包名各发行版不一，同一发行版的不同版本也会变
# （RHEL 9 是 google-noto-sans-cjk-ttc-fonts，Fedora 才是 google-noto-sans-cjk-fonts），
# 写死一个名字必然在某些机器上装不上，故逐个尝试候选
font_packages() {
    case "$1" in
        apt-get)     echo "fonts-noto-cjk" ;;
        dnf|yum)     echo "google-noto-sans-cjk-ttc-fonts google-noto-sans-cjk-fonts" ;;
        zypper)      echo "google-noto-sans-sc-fonts noto-sans-cjk-fonts" ;;
        pacman)      echo "noto-fonts-cjk" ;;
        apk)         echo "font-noto-cjk" ;;
    esac
}

install_font() {
    local pm pkg
    pm="$(detect_pkg_manager)"
    if [ -z "$pm" ]; then
        warn "未识别的包管理器，请手动安装 Noto Sans CJK 或文泉驿字体"
        return
    fi

    for pkg in $(font_packages "$pm"); do
        info "正在安装中文字体 $pkg"
        case "$pm" in
            apt-get)        $SUDO apt-get install -y -qq "$pkg" > /dev/null 2>&1 || true ;;
            dnf|yum|zypper) $SUDO "$pm" install -y "$pkg" > /dev/null 2>&1 || true ;;
            pacman)         $SUDO pacman -Sy --noconfirm "$pkg" > /dev/null 2>&1 || true ;;
            apk)            $SUDO apk add --no-cache "$pkg" > /dev/null 2>&1 || true ;;
        esac

        # 刚装上的字体不会立刻出现在 fc-list 里——它读的是 fontconfig 缓存，
        # 包的安装脚本重建缓存有延迟。不刷新的话这里会误判成装失败，
        # 继续去试后面的候选包，最后报一句「安装失败」，而字体其实已经装好了
        $SUDO fc-cache -f > /dev/null 2>&1 || true

        # 以 fc-list 的实际结果为准，而非包管理器的退出码：
        # 包名不存在时它也会失败，但换个候选名就能装上
        if has_cjk_font; then
            info "中文字体已就绪"
            return
        fi
    done

    warn "系统中文字体安装失败。程序自带中文字体，中文照常显示；系统字体补内置没有的字（如韩文）。
     可手动安装后重启服务，本发行版的候选包名：$(font_packages "$pm")"
}

# 程序自带中文字体；系统中文字体补内置没有的字（如韩文），没装也不影响中文显示
if ! has_cjk_font; then
    if [ "$NO_PACKAGES" = yes ]; then
        pm="$(detect_pkg_manager)"
        if [ -n "$pm" ]; then
            warn "未检测到系统中文字体。程序自带中文字体，中文照常显示；系统字体只补内置没有的字（如韩文）。需要时请手动安装，本发行版的候选包名：$(font_packages "$pm")"
        else
            warn "未检测到系统中文字体。程序自带中文字体，中文照常显示；系统字体只补内置没有的字（如韩文）。需要时请手动安装 Noto Sans CJK 或文泉驿字体。"
        fi
    else
        warn "未检测到中文字体"
        install_font
    fi
fi

# ---------------------------------------------------------------- 构建

if [ -f "$ROOT/build.sh" ]; then
    command -v mvn > /dev/null 2>&1 || die "未找到 Maven，请先安装 Maven 3.9 或更高版本"

    info "从源码构建"
    if [ -n "$BUILD_JAVA_HOME" ]; then
        # 17 只给这一趟构建用：JAVA_HOME 与 PATH 都指向它，构建一结束就不再有效
        JAVA_HOME="$BUILD_JAVA_HOME" PATH="$BUILD_JAVA_HOME/bin:$PATH" "$ROOT/build.sh" --skip-tests
    else
        "$ROOT/build.sh" --skip-tests
    fi
    SOURCE_DIR="$ROOT/dist/build"
else
    SOURCE_DIR="$ROOT"
fi

if [ ! -f "$SOURCE_DIR/NovaBot.jar" ]; then
    die "未找到构建产物 NovaBot.jar"
fi

# $1 比 $2 新才返回 0。按点切开比数字：5.7.10 比 5.7.9 新，不能按字母序。
version_newer() {
    local IFS=.
    local -a left right
    local i n x y
    read -r -a left <<< "$1"
    read -r -a right <<< "$2"
    n=${#left[@]}
    if [ "${#right[@]}" -gt "$n" ]; then
        n=${#right[@]}
    fi
    i=0
    while [ "$i" -lt "$n" ]; do
        x=0
        y=0
        if [ "${left[$i]+set}" = set ]; then
            x=${left[$i]}
        fi
        if [ "${right[$i]+set}" = set ]; then
            y=${right[$i]}
        fi
        x=${x%%[!0-9]*}
        y=${y%%[!0-9]*}
        x=${x:-0}
        y=${y:-0}
        if [ "$x" -gt "$y" ]; then
            return 0
        fi
        if [ "$x" -lt "$y" ]; then
            return 1
        fi
        i=$((i + 1))
    done
    return 1
}

# 这一版的实例是不是正在跑。没有 systemctl 时无从确认，不当作正在跑。
instance_running() {
    command -v systemctl > /dev/null 2>&1 || return 1
    systemctl is-active --quiet "novabot@$1"
}

# 这个实例开着开机自启、又不是要装的这一版，就关掉。同一个名字只处理一次。
disable_if_other_enabled() {
    local name="$1"
    [ -n "$name" ] || return 0
    [ "$name" = "$VERSION" ] && return 0
    case "$SEEN_AUTOSTART" in
        *" $name "*) return 0 ;;
    esac
    SEEN_AUTOSTART="$SEEN_AUTOSTART$name "
    if $SUDO systemctl is-enabled --quiet "novabot@${name}"; then
        $SUDO systemctl disable "novabot@${name}" > /dev/null 2>&1 || true
    fi
}

# ---------------------------------------------------------------- 安装

if [ ! -f "$SOURCE_DIR/BUILD-INFO" ]; then
    die "包里没有 BUILD-INFO，不能确定要装的版本"
fi
VERSION="$(sed -n 's/^version=//p' "$SOURCE_DIR/BUILD-INFO" | sed -n '1p')"
VERSION="${VERSION//$'\r'/}"
if [ -z "$VERSION" ]; then
    die "包里的 BUILD-INFO 没有版本号，不能安装"
fi
case "$VERSION" in
    .*|-*|*[!A-Za-z0-9._-]*) die "版本号「${VERSION}」不能当作目录名" ;;
esac

# 要装的这一版已经在，并且正在跑。换掉它等于换掉正在用的程序。
if [ -d "$INSTALL_DIR/releases/$VERSION" ] && instance_running "$VERSION"; then
    die "要装的 ${VERSION} 正在运行，这一次没有改安装目录里的文件"
fi

# 同一路径两边都有就停下，列出两边，不覆盖。
refuse_if_both() {
    local src="$1" dest="$2"
    if [ -e "$dest" ] || [ -L "$dest" ]; then
        die "同一件两边都有，不覆盖：
     ${src}
     ${dest}
     请处理后再运行。这一次没有继续搬。"
    fi
}

move_flat_entry() {
    local name="$1"
    local src="$INSTALL_DIR/$name"
    local dest="$INSTALL_DIR/releases/$OLD_FLAT_VERSION/$name"
    if [ ! -e "$src" ] && [ ! -L "$src" ]; then
        return 0
    fi
    refuse_if_both "$src" "$dest"
    $SUDO mkdir -p "$(dirname "$dest")"
    $SUDO mv "$src" "$dest"
}

# 构件名比法：去掉末尾、数字开头的版本号再比，免得前缀相同的第三方插件被连带。
move_flat_builtin_jars() {
    local kind="$1" jar artifact old base dest_dir dest
    [ -d "$SOURCE_DIR/$kind" ] || return 0
    [ -d "$INSTALL_DIR/$kind" ] || return 0
    for jar in "$SOURCE_DIR/$kind"/*.jar; do
        [ -f "$jar" ] || continue
        artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\.jar$//')"
        case "$artifact" in
            *.jar) continue ;;
        esac
        for old in "$INSTALL_DIR/$kind/$artifact"-[0-9]*.jar; do
            [ -f "$old" ] || continue
            base="$(basename "$old")"
            dest_dir="$INSTALL_DIR/releases/$OLD_FLAT_VERSION/$kind"
            dest="$dest_dir/$base"
            $SUDO mkdir -p "$dest_dir"
            refuse_if_both "$old" "$dest"
            $SUDO mv "$old" "$dest"
        done
    done
}

list_kept_user_jars() {
    local kind jar
    KEPT_USER_JARS=""
    for kind in plugins plugins-lib; do
        [ -d "$INSTALL_DIR/$kind" ] || continue
        for jar in "$INSTALL_DIR/$kind"/*.jar; do
            [ -f "$jar" ] || continue
            if [ -z "$KEPT_USER_JARS" ]; then
                KEPT_USER_JARS="$(basename "$jar")"
            else
                KEPT_USER_JARS="$KEPT_USER_JARS $(basename "$jar")"
            fi
        done
    done
}

# 旧的 start.sh 只会扁平地起。先拷到临时名再改名盖上，不原地覆写：
# 旧版本若还在跑，起它的那个 shell 还在读旧那份。
replace_flat_start() {
    local dest_dir="$INSTALL_DIR/releases/$OLD_FLAT_VERSION"
    local tmp="$dest_dir/start.sh.novabot-new"
    [ -f "$SOURCE_DIR/start.sh" ] || return 0
    $SUDO mkdir -p "$dest_dir"
    # 这个临时名只有这里用。上次拷到一半留下的，删掉再拷。
    if [ -e "$tmp" ] || [ -L "$tmp" ]; then
        $SUDO rm -f "$tmp"
    fi
    $SUDO cp "$SOURCE_DIR/start.sh" "$tmp"
    $SUDO mv "$tmp" "$dest_dir/start.sh"
    $SUDO chmod +x "$dest_dir/start.sh"
}

migrate_flat_layout() {
    local name
    info "检测到旧的扁平布局，先把旧程序搬进 releases/${OLD_FLAT_VERSION}/"
    $SUDO mkdir -p "$INSTALL_DIR/releases/$OLD_FLAT_VERSION"
    move_flat_builtin_jars plugins
    move_flat_builtin_jars plugins-lib
    for name in lib start.sh start.bat docker-entrypoint.sh Dockerfile tools BUILD-INFO LICENSE NOTICE; do
        move_flat_entry "$name"
    done
    # 根上的 NovaBot.jar（或只有 StarBotCore.jar 时是它）最后挪：它还在就表示没搬完，再跑一次接着搬。
    if [ -f "$INSTALL_DIR/NovaBot.jar" ]; then
        if [ -e "$INSTALL_DIR/StarBotCore.jar" ] || [ -L "$INSTALL_DIR/StarBotCore.jar" ]; then
            move_flat_entry StarBotCore.jar
        fi
        replace_flat_start
        move_flat_entry NovaBot.jar
    else
        replace_flat_start
        move_flat_entry StarBotCore.jar
    fi
    list_kept_user_jars
    MIGRATED=yes
}

if [ "$FLAT_LAYOUT" = yes ]; then
    migrate_flat_layout
fi

# 分目录的根上本来就有 plugins（使用者自己放的），所以先认 releases/，再做这条判断。
if [ ! -d "$INSTALL_DIR/releases" ]; then
    if [ -e "$INSTALL_DIR/lib" ] || [ -e "$INSTALL_DIR/plugins" ]; then
        die "$INSTALL_DIR 下已有 lib/ 或 plugins/，但没有 NovaBot.jar（亦无 StarBotCore.jar），不像 NovaBot 的安装目录。
     为免误删，请换一个目录，或先自行确认该目录内容"
    fi
fi

info "安装至 $INSTALL_DIR"

if ! id "$SERVICE_USER" > /dev/null 2>&1; then
    info "创建系统用户 $SERVICE_USER"
    $SUDO useradd --system --home-dir "$INSTALL_DIR" --shell /usr/sbin/nologin "$SERVICE_USER" \
        || $SUDO useradd --system --home-dir "$INSTALL_DIR" --shell /sbin/nologin "$SERVICE_USER"
fi

$SUDO mkdir -p "$INSTALL_DIR"

# 升级时保留既有配置。只需搬走会被构建产物覆盖的那两个文件：
# cookies.json / cookies.key 不在产物里，cp 不会碰到它们。
# 凭据也确实不该搬——落到 /tmp 里的可预测路径上，既会被同机其他用户读到，
# 也会被抢先创建的同名软链劫持；脚本中途失败时它们还会一直留在那里
KEEP_DIR="$($SUDO mktemp -d "${TMPDIR:-/tmp}/novabot-keep.XXXXXX")"
trap '[ -n "${KEEP_DIR:-}" ] && $SUDO rm -rf "$KEEP_DIR" || true' EXIT

for keep in application.yml datasource.json; do
    if [ -f "$INSTALL_DIR/$keep" ]; then
        $SUDO cp "$INSTALL_DIR/$keep" "$KEEP_DIR/$keep"
    fi
done

# 装之前先记下上一版，以及正在跑的那些版。装完只留：这一版、上一版、正在跑的。
# 更旧的删掉。正在跑的那一版不在「上一版」里也留着，不然正在用的程序会被卸掉。
FRESH=yes
previous=""
running_marks=" "
if [ -d "$INSTALL_DIR/releases" ]; then
    FRESH=no
    for dir in "$INSTALL_DIR"/releases/*; do
        [ -d "$dir" ] || continue
        name="$(basename "$dir")"
        release_name_is_version "$name" || continue
        if [ "$name" = "$VERSION" ]; then
            continue
        fi
        if [ -z "$previous" ] || version_newer "$name" "$previous"; then
            previous="$name"
        fi
        if instance_running "$name"; then
            running_marks="$running_marks$name "
        fi
    done
fi

release_dir="$INSTALL_DIR/releases/$VERSION"
if [ -d "$release_dir" ]; then
    $SUDO rm -rf "$release_dir"
fi
$SUDO mkdir -p "$release_dir"
for name in NovaBot.jar lib plugins plugins-lib start.sh start.bat docker-entrypoint.sh Dockerfile tools BUILD-INFO LICENSE NOTICE; do
    if [ -e "$SOURCE_DIR/$name" ]; then
        $SUDO cp -R "$SOURCE_DIR/$name" "$release_dir/"
    fi
done

# 新装才在根上建空的 plugins、plugins-lib，留给使用者自己加。
# 再升级不动这两处：里面可能已经有使用者自己放的件。
if [ "$FRESH" = yes ]; then
    $SUDO mkdir -p "$INSTALL_DIR/plugins" "$INSTALL_DIR/plugins-lib"
fi

for name in application.example.yml datasource.example.json Caddyfile novabot-backup.service novabot-backup.timer; do
    if [ -f "$SOURCE_DIR/$name" ]; then
        $SUDO cp "$SOURCE_DIR/$name" "$INSTALL_DIR/$name"
    fi
done
# 已有的备份单元写死了安装目录下的 tools/data-backup.sh。程序目录里那份跟着版本走，
# 根上这一份每次装都换新，旧单元不用改路径。
if [ -f "$SOURCE_DIR/tools/data-backup.sh" ]; then
    $SUDO mkdir -p "$INSTALL_DIR/tools"
    $SUDO cp "$SOURCE_DIR/tools/data-backup.sh" "$INSTALL_DIR/tools/data-backup.sh"
    $SUDO chmod +x "$INSTALL_DIR/tools/data-backup.sh"
fi

for dir in "$INSTALL_DIR"/releases/*; do
    [ -d "$dir" ] || continue
    name="$(basename "$dir")"
    release_name_is_version "$name" || continue
    if [ "$name" = "$VERSION" ]; then
        continue
    fi
    if [ "$name" = "$previous" ]; then
        continue
    fi
    case "$running_marks" in
        *" $name "*) continue ;;
    esac
    $SUDO rm -rf "$dir"
done

# 内容没变就不拷回：拷回会把修改时间改成现在，正在跑的旧版本看到 datasource.json「更新了」
# 就重读配置、去平台把每个主播的资料再补全一遍，赶上停机时还会刷出一串补全失败
for keep in application.yml datasource.json; do
    if [ -f "$KEEP_DIR/$keep" ]; then
        if [ ! -f "$INSTALL_DIR/$keep" ] || ! $SUDO cmp -s "$KEEP_DIR/$keep" "$INSTALL_DIR/$keep"; then
            $SUDO cp "$KEEP_DIR/$keep" "$INSTALL_DIR/$keep"
        fi
        info "已保留原有的 $keep"
    fi
done

# 🔴 5.1 起，发行包不再带 application.yml —— 它由程序在第一次保存设置时自己写出来。
#    所以全新安装的目录里这个文件是不存在的，下面两条都得先问一句它在不在：
#    不问的话，`sed -i` 对一个不存在的文件报错，而本脚本开着 set -e，整个安装当场中止，
#    错误信息是一句「No such file or directory」——与真正的原因隔着一层。
if [ -f "$INSTALL_DIR/application.yml" ]; then
    if [ "$PORT" != "7827" ]; then
        $SUDO sed -i "s/^  port: 7827/  port: $PORT/" "$INSTALL_DIR/application.yml"
    fi

    # 升级时保留的是旧 application.yml，其中的端口未必是 7827，上面的替换会静默落空。
    # 结尾提示的地址以文件里的实际取值为准，否则会给出一个打不开的地址
    EFFECTIVE_PORT="$($SUDO awk 'match($0, /^  port: [0-9]+/) { gsub(/[^0-9]/, "", $0); print; exit }' "$INSTALL_DIR/application.yml")"
    EFFECTIVE_PORT="${EFFECTIVE_PORT:-$PORT}"
    if [ "$EFFECTIVE_PORT" != "$PORT" ]; then
        warn "application.yml 中的端口是 ${EFFECTIVE_PORT}，与 --port $PORT 不一致（升级时保留了原有配置）。
     如需改用 ${PORT}，请手动编辑 $INSTALL_DIR/application.yml"
    fi
else
    # 全新安装：还没有配置文件可改，端口就是程序自己的默认值。
    # 不为了 --port 先造一个配置文件出来：那个文件一旦存在，程序就认为这台机器已经配过，
    # 首次打开控制台时不会再把人领到初始设置页——为了一个端口号换掉整条初始路径，不划算。
    EFFECTIVE_PORT=7827
    if [ "$PORT" != "$EFFECTIVE_PORT" ]; then
        warn "--port $PORT 这次没有落到任何地方：全新安装还没有 application.yml（它由程序首次保存设置时生成）。
     先按默认的 $EFFECTIVE_PORT 起，进控制台走完初始设置后在「设置 → 服务端口」里改；
     想在启动前就定下来，用 JAVA_OPTS=\"-Dserver.port=$PORT\" 起 start.sh"
    fi
fi

$SUDO chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
$SUDO chmod +x "$release_dir/start.sh"
# 凭据文件等同于账号密码，仅属主可读写
$SUDO chmod 600 "$INSTALL_DIR"/cookies.* 2>/dev/null || true

# ---------------------------------------------------------------- 服务

# 模板单元 novabot@.service，实例名是版本号。只改开机自启，不启动、也不停止正在跑的。
SERVICE_UNIT="novabot@${VERSION}"
SYSTEMD_SYSTEM_DIR="${NOVABOT_SYSTEMD_SYSTEM_DIR:-/etc/systemd/system}"
SYSTEMD_CONTROL_DIR="${NOVABOT_SYSTEMD_CONTROL_DIR:-/etc/systemd/system.control}"

# 两处核不过时调用。调用前新版已在 releases/，单元模板已写成并重新加载，
# 旧单元若存在则开机自启已关掉；正在跑的实例和原来的换版工具都还没动。
switch_tool_not_installed() {
    die "$*
     机器现在是这样：新版本已经写在 ${INSTALL_DIR}/releases/${VERSION}；服务单元模板 /etc/systemd/system/novabot@.service 已经换成指向这次安装目录的，并已重新加载。novabot 与 starbot 这两个旧单元若原先存在，开机自启已经关掉（正在跑的单元文件还留着，没在跑的已经删掉）。正在跑的实例没有停，也没有启动新版本。原来的换版工具没有换掉；原先没有的，现在也还没有装上。
     下一步：修好包里的 tools/switch-version.sh 后再安装一次。在这之前不要用原来那份换版工具去换到 ${VERSION}。"
}

# 换版工具装成 root 所有的系统命令（缺省 /usr/local/sbin，测试替身用 NOVABOT_SYSTEM_SBIN_DIR 改位置）：
# 安装目录整个归服务用户，root 不能去跑放在里面的脚本——服务用户改了它，就能借下一次换版拿 root。
# 装时调的、收尾提示里给的都是这一份，不是 releases 里那份。
SYSTEM_SBIN_DIR="${NOVABOT_SYSTEM_SBIN_DIR:-/usr/local/sbin}"
SWITCH_TOOL="$SYSTEM_SBIN_DIR/novabot-switch-version"
# 装完怎么走：start 没有人在跑，装时就把开机自启切到本版；legacy 旧单元在跑，照现在手动先停后起；
# switch 有别的分目录实例在跑，装完交给换版工具；hold 同上但给了 --no-switch，只装不换；
# no-service 没建服务（--no-service 或机器上没有 systemctl），不装工具也不调它
SWITCH_PLAN="no-service"
SWITCH_RC=0

# 服务的输出已进 journal 与程序自己的日志文件，rsyslog 从 journal 收下再写进 /var/log/syslog 的那份没人读。
# 缺省 /etc/rsyslog.d，测试替身用 NOVABOT_RSYSLOG_DIR 改位置；没有这个目录就当机器上没有 rsyslog。
RSYSLOG_DIR="${NOVABOT_RSYSLOG_DIR:-/etc/rsyslog.d}"
RSYSLOG_RULE="$RSYSLOG_DIR/30-novabot.conf"
RSYSLOG_MARK="# 由 NovaBot 安装脚本 install.sh 写入"
RSYSLOG_CONTENT="${RSYSLOG_MARK}：NovaBot 的输出已在 journal 与它自己的日志文件里，这里不再多存一份到系统日志。
# 要恢复：删掉本文件后 systemctl restart rsyslog，或重新安装时加 --keep-syslog。
if \$programname == 'novabot' then stop"
# 收尾那一句：written 写了或已是这份、removed 删了先前那份、kept --keep-syslog 且没有要删的、
# absent 没有 rsyslog、空 没建服务
SYSLOG_RESULT=""

reload_rsyslog() {
    # try-restart：rsyslog 没在跑就不起它。失败不拦安装
    $SUDO systemctl try-restart rsyslog > /dev/null 2>&1 \
        || warn "没能让 rsyslog 重新读配置，可稍后自己执行 sudo systemctl restart rsyslog"
}

configure_syslog() {
    if [ ! -d "$RSYSLOG_DIR" ]; then
        SYSLOG_RESULT="absent"
        return 0
    fi
    if [ "$KEEP_SYSLOG" = "yes" ]; then
        SYSLOG_RESULT="kept"
        if [ -f "$RSYSLOG_RULE" ] && $SUDO grep -qF "$RSYSLOG_MARK" "$RSYSLOG_RULE"; then
            $SUDO rm -f "$RSYSLOG_RULE"
            SYSLOG_RESULT="removed"
            reload_rsyslog
        fi
        return 0
    fi
    SYSLOG_RESULT="written"
    # 内容相同就不重写，也不让 rsyslog 重读
    if [ -f "$RSYSLOG_RULE" ] && [ "$($SUDO cat "$RSYSLOG_RULE")" = "$RSYSLOG_CONTENT" ]; then
        return 0
    fi
    printf '%s\n' "$RSYSLOG_CONTENT" | $SUDO tee "$RSYSLOG_RULE" > /dev/null
    $SUDO chmod 0644 "$RSYSLOG_RULE"
    reload_rsyslog
}

# 5.x 的 novabot.service、更早的 starbot.service。只关开机自启，不停正在跑的。
# 不在跑的删单元文件；在跑的留着，等用户自己停，下次再跑、它已不在跑时再删。
# 覆盖设置目录不删，新单元不沿用。
retire_legacy_unit() {
    local name="$1"
    local unit_file="$SYSTEMD_SYSTEM_DIR/${name}.service"
    local drop
    [ -f "$unit_file" ] || return 0
    if [ -z "$LEGACY_UNITS_FOUND" ]; then
        LEGACY_UNITS_FOUND="$name"
    else
        LEGACY_UNITS_FOUND="$LEGACY_UNITS_FOUND $name"
    fi
    $SUDO systemctl disable "$name" > /dev/null 2>&1 || true
    if $SUDO systemctl is-active --quiet "$name"; then
        info "旧服务 ${name} 正在运行，只关掉开机自启，单元文件留着，等你自己停"
        if [ -z "$LEGACY_UNITS_RUNNING" ]; then
            LEGACY_UNITS_RUNNING="$name"
        else
            LEGACY_UNITS_RUNNING="$LEGACY_UNITS_RUNNING $name"
        fi
    else
        $SUDO rm -f "$unit_file"
        info "旧服务 ${name} 没在运行，已关掉开机自启并删除单元文件"
    fi
    for drop in "$SYSTEMD_SYSTEM_DIR/${name}.service.d" "$SYSTEMD_CONTROL_DIR/${name}.service.d"; do
        if [ -d "$drop" ]; then
            LEGACY_DROPINS="${LEGACY_DROPINS}       ${drop}
"
        fi
    done
}

if [ "$CREATE_SERVICE" = "yes" ] && command -v systemctl > /dev/null 2>&1; then
    retire_legacy_unit novabot
    retire_legacy_unit starbot
    info "创建 systemd 服务"
    [ -f "$SOURCE_DIR/novabot@.service" ] || die "缺少 $SOURCE_DIR/novabot@.service"
    $SUDO sed -e "s#/opt/starbot#$INSTALL_DIR#g" -e "s/^User=.*/User=$SERVICE_USER/" -e "s/^Group=.*/Group=$SERVICE_USER/" \
        "$SOURCE_DIR/novabot@.service" | $SUDO tee /etc/systemd/system/novabot@.service > /dev/null
    $SUDO systemctl daemon-reload
    configure_syslog

    [ -f "$SOURCE_DIR/tools/switch-version.sh" ] || die "缺少 $SOURCE_DIR/tools/switch-version.sh"
    $SUDO mkdir -p "$SYSTEM_SBIN_DIR"
    # 先落临时件、核过再到位：替换没换上就装出去，是一份每次换版都还指着 /opt/starbot 的系统命令
    SWITCH_TMP="$($SUDO mktemp "$SYSTEM_SBIN_DIR/.novabot-switch-version.XXXXXX")"
    $SUDO sed -e "s#/opt/starbot#$INSTALL_DIR#g" "$SOURCE_DIR/tools/switch-version.sh" \
        | $SUDO tee "$SWITCH_TMP" > /dev/null
    EXPECTED_INSTALL_DIR="INSTALL_DIR=\"\${NOVABOT_INSTALL_DIR:-$INSTALL_DIR}\""
    FOUND_INSTALL_DIR="$($SUDO grep -cFx "$EXPECTED_INSTALL_DIR" "$SWITCH_TMP" || true)"
    if [ "$FOUND_INSTALL_DIR" != "1" ]; then
        $SUDO rm -f "$SWITCH_TMP"
        switch_tool_not_installed "包里 tools/switch-version.sh 的 INSTALL_DIR 那一行换不成本次安装目录，这一次没有装换版工具。"
    fi
    if ! $SUDO bash -n "$SWITCH_TMP"; then
        $SUDO rm -f "$SWITCH_TMP"
        switch_tool_not_installed "装出的换版工具有语法错误，这一次没有装换版工具。"
    fi
    $SUDO chown root:root "$SWITCH_TMP"
    $SUDO chmod 0755 "$SWITCH_TMP"
    $SUDO mv "$SWITCH_TMP" "$SWITCH_TOOL"

    # 「别的分目录实例在跑」＝releases/ 下本版以外、正在跑的那个
    OTHER_INSTANCES=""
    for dir in "$INSTALL_DIR"/releases/*; do
        [ -d "$dir" ] || continue
        name="$(basename "$dir")"
        release_name_is_version "$name" || continue
        [ "$name" = "$VERSION" ] && continue
        if instance_running "$name"; then
            if [ -z "$OTHER_INSTANCES" ]; then
                OTHER_INSTANCES="$name"
            else
                OTHER_INSTANCES="$OTHER_INSTANCES $name"
            fi
        fi
    done

    SWITCH_PLAN="start"
    if [ -n "$LEGACY_UNITS_RUNNING" ]; then
        SWITCH_PLAN="legacy"
    elif [ -n "$OTHER_INSTANCES" ]; then
        if [ "$NO_SWITCH" = "yes" ]; then
            SWITCH_PLAN="hold"
        else
            SWITCH_PLAN="switch"
        fi
    fi

    if [ "$SWITCH_PLAN" = "switch" ] || [ "$SWITCH_PLAN" = "hold" ]; then
        # 开机自启等换过版再切：装时就切，机器在「装完、还没换」之间重启会起来还没人看过的新版本
        info "服务已创建，开机自启等换过版再切"
    else
        # list-unit-files 只列得出模板文件，列不出 novabot@版本 这种实例，所以逐个问。
        SEEN_AUTOSTART=" "
        if [ -d "$INSTALL_DIR/releases" ]; then
            for dir in "$INSTALL_DIR"/releases/*; do
                [ -d "$dir" ] || continue
                disable_if_other_enabled "$(basename "$dir")"
            done
        fi
        for link in /etc/systemd/system/*.wants/novabot@*.service; do
            [ -e "$link" ] || continue
            unit="$(basename "$link")"
            inst="${unit#novabot@}"
            inst="${inst%.service}"
            disable_if_other_enabled "$inst"
        done
        $SUDO systemctl enable "$SERVICE_UNIT" > /dev/null 2>&1
        info "服务已创建并设为开机自启"
    fi
fi

# 有别的分目录实例在跑、又没给 --no-switch：装完直接换过去。工具的输出原样透出，退码原样带走。
if [ "$SWITCH_PLAN" = "switch" ]; then
    $SUDO "$SWITCH_TOOL" "$VERSION" || SWITCH_RC=$?
fi

# ---------------------------------------------------------------- 完成

LOG_STEP=2
case "$SWITCH_PLAN" in
    no-service)
        # --no-service，或机器上没有 systemctl：不装工具、不调工具，收尾也不出 systemctl 行
        cat <<EOF

安装完成，接下来：

  1. 启动新版本
       sudo -u $SERVICE_USER "$INSTALL_DIR/releases/$VERSION/start.sh"
EOF
        ;;
    legacy)
        cat <<EOF

安装完成，接下来：

EOF
        cat <<EOF
  这一次会先停旧版本，再起新版本，中间会断一小会儿。
  正在跑的旧版本没有单实例锁，新版本起来时不会等旧版本放开，两份会同时连直播间、抢端口。

  1. 先停旧版本
EOF
        for name in $LEGACY_UNITS_RUNNING; do
            printf '       sudo systemctl stop %s\n' "$name"
        done
        cat <<EOF

  2. 再启动新版本
       sudo systemctl start $SERVICE_UNIT
EOF
        LOG_STEP=3
        ;;
    switch)
        if [ "$SWITCH_RC" -eq 0 ]; then
            # 换版工具已把新版换上，收尾不再出「先停旧、再起新」两步
            cat <<EOF

安装完成，接下来：
EOF
            LOG_STEP=1
        else
            cat <<EOF

安装完成，接下来：

  新版本已装好，换版没有全部做成，机器上现在跑的是哪一版（和开机自启在哪一版）看上面最后一行。

  1. 重试换版
       sudo $SWITCH_TOOL $VERSION
EOF
            LOG_STEP=2
        fi
        ;;
    hold)
        cat <<EOF

安装完成，接下来：

  1. 换到新版本，换成之后才切开机自启
       sudo $SWITCH_TOOL $VERSION
EOF
        LOG_STEP=2
        ;;
    start)
        cat <<EOF

安装完成，接下来：

EOF
        if [ "$MIGRATED" = yes ] && [ -z "$LEGACY_UNITS_FOUND" ]; then
            # 扁平布局搬过来又没认出旧单元：旧程序是不是还在跑无从确认，只能照它原来起的办法停
            cat <<EOF
  旧程序若还在跑，先照你原来起它的办法停掉。

EOF
        fi
        cat <<EOF
  1. 启动新版本
       sudo systemctl start $SERVICE_UNIT
EOF
        LOG_STEP=2
        ;;
esac
if [ -n "$KEPT_USER_JARS" ]; then
    cat <<EOF

  根上留下的使用者插件：
       $KEPT_USER_JARS
EOF
fi
if [ -n "$LEGACY_DROPINS" ]; then
    printf '\n  新单元不沿用这些覆盖设置：\n%s' "$LEGACY_DROPINS"
fi
case "$SYSLOG_RESULT" in
    written)
        printf '\n  系统日志里不再多记一份 NovaBot 的输出（规则在 %s；要保留，重装时加 --keep-syslog）\n' "$RSYSLOG_RULE" ;;
    removed)
        printf '\n  系统日志里照旧记 NovaBot 的输出：已删掉先前写的 %s\n' "$RSYSLOG_RULE" ;;
    kept)
        printf '\n  系统日志里照旧记 NovaBot 的输出（--keep-syslog）\n' ;;
    absent)
        printf '\n  机器上没有 rsyslog（%s 不在），系统日志这一项跳过\n' "$RSYSLOG_DIR" ;;
esac
if [ "$SWITCH_PLAN" = "no-service" ]; then
    # 没有 systemd，启动输出就是日志
    BROWSER_STEP=2
    LOGIN_STEP=3
    cat <<EOF

  ${BROWSER_STEP}. 在浏览器中打开启动输出里的配置界面地址完成配置
       该地址形如 http://127.0.0.1:$EFFECTIVE_PORT/config?token=xxxxx

     若 NovaBot 装在远程服务器上，先在本机建立隧道再访问：
       ssh -L $EFFECTIVE_PORT:127.0.0.1:$EFFECTIVE_PORT 用户名@服务器地址

  ${LOGIN_STEP}. 使用哔哩哔哩客户端扫描输出中的二维码完成登录
EOF
else
    if [ "$SWITCH_PLAN" = "switch" ] && [ "$SWITCH_RC" -ne 0 ]; then
        # 换版没全做成：新版没起来，配置界面与二维码都没有，只给看新版本那一份日志
        cat <<EOF

  ${LOG_STEP}. 查看启动日志，看换版卡在哪一步
       sudo journalctl -u $SERVICE_UNIT -f
EOF
    else
        BROWSER_STEP=$((LOG_STEP + 1))
        LOGIN_STEP=$((LOG_STEP + 2))
        cat <<EOF

  ${LOG_STEP}. 查看启动日志，其中包含配置界面地址与首次登录的二维码
       sudo journalctl -u $SERVICE_UNIT -f

  ${BROWSER_STEP}. 在浏览器中打开日志里输出的配置界面地址完成配置
       该地址形如 http://127.0.0.1:$EFFECTIVE_PORT/config?token=xxxxx

     若 NovaBot 装在远程服务器上，先在本机建立隧道再访问：
       ssh -L $EFFECTIVE_PORT:127.0.0.1:$EFFECTIVE_PORT 用户名@服务器地址

  ${LOGIN_STEP}. 使用哔哩哔哩客户端扫描日志中的二维码完成登录
EOF
    fi
fi

cat <<EOF

配置界面默认仅监听本机回环地址。如需从其他机器直接访问，请先阅读 SECURITY.md，
并在配置中同时设置访问令牌与来源 IP 白名单。

安装目录：$INSTALL_DIR
EOF

# 换版没全做成时把工具的退码原样带走：「装好了」和「换过去了」是两件事，退码只答后者
if [ "$SWITCH_RC" -ne 0 ]; then
    exit "$SWITCH_RC"
fi
