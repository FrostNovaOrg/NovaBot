#!/usr/bin/env bash
#
# NovaBot 启动脚本
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# JVM 参数按 1G 内存的 VPS 调校，说明见 docs/performance.md。
# 这里是全部部署方式（手动、systemd、容器）共用的唯一一份 JVM 参数，
# 镜像里不要另抄一份：抄过一次，两边就漂移过一次
JVM_OPTS=""
# 初始堆取 64m 而非 128m：初始堆会被提前提交，页面一旦触碰就长期常驻，
# 实测把 128m 降到 64m 可使稳态常驻内存从 130~155 MB 降到 105~110 MB，且读数更稳定
JVM_OPTS="$JVM_OPTS -Xms64m -Xmx512m"
JVM_OPTS="$JVM_OPTS -XX:+UseSerialGC"
# 线程栈 256K：NovaBot 的调用栈很浅，默认 1M 在几十个线程下白白占掉数十兆
JVM_OPTS="$JVM_OPTS -Xss256k"
JVM_OPTS="$JVM_OPTS -XX:MaxMetaspaceSize=192m"
JVM_OPTS="$JVM_OPTS -XX:+ExitOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -Djava.awt.headless=true"
JVM_OPTS="$JVM_OPTS -Duser.timezone=${TZ:-Asia/Shanghai}"
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8"
# 外部传入的 JAVA_OPTS 放最后：同类参数 JVM 取后出现的那个，
# 放前面的话 JAVA_OPTS="-Xmx1g" ./start.sh 会被下面的默认值悄悄盖掉
JVM_OPTS="$JVM_OPTS ${JAVA_OPTS:-}"

# 下载完插件依赖后需要重启才能加载，程序会以该退出码退出
RESTART_EXIT_CODE=90

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

# systemd 部署时由 Restart=on-failure 负责拉起，此处的循环是给手动运行与容器用的。
# 不在程序内部自行派生子进程重启：那样父进程要一直驻留等子进程结束，白占一份内存，
# systemd 下的进程树也不正确
while true; do
  set +e
  java $JVM_OPTS -Dloader.path=lib,plugins-lib -jar StarBotCore.jar "$@" &
  child=$!

  # wait 被信号打断时会立刻返回 128+信号号，而此时 java 才刚开始停机。
  # 必须接着等它真正退出，否则容器会在停机做到一半时就把进程收走
  wait "$child"; code=$?
  while [ "$code" -gt 128 ] && kill -0 "$child" 2>/dev/null; do
    wait "$child"; code=$?
  done
  set -e

  if [ "$code" -ne "$RESTART_EXIT_CODE" ]; then
    exit "$code"
  fi

  echo "插件依赖已下载完毕，正在重启以加载…"
done
