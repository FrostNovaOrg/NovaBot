#!/usr/bin/env bash
#
# StarBot 一键安装脚本（Linux）
#
# 用法：
#   ./install.sh                        从当前源码构建并安装到 /opt/starbot
#   ./install.sh --dir /srv/starbot     指定安装目录
#   ./install.sh --no-service           跳过 systemd 服务创建
#   ./install.sh --port 7827            指定服务端口
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

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)        INSTALL_DIR="$2"; shift 2 ;;
        --port)       PORT="$2"; shift 2 ;;
        --user)       SERVICE_USER="$2"; shift 2 ;;
        --no-service) CREATE_SERVICE="no"; shift ;;
        -h|--help)    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            die "未知参数: $1（可用 --help 查看用法）" ;;
    esac
done

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

install_java() {
    local pm
    pm="$(detect_pkg_manager)"
    [ -n "$pm" ] || die "未识别的包管理器，请手动安装 JDK 17 后重新运行"

    info "正在安装 JDK 17（使用 $pm）"
    case "$pm" in
        apt-get) $SUDO apt-get update -qq && $SUDO apt-get install -y openjdk-17-jre-headless ;;
        dnf)     $SUDO dnf install -y java-17-openjdk-headless ;;
        yum)     $SUDO yum install -y java-17-openjdk-headless ;;
        pacman)  $SUDO pacman -Sy --noconfirm jre17-openjdk-headless ;;
        apk)     $SUDO apk add --no-cache openjdk17-jre-headless ;;
        zypper)  $SUDO zypper install -y java-17-openjdk-headless ;;
    esac
}

java_major() {
    command -v java > /dev/null 2>&1 || { echo 0; return; }
    java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

info "检查运行环境"
if [ "$(java_major)" -lt 17 ]; then
    install_java
    [ "$(java_major)" -ge 17 ] || die "JDK 17 安装后仍不可用，请手动检查"
fi
info "Java 版本：$(java -version 2>&1 | head -1)"

# 绘制动态图片需要中文字体，缺失时图片中的中文会变成方块
if ! fc-list 2>/dev/null | grep -qiE 'cjk|noto sans sc|wqy|source han'; then
    warn "未检测到中文字体，动态图片中的中文可能显示为方块"
    pm="$(detect_pkg_manager)"
    case "$pm" in
        apt-get) info "正在安装中文字体"; $SUDO apt-get install -y -qq fonts-noto-cjk > /dev/null 2>&1 || warn "字体安装失败，可稍后手动安装 fonts-noto-cjk" ;;
        dnf|yum) info "正在安装中文字体"; $SUDO "$pm" install -y google-noto-sans-cjk-fonts > /dev/null 2>&1 || warn "字体安装失败" ;;
        *)       warn "请手动安装 Noto Sans CJK 或文泉驿字体" ;;
    esac
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

# 升级时保留既有的配置与登录凭据
for keep in application.yml datasource.json cookies.json cookies.key; do
    if [ -f "$INSTALL_DIR/$keep" ]; then
        $SUDO cp "$INSTALL_DIR/$keep" "/tmp/starbot-keep-$keep"
    fi
done

$SUDO rm -rf "$INSTALL_DIR/lib" "$INSTALL_DIR/plugins" "$INSTALL_DIR/plugins-lib"
$SUDO cp -r "$SOURCE_DIR"/. "$INSTALL_DIR/"

for keep in application.yml datasource.json cookies.json cookies.key; do
    if [ -f "/tmp/starbot-keep-$keep" ]; then
        $SUDO cp "/tmp/starbot-keep-$keep" "$INSTALL_DIR/$keep"
        $SUDO rm -f "/tmp/starbot-keep-$keep"
        info "已保留原有的 $keep"
    fi
done

if [ "$PORT" != "7827" ]; then
    $SUDO sed -i "s/^  port: 7827/  port: $PORT/" "$INSTALL_DIR/application.yml"
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
       该地址形如 http://127.0.0.1:$PORT/config?token=xxxxx

     若 StarBot 装在远程服务器上，先在本机建立隧道再访问：
       ssh -L $PORT:127.0.0.1:$PORT 用户名@服务器地址

  4. 使用哔哩哔哩客户端扫描日志中的二维码完成登录

配置界面默认仅监听本机回环地址。如需从其他机器直接访问，请先阅读 SECURITY.md，
并在配置中同时设置访问令牌与来源 IP 白名单。

安装目录：$INSTALL_DIR
EOF
