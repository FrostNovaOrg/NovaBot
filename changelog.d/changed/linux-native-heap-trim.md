Linux 上的启动脚本会在 Java 支持时默认每 60 秒归还闲置的原生内存，实测常驻内存减少约一成；旧 Java 和其他系统照常启动，可用 JAVA_OPTS="-XX:TrimNativeHeapInterval=0" 关闭。
