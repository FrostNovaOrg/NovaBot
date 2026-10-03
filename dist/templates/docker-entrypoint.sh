#!/usr/bin/env bash
#
# 容器入口：把镜像里的程序文件铺到工作目录，再交给 start.sh
#
# 为什么要铺这一道：程序读写的文件——application.yml、cookies.json、cookies.key、
# datasource.json、备份——全部使用相对工作目录的路径，
# 状态与程序天然混在同一个目录里，没法只把状态挂出来。
# plugins-lib 也是相对工作目录的路径：镜像自带的插件依赖每次启动按构件名换新，
# 自己放的留下。
#
# 所以程序文件放在镜像内的 /opt/starbot，每次启动同步到 /app，/app 整个挂成卷：
#   - 配置与登录态跟着卷走，容器重建不丢
#   - 程序文件每次启动都刷新，换新镜像即完成升级
#
set -euo pipefail

SRC=/opt/starbot
DST=/app

# 先占容器自己的锁，再铺程序文件。同一个卷上第二个容器在这里退出，一个文件都不改。
# 锁件是 novabot-container.lock，不碰程序的 novabot.lock。网络盘上 flock 会被当成
# 整件的字节区锁，和程序那把锁互相挡；锁同一件的话，容器里的程序会以为已经有一份在跑。
# 只有这把锁被别人占着才退出。取不到——文件系统不支持、没有 flock、锁件打不开——
# 说明一句后照常启动。
# 描述符 9 一直开着，exec 之后交给 start.sh，再交给 java；两边都退出，锁才松开。
if [ ! -d "$DST" ]; then
    mkdir -p "$DST"
fi
case "$DST" in
    /*) lock_path="$DST/novabot-container.lock" ;;
    *) lock_path="$(cd "$DST" && pwd)/novabot-container.lock" ;;
esac
# 73 只表示锁被占。别的退码都不是「已有一份在跑」。
if ! exec 9<>"$lock_path"; then
    echo "没法确认这个卷是不是已有一份在跑，照常启动。" >&2
else
    lock_status=0
    flock -n -E 73 9 || lock_status=$?
    if [ "$lock_status" -eq 73 ]; then
        echo "这个目录已有一份 NovaBot 在运行（${lock_path}）。要换版本请先停掉它。" >&2
        exit 1
    fi
    if [ "$lock_status" -ne 0 ]; then
        echo "没法确认这个卷是不是已有一份在跑，照常启动。" >&2
    fi
fi

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
# 目录里一个 jar 都没有时通配符原样留下，cp 会当场退出；照上面的样子逐个铺，没有就跳过
for jar in "$SRC"/plugins/*.jar; do
    [ -f "$jar" ] || continue
    cp -f "$jar" "$DST/plugins/"
done

# plugins-lib 是内置插件的运行期依赖（caffeine、jieba-analysis 等）。此前只建空目录，
# 新装容器上这些依赖不在类路径里，程序起不来（NoClassDefFoundError）。里面也可能有
# 使用者自己放的第三方依赖，不能整个替换，照上面 plugins 的同一套规则、与 install.sh
# 处理 plugins-lib 的规则一致：按构件名删旧拷新，构件名对不上的原样留下。
for jar in "$SRC"/plugins-lib/*.jar; do
    [ -f "$jar" ] || continue
    artifact="$(basename "$jar" | sed -E 's/-[0-9][^-]*\.jar$//')"
    case "$artifact" in
        *.jar) continue ;;
    esac
    find "$DST/plugins-lib" -maxdepth 1 -type f -name "$artifact-[0-9]*.jar" -delete
done
for jar in "$SRC"/plugins-lib/*.jar; do
    [ -f "$jar" ] || continue
    cp -f "$jar" "$DST/plugins-lib/"
done

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
