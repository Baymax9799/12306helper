# TicketHelper-Linux 设计文档

- 日期：2026-11-25
- 版本：v3（已并入 10 项决策，待批准）
- 底座：fork 自 [Jactil777/JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)（MIT，Copyright (c) 2024 jactil）
- 目标平台：Linux x86_64，Docker，自建 NAS（飞牛 fnOS，Xeon E3-1225v6 / 16 GB）

---

## 1. 为什么做

原 `Bypass` 仓库只有编译好的 .NET 4.0 WinForms 的 `Bypass.exe`，没有源码，不能为 Linux 重新编译。`JavaFx-TicketHelper` 有完整源码、Java 17、MIT 协议，且其 `service` / `util` / `model` / `config` 四个包**不含任何 JavaFX 依赖**，可以整体抽为无界面核心。

目标：保留其已验证的「扫码登录 + 余票查询」HTTP 逻辑，去掉图形界面，做成常驻 NAS 的 Web 服务，补上原作者没写完的**下单**与**候补**，抢到「待支付」即停并推送通知。

## 2. 目标 / 非目标 / 反目标

**目标**

1. 单容器部署，`docker compose up -d` 后浏览器即可操作，无显示器、无 VNC。
2. 会话凭证持久化，容器重启不掉登录（Cookie 失效才需重新扫码或短信登录）。
3. 多任务并发监控：多车次 × 多日期 × 多席别 × 多乘客。
4. 命中余票后自动提交订单，成功则停在「待支付」并通知；**绝不自动付款**。
5. **定时开抢**：任务可设开抢时刻，到点前预热、开售窗口提频。
6. 全程可观测：任务状态、实时日志、订单记录都可查。

**非目标**

- 不实现自动支付（涉及资金与风控，且需指纹/人脸）。
- 不做多账号池、不做代购/倒票。
- 不自动取消 12306 侧的超时订单（只提醒）。
- 不做「买长乘短 / 多买一站」的备选区间（v1；仅报错时提示区间限售）。
- 不做 CDN 测速优选（原作者也标注「不做」）。
- 不破解滑块验证码（只做人工接管）。
- 不做移动端 App、不做公网反代方案（内网自用，反代由用户自己接）。

**反目标**（做到了也算失败）

- 把并发查询拉满刷 12306，触发风控或封号。
- 把 12306 密码落盘。
- 保留 JavaFX 依赖导致镜像膨胀到 1 GB+。
- 在开售日把账号锁死一天（见 D12）。

## 3. 约束

| 约束 | 说明 |
|---|---|
| **每日 3 单锁号** | 12306 规则：自然日内未支付**或**取消订单合计超 3 次，账号当日线上购票权限被锁 → 必须全局闸门 + 每日单量上限 |
| 12306 风控 | 单账号高频请求会被限流；并发提交会被拦截 → 全局串行提交 + 限速 |
| 滑块/人机验证 | 无头环境无法自动过，触发后必须转人工 |
| 接口易变 | 12306 前端改版会破坏接口；需可回放测试 |
| 无 NTP | 容器内时钟可能漂移，抢票对秒敏感 → 用 12306 响应头校时 |
| 内存预算 | NAS 上希望常驻 < 500 MB |
| 许可 | MIT，派生作品必须保留原作者版权声明 |
| 网络 | 仅内网访问，不做 HTTPS（无 Secure Cookie，风险可接受） |

## 4. 方案取舍

| 方案 | 做法 | 结论 |
|---|---|---|
| J1 图形界面容器化 | Xvfb + VNC + noVNC 跑原 JavaFX | 1 天可跑，但内存 ~700 MB、移动端不可用、抢票仍是 TODO → 仅作备选 |
| **J2 无头服务 + Web** | 抽 core，新写 engine 与 web | **选定**：唯一满足「7×24 常驻 + 浏览器操作」的形态 |
| J3 只容器化 | J1 + 自动启动 | 不单独选 |
| Python 重写 | 参考其接口代码用 Python 重写 | 放弃：登录/查询代码已可用，重写等于白扔已验证逻辑 |

## 5. 总体架构

