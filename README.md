# tickethelper-linux

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
git clone https://github.com/Baymax9799/tickethelper-linux.git
cd tickethelper-linux
cp .env.example .env          # 可选。想改端口或指定口令就改这里
docker compose up -d --build
docker compose logs tickethelper | grep 口令
```

构建要多等几分钟，Maven 要下依赖。起来后浏览器打开 `http://<NAS地址>:28080`。

`.env` 里 `ADMIN_PASSWORD` 留空的话，首次启动会随机生成一个 16 位口令，上面那条 `logs | grep 口令` 就是取它。日志里找不到，说明之前已经初始化过，口令存在 `data/settings.json` 里（bcrypt 哈希，取不出明文）——忘了就删 `data/settings.json` 重来。

### 方式二：从 Docker Hub 拉

`docker-compose.hub.yml` 只拉镜像、不编译，适合不想在 NAS 上装源码的场景：

```bash
docker compose -f docker-compose.hub.yml up -d
```

**这个文件目前还用不了**——我手上没有你的 Docker Hub 账号，镜像没推上去过。要启用它，先在你的机器上推一次：

```bash
docker login
docker build -t baymax9799/tickethelper-linux:latest .
docker push baymax9799/tickethelper-linux:latest
```

或者打个 tag 交给 GitHub Actions。仓库里 `.github/workflows/docker-publish.yml` 会在推 `v*` tag 时自动构建并推送，需要在 Settings → Secrets 里加 `DOCKERHUB_USERNAME` 和 `DOCKERHUB_TOKEN` 两个变量。

### 目录权限

容器用 uid 1000 跑。`data/` 目录在仓库里有个占位文件，clone 下来就存在，但如果它是 Docker 在你第一次 `up` 时创建的，属主会是 root，服务写不进数据库，表现是启动后反复重启。修法：

```bash
sudo chown -R 1000:1000 data
docker compose restart tickethelper
```

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
| `HOST_PORT` | `28080` | 宿主机端口 |
| `ADMIN_PASSWORD` | 空 | 留空则首次启动随机生成并打印 |
| `JAVA_OPTS` | `-Xms128m -Xmx384m` | 单账号多任务够用 |
| `TZ` | `Asia/Shanghai` | 影响每日额度的结算日 |
| `WAITLIST_ENABLED` | `false` | 候补下单开关 |

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
mvn package     # target/tickethelper-linux.jar
java -jar target/tickethelper-linux.jar
```

本地跑要三个环境变量：`DATA_DIR`、`PORT`、`ADMIN_PASSWORD`。

代码分四层，依赖方向单向：`core`（HTTP、登录、解析）← `engine`（调度、下单、闸门）← `web`（接口和页面）← `app`（装配）。

```
src/main/java/com/jactil/tickethelper/
├── app/      入口
├── core/     client · auth · query · station · passenger · store · config · security · time
├── engine/   调度 · 状态机 · 下单 · 闸门 · 对账 · 候补
├── notify/   邮件 · 微信 · Webhook
└── web/      API 与装配
```

## 许可

MIT。派生自 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)，原项目版权归 jactil 所有，见 [LICENSE](./LICENSE)。

只给本人买票用。别拿它代购、倒票、开多账号批量刷，12306 的用户协议也不允许自动化工具。
