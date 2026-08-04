#!/usr/bin/env bash
#
# 容器入口：把镜像里的程序文件铺到工作目录，再交给 start.sh
#
# 为什么要铺这一道：程序读写的文件——application.yml、cookies.json、cookies.key、
# datasource.json、备份、plugins-lib——全部使用相对工作目录的路径，
# 状态与程序天然混在同一个目录里，没法只把状态挂出来。
#
# 所以程序文件放在镜像内的 /opt/starbot，每次启动同步到 /app，/app 整个挂成卷：
#   - 配置与登录态跟着卷走，容器重建不丢
#   - 程序文件每次启动都刷新，换新镜像即完成升级
#
set -euo pipefail

SRC=/opt/starbot
DST=/app

mkdir -p "$DST/plugins" "$DST/plugins-lib"

# 程序文件每次启动都覆盖，这样升级镜像就等于升级程序
cp -f "$SRC/StarBotCore.jar" "$SRC/start.sh" "$DST/"
rm -rf "$DST/lib"
cp -R "$SRC/lib" "$DST/lib"

# plugins 里可能有使用者自己放进卷的第三方插件，不能整个替换。
# 只清掉内置插件的旧版本，规则与 install.sh 保持一致：
# 版本位限定数字开头，避免 starbot-onebot-adapter-* 连带匹配
# starbot-onebot-adapter-napcat-extension-*，也避免误删以内置插件名为前缀的第三方插件
for jar in "$SRC"/plugins/*.jar; do
    [ -f "$jar" ] || continue
    artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\.jar$//')"
    case "$artifact" in
        *.jar) continue ;;
    esac
    find "$DST/plugins" -maxdepth 1 -type f -name "$artifact-[0-9]*.jar" -delete
done
cp -f "$SRC"/plugins/*.jar "$DST/plugins/"

# 配置只在缺失时铺默认值，否则每次启动都会把使用者改过的配置冲掉
for conf in application.yml datasource.json; do
    if [ ! -f "$DST/$conf" ]; then
        cp "$SRC/$conf" "$DST/$conf"
    fi
done

cd "$DST"
# exec 让 start.sh 成为 1 号进程，直接收到 docker stop 的 SIGTERM，
# 再由它转发给 java 完成优雅停机
exec ./start.sh "$@"