```
┌──────────────────────────────────────────────────────────────────┐
│ 浏览器  (扫码/短信登录 · 任务 · 实时日志 SSE · 订单 · 设置)        │
└───────────────────────────┬──────────────────────────────────────┘
                            │ HTTP :8080 （仅内网）
┌───────────────────────────▼──────────────────────────────────────┐
│ web       Javalin 6 + 静态资源 + SSE + 单用户 Session             │
├──────────────────────────────────────────────────────────────────┤
│ engine    Scheduler · TaskRunner · BookingStateMachine · 候补      │
│           AccountGate(全局闸门/每日额度) · OrderReconciler(对账)   │
│           RateLimiter(令牌桶+提交锁) · Blacklist(小黑屋)          │
├──────────────────────────────────────────────────────────────────┤
│ core      TicketClient(OkHttp) · 扫码/短信登录 · 查询 · 站点 · 校时│
│           会话持久化 CookieStore · 乘车人同步                     │
├──────────────────────────────────────────────────────────────────┤
│ notify    EmailNotifier · WeChatNotifier(Server酱/PushPlus/企微)  │
│           WebhookNotifier                                        │
├──────────────────────────────────────────────────────────────────┤
│ store     SQLite (WAL)  ← /data/tickethelper.db                   │
└──────────────────────────────────────────────────────────────────┘
                            │ HTTPS
                       kyfw.12306.cn
```

## 6. 模块与包结构

```
tickethelper-linux/
├── pom.xml                       # 多模块
├── core/                         # 无 JavaFX、无 Javalin
│   └── ...core
│       ├── client/   TicketClient, PersistentCookieStore, RailDeviceId
│       ├── auth/     QrLoginFlow, SmsLoginFlow, PasswordCipher(SM4, 仅内存)
│       ├── query/    LeftTicketQuery, TicketParser, PriceParser
│       ├── passenger/PassengerSync（以 12306 为准）
│       ├── station/  StationRegistry（三字码/cityCode/同城站）
│       ├── time/     ServerClock
│       └── config/   AppPaths(/data), Settings
├── engine/
│   └── ...engine
│       ├── Scheduler（开抢时刻 / 预热 / 提频 / 退避）
│       ├── TaskRunner（每任务一个平台线程；Java 17 无虚拟线程，不为此升级 21）
│       ├── BookingStateMachine
│       ├── WaitlistService（候补，M5）
│       ├── AccountGate（全局闸门 + 每日单量）
│       ├── OrderReconciler（启动/周期对账 12306 未完成订单）
│       ├── RateLimiter（令牌桶 + 提交互斥锁）
│       ├── Blacklist（小黑屋：车次+席别冷却）
│       └── EventBus（SSE 与通知的事件源）
├── notify/
│   └── EmailNotifier, WeChatNotifier, WebhookNotifier
├── web/
│   └── ...web
│       ├── App（Javalin 装配）
│       ├── api/   AuthApi, TaskApi, QueryApi, OrderApi, GateApi, SettingApi, StreamApi
│       └── static/ index.html + app.js + style.css（沿用原 Bypass 风格）
└── app/  Main + 配置装配 + 优雅停机
```

来源映射：原 `LoginServiceImpl`（扫码 + 短信部分）→ `core/auth`；`TicketServiceImpl` → `core/query`；`HttpClientUtil` → `core/client/TicketClient`（重写）；`StationUtil`、`TimeUtil`、`Sm4Util` → `core` 对应包；`MainStage` / `LoginStage` / `component` / `IconGenerator` **删除**（git 历史保留）。

## 7. 关键决策

**D1 用 Javalin 而非 Spring Boot** — 内嵌 Jetty 启动约 200 ms，常驻内存比 Boot 省 150 MB 以上；本项目只有十几个端点，用不着 Boot 的生态。

**D2 前端不引入构建链** — 单页 HTML + 原生 JS + 沿用原 `style.css`（969 行，Bypass 风格已成型）。NAS 上不需要 Node，`docker build` 只跑 Maven。

**D3 存储用 SQLite** — 任务、会话、订单、事件需要查询与并发写；WAL 模式下单文件足够，挂 `/data` 即持久化。

**D4 会话持久化** — 自写 `PersistentCookieStore` 实现 OkHttp `CookieJar`：内存缓存 + 变更时原子落库（临时文件 + `ATOMIC_MOVE`）。启动时加载，`RAIL_DEVICEID` 单独持久化（风控指纹，丢了等于换设备）。另提供「粘贴 Cookie 字符串导入」作为人工接管兜底。

