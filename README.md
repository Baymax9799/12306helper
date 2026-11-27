# 12306Helper

12306 抢票工具的无界面版本，跑在 Docker 里，用浏览器操作。原项目 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper) 是 JavaFX 桌面程序，这里把界面拆了，换成 Web 服务和一套 HTTP 接口。

为什么要拆：抢票要守到放票那一刻，桌面程序得开着电脑；换成常驻服务，扔在 NAS 上就行，手机也能看。

## 现在能做什么

| 功能 | 状态 |
|---|---|
| 扫码登录 / 短信验证码登录 / 粘贴 Cookie | 可用 |
| 余票查询，带各席别票价 | 可用 |
| 乘车人同步（本地只存掩码，不存身份证号） | 可用 |
| 建任务：多车次、多日期、多席别、多乘客 | 可用 |
| 定时开抢：提前 30 秒预热，放票后 60 秒内 300ms 一轮 | 可用 |
| 自动提交订单，停在「待支付」 | 已实现，参数需实盘对一次 |
| 邮件 / 微信 / Webhook 通知 | 可用 |
| 候补下单 | 默认关闭，见文末 |

抢到之后不会替你付款，也不会替你取消订单。这两件事都得人在 12306 App 里做，原因在下面「关于每日额度」。

## 部署

镜像端口固定 28080，宿主机端口由 `HOST_PORT` 决定，默认也是 28080。撞端口就改 `.env`，不用动 compose 文件。

### 方式一：本地构建（现在就能用）

```bash
git clone https://github.com/Baymax9799/12306Helper.git
cd 12306Helper
cp .env.example .env          # 可选。想改端口或指定口令就改这里
docker compose up -d --build
docker compose logs 12306helper | grep 口令
```

构建要多等几分钟，Maven 要下依赖。起来后浏览器打开 `http://<NAS地址>:28080`。

`.env` 里 `ADMIN_PASSWORD` 留空的话，首次启动会随机生成一个 16 位口令，上面那条 `logs | grep 口令` 就是取它。日志里找不到，说明之前已经初始化过，口令存在 `data/settings.json` 里（bcrypt 哈希，取不出明文）。

口令的优先级是：**环境变量 > `data/settings.json` 里的旧哈希 > 随机生成**。所以容器环境变量里改了 `ADMIN_PASSWORD`，重启容器就生效，会覆盖之前的旧口令——不是只有首次启动才算数。忘了口令又不想重建数据，就在环境变量里指定一个新的重启。

### 方式二：从 Docker Hub 拉

镜像地址是 `baymax9799/12306helper:latest`。`docker-compose.hub.yml` 只拉镜像、不编译，NAS 上不需要源码、JDK 和 Maven：

```bash
docker compose -f docker-compose.hub.yml up -d
```

前提是镜像已经发布过（见下面「发布镜像到 Docker Hub」）。仓库如果是 Public，拉取不需要登录；如果设成了 Private，先 `docker login` 再拉。

Docker Hub 用户名不是 `baymax9799` 的话，在 `.env` 里改一行：

```bash
IMAGE=你的用户名/12306helper:latest
```

### 在 NAS 的图形界面里创建

镜像里没有写任何 `ENV`，所以创建容器时环境变量表是空的，不需要照抄一堆默认值。你只要设两处：

| 项目 | 填什么 |
|---|---|
| 端口映射 | 容器 `28080` → 宿主自选，比如 `28080` |
| 存储 / 挂载 | 容器 `/data` → 宿主绝对路径，比如 `/vol1/1000/docker/12306helper/data` |

不要填 `./data` 这种相对路径，图形界面解析不了，容器会创建失败或起来后立刻退出。目录要先存在并给 uid 1000 写权限：

```bash
mkdir -p /vol1/1000/docker/12306helper/data
chown -R 1000:1000 /vol1/1000/docker/12306helper/data
```

口令可以留空（看日志里的随机口令），也可以在环境变量里加一条 `ADMIN_PASSWORD=你的口令`。

### 目录权限

容器用 uid 1000 跑。`data/` 目录在仓库里有个占位文件，clone 下来就存在，但如果它是 Docker 在你第一次 `up` 时创建的，属主会是 root，服务写不进数据库，表现是启动后反复重启。修法：

```bash
sudo chown -R 1000:1000 data
docker compose restart 12306helper
```

## 发布镜像到 Docker Hub

两条路，选一条。仓库名固定为 `12306helper`，和 `.env.example` 里的 `IMAGE` 对应。

### 路线一：本机构建后推

```bash
docker login -u baymax9799
docker build -t baymax9799/12306helper:latest .
docker push baymax9799/12306helper:latest
```

构建阶段要下 Maven 依赖，国内网络会很慢，可以指定镜像源：

```bash
docker build \
  --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public \
  -t baymax9799/12306helper:latest .
```

第一次 push 会在 Docker Hub 自动创建仓库，**新仓库的默认可见性可能是 Private**。去 <https://hub.docker.com/r/baymax9799/12306helper/settings> 改成 Public，否则 NAS 上不登录就拉不下来。

### 路线二：打 tag 交给 GitHub Actions

仓库里的 `.github/workflows/docker-publish.yml` 会在推送 `v*` tag 时自动构建并推送 `latest` 和该 tag。

1. Docker Hub → Account settings → Security → New Access Token，权限选 Read & Write，复制出来（只显示一次）。
2. GitHub 仓库 → Settings → Secrets and variables → Actions：
   - **Variables** 页签 → New repository variable：`DOCKERHUB_USERNAME` = `baymax9799`（用户名不算机密，放变量里方便查看）
   - **Secrets** 页签 → New repository secret：`DOCKERHUB_TOKEN` = 上一步的 token（必须放机密，日志里不会显示）
