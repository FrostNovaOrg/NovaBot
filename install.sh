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
#
# 脚本会依次完成：检查并安装 Java 17、构建、安装到目标目录、生成配置、
# 创建 systemd 服务、输出配置界面地址。
#
set -euo pipefail

INSTALL_DIR="/opt/starbot"
SERVICE_USER="starbot"
PORT="7827"
CREATE_SERVICE="yes"

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
        -h|--help)    usage; exit 0 ;;
        *)            die "未知参数: $1（可用 --help 查看用法）" ;;
    esac
done

# 以下取值会被拼进 rm -rf 与 sed，先卡住明显危险的输入。
# --dir "" 会让后面的清理变成 rm -rf /lib，以 root 运行时足够毁掉整个系统
case "$INSTALL_DIR" in
    /)     die "--dir 不能是根目录" ;;
    /?*)   ;;
    *)     die "--dir 需为绝对路径，当前为「$INSTALL_DIR」" ;;
esac
case "$INSTALL_DIR" in
    *'#'*) die "--dir 不能包含 # 号（生成 systemd 单元时以 # 作 sed 分隔符）" ;;
esac
case "$PORT" in
    ''|*[!0-9]*) die "--port 需为数字，当前为「$PORT」" ;;
esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "--port 取值需在 1-65535 之间，当前为 $PORT"
case "$SERVICE_USER" in
    ''|*[!a-zA-Z0-9_-]*) die "--user 只能包含字母、数字、下划线与连字符，当前为「$SERVICE_USER」" ;;
esac

[ "$(uname -s)" = "Linux" ] || die "本脚本仅适用于 Linux。macOS 与 Windows 请参考 README 手动部署"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    command -v sudo > /dev/null 2>&1 || die "需要 root 权限，且未找到 sudo"
    SUDO="sudo"
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

    info "正在安装 $kind 17（$pm：$pkg）"
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
    # sed 会读完全部输入，只是仅对第一行做替换
    version="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
    echo "${version:-0}"
}

# 从源码构建要用 javac，只跑现成产物则 JRE 足够（JDK 多占约 150 MB）
NEED_JAVA="JRE"
if [ -f "$ROOT/build.sh" ]; then
    NEED_JAVA="JDK"
fi

info "检查运行环境"
if [ "$(java_major)" -lt 17 ]; then
    install_java "$NEED_JAVA"
    [ "$(java_major)" -ge 17 ] || die "$NEED_JAVA 17 安装后仍不可用，请手动检查"
fi
# 机器上可能已有 JRE 17 但没有 javac，此时版本检查会通过，构建却会失败
if [ "$NEED_JAVA" = "JDK" ] && ! command -v javac > /dev/null 2>&1; then
    install_java JDK
    command -v javac > /dev/null 2>&1 || die "从源码构建需要 javac，安装 JDK 后仍未找到"
fi
info "Java 版本：$(java -version 2>&1 | sed -n 1p)"

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
        dnf|yum)     echo "google-noto-sans-cjk-ttc-fonts google-noto-sans-cjk-fonts wqy-zenhei-fonts" ;;
        zypper)      echo "google-noto-sans-sc-fonts noto-sans-cjk-fonts wqy-zenhei-fonts" ;;
        pacman)      echo "noto-fonts-cjk wqy-zenhei" ;;
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

    warn "中文字体安装失败，动态图片中的中文会显示为方块。
     可手动安装后重启服务，本发行版的候选包名：$(font_packages "$pm")"
}

# 绘制动态图片需要中文字体，缺失时图片中的中文会变成方块
if ! has_cjk_font; then
    warn "未检测到中文字体"
    install_font
fi

# ---------------------------------------------------------------- 构建

if [ -f "$ROOT/build.sh" ]; then
    command -v mvn > /dev/null 2>&1 || die "未找到 Maven，请先安装 Maven 3.9 或更高版本"

    info "从源码构建"
    "$ROOT/build.sh" --skip-tests
    SOURCE_DIR="$ROOT/dist/build"
else
    SOURCE_DIR="$ROOT"
fi

[ -f "$SOURCE_DIR/StarBotCore.jar" ] || die "未找到构建产物 StarBotCore.jar"

# ---------------------------------------------------------------- 安装

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
KEEP_DIR="$($SUDO mktemp -d)"
trap '[ -n "${KEEP_DIR:-}" ] && $SUDO rm -rf "$KEEP_DIR" || true' EXIT

for keep in application.yml datasource.json; do
    if [ -f "$INSTALL_DIR/$keep" ]; then
        $SUDO cp "$INSTALL_DIR/$keep" "$KEEP_DIR/$keep"
    fi
done

