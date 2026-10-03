# 第 13 章　升级与备份

程序会持续改进，这一章讲两件事：怎么把现在的版本换成新版本，怎么把数据备到另一处。
升级前先备份，换坏了随时回得去；平时定期备份，机器坏了数据还在。
两件都是按顺序做下来的事，跟着走就行。

## 升级前先留好这几样

- 备份 `application.yml` 与 `datasource.json`，它们是你的全部配置
- 备份 `cookies.json` 与 `cookies.key`，它们等同于哔哩哔哩账号的完整控制权
- 旁边若还有 `cookies.json.plain.bak`，那是明文迁成加密时留下的明文原件，程序不读它：
  不用备、升级后也不用拷回，确认能正常登录后就把它删掉

登录凭据默认加密后仍写在 `cookies.json` 里，密钥在 `cookies.key`，没有另存一份密文文件，
所以这两个都要备。直播数据不用特意搬：升级换的是 `releases/` 里的程序，配置、数据、
凭据与锁都在安装目录根上，升级不动它们。
工作目录里的 `novabot.lock` 只表示已经有一份程序在这个目录运行，`novabot-container.lock` 只表示已经有一个容器在用这个数据卷。这两个都不必备份；程序停着的时候可以删，下次启动会重新写一份。

## 一键升级

Linux 一键安装过的机器，重新跑一遍安装脚本就是升级：

```bash
cd NovaBot
git pull
./install.sh
```

程序装在安装目录的 `releases/<版本>/` 下。这一跑会把新版装进 `releases/<新版本>/`，
上一版原样留在 `releases/<上一版>/`，更旧的版本目录删掉，正在跑的那一版也留着。
配置、数据、凭据与锁在安装目录根上，不随版本走。要装的版本已有实例在跑时，
脚本会停下不装——先把那个实例停掉再跑。

脚本不停也不起任何服务。同一个安装目录同时只跑一份：升级时先停旧版本，再起新版本，
中间会断一小会儿。旧版本还在跑时先起了新版本的话，新版本起不来，日志里会写
「这个目录已有一份 NovaBot 在运行」；停掉旧版本后十来秒内它会自己起来：

```bash
sudo systemctl stop novabot@<上一版>
sudo systemctl start novabot@<新版本>
sudo journalctl -u novabot@<新版本> -f
```

服务单元是模板单元 `novabot@.service`，实例名就是版本号。脚本每次安装都会
重写模板单元，并只把新版本设为开机自启。

**安装目录根上的 `plugins/` 与 `plugins-lib/` 留给你自己放第三方插件与依赖。**
升级不碰这两个目录；发行包自带的内置插件与依赖在各版本目录里
（`releases/<版本>/plugins/`、`releases/<版本>/plugins-lib/`），随版本换新。
根上放的插件与依赖，每个版本启动时都会一起加载。

## 老用户第一次升级

旧版的一键安装是扁平布局：程序直接放在安装目录根上。从那种机器升上来、
第一次跑安装脚本时，脚本会先把旧程序搬进 `releases/<旧版本>/`（旧版本号从旧的
`NovaBot.jar` 里读），再装新版。内置插件、自带依赖与 `lib/`、`tools/` 等都搬过去；
更早的 `StarBotCore.jar` 也一并挪进旧版本目录——它作为上一版的一部分留着，
再升一版才会跟着那个目录被删。

搬走的是与新包同构件名的旧 jar（构件名就是去掉版本号的那截文件名），其余——
自己放的第三方插件与依赖、改过名的老内置件——不会被搬，留在根上。收尾列出的
「根上留下的使用者插件」就是根上 `plugins/`、`plugins-lib/` 里剩下的全部 jar。
留下的这些启动时仍会被加载，与版本目录里新版自带的并存——确认不用的、与新版
自带依赖同名的，自己删掉，免得新旧两份一起加载。从 **5.2 及更早**升上来时
主程序与内置插件还叫旧名字，搬不走、也盖不掉，这些也在名单里，要自己删：

- `starbot-onebot-adapter-1.0.0.jar`
- `starbot-onebot-adapter-napcat-extension-1.0.0.jar`
- `starbot-bilibili-1.0.0.jar`
- `starbot-novabot-console-1.0.0.jar`
- `starbot-report-1.0.0.jar`

