# 12306Helper

12306 抢票的无界面版本。上游 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper) 是 JavaFX 桌面程序，把界面拆掉改成 Web 服务，就能挂在 NAS 上一直跑，用浏览器操作。

一开始想改的是 [Bypass](https://github.com/Magic2510/Bypass)，但那个仓库里只有一个编译好的 .NET exe，没有源码。JavaFx 版有源码，而且 `service`、`util`、`model` 这几个包不依赖 JavaFX，拆起来省事，就从它改。

抢到票就停。不自动付款，也不替你取消订单。

## 能用的和还没验证的

登录有三种：扫码、短信验证码、粘贴 Cookie，都是能用的。短信那条是为了人在外面、手边只有手机的情况——密码只在当次请求里用一下，不落盘。

余票查询带各席别票价。乘车人从 12306 同步，本地只存 `张*`、`****1234` 这种掩码。任务可以同时盯多车次、多日期、多席别、多名乘客；设了开抢时刻的话，放票前 30 秒开始预热，放票后 60 秒内按 300ms 一轮查。

通知支持邮件、Server酱 / PushPlus / 企业微信机器人、通用 Webhook。

自动下单的代码写完了，参数照着公开实现来的，还没用真账号验过。候补接口也有，默认关着。

## 部署

### 拉现成镜像（推荐）

镜像是公开的，不用登录：

```bash
docker compose -f docker-compose.hub.yml up -d
```

`latest` 和 `v0.1.0` 两个标签都在。

镜像只出了 amd64。x86_64 的机器直接用——群晖 DS918+、威联通 TS-x51、自建 E3 这些都行；ARM 机型（DS220j、树莓派之类）拉下来会报 `exec format error`，得自己 build，见下。

Docker Hub 用户名不是 `baymax9799` 的话，`.env` 里改一行 `IMAGE`。

### 本地构建

```bash
git clone https://github.com/Baymax9799/12306Helper.git
cd 12306Helper
cp .env.example .env          # 可选，想改端口或指定口令就改这里
docker compose up -d --build
docker compose logs 12306helper | grep 口令
```

国内下 Maven 依赖很慢，可以先单独 build 一次，带上阿里云镜像源：

```bash
docker build --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public \
  -t baymax9799/12306helper:latest .
docker compose up -d
```

ARM 再加 `--platform linux/arm64`。首次构建要等几分钟。起来后浏览器开 `http://<NAS地址>:28080`。发布新版本镜像的步骤写在 [docs/PUBLISH.md](docs/PUBLISH.md)。

### 图形界面里创建容器

镜像里没写任何 `ENV`，所以创建容器时环境变量表是空的，不用照抄默认值。

端口映射填容器 `28080`；存储把容器 `/data` 挂到宿主的绝对路径，比如 `/vol1/1000/docker/12306helper/data`。

挂载别填 `./data`。图形界面按字面解析相对路径，容器会创建失败或者起来就退出。目录先建出来，给 uid 1000 写权限：

```bash
mkdir -p /vol1/1000/docker/12306helper/data
chown -R 1000:1000 /vol1/1000/docker/12306helper/data
```

口令可以留空，日志里看随机生成的那个；也可以在环境变量加一条 `ADMIN_PASSWORD=你的口令`。

### 目录权限

容器以 uid 1000 运行。`data/` 如果是 Docker 第一次 `up` 时自动建的，属主会是 root，服务写不进数据库，表现是启动后一直重启：

```bash
sudo chown -R 1000:1000 data
docker compose restart 12306helper
```

## 口令

`.env` 里 `ADMIN_PASSWORD` 留空，首次启动会随机生成 16 位并打到日志里，就是上面那条 `grep 口令`。

改了环境变量重启就生效，会覆盖旧口令。优先级是环境变量 > `data/settings.json` 里的旧哈希 > 随机生成。早先的版本只在首次启动读环境变量，之后再改就进不去——那个 bug 在 v0.1.0 之后修了，用旧镜像的话拉新的。

忘了口令又不想重建数据，就在环境变量里指定一个新的再重启。

## 用起来

1. 输管理员口令进页面。
2. 点「扫码登录」，用手机 12306 扫。人不在家、身边只有手机时，换下面的「短信验证码登录」。
3. 点「同步乘车人」，把 12306 里的列表拉过来。
4. 在「查余票」里看一眼目标车次和票价，确定抢哪几趟。
5. 建任务：起终站、日期、席别、乘车人。要抢放票就填「开抢时刻」，到点它自己提速。
6. 决定要不要开自动下单。不开就是监控模式，查到票只发通知。

## 每日额度

12306 的规则：自然日里未支付或者取消的订单合计超过 3 次，账号当天的线上购票权限被锁。

所以抢到第一单之后，这个服务会暂停所有任务，等你去处理。它也不替你取消订单——取消一次就烧掉三分之一额度，还可能弹人脸核验，无头环境过不去。

每日下单上限默认 1，设置里能调到 3。我留在 1。

处理顺序：收到通知 → 去 12306 付款 → 回来点「我已付款」解锁。

## 配置

端口和口令走 `.env`，其余在页面「设置」里改。

| 变量 | 默认 | 说明 |
|---|---|---|
| `IMAGE` | `baymax9799/12306helper:latest` | 镜像地址，用户名不同就改这里 |
| `HOST_PORT` | `28080` | 宿主机端口，只影响 compose 的端口映射 |
| `DATA_DIR` | `./data` | 宿主机上的数据目录；NAS 上写绝对路径 |
| `ADMIN_PASSWORD` | 空 | 留空则首次启动随机生成并打印；填了就以它为准 |
| `WAITLIST_ENABLED` | `false` | 候补下单开关 |

镜像里还有两个默认值，要改才用 `-e` 覆盖：端口 `PORT=28080`，容器内数据目录 `/data`。JVM 参数默认 `-Xms128m -Xmx384m`，调就设 `JAVA_OPTS`；时区已经写进镜像的 `/etc/localtime`，不靠 `TZ`。

SMTP、微信、Webhook 在页面里填。密码和 Key 用 AES-GCM 加密后写进 `data/settings.json`，密钥在 `data/secret.key`，权限 600。备份 `data/` 等于备份所有状态，里面也有 12306 的登录 Cookie，别随便传。

微信推送走 Server酱 / PushPlus / 企微机器人。个人申请不到能发模板消息的公众号，这三个是等价替代，一个 Key 就通。

## 参与测试

现在还缺的是有人用真账号跑一遍。下面几处只有实盘能确认：

- 自动下单的六步接口，参数和 12306 当前版本对不对得上
- 席别列 24/25/26/30 的归属：软座还是无座，高级软卧还是特等座
- 候补提交（`WAITLIST_ENABLED=true`）
- QQ 邮箱 / 163 / Gmail 的 SMTP 参数，Server酱 / PushPlus / 企微的实际到达
- ARM 自建镜像能不能跑

开 Issue 时带上这些，不然很难定位：镜像标签（`v0.1.0` 或 `latest`）、部署方式、`docker compose logs 12306helper --tail 200` 的输出、复现步骤（点了几次、什么车次、什么时间）。下单失败的话，把日志里 12306 返回的那段原文也贴出来。

日志里可能有 `RAIL_DEVICEID`、`_passport_session`，那是你的登录态，贴之前替换掉；手机号和身份证号一并抹去。

## 已知问题

下单参数要实盘对一次。六个接口（`submitOrderRequest` → `initDc` → `checkOrderInfo` → `getQueueCount` → `confirmSingleForQueue` → `queryOrderWaitTime`）的顺序和参数名是照公开实现写的，有回放测试，但 12306 改版频繁，第一次真用请开着日志看。

席别列 24/25/26/30 的含义有歧义。公开资料在「软座还是无座」「高级软卧还是特等座」上互相矛盾，这里沿用上游作者的映射，集中在 `SeatCatalog` 一张表里，实盘发现错位改一行就行。

票价只覆盖有票的席别。数据来自 `queryI` 响应里的票价段（10 字符一组，首位席别代码加 6 位分），售完或者不存在的席别没有价格。

候补默认不提交。`confirmHB` 的参数是几段拼装字符串，猜错可能触发风控，所以默认只在页面提示、退化成普通抢票。要试就设 `WAITLIST_ENABLED=true`。

单账号，只按内网部署。表结构留了 `account_id`，以后加多账号是加法。服务不处理 HTTPS，要上公网自己接反代，记得把 Cookie 的 `Secure` 打开。

## 开发

```bash
mvn test        # 96 个用例，全用 MockWebServer / GreenMail，不碰真实 12306
mvn package     # target/12306helper.jar
java -jar target/12306helper.jar
```

本地跑要设 `DATA_DIR`、`PORT`、`ADMIN_PASSWORD`。

代码分四层，依赖单向：`core`（HTTP、登录、解析）← `engine`（调度、下单、闸门）← `web`（接口和页面）← `app`（装配）。

```
src/main/java/com/jactil/tickethelper/
├── app/      入口
├── core/     client · auth · query · station · passenger · store · config · security · time
├── engine/   调度 · 状态机 · 下单 · 闸门 · 对账 · 候补
├── notify/   邮件 · 微信 · Webhook
└── web/      API 与装配
```

## 许可

MIT，派生自 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)，原项目版权归 jactil，见 [LICENSE](./LICENSE)。

用途限于给本人买票。代购、倒票、多账号批量刷都不在设计目标里，12306 的用户协议也不允许自动化工具。
