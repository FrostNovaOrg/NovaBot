# StarBot 运行镜像
#
# 构建：
#   ./build.sh
#   docker build -f dist/templates/Dockerfile -t starbot:3.0 dist/build
#
# 运行（配置与凭据通过卷挂载持久化）：
#   docker run -d --name starbot \
#     -v "$PWD/data:/app/data" \
#     -p 127.0.0.1:7827:7827 \
#     starbot:3.0
#
# 首次启动需扫码登录：docker logs -f starbot

FROM eclipse-temurin:17-jre-noble

# 绘制动态图片需要中文字体，缺失时图片中的中文会显示为方块
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin starbot
WORKDIR /app

COPY --chown=starbot:starbot . /app/

USER starbot

ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
ENV TZ=Asia/Shanghai

EXPOSE 7827

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dloader.path=lib,plugins-lib -jar StarBotCore.jar"]
