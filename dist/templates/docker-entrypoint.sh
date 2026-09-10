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
cp -f "$SRC/NovaBot.jar" "$SRC/start.sh" "$DST/"
rm -rf "$DST/lib"
cp -R "$SRC/lib" "$DST/lib"

# plugins 里可能有使用者自己放进卷的第三方插件，不能整个替换。
# 只清掉内置插件的旧版本，规则与 install.sh 保持一致：
# 版本位限定数字开头，避免 nova-onebot-adapter-* 连带匹配
# nova-onebot-adapter-napcat-extension-*，也避免误删以内置插件名为前缀的第三方插件
for jar in "$SRC"/plugins/*.jar; do
    [ -f "$jar" ] || continue
    artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\.jar$//')"
    case "$artifact" in
        *.jar) continue ;;
    esac
    find "$DST/plugins" -maxdepth 1 -type f -name "$artifact-[0-9]*.jar" -delete
done
cp -f "$SRC"/plugins/*.jar "$DST/plugins/"

# 🔴 5.1 起，镜像里不再带 application.yml 与 datasource.json：程序自己会在第一次保存设置、
#    第一次加主播时把它们写出来，写到数据卷上（$DST），也就是<b>本来就该在的地方</b>。
#    所以这里只把示例铺过去，铺完不再管——铺一份「默认配置」等于替使用者做了一半的事，
#    而那一半做完之后，控制台再也认不出这是一台还没配过的机器。
#    仍然只在缺失时铺：镜像重启一次就把使用者改过的示例冲掉，与冲掉配置一样难查。
for conf in application.example.yml datasource.example.json; do
    if [ -f "$SRC/$conf" ] && [ ! -f "$DST/$conf" ]; then
        cp "$SRC/$conf" "$DST/$conf"
    fi
done

cd "$DST"
# exec 让 start.sh 成为 1 号进程，直接收到 docker stop 的 SIGTERM，
# 再由它转发给 java 完成优雅停机
exec ./start.sh "$@"