**D5 口径：只到「待支付」** — 拿到 `orderId` 后落订单记录、发通知、**触发全局闸门**（D12）。付款永远由人在 12306 App 完成，不可配置。

**D6 凭据：不落盘密码** — 扫码是主路径，无密码。短信登录需要密码，但密码**只在当次登录请求的内存中使用**（SM4 加密后即丢弃），不写库、不写日志。身份证号不落库（D14）。

**D7 登录双通道：扫码为主，短信兜底** — 扫码复用原实现的 `create-qr64` / `checkqr`（base64 PNG，网页渲染轮询，不碰滑块）；短信走 `checkLoginVerify` → `getMessageCode` → `login`。加短信的唯一原因是：NAS 的意义是人不在家，若只有一部手机，没有第二块屏就扫不了码，届时可用短信在手机上完成重登录。

**D8 风控即转人工** — 遇到 302 跳登录、`result_code` 异常、排队人数远超余票、接口返回验证页时：任务置 `NEED_VERIFY`，停止轮询，推送通知，页面提示「重新扫码 / 短信登录 / 粘贴 Cookie」。不尝试绕过。

**D9 时钟** — 容器内用 12306 响应头 `Date` 计算偏移，启动后每 10 分钟校准；页面显示偏移量，> 2 s 时告警。开抢时刻以校准后的服务器时间为准。

**D10 全局限速** — 全局令牌桶默认 1 次/秒，提交用单把互斥锁，同一时刻只允许一个提交请求在途。任务间共享，避免多任务叠加把账号打爆。

**D11 通知：邮件 + 微信两条腿** — 统一 `Notifier` 接口，事件驱动（下单成功 / 待验证 / 任务失败 / 登录过期 / 闸门触发），带重试与去重。

- **邮件（主）**：`jakarta.mail` + SMTP，支持 SSL / STARTTLS 与自定义端口，适配 QQ 邮箱、163、Gmail 应用专用密码。
- **微信（主）**：需要说清楚——个人做不到「自己申请公众号发模板消息」（需企业主体认证的服务号）。三条等价通路，按门槛排序：
  1. **Server酱**：绑定后消息直达你的微信，一个 `SendKey` 即可，有免费额度；
  2. **PushPlus**：同上；
  3. **企业微信机器人**：建一个只有自己的群，拿 Webhook，消息在微信里能收。
- **通用 Webhook（兜底）**：任意 URL + 自定义 Header / Body，方便接 ntfy、Bark、飞书、自建服务。

Server酱 / PushPlus / 企微机器人做成同一族的 `WeChatNotifier` 三个变体，配置里选一个填 Key。若确实要用自己的公众号，需先有认证服务号 + 用户 openid，是另一条路。

**D12 账号闸门与每日额度（新增，来自评审）** — 12306 规则下，「抢到就不再抢」不是礼貌而是安全问题：

1. 任一任务成功创建订单 → 立即**全局暂停所有任务**（`gate.state=HOLD`），页面顶部红条 + 通知「请去 12306 付款或取消，然后回来解锁」。
2. **不自动取消**任何订单（每取消一次烧掉 1/3 额度，且可能触发人脸核验，无头环境过不了）。
3. **每日最多创建 1 单**（`daily_order_limit`，可在设置里改到最多 3）。超限时新任务直接不启动，提示「今日额度已用完」。
4. **启动对账**：进程启动时 + 每 60 s 查一次 12306 未完成订单，若存在本服务不知道的未支付单（例如进程在提交后被杀），落库并同样触发闸门。
5. 恢复全靠人工：页面上「我已付款 / 我已取消」按钮 → 解锁，若当日已付款则额度随之重置。

**D13 定时开抢与提频（新增，来自评审）** — 任务可设 `start_at`（可选）：

- 开抢前 30 s：预热（校时、校验会话、拉一次站点/车次缓存），不做高频。
- 开抢时刻起 60 s 窗口内：该任务轮询间隔降到 300 ms。
- 窗口结束后：回落 1 s + 随机抖动；连续 N 次无票逐步退避到 2 s。
- 提频只在**用户显式设了 `start_at`** 的任务上启用，默认关闭，设置页有醒目警示（与反目标「不刷爆」的平衡点：限时、有限、单任务）。
- 提交仍然串行，提频只影响查询。