# INSTALL_DIR 由命令行指定，误传 /usr 之类的路径会让清理毁掉系统。
# 只在目标目录确实是既有安装时才清理，认不出来就停下来问，不硬着头皮删
if [ -f "$INSTALL_DIR/StarBotCore.jar" ]; then
    # 这两个目录完全由新版本重新生成
    $SUDO rm -rf "$INSTALL_DIR/lib" "$INSTALL_DIR/plugins-lib"

    # plugins 不能整个删：里面可能有使用者自己放的第三方插件，删掉等于静默卸载。
    # 但内置插件带版本号，旧版留着会与新版同时被加载，故按构件名精确清理；
    # 版本号剥不出来时宁可留下也不误删。
    # 匹配式里版本位限定为数字开头，否则 starbot-onebot-adapter-* 会连带匹配
    # starbot-onebot-adapter-napcat-extension-*，第三方插件若以内置插件名为前缀也会被误删
    for jar in "$SOURCE_DIR"/plugins/*.jar; do
        [ -f "$jar" ] || continue
        artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\.jar$//')"
        case "$artifact" in
            *.jar) continue ;;
        esac
        $SUDO find "$INSTALL_DIR/plugins" -maxdepth 1 -type f -name "$artifact-[0-9]*.jar" -delete
    done
elif [ -e "$INSTALL_DIR/lib" ] || [ -e "$INSTALL_DIR/plugins" ]; then
    die "$INSTALL_DIR 下已有 lib/ 或 plugins/，但没有 StarBotCore.jar，不像 NovaBot 的安装目录。
     为免误删，请换一个目录，或先自行确认该目录内容"
fi

$SUDO cp -r "$SOURCE_DIR"/. "$INSTALL_DIR/"

for keep in application.yml datasource.json; do
    if [ -f "$KEEP_DIR/$keep" ]; then
        $SUDO cp "$KEEP_DIR/$keep" "$INSTALL_DIR/$keep"
        info "已保留原有的 $keep"
    fi
done

if [ "$PORT" != "7827" ]; then
    $SUDO sed -i "s/^  port: 7827/  port: $PORT/" "$INSTALL_DIR/application.yml"
fi

# 升级时保留的是旧 application.yml，其中的端口未必是 7827，上面的替换会静默落空。
# 结尾提示的地址以文件里的实际取值为准，否则会给出一个打不开的地址
EFFECTIVE_PORT="$($SUDO awk 'match($0, /^  port: [0-9]+/) { gsub(/[^0-9]/, "", $0); print; exit }' "$INSTALL_DIR/application.yml")"
EFFECTIVE_PORT="${EFFECTIVE_PORT:-$PORT}"
if [ "$EFFECTIVE_PORT" != "$PORT" ]; then
    warn "application.yml 中的端口是 $EFFECTIVE_PORT，与 --port $PORT 不一致（升级时保留了原有配置）。
     如需改用 $PORT，请手动编辑 $INSTALL_DIR/application.yml"
fi

$SUDO chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
$SUDO chmod +x "$INSTALL_DIR/start.sh"
# 凭据文件等同于账号密码，仅属主可读写
$SUDO chmod 600 "$INSTALL_DIR"/cookies.* 2>/dev/null || true

# ---------------------------------------------------------------- 服务

if [ "$CREATE_SERVICE" = "yes" ] && command -v systemctl > /dev/null 2>&1; then
    info "创建 systemd 服务"

    $SUDO sed -e "s#/opt/starbot#$INSTALL_DIR#g" -e "s/^User=.*/User=$SERVICE_USER/" -e "s/^Group=.*/Group=$SERVICE_USER/" \
        "$INSTALL_DIR/starbot.service" | $SUDO tee /etc/systemd/system/starbot.service > /dev/null

    $SUDO systemctl daemon-reload
    $SUDO systemctl enable starbot > /dev/null 2>&1
    info "服务已创建并设为开机自启"
fi

# ---------------------------------------------------------------- 完成

cat <<EOF

安装完成，接下来：

  1. 启动服务
       sudo systemctl start starbot

  2. 查看启动日志，其中包含配置界面地址与首次登录的二维码
       sudo journalctl -u starbot -f

  3. 在浏览器中打开日志里输出的配置界面地址完成配置
       该地址形如 http://127.0.0.1:$EFFECTIVE_PORT/config?token=xxxxx

     若 NovaBot 装在远程服务器上，先在本机建立隧道再访问：
       ssh -L $EFFECTIVE_PORT:127.0.0.1:$EFFECTIVE_PORT 用户名@服务器地址

  4. 使用哔哩哔哩客户端扫描日志中的二维码完成登录

配置界面默认仅监听本机回环地址。如需从其他机器直接访问，请先阅读 SECURITY.md，
并在配置中同时设置访问令牌与来源 IP 白名单。

安装目录：$INSTALL_DIR
EOF
