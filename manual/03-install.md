# 第 3 章　安装

三种装法挑一种：大多数人是第一种（Linux 一键安装）；用 macOS、Windows
或不想让脚本碰系统的用第二种；用 Docker 的看第三种。

## 源码从哪来

源码用 `git clone` 取得，仓库是 `https://github.com/FrostNovaOrg/NovaBot`。
从网页下载的源码压缩包不是 git 仓库，构建脚本会在第一步停下，提示「这里不是
git 仓库，无法记录构建来源」——所以别下载 zip。

## 装法一：Linux 一键安装

```bash
git clone https://github.com/FrostNovaOrg/NovaBot
cd NovaBot
./install.sh
```

脚本一口气做完：检查并安装 Java 17 与中文字体、构建、安装到 `/opt/starbot`、
生成初始配置、创建 systemd 服务。坐等跑完就行。

机器上已经装有更高版本 JDK 时，一键安装会停在构建那一步。处理办法：装一个
JDK 17 并把它排在 PATH 最前，再重新运行脚本。**只改 JAVA_HOME、不改 PATH
不算数**——构建脚本查的是 PATH 上的 `java`。

可选参数（写在 `./install.sh` 后面）：

| 参数 | 作用 |
|---|---|
| `--dir /srv/novabot` | 指定安装目录（默认 `/opt/starbot`） |
| `--port 7827` | 指定服务端口 |
| `--no-service` | 跳过 systemd 服务创建 |

装完启动并看日志：

```bash
sudo systemctl start novabot && sudo journalctl -u novabot -f
```

## 装法二：手动安装

适合 macOS、Windows，或者不想让脚本建 systemd 服务的机器：

```bash
./build.sh
```

不带参数会跑全部测试，这需要 Node 22。只出包、不跑测试：`./build.sh --skip-tests`。

产物在 `dist/build/` 目录，把它整个拷到目标机器，在里面运行：

```bash
./start.sh
```

> [!NOTE] 构建必须走 `build.sh`，不要直接调 maven：插件预处理、打可运行包、
> 起动自检都串在这一个入口里，单独跑其中一段会漏掉前置步骤。

## 装法三：容器部署

```bash
./build.sh
docker build -f dist/templates/Dockerfile -t novabot dist/build
docker run -d --name novabot --restart unless-stopped \
  -v novabot-data:/app -p 127.0.0.1:7827:7827 novabot
```

两件事必须知道：

- **卷必须挂在 `/app`。** 配置、登录凭据、推送目标全写在程序的工作目录下；
  挂到 `/app/data` 之类的子目录等于什么都没持久化，容器一重建就得重新扫码登录。
- **容器里的监听地址固定为所有网卡。** 对外开不开，完全由 `docker run` 的 `-p`
  决定：例子里的 `-p 127.0.0.1:7827:7827` 只把端口开在宿主机回环上。不要图省事
  改成直接发布到公网。设置页里改监听地址对容器不起作用。

换镜像会换掉卷上的程序，`plugins-lib/` 目录不跟着换——用到时看
[第 13 章　升级与备份](13-upgrade-and-backup.md)。

## 装完之后

无论哪种装法，程序启动后都会在日志里打出**控制台地址**，这就是下一章的起点：

- 没设口令登录时，每次启动都打带访问令牌的地址；令牌没在配置里写死的话，每次启动都换一个。
  设了口令登录，只打不带令牌的地址。
- 还没登录过 B 站、又没开免登录时，另打一张**登录二维码**。
- 登录过（保存的凭据还有效）或开了免登录，就不打二维码——日志里找不到它不是装坏了。

没设口令登录时，启动日志里会有这样两行（`<令牌>` 处是一长串字母、数字，中间也会夹着 `-` 和 `_`）：

```
配置界面已启动: http://127.0.0.1:7827/config?token=<令牌>
该地址包含访问令牌, 请勿分享。令牌未在配置文件中显式设置时, 每次启动都会重新生成
```