**D14 乘车人以 12306 为准（新增，来自评审）** — 不手工录入身份证号。页面提供「从 12306 同步乘车人」，本地只存 `12306 乘客 id` + 掩码姓名 + 票种 + 证件类型。下单时用 `getPassengerDTOs` 返回的原始字段拼 `passengerTicketStr`。代价：依赖该接口稳定；好处：NAS 上不存在可解密的身份证数据。

## 8. 数据模型（SQLite）

| 表 | 关键字段 |
|---|---|
| `account` | id, note, login_name(仅展示), created_at |
| `session` | account_id PK, cookies_json, rail_deviceid, updated_at, expires_at |
| `passenger` | id, account_id, pid(12306), name_masked, id_type, ticket_type, synced_at |
| `task` | id, name, status, mode(GRAB/WAITLIST), from_station, to_station, dates_json, trains_json, seat_types_json, passenger_ids_json, interval_ms, start_at, created_at, updated_at |
| `order` | id, task_id, order_no, train_no, seat_type, amount, status, created_at |
| `event` | id, task_id, level, message, created_at |
| `gate` | id, state(HOLD/OPEN), reason, updated_at |
| `daily_stat` | day(PK), orders_created, orders_paid, orders_cancelled |

`task.status`：`PENDING`（未到开抢时刻）/ `RUNNING` / `PAUSED` / `NEED_VERIFY` / `HELD`（闸门触发）/ `DONE` / `FAILED`。
`order.status`：`PENDING_PAY` / `PAID` / `CANCELLED`（后两者由对账任务从 12306 回写）。
设置项（SMTP、微信 Key、限速、每日额度、间隔）存 `/data/settings.json`，敏感字段 AES-GCM 加密。

## 9. 抢票 / 候补状态机

```
            ┌──────── 开抢时刻未到 ────────┐
            ▼                              │
        PENDING ──到点──▶ RUNNING ─────────┘
                             │
                       查询命中（余票 ≥ 所需，或部分满足）
                             ▼
                    [闸门检查：额度 / HOLD / 会话有效]
                             │通过
                             ▼
   SUBMIT_ORDER → INIT_DC → CHECK_ORDER → QUEUE_COUNT → CONFIRM
        ▲                                                    │
        │                                           WAIT_QUEUE(≤60s)
   余票不足/排队超 ──▶ 小黑屋(30s) ◀──────────────┐          │
        │                                    RESULT ──成功──▶ DONE(PENDING_PAY)
        └────────── 连续3次 → 5min ────────────┘    │         + 全局 HOLD + 通知
                                             失败 ──┘
风控任一环节命中 → NEED_VERIFY + 通知 + 停止

候补（mode=WAITLIST）：
RUNNING ──候补可用──▶ CONFIRM_HB ──▶ 轮询候补订单 ──成功──▶ DONE(待支付) + HOLD + 通知
                                            └─无候补──▶ 退化为普通抢票循环
```

小黑屋按 `车次+席别` 维度冷却，避免反复提交缓存票污染其他车次的查询配额。
「部分满足」规则：余票少于勾选乘客数时，按乘客勾选顺序提交能买到的数量，通知里明确写了买的是谁（可在任务里关掉，关掉则整单放弃）。

## 10. 12306 接口清单