已发布的 5.2.0 及更早只有前三个插件 jar；后两个只在 5.3.0 发布前、目录还没改名的构建里有。

旧单元 `novabot.service`（更早的 `starbot.service`）只关掉开机自启：正在跑的不停、
单元文件留着，等你自己停；没在跑的删掉单元文件。旧单元上的覆盖设置不带到新单元，
收尾会列出这些目录——要继续用（比如调过的内存上限），按
[附录 A](appendix-a-measurements.md#内存偏高时怎么量、怎么调) 的做法在模板单元上重设。

这一次与分目录之间的升级一样，都是先停旧版本、再起新版本，中间会断一小会儿；
特别的是顺序不能反：旧版本还没有单实例锁，新版本起来时不会等旧版本放开，
两份会同时连直播间、抢端口：

```bash
sudo systemctl stop <旧单元>
sudo systemctl start novabot@<新版本>
sudo journalctl -u novabot@<新版本> -f
```

具体停哪个、先做哪一步，收尾提示会写明。

## 上一版还在

升级后上一版没有被删：程序在 `releases/<上一版>/`，对应的实例是 `novabot@<上一版>`。
新版本有问题想切回去，先照本章备份一次，然后停当前版本、起上一版，并把开机自启
换到上一版：

```bash
sudo systemctl stop novabot@<当前版本>
sudo systemctl start novabot@<上一版>
# 确认上一版起来了之后
sudo systemctl disable novabot@<当前版本>
sudo systemctl enable novabot@<上一版>
```

## 手工升级

不用脚本的机器（macOS、Windows，或不想让脚本碰系统的），目录不分版本，
程序、配置与数据都在同一个目录里，照这个顺序做：

1. 停止服务
2. 备份 `application.yml`、`datasource.json`、`cookies.json`、`cookies.key`
3. 用新版本的产物整个换掉 `NovaBot.jar` 与 `lib/`
4. `plugins-lib/` 里新包自带的 jar 按名字删旧放新，自己放的留下
5. `plugins/` **不要整个替换**，只按名字换这五个内置插件并删旧版
   （`<版本>` 处换成新版本号）：
   - `nova-onebot-adapter-<版本>.jar`
   - `nova-onebot-adapter-napcat-extension-<版本>.jar`
   - `nova-bilibili-<版本>.jar`
   - `nova-console-<版本>.jar`
   - `nova-report-<版本>.jar`
6. 保留原有的 `application.yml` 与 `datasource.json`
7. 启动，看日志有没有告警

## 容器怎么升级

容器里的目录也不分版本。换新镜像、重建容器就是升级。容器每次启动都会用镜像里的
`NovaBot.jar` 与 `lib/` 盖掉卷 `/app` 上的同名文件，并按新文件名换内置插件；
`plugins-lib/` 里镜像自带的依赖也按构件名换新，自己放的留下（与上面手工升级
第 4 步同一个规则）。要自己动手的：

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
「直播数据 · 文件路径」所在的目录：一键安装默认在安装目录根上（装在 `/opt/starbot`
的话就是 `/opt/starbot`，程序在它下面的 `releases/` 里），手动安装就是程序目录，
容器是 `/app`。同步只增不删——目标上已有的文件不会被去掉；`reports/`、`releases/`
与 `*.tmp` 不同步，`releases/` 里是各版本的程序，不算直播数据。

> [!WARNING] 同步是按目录走的。数据目录若与配置、凭据在同一个目录（三种装法默认都是），
> `cookies.json`、`cookies.key`、`application.yml` 会一并被备出去。备份目的地要按
> 存放凭据的标准保管；只想备数据的话，先把数据文件挪进一个子目录、在设置里把
> 「直播数据 · 文件路径」指过去，再备那个子目录。

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
- 程序件别拷回：一键安装的程序在 `releases/` 下，备份不同步 `releases/`，本来就带不上；
  手动安装与容器的备份里会带着备份那天的 `NovaBot.jar`、`lib/`、`plugins/`、
  `plugins-lib/`，整份拷回会把旧 jar 混进新版
- 安装目录根上的 `plugins/` 与 `plugins-lib/`（手动安装则是 `plugins/` 里除了
  [手工升级](#手工升级)列的五个内置插件之外）是你自己装的第三方插件与依赖，
  恢复时要从备份里拷回；5.2 及更早的 `starbot-` 开头旧名插件确认不用就删掉，别拷回

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
