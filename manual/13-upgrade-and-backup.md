# 第 13 章　升级与备份

程序会持续改进，这一章讲两件事：怎么把现在的版本换成新版本，怎么把数据备到另一处。
升级前先备份，换坏了随时回得去；平时定期备份，机器坏了数据还在。
两件都是按顺序做下来的事，跟着走就行。

## 升级前先留好这几样

- 停止服务：systemd 是 `sudo systemctl stop novabot`，容器是 `docker stop`
- 备份 `application.yml` 与 `datasource.json`，它们是你的全部配置
- 备份 `cookies.json` 与 `cookies.key`，它们等同于哔哩哔哩账号的完整控制权
- 旁边若还有 `cookies.json.plain.bak`，那是明文迁成加密时留下的明文原件，程序不读它：
  不用备、升级后也不用拷回，确认能正常登录后就把它删掉

登录凭据默认加密后仍写在 `cookies.json` 里，密钥在 `cookies.key`，没有另存一份密文文件，
所以这两个都要备。直播数据不用特意搬：升级只换程序文件，数据不动。

## 用安装脚本升级

Linux 一键安装过的机器，重新跑一遍安装脚本就是升级：

```bash
cd NovaBot
git pull
./install.sh
```

脚本会重新构建并装进安装目录，主程序 `NovaBot.jar` 与 `lib/` 整个换成新的。
另外两个目录它不整个删掉重来，规则如下。

**`plugins-lib/` 里自己放的东西不会被清掉。** 脚本只动三类，其余原样留着：

- 发行包自带的依赖：按构件名删掉旧版本，放入新版本
- 文件名里认不出版本号的（例如以 `-jre`、`-SNAPSHOT` 结尾的）：不删
- 新包里没有的 jar，包括你自己放的：原样保留，脚本结束时列出留了哪些

「认不出版本号」的那批里，恰与新包里文件同名的会被新版盖掉，不列入保留名单；
其余的和新版并存。列出保留的 jar 启动时都会装上，确认不用的可以自己删。

**`plugins/` 里的内置插件按名字换新，第三方插件一概不动。** 这个目录里每个 jar
启动时都会加载，旧版不删会与新版一起装上，所以只换这五个、并删掉它们的旧版本文件
（`<版本>` 处换成新版本号）：

- `nova-onebot-adapter-<版本>.jar`
- `nova-onebot-adapter-napcat-extension-<版本>.jar`
- `nova-bilibili-<版本>.jar`
- `nova-console-<版本>.jar`
- `nova-report-<版本>.jar`

安装脚本会按名字删掉这五个的旧版本再放入新版。自己放的第三方插件它不碰。
从 **5.2 及更早**升上来时主程序与内置插件还叫旧名字，换成新名字盖不掉旧文件，
`plugins/` 里这些要自己删：

- `starbot-onebot-adapter-1.0.0.jar`
- `starbot-onebot-adapter-napcat-extension-1.0.0.jar`
- `starbot-bilibili-1.0.0.jar`
- `starbot-novabot-console-1.0.0.jar`
- `starbot-report-1.0.0.jar`

已发布的 5.2.0 及更早只有前三个插件 jar；后两个只在 5.3.0 发布前、目录还没改名的构建里有。
程序目录里的 `StarBotCore.jar` 也是旧版留下的，安装脚本会删它。

## 手工升级

不用脚本的机器（macOS、Windows，或不想让脚本碰系统的），照这个顺序做：

1. 停止服务
2. 备份 `application.yml`、`datasource.json`、`cookies.json`、`cookies.key`
3. 用新版本的产物整个换掉 `NovaBot.jar` 与 `lib/`
4. `plugins-lib/` 里新包自带的 jar 按名字删旧放新，自己放的留下
5. `plugins/` **不要整个替换**，只按名字换上面那五个内置插件并删旧版
6. 保留原有的 `application.yml` 与 `datasource.json`
7. 启动，看日志有没有告警

## 容器怎么升级

换新镜像、重建容器就是升级。容器每次启动都会用镜像里的 `NovaBot.jar` 与 `lib/`
盖掉卷 `/app` 上的同名文件，并按新文件名换内置插件。两处要自己动手：

- `plugins-lib/` 只会建出空目录，镜像里的那份不铺到卷上——换镜像后卷里的不是新的，要自己换
- 旧名插件与 `StarBotCore.jar` 容器入口都不删，要自己删

配置、凭据与数据都在卷里，容器重建不丢，前提是卷挂在 `/app`
（见[第 3 章](03-install.md)）。

## 配置要不要跟着改

**从 5.4 及以后升级，配置一般直接沿用**，`application.yml` 与 `datasource.json` 保留原样。

