#!/usr/bin/env bash
#
# StarBot 启动脚本
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# JVM 参数按 1G 内存的 VPS 调校，说明见 docs/performance.md
JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS="$JAVA_OPTS -Xms128m -Xmx512m"
JAVA_OPTS="$JAVA_OPTS -XX:+UseSerialGC"
# 线程栈 256K：StarBot 的调用栈很浅，默认 1M 在几十个线程下白白占掉数十兆
JAVA_OPTS="$JAVA_OPTS -Xss256k"
JAVA_OPTS="$JAVA_OPTS -XX:MaxMetaspaceSize=192m"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"

exec java $JAVA_OPTS -Dloader.path=lib,plugins-lib -jar StarBotCore.jar "$@"