| 用途 | 端点 | 现状 |
|---|---|---|
| 扫码登录建码 / 轮询 | `passport/web/create-qr64`、`checkqr` | 已有，迁移 |
| 短信：查验证方式 / 发码 / 登录 | `passport/web/checkLoginVerify`、`getMessageCode`、`passport/web/login` | 已有，迁移（密码仅内存） |
| 登录票据 | `passport/web/auth/uamtk`、`otn/uamauthclient` | 已有 |
| 用户信息 | `otn/index/initMy12306Api` | 已有 |
| 余票查询 | `otn/leftTicket/queryI` | 已有（含票价解析） |
| 站点库 | `otn/resources/js/framework/station_name.js` | 已有（启动缓存 + 定期刷新） |
| 校时 | 任意 12306 响应头 `Date` | 已有 |
| **提交订单** | `otn/leftTicket/submitOrderRequest` | **新写** |
| **初始化下单页** | `otn/confirmPassenger/initDc`（token / leftTicketStr / key_check_isChange） | **新写** |
| **乘客列表** | `otn/confirmPassenger/getPassengerDTOs` | **新写** |
| **校验订单** | `otn/confirmPassenger/checkOrderInfo` | **新写** |
| **排队人数** | `otn/confirmPassenger/getQueueCount` | **新写** |
| **确认提交** | `otn/confirmPassenger/confirmSingleForQueue` | **新写** |
| **排队结果** | `otn/confirmPassenger/queryOrderWaitTime` | **新写** |
| **未完成订单对账** | `otn/queryOrder/queryMyOrderNoComplete`（端点名待实盘确认） | **新写（D12 依赖）** |
| **候补下单** | `confirmPassenger/confirmHB` + 候补队列查询 | **新写（M5）** |

> 参数以公开实现 + 实盘联调为准；编码期用「录制 → 回放」固化。

## 11. Web 接口

```
POST /api/auth/qr                创建二维码 {uuid, imageBase64}
GET  /api/auth/qr/{uuid}         轮询状态 {WAITING|SCANNED|OK|EXPIRED}
POST /api/auth/sms/send          发短信验证码
POST /api/auth/sms/login         短信登录（密码仅本次请求使用）
POST /api/auth/cookie            粘贴 Cookie 导入
GET  /api/auth/me                当前登录态
POST /api/auth/logout            退出

GET  /api/stations/search?q=     车站补全
POST /api/passengers/sync        从 12306 同步乘车人
GET  /api/passengers             乘车人列表（掩码）
POST /api/query                  立即查询余票（不建任务）
GET/POST/PUT/DELETE /api/tasks   任务 CRUD（mode=GRAB|WAITLIST，含 start_at）
POST /api/tasks/{id}/start|stop  启停
GET  /api/orders                 订单列表
POST /api/orders/reconcile       手动触发对账
GET  /api/gate                   闸门状态 / 今日额度
POST /api/gate/unlock            我已付款/已取消 → 解锁
GET  /api/settings / PUT         设置（SMTP / 微信 Key / 限速 / 额度 / 间隔）
POST /api/settings/test-notify   发测试通知
GET  /api/events/stream          SSE：任务状态 / 日志 / 闸门事件
GET  /healthz                    健康检查
```

鉴权：单用户，首次启动从 `ADMIN_PASSWORD` 读取或生成随机密码打印到日志，BCrypt 存库；会话用 HttpOnly + SameSite=Lax Cookie（内网无 HTTPS，不加 Secure）。

## 12. 部署

**Dockerfile**：两阶段。`maven:3.9-eclipse-temurin-17` 构建 → `eclipse-temurin:17-jre-jammy` 运行；非 root 用户 `app`(1000)；`tzdata` + `Asia/Shanghai`；`HEALTHCHECK` 打 `/healthz`。

**compose**（`docker-compose.yml`）：

```yaml
services:
  tickethelper:
    build: .
    image: tickethelper-linux:0.1
    container_name: tickethelper
    restart: unless-stopped
    ports: ["8080:8080"]
    environment:
      TZ: Asia/Shanghai
      ADMIN_PASSWORD: "换成你自己的"
      JAVA_OPTS: "-Xms128m -Xmx384m"
    volumes: ["./data:/data"]
    healthcheck: { test: ["CMD","curl","-f","http://127.0.0.1:8080/healthz"], interval: 30s, timeout: 5s, retries: 3 }
```

**飞牛 fnOS 落地**：Docker → Compose 项目 → 工作目录 `/vol1/1000/docker/tickethelper` → `chown -R 1000:1000 data` → 启动 → `http://NAS_IP:8080`。仅内网，反代自行接（若接 HTTPS，记得打开 Cookie 的 Secure）。

**镜像预期**：约 230 MB（JRE 17 ~180 MB + jar），常驻内存 300–500 MB。

## 13. 里程碑