**从 5.3 及更早升级**，旧根键 `starbot:` 下的设置不再读取。启动日志会有一条 WARN
说明没被读取、该挪到哪：程序不替你改配置文件，请手工把它们挪到新根键 `novabot:` 下
（还没有新根键的，把 `starbot:` 改名即可），改完重启。最短对照：

```yaml
# 改前（不会被读取）
starbot:
  bilibili:
    account:
      cookie-path: cookies.json

# 改后
novabot:
  bilibili:
    account:
      cookie-path: cookies.json
```

个别整节挪过位置的设置，根键还是 `starbot:` 时挪位那条提醒不会出。先按那条 WARN
把根键改成 `novabot:`、改完重启；下一次启动，还写在旧位置的整节才会被点名。
从上游 StarBot 迁移还有别的差异，见[附录 D](appendix-d-migrate-from-starbot.md)。

## 怎么备份

直播数据可以用一条命令同步到本机另一个目录或另一台机器：

```bash
bash /opt/starbot/tools/data-backup.sh <数据目录> user@备份机:/var/backups/novabot
```

数据目录就是装着 `sessions.jsonl` 与 `details/` 的那个目录，也就是设置里
「直播数据 · 文件路径」所在的目录：默认是程序目录（systemd 装在 `/opt/starbot` 的话
就是 `/opt/starbot`，容器是 `/app`）。同步只增不删——目标上已有的文件不会被去掉；
`reports/` 与 `*.tmp` 不同步。

> [!WARNING] 同步是按目录走的。数据目录若就是程序目录，`cookies.json`、`cookies.key`、
> `application.yml` 会一并被备出去。备份目的地要按存放凭据的标准保管；只想备数据的话，
> 先把数据文件挪进一个子目录、在设置里把「直播数据 · 文件路径」指过去，再备那个子目录。

**每天自动跑一次**：把安装目录下的 `novabot-backup.service` 与 `novabot-backup.timer`
拷到 `/etc/systemd/system/`，改 service 里的目标路径（安装目录若不是 `/opt/starbot`，
脚本路径和数据目录一并改），然后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now novabot-backup.timer
```

默认凌晨 4 点跑，改 `novabot-backup.timer` 里的 `OnCalendar` 可以换时间点。
**恢复**：先停服务，再从备份目录拷回数据与配置，不拷程序件；拷完再启动。

- 数据：`data.json`、`state.json`、`sessions.jsonl`、`snapshots.jsonl`、`event-stream-tokens.jsonl`
  与 `details/`、`timeline/` 两个目录
- 配置：`application.yml`、`datasource.json`、`template-defaults.json`、`cookies.json`、`cookies.key`
- 备份里若有明文迁成加密时留下的 `cookies.json.plain.bak`，不用拷回，程序不读它。它是明文凭据，
  备份只增不删，安装目录里删掉了备份里也还留着——恢复后确认能正常登录，就把备份里那份也删掉
- `NovaBot.jar`、`lib/`、`plugins/`、`plugins-lib/` 是程序件，备份里的是备份那天的旧版，
  整份拷回会把旧 jar 混进新版。但 `plugins/` 里除了上面[用安装脚本升级](#用安装脚本升级)列的
  五个内置插件（以及 5.2 及更早的 `starbot-` 开头旧名插件），别的 jar 都是你自己装的第三方插件，
  要从备份里单独拷回；自己往 `plugins-lib/` 放过的 jar 也单独拷回

订阅名单、命令开关、绑定关系都存在 `state.json` 里，它与数据文件在同一目录，
按上面备数据时会一起带上。换机器时少备了它，群里那些订阅就得重新订一遍。

## 登录凭据会不会掉

**一般不会。** 扫码登录走的是电视端接口，拿到的令牌有效期 180 天，到期前 30 天
程序会自动续期（设置项「登录凭据 · 自动续期」，默认开启）。
启动日志会明说拿到的是哪一种凭据，出问题先看这一行：

- 「已取得可自动续期的登录令牌, 有效期至 X」——正常，不用管
- 「已取得网页端刷新口令」或「未取得任何刷新口令」——退化了，重新扫一次码即可

退化的成因是个已知限制：网页端扫码登录时服务端把持久化刷新口令返回为空，
所以改走了电视端接口。拿不到口令时续期直接跳过，不会有副作用，只是没法自动续。

掉登录的表现是动态推送静默停止（直播推送不受影响），首页健康自检会明确告警，
重新扫码即可。健康自检写着「未取得刷新口令，无法自动续期」的，说明这份凭据是旧版本或
设置项「扫码登录方式」改成 `web` 时扫出来的，到连接页「哔哩哔哩」卡点「退出登录并重新扫码」
即可换成可续期的。频繁掉登录的其它成因见[第 14 章](14-troubleshooting.md#账号相关)。

## 接下来

升级备份都有了，出问题怎么查、去哪问：
[第 14 章　遇到问题怎么办](14-troubleshooting.md)。
