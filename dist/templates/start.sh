#!/usr/bin/env bash
#
# StarBot 启动脚本
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# JVM 参数按 1G 内存的 VPS 调校，说明见 docs/performance.md
JAVA_OPTS="${JAVA_OPTS:-}"
# 初始堆取 64m 而非 128m：初始堆会被提前提交，页面一旦触碰就长期常驻，
# 实测把 128m 降到 64m 可使稳态常驻内存从 130~155 MB 降到 105~110 MB，且读数更稳定
JAVA_OPTS="$JAVA_OPTS -Xms64m -Xmx512m"
JAVA_OPTS="$JAVA_OPTS -XX:+UseSerialGC"
# 线程栈 256K：StarBot 的调用栈很浅，默认 1M 在几十个线程下白白占掉数十兆
JAVA_OPTS="$JAVA_OPTS -Xss256k"
JAVA_OPTS="$JAVA_OPTS -XX:MaxMetaspaceSize=192m"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"

# 下载完插件依赖后需要重启才能加载，程序会以该退出码退出
RESTART_EXIT_CODE=90

# systemd 部署时由 Restart=on-failure 负责拉起，此处的循环是给手动运行的场景用的。
# 不在程序内部自行派生子进程重启：那样父进程要一直驻留等子进程结束，白占一份内存，
# systemd 下的进程树也不正确
while true; do
  set +e
  java $JAVA_OPTS -Dloader.path=lib,plugins-lib -jar StarBotCore.jar "$@"
  code=$?
  set -e

  if [ "$code" -ne "$RESTART_EXIT_CODE" ]; then
    exit "$code"
  fi

  echo "插件依赖已下载完毕，正在重启以加载…"
done