| 阶段 | 内容 | 预估 |
|---|---|---|
| M0 | 多模块骨架、可构建、Dockerfile、compose、健康检查、优雅停机 | 1 天 |
| M1 | core 抽离、PersistentCookieStore、扫码登录 + 短信登录、乘车人同步、Web 鉴权 | 2 天 |
| M2 | 查询、任务 CRUD、Scheduler（开抢/预热/提频）、限速、SSE 日志、前端三页 | 2 天 |
| M3 | 下单状态机到 `PENDING_PAY`、小黑屋、AccountGate、OrderReconciler、对账 | 2.5 天 |
| M4 | 通知（SMTP + Server酱/PushPlus/企微 + Webhook）、测试按钮、运维文档 | 1 天 |
| M5 | 候补：`confirmHB` 全链路、候补任务模式、退化为抢票 | 2 天 |

合计约 10.5 天。M1–M3 是关键路径，M4 可与 M3 并行，M5 最后。

## 14. 测试策略

1. **解析单测**：仓库自带 `12306车票查询接口响应.txt`（195 KB 真实响应）作 fixture，验证余票/票价解析。
2. **状态机单测**：录制响应回放驱动 `BookingStateMachine`，覆盖余票不足、排队超、结果为空、成功四种分支。
3. **闸门单测**：额度耗尽 / HOLD 状态下不得发起提交；对账发现孤儿单必须触发 HOLD。
4. **HTTP 契约测试**：MockWebServer 断言请求参数（token、leftTicketStr、passengerTicketStr 拼装）。
5. **通知单测**：MockWebServer 断言 Server酱/企微 Body 与重试；SMTP 用 GreenMail 本地假服务器。
6. **容器冒烟**：`docker compose up` → `/healthz` 200 → 首页可打开 → 二维码接口返回 base64。
7. **实盘联调**：真实账号跑通扫码登录 + 查询 + 同步乘车人。

## 15. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 12306 改版/接口变更 | 查询或下单直接失败 | 解析层与 HTTP 层分离；fixture 回放；错误落库可回溯 |
| 风控/滑块 | 任务中断 | D8 转人工 + 通知，不硬刚 |
| **真实下单每天最多验 3 次** | 无法边改边测 | 先做扎实的回放台；实盘只做定点确认，交付时标注「参数待实盘」 |
| `RAIL_DEVICEID` 缺失或漂移 | 频繁二次验证 | 首次登录后持久化，导入 Cookie 时校验 |
| 会话过期 | 需人工重登 | 过期前 6 h 告警；扫码/短信双通道 |
| 候补接口最易变 | M5 可能返工 | 放最后；接口层与抢票解耦，失败不影响 M1–M4 |
| 提频策略被误用 | 触发风控 | 仅 `start_at` 任务启用、默认关、页面警示、窗口限 60 s |

## 16. 合规声明

- 本项目仅供个人抢购**本人**车票使用。禁止代购、倒票、多账号批量操作。
- 12306 用户协议对自动化工具有限制，使用风险由使用者自负。
- 派生自 MIT 项目，保留原作者 jactil 版权声明，派生说明写入 README。

## 17. 已确认决策

| # | 议题 | 结论 |
|---|---|---|
| 1 | 通知渠道 | SMTP 邮件 + 微信（Server酱 / PushPlus / 企微机器人三选一）+ 通用 Webhook 兜底 |
| 2 | 账号数 | 单账号 |
| 3 | 登录方式 | 扫码为主 + 短信兜底；密码仅内存使用，不落盘 |
| 4 | 候补 | 做，M5，约 2 天 |
| 5 | 网络 | 仅内网，反代用户自理 |
| 6 | 额度闸门 | 成功后全局暂停 + 不自动取消 + 每日 1 单 + 启动对账 |
| 7 | 主战场 | 捡漏 + 定时开抢（开售 60 s 窗口提频到 300 ms） |
| 8 | 乘车人 | 从 12306 同步，本地不存身份证号 |
| 9 | 重登录 | 加短信登录，手机单设备可恢复 |
| 10 | 买长乘短 | v1 不做 |

## 18. 留给实现者的自由

页面视觉细节、日志格式、`event` 表的保留天数（默认 30 天）、UI 文案、单测框架的具体组织方式、SSE 重连策略（默认 EventSource 自动重连）。