3. 打 tag 并推送：

```bash
git tag v0.1.0
git push origin v0.1.0
```

去仓库的 Actions 页看构建进度，完成后 Docker Hub 上就有 `latest` 与 `v0.1.0` 两个标签。

以后发新版：改代码 → 提交 → 打新 tag（`v0.1.1`）→ 推 tag。`latest` 会跟着更新，旧 tag 保留。

Docker Hub 免费账号对匿名拉取有频率限制（每 6 小时 100 次），个人自用够。

## 用起来

1. 输管理员口令进页面。
2. 点「扫码登录」，手机 12306 扫二维码。人不在家、手边只有手机的时候，用下面的「短信验证码登录」——密码只在这次请求里用一下，不落盘。
3. 点「同步乘车人」，把 12306 里的乘车人拉过来。本地只存 `张*` 和 `****1234` 这种掩码。
4. 在「查余票」里看一眼目标车次和票价，确认要抢的是哪几趟。
5. 建任务：填起终站、日期、席别、乘车人。要抢放票就填「开抢时刻」，到点自己提速。
6. 决定要不要开自动下单。不开就是监控模式，查到票只发通知。

## 关于每日额度

12306 的规则：一个自然日里，未支付或者取消的订单加起来超过 3 次，账号当天的线上购票权限就被锁。

所以这个服务在抢到第一单之后会暂停**所有**任务，等你去处理。它也不替你取消订单——每取消一次就烧掉三分之一额度，还可能弹人脸核验，无头环境过不去。

每日下单上限默认 1，设置里可以调到 3。我建议留 1。

抢到票的处理顺序：收到通知 → 去 12306 付款 → 回来点「我已付款」解锁。

## 配置

端口和口令走 `.env`，其余在页面「设置」里改。

| 变量 | 默认 | 说明 |
|---|---|---|
| `IMAGE` | `baymax9799/12306helper:latest` | 镜像地址，用户名不同就改这里 |
| `HOST_PORT` | `28080` | 宿主机端口，只影响 compose 的端口映射 |
| `DATA_DIR` | `./data` | 宿主机上的数据目录；NAS 上请写绝对路径 |
| `ADMIN_PASSWORD` | 空 | 留空则首次启动随机生成并打印；填了就以它为准 |
| `WAITLIST_ENABLED` | `false` | 候补下单开关 |

镜像内部还有两个默认值，需要时才用 `-e` 覆盖：端口 `PORT=28080`、数据目录容器内为 `/data`。JVM 参数默认 `-Xms128m -Xmx384m`，要调就设 `JAVA_OPTS`；时区已经写进镜像的 `/etc/localtime`，不依赖 `TZ` 变量。

SMTP、微信推送、Webhook 在页面里填。密码和 Key 用 AES-GCM 加密后写进 `data/settings.json`，密钥在 `data/secret.key`，权限 600。备份 `data/` 就等于备份了全部状态（包括 12306 的登录 Cookie，别随便传）。

微信推送走的是 Server酱 / PushPlus / 企业微信机器人。个人申请不到能发模板消息的公众号，这三条是等价的替代品，一个 Key 就能通。

## 已知问题

**下单参数要实盘对一次。** 六个接口（`submitOrderRequest` → `initDc` → `checkOrderInfo` → `getQueueCount` → `confirmSingleForQueue` → `queryOrderWaitTime`）的顺序和参数名是照公开实现写的，有回放测试，但 12306 改版频繁。第一次真用请开着日志看。

**席别列 24/25/26/30 的含义有歧义。** 公开资料在「软座还是无座」「高级软卧还是特等座」上互相矛盾。这里沿用了上游作者的映射，代码里集中在 `SeatCatalog` 一张表，实盘发现错位改一行就行。

**票价只覆盖有票的席别。** 数据来自 `queryI` 响应里的票价段（按 10 字符一组切，首位席别代码 + 6 位分），售完或不存在的席别没有价格可显示。

**候补默认不提交。** `confirmHB` 的参数是几段拼装字符串，猜错可能触发风控，所以默认只在页面提示、退化为普通抢票。要试就设 `WAITLIST_ENABLED=true`，并且知道自己在做什么。

**单账号、仅内网。** 表结构留了 `account_id`，加多账号是加法。服务不处理 HTTPS，要公网访问自己接反向代理，记得把 Cookie 的 `Secure` 打开。

## 开发

```bash
mvn test        # 87 个用例，全用 MockWebServer / GreenMail，不碰真实 12306
mvn package     # target/12306helper.jar
java -jar target/12306helper.jar
```

本地跑要三个环境变量：`DATA_DIR`、`PORT`、`ADMIN_PASSWORD`。

代码分四层，依赖方向单向：`core`（HTTP、登录、解析）← `engine`（调度、下单、闸门）← `web`（接口和页面）← `app`（装配）。

```
src/main/java/com/jactil/12306helper/
├── app/      入口
├── core/     client · auth · query · station · passenger · store · config · security · time
├── engine/   调度 · 状态机 · 下单 · 闸门 · 对账 · 候补
├── notify/   邮件 · 微信 · Webhook
└── web/      API 与装配
```

## 许可

MIT。派生自 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)，原项目版权归 jactil 所有，见 [LICENSE](./LICENSE)。

只给本人买票用。别拿它代购、倒票、开多账号批量刷，12306 的用户协议也不允许自动化工具。
