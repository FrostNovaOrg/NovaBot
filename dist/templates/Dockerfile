# NovaBot 运行镜像
#
# 构建：
#   ./build.sh
#   docker build -f dist/templates/Dockerfile -t starbot:3.0 dist/build
#
# 运行：
#   docker run -d --name starbot \
#     --restart unless-stopped \
#     -v starbot-data:/app \
#     -p 127.0.0.1:7827:7827 \
#     starbot:3.0
#
# 卷必须挂在 /app。程序把配置、登录凭据、推送规则、插件依赖全部写在工作目录下，
# 挂到 /app/data 之类的子目录等于什么都没持久化，容器一重建就要重新扫码。
#
# 程序文件在镜像的 /opt/starbot，每次启动由入口脚本同步到 /app，
# 所以换用新镜像即完成升级，卷里的配置与登录态原样保留。
#
# 建议用具名卷（如上）。用绑定挂载时宿主目录属主须为容器内的 starbot（uid 10001），
# 否则程序无权写入：sudo chown -R 10001:10001 ./data
#
# 调整 JVM 参数用 -e JAVA_OPTS="-Xmx1g"，会追加在 start.sh 的默认值之后生效。
#
# 首次启动需扫码登录：docker logs -f starbot

FROM eclipse-temurin:17-jre-noble

# 绘制动态图片需要中文字体，缺失时图片中的中文会显示为方块
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# uid 固定为 10001，便于绑定挂载时在宿主上对齐属主
RUN useradd --system --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin starbot

COPY --chown=starbot:starbot . /opt/starbot/
RUN chmod +x /opt/starbot/start.sh /opt/starbot/docker-entrypoint.sh

WORKDIR /app
VOLUME /app
USER starbot

# JVM 参数只在 start.sh 里定义一份，此处不再重复：
# 镜像里抄一份，两边迟早会漂移——曾出现过镜像仍在用已被实测淘汰的 -Xms128m
ENV TZ=Asia/Shanghai

EXPOSE 7827

# 入口脚本 exec 到 start.sh，由它接住 docker stop 的 SIGTERM 并转发给 java，
# 直接 exec java 则会绕过 start.sh 的插件依赖重启逻辑（退出码 90）
ENTRYPOINT ["/opt/starbot/docker-entrypoint.sh"]
