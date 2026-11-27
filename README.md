<div align="center">

# 12306Helper

### 12306 抢票 · 扫码登录 · 定时开抢 · 抢到即停

**无界面 Web 服务 | Docker 一键部署 | 支持 NAS | 邮件与微信通知**

[![GitHub stars](https://img.shields.io/github/stars/Baymax9799/12306Helper?style=for-the-badge&logo=github)](https://github.com/Baymax9799/12306Helper/stargazers)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/baymax9799/12306helper?style=for-the-badge&logo=docker&logoColor=white)](https://hub.docker.com/r/baymax9799/12306helper)
[![Java](https://img.shields.io/badge/Java-17-orange?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)

> 抢到票就停，不自动付款。代码全部公开，数据只留在你自己的机器上。

</div>

---

## 功能特性

- **三种登录方式** — 扫码登录为主，手机 12306 扫一下就进；人在外面只有一部手机时用短信验证码登录，密码只在当次请求里用一下、不落盘；实在不行还能粘贴浏览器 Cookie
- **余票查询带票价** — 按车次列出各席别余票与票价，抢之前先看清要买哪趟
- **多任务并行** — 一个任务可同时盯多个车次、多个日期、多个席别、多名乘客；任务之间共享全局限速，不叠加刷 12306
- **定时开抢** — 填好开抢时刻，提前 30 秒预热，放票后 60 秒内按 300ms 一轮查询，之后自动退避
- **自动下单到「待支付」** — 命中余票自动走完六个下单接口，拿到订单号立刻停下并通知，**永远不碰支付**
- **账号闸门** — 12306 一个自然日里未支付或取消超过 3 单会锁账号，所以抢到第一单就暂停所有任务，每日下单上限默认 1
- **订单对账** — 启动时和每 60 秒查一次 12306 未完成订单，发现本地没记录的孤儿单立刻停手
- **通知** — 邮件（SMTP）、微信（Server酱 / PushPlus / 企业微信机器人）、通用 Webhook，失败重试、5 分钟内去重
- **隐私** — 乘车人只存掩码（`张*`、`****1234`），不存身份证号；SMTP 密码与微信 Key 用 AES-GCM 加密落盘
- **无界面** — 一个容器一个网页，手机也能操作，没有 VNC、没有桌面环境

---

## 🐳 一键部署

**最快的方式，一条命令拉起**（把数据目录换成你自己的绝对路径）：

```bash
docker run -d --name 12306helper --restart unless-stopped \
  -p 28080:28080 \
  -v /vol1/1000/docker/12306helper/data:/data \
  -e ADMIN_PASSWORD=你的口令 \
  baymax9799/12306helper:latest
```

> 首次执行会看到 `Unable to find image 'baymax9799/12306helper:latest' locally`——这是 Docker 在说「本地没有，我去远端拉」，不是报错。下一行开始是真正的下载进度，拉完自动启动。

**部署前先建好数据目录**，容器以 uid 1000 运行：

```bash
mkdir -p /vol1/1000/docker/12306helper/data
chown -R 1000:1000 /vol1/1000/docker/12306helper/data
```

启动后浏览器打开 `http://<NAS地址>:28080`，用 `ADMIN_PASSWORD` 登录。口令留空的话，首次启动会随机生成一个 16 位口令并打到日志：

```bash
docker logs 12306helper | grep 口令
```

**镜像只提供 `linux/amd64`**（Intel / AMD 的 x86_64）。ARM 机型（树莓派、DS220j 之类）会报 `exec format error`，需要用下面的方式自己构建。

---

## Docker Compose 部署

仓库里带两份 compose，`h` 结尾那份只拉镜像不编译。

```bash
# 只拉现成镜像
curl -O https://raw.githubusercontent.com/Baymax9799/12306Helper/main/docker-compose.hub.yml
docker compose -f docker-compose.hub.yml up -d
```

`docker compose -f` 只认当前目录下的文件，所以别在 `/root` 之类的目录里直接执行，要么先 `curl -O` 下来，要么 clone 仓库。

**从源码构建**（ARM 机型走这条）：

```bash
git clone https://github.com/Baymax9799/12306Helper.git
cd 12306Helper
cp .env.example .env
docker compose up -d --build
```

国内下 Maven 依赖慢，建议单独 build 一次带镜像源：

```bash
docker build --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public \
  -t baymax9799/12306helper:latest .
docker compose up -d
```

ARM 再加 `--platform linux/arm64`。

---

## 快速开始

1. 打开 `http://<NAS地址>:28080`，输管理员口令进页面
2. 点「扫码登录」，用手机 12306 扫码；只有一部手机时用「短信验证码登录」
3. 点「同步乘车人」，把 12306 里的乘车人列表拉过来（本地只存掩码）
4. 在「查余票」里看一眼目标车次和票价，确定抢哪几趟
5. 建任务：起终站、日期、席别、乘车人；要抢放票就填「开抢时刻」
6. 决定要不要开自动下单。不开就是监控模式，查到票只发通知

抢到票之后不要关页面，日志里会给出订单号，去 12306 App 付款，回来点「我已付款」解锁闸门。

---

## API 接口

页面上的功能都有对应接口，可以自己写脚本调。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/healthz` | 健康检查 |
| `POST` | `/api/auth/admin` | 管理员口令登录 |
| `GET` | `/api/auth/me` | 当前登录态 |
| `POST` | `/api/auth/qr` | 生成 12306 登录二维码（base64） |
| `GET` | `/api/auth/qr/{uuid}` | 轮询扫码状态 |
| `POST` | `/api/auth/sms/send` | 发送短信验证码 |
| `POST` | `/api/auth/sms/login` | 短信验证码登录 |
| `POST` | `/api/auth/cookie` | 粘贴 Cookie 导入会话 |
| `POST` | `/api/query` | 立即查询余票（带票价） |
| `GET` | `/api/stations/search` | 车站补全 |
| `GET` | `/api/passengers` | 乘车人列表（掩码） |
| `POST` | `/api/passengers/sync` | 从 12306 同步乘车人 |
| `GET` `POST` | `/api/tasks` | 任务列表 / 新建任务 |
| `GET` `PUT` `DELETE` | `/api/tasks/{id}` | 查 / 改 / 删单个任务 |
| `POST` | `/api/tasks/{id}/start` | 启动任务 |
| `POST` | `/api/tasks/{id}/stop` | 停止任务 |
| `GET` | `/api/orders` | 订单列表 |
| `POST` | `/api/orders/reconcile` | 手动触发一次订单对账 |
| `GET` | `/api/gate` | 闸门状态与今日额度 |
| `POST` | `/api/gate/unlock` | 我已付款 / 已取消，解锁 |
| `GET` | `/api/events?after=` | 增量拉事件日志 |
| `GET` `PUT` | `/api/settings` | 读取 / 保存设置 |
| `POST` | `/api/settings/test-notify` | 发一条测试通知 |
| `GET` `POST` | `/api/booking` | 查看 / 开关自动下单 |
| `GET` | `/api/meta` | 席别列表、间隔上下限等元数据 |

所有 `/api/*` 需要管理员会话（`sid` Cookie）。只有 `/api/auth/admin` 和 `/healthz` 是公开的。

---

## 配置说明

端口和口令走 `.env`，其余在页面「设置」里改。

| 变量 | 默认 | 说明 |
|------|------|------|
| `IMAGE` | `baymax9799/12306helper:latest` | 镜像地址，用户名不同就改这里 |
| `HOST_PORT` | `28080` | 宿主机端口，只影响 compose 的端口映射 |
| `DATA_DIR` | `./data` | 宿主机上的数据目录；NAS 上写绝对路径 |
| `ADMIN_PASSWORD` | 空 | 留空则首次启动随机生成并打印；填了就以它为准 |
| `WAITLIST_ENABLED` | `false` | 候补下单开关 |
| `JAVA_OPTS` | `-Xms128m -Xmx384m` | JVM 参数，需要时用 `-e` 覆盖 |
| `PORT` | `28080` | 容器内端口，一般不用改 |

时区已经写进镜像的 `/etc/localtime`，不靠 `TZ` 变量。SMTP、微信、Webhook 在页面里填，密码和 Key 加密后写进 `data/settings.json`，密钥在 `data/secret.key`。备份 `data/` 等于备份全部状态——里面也有 12306 的登录 Cookie，别随便传。

---

## 常见问题

<details>
<summary><b>提示 <code>Unable to find image ... locally</code></b></summary>

不是错误。Docker 启动容器前会先看本地有没有这个镜像，没有就打这行提示然后去远端拉。只要后面跟着 `Pulling from baymax9799/12306helper` 和 `Status: Downloaded`，就是正常拉取。

如果后面出现 `no matching manifest for linux/arm64`，说明你的机器是 ARM，我们的镜像只有 amd64，得按上面「从源码构建」自己打一个。
</details>

<details>
<summary><b>口令不对 / 忘了口令</b></summary>

`.env` 或容器环境变量里设了 `ADMIN_PASSWORD` 的话，重启容器就会按它重设，覆盖旧口令。

优先级是：环境变量 > `data/settings.json` 里的旧哈希 > 随机生成。忘了口令又不想动数据，就在环境变量里指定一个新的重启。
</details>

<details>
<summary><b>容器起来又反复重启</b></summary>

九成是数据目录权限。容器用 uid 1000 运行，`data` 目录如果是 Docker 自动建的，属主是 root，写不进数据库：

```bash
sudo chown -R 1000:1000 /你的路径/data
docker restart 12306helper
```
</details>

<details>
<summary><b>28080 端口被占用</b></summary>

换宿主端口，容器内始终是 28080。命令行加 `-p 38080:28080`，compose 里改 `.env` 的 `HOST_PORT`。
</details>

<details>
<summary><b>能不能自动付款</b></summary>

不提供。付款要指纹或人脸，无头环境过不去，也涉及资金安全。服务抢到「待支付」就停，剩下十分钟内你自己去 12306 付。
</details>

<details>
<summary><b>候补为什么不默认开</b></summary>

`confirmHB` 的参数是几段拼装字符串，格式随 12306 改版会变，猜错可能触发风控。默认只提示、退化为普通抢票，要试就设 `WAITLIST_ENABLED=true`。
</details>

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 / 运行时 | Java 17 |
| HTTP 服务 | Javalin 6（内嵌 Jetty） |
| 12306 客户端 | OkHttp 4 |
| 数据存储 | SQLite（WAL 模式） |
| 加解密 | BouncyCastle（SM4 / AES-GCM） |
| 邮件 | Angus Mail（jakarta.mail） |
| 测试 | JUnit 5 + MockWebServer + GreenMail |

---

## 项目结构

```
src/main/java/com/jactil/tickethelper/
├── app/      入口与装配
├── core/     client · auth · query · station · passenger · store · config · security · time
├── engine/   调度 · 状态机 · 下单 · 闸门 · 对账 · 候补
├── notify/   邮件 · 微信 · Webhook
└── web/      API 与静态页面
```

依赖方向单向：`core` ← `engine` ← `web` ← `app`。测试全部用 MockWebServer / GreenMail 回放，不访问真实 12306。

```bash
mvn test        # 96 个用例
mvn package     # target/12306helper.jar
java -jar target/12306helper.jar
```

本地跑要设 `DATA_DIR`、`PORT`、`ADMIN_PASSWORD`。

---

## 已知限制

**下单参数要实盘对一次。** 六个接口（`submitOrderRequest` → `initDc` → `checkOrderInfo` → `getQueueCount` → `confirmSingleForQueue` → `queryOrderWaitTime`）的顺序和参数名是照公开实现写的，有回放测试，但 12306 改版频繁，第一次真用请开着日志看。

**席别列 24/25/26/30 的含义有歧义。** 公开资料在「软座还是无座」「高级软卧还是特等座」上互相矛盾，这里沿用上游作者的映射，集中在 `SeatCatalog` 一张表里，实盘发现错位改一行就行。

**票价只覆盖有票的席别。** 数据来自 `queryI` 响应里的票价段（10 字符一组，首位席别代码加 6 位分），售完或没有的席别不显示价格。

**单账号，只按内网部署。** 表结构留了 `account_id`，以后加多账号是加法。服务不处理 HTTPS，要上公网自己接反代，记得打开 Cookie 的 `Secure`。

---

## 参与测试

这个项目现在还缺有人用真账号跑一遍。下面几处只有实盘能确认：自动下单的六步接口参数、席别列 24/25/26/30 的归属、候补提交、各家 SMTP 与微信通道的实际到达、ARM 自建镜像。

开 Issue 时带上镜像标签、部署方式、`docker logs 12306helper --tail 200` 的输出、复现步骤；下单失败的话把 12306 返回的原文也贴出来。日志里可能有 `RAIL_DEVICEID`、`_passport_session`，那是登录态，贴之前替换掉，手机号和身份证号一并抹去。

---

## 开源协议

MIT。派生自 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)，原项目版权归 jactil 所有，见 [LICENSE](LICENSE)。

### 免责声明

- 本软件按「原样」提供，不提供任何形式的担保
- 仅供个人抢购本人车票使用，代购、倒票、多账号批量刷都不在设计目标内
- 12306 用户协议对自动化工具有限制，使用风险自负
- 因使用本软件造成的任何损失，作者不承担责任

---

## 致谢

- [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper) — 本项目的上游，登录与查询逻辑来自这里
- [Javalin](https://javalin.io/) — 轻量 HTTP 框架
- [OkHttp](https://square.github.io/okhttp/) — HTTP 客户端
- [BouncyCastle](https://www.bouncycastle.org/) — SM4 / AES 实现

---

<div align="center">

**如果这个项目帮你抢到了票，点个 Star 就好。**

</div>
