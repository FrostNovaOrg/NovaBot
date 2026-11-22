#!/usr/bin/env bash
#
# NovaBot 启动脚本
#
set -euo pipefail

# 程序在 releases/<版本>/、数据在再上一级时，工作目录留在安装目录。
# 锁、配置、凭据和日志都落在工作目录；进了版本目录，两版就会各拿一把锁一起跑。
# 其余布局脚本就在程序旁边，仍进入脚本自己的目录。
script_dir=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)
app_dir=$script_dir
data_dir=$script_dir
if [ "$(basename "$(dirname "$script_dir")")" = "releases" ]; then
    data_dir=$(dirname "$(dirname "$script_dir")")
    cd "$data_dir"
else
    cd "$(dirname "${BASH_SOURCE[0]}")"
fi

# JVM 参数按 1G 内存的 VPS 调校，说明见 docs/performance.md。
# 这里是全部部署方式（手动、systemd、容器）共用的唯一一份 JVM 参数，
# 镜像里不要另抄一份：抄过一次，两边就漂移过一次
JVM_OPTS=""
# 初始堆取 64m 而非 128m：初始堆会被提前提交，页面一旦触碰就长期常驻，
# 实测把 128m 降到 64m 可使稳态常驻内存从 130~155 MB 降到 105~110 MB，且读数更稳定
JVM_OPTS="$JVM_OPTS -Xms64m -Xmx512m"
JVM_OPTS="$JVM_OPTS -XX:+UseSerialGC"
# 降低空闲比例，让堆在回收后更容易缩小。试跑一天多，堆通常维持在 170–190 MB，
# 完整回收停顿为 0.46–0.72 秒（改前约 2.33 秒）；同期也减少了常驻数据。
# 用 JAVA_OPTS="-XX:MinHeapFreeRatio=40 -XX:MaxHeapFreeRatio=70" 恢复 JDK 默认值。
# JAVA_OPTS 指定任一比例时就省略这两个默认值，另一项由 JVM 决定。
if [[ ! ${JAVA_OPTS:-} =~ (^|[[:space:]])-XX:(MinHeapFreeRatio|MaxHeapFreeRatio)= ]]; then
    JVM_OPTS="$JVM_OPTS -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40"
fi
# 线程栈 256K：NovaBot 的调用栈很浅，默认 1M 在几十个线程下白白占掉数十兆
JVM_OPTS="$JVM_OPTS -Xss256k"
JVM_OPTS="$JVM_OPTS -XX:MaxMetaspaceSize=192m"
JVM_OPTS="$JVM_OPTS -XX:+ExitOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -Djava.awt.headless=true"
JVM_OPTS="$JVM_OPTS -Duser.timezone=${TZ:-Asia/Shanghai}"
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8"
# Linux/glibc 每 60 秒把 malloc 池里已不用的内存还给系统：
# 2 核、2 GB、OpenJDK 17.0.20 各跑 75 分钟，RSS 从 507 MB 降到 455 MB，约省一成。
# 先用同一个 java 试跑确认支持，避免旧 Java 因不认识选项而无法启动。
# 如需关闭，设置 JAVA_OPTS="-XX:TrimNativeHeapInterval=0"，后面的用户值会覆盖默认值。
if [ "$(uname -s)" = "Linux" ] && java -XX:TrimNativeHeapInterval=60000 -version >/dev/null 2>&1; then
    JVM_OPTS="$JVM_OPTS -XX:TrimNativeHeapInterval=60000"
fi

prepare_gc_log() (
    mkdir -p logs && [[ -w logs && -x logs ]] || exit 1
    if [[ -e logs/gc.log || -L logs/gc.log ]]; then
        [[ -f logs/gc.log ]] && : >> logs/gc.log || exit 1
    fi
    # 实际写入以检查磁盘空间，保留原日志给 JVM 轮转。
    probe=$(mktemp logs/.gc-log.XXXXXX) || exit 1
    trap 'rm -f "$probe"' EXIT
    printf '\n' > "$probe"
)

# 用 JAVA_OPTS="-Xlog:disable" 关闭 JVM 统一日志。跳过默认日志选项，避免 JVM 仍打开并轮转文件。
if [[ ! ${JAVA_OPTS:-} =~ (^|[[:space:]])-Xlog:disable($|[[:space:]]) ]]; then
    if prepare_gc_log 2>/dev/null; then
        JVM_OPTS="$JVM_OPTS -Xlog:gc,gc+heap:file=logs/gc.log:time,uptime,level,tags:filecount=20,filesize=5m"
    else
        printf '警告：无法写入 logs/gc.log，本次启动跳过垃圾回收日志。\n' >&2
    fi
fi
# 外部传入的 JAVA_OPTS 放最后：同类参数 JVM 取后出现的那个，
# 放前面的话 JAVA_OPTS="-Xmx1g" ./start.sh 会被下面的默认值悄悄盖掉
JVM_OPTS="$JVM_OPTS ${JAVA_OPTS:-}"

child=""

# 容器里本脚本是 1 号进程，停机信号只送到它这里，不转发的话 java 根本收不到 SIGTERM，
# 只能等 docker stop 超时后被 SIGKILL：优雅停机（实测 0.7 秒）失效，
# 还可能在写 cookies.json 的中途被硬杀。
# systemd 下 KillMode 默认按 cgroup 杀，java 本来就收得到，这里是多一层保险
forward_signal() {
    if [ -n "$child" ]; then
        kill -TERM "$child" 2>/dev/null || true
    fi
}
trap forward_signal TERM INT

# 一次运行，退出码原样交给 systemd／容器。
# systemd 部署时由 Restart=on-failure 负责拉起；容器用 --restart unless-stopped。
# 不在程序内部自行派生子进程重启：那样父进程要一直驻留等子进程结束，白占一份内存，
# systemd 下的进程树也不正确
set +e
if [ "$(basename "$(dirname "$script_dir")")" = "releases" ]; then
    java $JVM_OPTS "-Dloader.path=$app_dir/lib,$app_dir/plugins,$app_dir/plugins-lib,$data_dir/plugins,$data_dir/plugins-lib" -jar "$app_dir/NovaBot.jar" "$@" &
else
java $JVM_OPTS -Dloader.path=lib,plugins,plugins-lib -jar NovaBot.jar "$@" &
fi
child=$!

# wait 被信号打断时会立刻返回 128+信号号，而此时 java 才刚开始停机。
# 必须接着等它真正退出，否则容器会在停机做到一半时就把进程收走
wait "$child"; code=$?
while [ "$code" -gt 128 ] && kill -0 "$child" 2>/dev/null; do
  wait "$child"; code=$?
done
set -e

exit "$code"
