# TicketHelper-Linux v1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native) or subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 JavaFx-TicketHelper 重构为无界面 Java 服务，Docker 部署在 NAS 上，能扫码登录、查余票、自动下单到「待支付」、定时开抢、候补，并通过邮件/微信通知。

**Architecture:** 单 Maven 模块，按包分层：`core`（HTTP/登录/解析，无框架依赖）→ `engine`（调度/状态机/闸门）→ `notify` → `web`（Javalin + 静态页 + SSE）→ `app`（装配）。SQLite 落在 `/data`，会话持久化，进程重启不掉登录。

**Tech Stack:** Java 17、Javalin 6、OkHttp 4.12、Jackson 2.17、sqlite-jdbc、BouncyCastle(SM4)、Angus Mail、jBCrypt、JUnit 5 + MockWebServer + GreenMail、Logback。

**Spec:** `docs/superpowers/specs/2026-11-25-tickethelper-linux-design.md`

## Global Constraints

- Java 17 源码级别；**不使用虚拟线程**，任务用平台线程。
- 不引入 `javafx-*` 任何依赖；原 UI 包不迁移。
- 运行镜像 `eclipse-temurin:17-jre-jammy`，非 root 用户 uid 1000，`TZ=Asia/Shanghai`。
- 所有状态落 `/data`：`tickethelper.db`、`settings.json`、`secret.key`、`logs/`。
- 单账号；**不落盘 12306 密码**，密码只在短信登录请求的内存里用一次。
- **永不自动支付**，也不自动取消 12306 订单。
- 任何测试**不得访问真实 12306**；网络层测试一律 MockWebServer。
- HTTP 层默认限速 1 req/s，提交串行；开抢提频仅限 `start_at` 任务且窗口 60 s。
- 内部模块**不依赖** `web` 包（依赖方向 core ← engine ← web ← app）。
- 所有面向用户的文案用中文。

## Review Focus

1. **票据/座席字段错位**：12306 `queryI` 返回的是 40+ 段竖线分隔字符串，列索引错一位就会「查到票但买错席别」——用仓库自带 195 KB 真实响应做断言，每个席别列都要有值。
2. **未支付订单泄漏**：进程在 `confirmSingleForQueue` 之后被杀，12306 侧有单、本地无记录——启动对账必须能发现并触发 HOLD。
3. **额度计数按自然日**：`daily_stat` 用 Asia/Shanghai 的日期，跨零点不能串账；HOLD 状态下所有任务都不得发起提交。
4. **会话并发写坏 Cookie 文件**：多任务共用一个 `PersistentCookieStore`，必须加锁 + 原子替换，半截文件在重启后等于掉登录。
5. **SSE 连接泄漏**：浏览器刷新/关闭后服务端必须回收，否则 7×24 跑几周会 OOM。

## 文件结构

```
tickethelper-linux/
├── pom.xml
├── Dockerfile · docker-compose.yml · README.md
├── src/main/java/com/jactil/tickethelper/
│   ├── app/Main.java                         # 装配 + 优雅停机
│   ├── core/config/{AppPaths,Settings}.java
│   ├── core/store/{Db,TaskDao,OrderDao,EventDao,PassengerDao,SessionDao,StatDao}.java
│   ├── core/client/{TicketClient,PersistentCookieStore,RailDeviceId}.java
│   ├── core/auth/{QrLoginFlow,SmsLoginFlow,PasswordCipher}.java
│   ├── core/query/{LeftTicketQuery,TicketParser,TrainRow,SeatInfo}.java
│   ├── core/passenger/PassengerSync.java
│   ├── core/station/StationRegistry.java
│   ├── core/time/ServerClock.java
│   ├── engine/{Scheduler,TaskRunner,BookingStateMachine,AccountGate,OrderReconciler,RateLimiter,Blacklist,EventBus}.java
│   ├── notify/{Notifier,EmailNotifier,WeChatNotifier,WebhookNotifier,NotifyDispatcher}.java
│   └── web/{App,AuthApi,TaskApi,QueryApi,OrderApi,GateApi,SettingApi,StreamApi,ApiError}.java
├── src/main/resources/{logback.xml,static/index.html,static/app.js,static/style.css}
└── src/test/java/...  + src/test/resources/query-leftTicket-fixture.json
```

**与 spec 的偏离（实现者决定）**：spec 写「多模块 pom」，实际用**单模块 + 包分层**。理由：只有一个可部署产物，多模块只增加构建复杂度；包边界与 spec 的模块一致，依赖方向用包依赖约束 + 代码评审保证。

---

### Task 1: 骨架可启动

**Files:** Create `pom.xml`、`app/Main.java`、`web/App.java`、`resources/logback.xml`、`.gitignore`；Test `src/test/java/.../web/HealthTest.java`

**Interfaces:**
- Produces: `web.App#create(EngineDeps deps)` → `io.javalin.Javalin`；`GET /healthz` → 200 `{"status":"ok"}`

- [ ] 写 `HealthTest`：起 Javalin 随机端口，`GET /healthz` 断言 200 且 body 含 `"ok"`
- [ ] 跑测试确认失败（无 `App` 类）
- [ ] 建 `pom.xml`：`maven.compiler.release=17`，依赖 javalin 6.3.0 / okhttp 4.12.0 / jackson-databind 2.17.2 / slf4j-api 2.0.13 / logback-classic 1.5.6 / sqlite-jdbc 3.46.1.3 / bcprov-jdk18on 1.78 / angus-mail 2.0.3 / jbcrypt 0.4 / junit-jupiter 5.10.2 / mockwebserver 4.12.0 / greenmail 2.1.0；`maven-surefire-plugin 3.2.5`；`maven-shade-plugin` 打可执行 jar
- [ ] 实现 `App#create`：注册 `/healthz`，返回 `{"status":"ok"}`
- [ ] 实现 `Main`：读 `PORT`（默认 8080）启动
- [ ] 跑 `mvn -q test` 通过；手工 `mvn -q exec:java` 后 `curl localhost:8080/healthz`
- [ ] 提交

### Task 2: 路径、设置与管理员鉴权

**Files:** Create `core/config/AppPaths.java`、`core/config/Settings.java`、`web/AuthApi.java`(仅 admin 部分)；Test `AppPathsTest`、`SettingsTest`、`AdminAuthTest`

**Interfaces:**
- Produces: `AppPaths.fromEnv()`（`DATA_DIR` 默认 `/data`，非容器环境回退 `~/.tickethelper`）；`Settings.load(Path)` / `Settings#save(Path)`；`AdminAuth#hash(String)`/`verify(String,String)`；`POST /api/auth/admin {password}` → Set-Cookie `sid`
- 常量：`AppPaths#db()`、`#settings()`、`#secretKey()`、`#logs()`

- [ ] 测试：无 `DATA_DIR` 时 `fromEnv()` 返回 `~/.tickethelper`，有则用之
- [ ] 测试：`Settings` 往返序列化，SMTP 密码字段落盘后是密文（前缀 `enc:`）
- [ ] 测试：错误管理员密码 → 401；正确 → 200 且带 `sid` cookie
- [ ] 实现 `AppPaths`（创建目录，失败抛 `IllegalStateException`）
- [ ] 实现 `Settings`（Jackson；`AesGcm` 小工具类做 `enc:` 加解密，密钥取 `secret.key`，首次生成 32 字节并 `chmod 600`）
- [ ] 实现 `AdminAuth`（jBCrypt，cost 10）与 `AuthApi` 的 admin 端点 + `BeforeHandler` 保护 `/api/**`（`/healthz`、`/api/auth/admin`、静态资源除外）
- [ ] 跑测试通过；提交

### Task 3: SQLite 存储层

**Files:** Create `core/store/{Db,TaskDao,OrderDao,EventDao,PassengerDao,SessionDao,StatDao}.java`；Test `DbMigrationTest`、`TaskDaoTest`

**Interfaces:**
- Produces: `Db.open(Path)` + `#migrate()`（建 §8 全部表，`PRAGMA journal_mode=WAL`）；DAO 方法签名见下
- `TaskDao#insert(TaskRow)/#update/#findById(long)/#findAll()/#delete(long)`
- `OrderDao#insert(OrderRow)/#findAll()/#markStatus(String orderNo, String status)`
- `StatDao#todayOrdersCreated()/#incrementCreated()/#resetIfNewDay(LocalDate)`；`EventDao#append(long taskId, String level, String msg)/#recent(int)`

- [ ] 测试：`migrate()` 在空文件上建表，重复调用幂等
- [ ] 测试：`TaskDao` 往返所有字段（含 `dates_json`、`start_at`）
- [ ] 测试：`StatDao` 在新日期把 `orders_created` 归零
- [ ] 实现 `Db`（HikariCP 不需要，单连接 + `synchronized` 足够；WAL）
- [ ] 实现各 DAO（手写 JDBC，`PreparedStatement`）
- [ ] 跑测试；提交

### Task 4: TicketClient 与会话持久化

**Files:** Create `core/client/{TicketClient,PersistentCookieStore,RailDeviceId}.java`、`core/time/ServerClock.java`；Test `PersistentCookieStoreTest`、`TicketClientHeaderTest`

**Interfaces:**
- Produces: `PersistentCookieStore(Path)` implements `okhttp3.CookieJar`，加 `#dumpHeader():String`、`#loadFromHeader(String)`、`#save()`
- `TicketClient(PersistentCookieStore, ServerClock)`，`#get(url)/#post(url,Map)/#postJson(url,String)`，`#cookies()`
- `ServerClock#now()`（服务器校准时间，`Duration#offset()`）

- [ ] 测试：store 往返 5 个 cookie（含 `RAIL_DEVICEID`），重启后 `loadForRequest` 仍返回
- [ ] 测试：并发 8 线程写不同 cookie，落盘文件可被解析且无半截 JSON（读回 5 个 key）
- [ ] 测试：`TicketClient.get` 对 MockWebServer 断言 `User-Agent` 含 `Chrome`、`Referer` 指向 `kyfw.12306.cn`
- [ ] 实现 store：`ReentrantLock` + 临时文件 + `ATOMIC_MOVE`
- [ ] 实现 `TicketClient`：OkHttp 单例实例、10 s 连接 / 30 s 读超时、**不做 trustAll**（原实现 `trustAllCerts` 删除，JDK 17 的 TLS 对 12306 正常）、`ServerClock` 从 `Date` 头校准
- [ ] 实现 `RailDeviceId`：从 cookie 读，缺失时 `GET /otn/leftTicket/init` 后重读
- [ ] 跑测试；提交

### Task 5: 站点库

**Files:** Create `core/station/StationRegistry.java`；Test `StationRegistryTest`（fixture：截取 `station_name.js` 前 30 条）

**Interfaces:** `StationRegistry#load(String js)`、`#codeOf(String name)`、`#nameOf(String code)`、`#sameCity(String code):Set<String>`、`#suggest(String q, int limit):List<StationRow>`

- [ ] 测试：解析 `var station_names ='@bjb|北京北|VAP|beijingbei|bjb|0|0357|...'` 取到 `VAP`/`北京北`/cityCode
- [ ] 测试：`suggest("北京")` 返回 ≤ limit 且北京北在前
- [ ] 实现正则切分 `@` + `|`
- [ ] 加 `refresh()`：`GET station_name.js`，失败保留旧缓存并记 WARN
- [ ] 跑测试；提交

### Task 6: 登录三通道

**Files:** Create `core/auth/{QrLoginFlow,SmsLoginFlow,PasswordCipher}.java`、`web/AuthApi` 扩展；Test `QrLoginFlowTest`、`SmsLoginFlowTest`

**Interfaces:**
- `QrLoginFlow#create():QrCode(uuid,imageBase64,expiresAt)`；`#poll(uuid):QrState{WAITING,SCANNED,LOGGED_IN,EXPIRED}`；成功后内部跑 `uamtk → uamauthclient → initMy12306Api`
- `SmsLoginFlow#sendCode(username)`；`#login(username,password,smsCode)`（密码即刻丢弃）
- `POST /api/auth/qr`、`GET /api/auth/qr/{uuid}`、`POST /api/auth/sms/send|login`、`POST /api/auth/cookie`、`GET /api/auth/me`

- [ ] 测试：MockWebServer 返回 `{"result_code":0,"uuid":"u1","image":"BASE64"}` → `create()` 的 `imageBase64` 非空
- [ ] 测试：poll 第一次 `result_code=2`（等待）→ `WAITING`；`result_code=1` 后跑三步换票并断言调用了 `uamauthclient`
- [ ] 测试：cookie 导入 `RAIL_DEVICEID=abc; _passport_session=xyz` 后 store 含两项
- [ ] 实现三个流程（复用原 `LoginServiceImpl` 的参数名，去掉 JavaFX 回调）
- [ ] 实现 `AuthApi`，登录态以「session 表存在 cookie」为准
- [ ] 跑测试；提交

### Task 7: 余票解析

**Files:** Create `core/query/{LeftTicketQuery,TicketParser,TrainRow,SeatInfo}.java`；Test `TicketParserTest`（fixture 用仓库里 `12306车票查询接口响应.txt` 裁成 `query-leftTicket-fixture.json`）

**Interfaces:**
- `TicketParser#parse(String json, String fromCode, String toCode):List<TrainRow>`
- `TrainRow(trainNo, stationTrainCode, fromCode, toCode, fromName, toName, startTime, arriveTime, duration, trainLocation, secretStr, Map<String,SeatInfo> seats)`
- `SeatInfo(code, name, int count, String raw)`；`count = -1` 表示「无此席别」，`0` 表示售完
- `LeftTicketQuery(TicketClient, StationRegistry)#query(date, from, to, purposeCodes):List<TrainRow>`

- [ ] 测试：fixture 中 `G1` 车次的二等座列 = 具体数字，硬卧列 = `--`（`count=-1`）
- [ ] 测试：`secretStr` 非空且不包含 `|`
- [ ] 实现解析（**列索引复用原 `TicketServiceImpl` 的分列常量**，从 fixture 断言逐个校正，不凭记忆）
- [ ] 实现 `LeftTicketQuery`（参数 `leftTicketDTO.train_date/from_station/to_station`、`purpose_codes=ADULT`）
- [ ] 跑测试；提交

### Task 8: 乘车人同步

**Files:** Create `core/passenger/PassengerSync.java`；Test `PassengerSyncTest`

**Interfaces:** `PassengerSync(TicketClient)#sync():List<PassengerRow>`；`GET /api/passengers`、`POST /api/passengers/sync`

- [ ] 测试：Mock 返回两个乘客（`passenger_name`/`passenger_id_no`/`passenger_type`），断言落库只有掩码姓名（`张*`）与 `passenger_id_no` 的后 4 位在日志中被脱敏，DB 里**无**完整证件号
- [ ] 实现：先 `POST /otn/confirmPassenger/getPassengerDTOs`，映射到 `passenger` 表
- [ ] 跑测试；提交

### Task 9: 任务 CRUD

**Files:** Create `web/TaskApi.java`、`engine/TaskSpec.java`；Test `TaskApiTest`

**Interfaces:** `POST /api/tasks {name,mode,from,to,dates[],trains[],seatTypes[],passengerIds[],intervalMs,startAt?}` → 201 带 id；`GET/PUT/DELETE /api/tasks[/{id}]`

- [ ] 测试：创建后 `GET` 能取回，`dates` 乱序被规范成升序
- [ ] 测试：`startAt` 早于现在 → 400 且 message 含「开抢时刻已过」
- [ ] 实现 CRUD + 参数校验（车站必须是合法三字码，否则 400）
- [ ] 跑测试；提交

### Task 10: 调度器与查询循环

**Files:** Create `engine/{Scheduler,TaskRunner,RateLimiter,Blacklist,EventBus}.java`；Test `TaskRunnerTest`、`RateLimiterTest`

**Interfaces:**
- `RateLimiter#acquire()`（阻塞到有令牌）、`#setBurst(boolean)`
- `Blacklist#hit(String train, String seat)`、`#isBlocked(...)`（30 s，3 次后 5 min）
- `EventBus#publish(Event)` + `#subscribe(Consumer<Event>)`
- `TaskRunner#start(TaskSpec)`、`#stop(long taskId)`；状态写入 `task.status`

- [ ] 测试：`RateLimiter` 在 1 req/s 下 5 次 `acquire` 耗时 ≥ 4 s（用 `assertTimeout` 与注入时钟）
- [ ] 测试：`Blacklist` 第 3 次命中后冷却变 5 min
- [ ] 测试：`TaskRunner` 在 Mock 查询返回有票时发布 `MATCH` 事件并置 `DONE`（下单用 stub `BookingStateMachine`）
- [ ] 实现令牌桶（`Semaphore` + 时间戳，不用 `Thread.sleep` 忙等）
- [ ] 实现 `TaskRunner`：每任务一个平台线程，循环 `query → 过滤 → 匹配 → 发布`，`stop` 用 volatile 标志 + 中断
- [ ] 跑测试；提交

### Task 11: SSE 与前端

**Files:** Create `web/StreamApi.java`、`resources/static/{index.html,app.js,style.css}`；Test `StreamApiTest`

**Interfaces:** `GET /api/events/stream` → `text/event-stream`，事件名 `task`/`log`/`gate`；前端三块：登录、任务列表、实时日志

- [ ] 测试：订阅后 `publish` 一条事件，客户端在 2 s 内收到 `data:` 行
- [ ] 测试：客户端断开后 handler 内的 `onClose` 被调用（计数器归零），验证无泄漏
- [ ] 实现 `StreamApi`（`SseHandler` + `ConcurrentHashMap<Context,Consumer>`，`onClose` 注销）
- [ ] 实现静态页（沿用原 `style.css` 的配色与网格，去掉 JavaFX 相关类名）
- [ ] `curl -N` 冒烟；提交

### Task 12: 下单状态机

**Files:** Create `engine/BookingStateMachine.java`；Test `BookingStateMachineTest`、`Mock12306BookingFixture`

**Interfaces:**
- `BookingStateMachine(TicketClient, EventBus)#attempt(TrainRow train, String seatCode, List<PassengerRow> pax):BookingResult{SUCCESS(orderNo), NO_TICKET, QUEUE_FULL, RISK, ERROR}`
- 顺序：`submitOrderRequest → initDc(token,leftTicketStr,key_check_isChange) → checkOrderInfo → getQueueCount → confirmSingleForQueue → queryOrderWaitTime(≤60s)`

- [ ] 测试：Mock 全链路成功 → `SUCCESS` 且 `orderNo` 取自 `queryOrderWaitTime` 的 `orderId`
- [ ] 测试：`checkOrderInfo` 返回「余票不足」→ `NO_TICKET`，不发 `confirmSingleForQueue`
- [ ] 测试：`getQueueCount` 排队数 > 余票 → `QUEUE_FULL`，触发小黑屋
- [ ] 测试：`queryOrderWaitTime` 60 s 无结果 → `ERROR`，且不重试同一单
- [ ] 实现状态机（内部类或 enum 跳转，每步失败即返回，不抛到调用方）
- [ ] 实现 `passengerTicketStr` 拼装（`座位,票种,姓名,证件类型,证件号,手机号,N`）与 `oldPassengerStr`
- [ ] 跑测试；提交

### Task 13: 账号闸门与对账

**Files:** Create `engine/{AccountGate,OrderReconciler}.java`、`web/GateApi.java`；Test `AccountGateTest`、`OrderReconcilerTest`

**Interfaces:**
- `AccountGate#canSubmit():boolean`（HOLD 或当日额度用完 → false）、`#onOrderCreated(orderNo)`（置 HOLD + `incrementCreated`）、`#unlock(reason)`
- `OrderReconciler#runOnce():int`（发现孤儿单 → 落库 + `gate.onOrderCreated`）
- `GET /api/gate`、`POST /api/gate/unlock {action: PAID|CANCELLED}`

- [ ] 测试：`onOrderCreated` 后 `canSubmit()` 为 false，直到 `unlock`
- [ ] 测试：`daily_stat` 已达 1 时 `canSubmit()` 为 false（额度=1）
- [ ] 测试：对账返回一个本地不存在的订单号 → 落库且触发 HOLD
- [ ] 测试：跨零点后 `canSubmit()` 恢复（`resetIfNewDay`）
- [ ] 实现闸门（状态在 `gate` 表，进程重启仍在 HOLD）
- [ ] 实现对账（启动 + 每 60 s；端点名待实盘确认，失败只记 WARN 不崩）
- [ ] 跑测试；提交

### Task 14: 通知

**Files:** Create `notify/*.java`、`web/SettingApi.java`；Test `WeChatNotifierTest`、`EmailNotifierTest`（GreenMail）

**Interfaces:** `Notifier#send(NotifyEvent)`；`NotifyDispatcher#dispatch(NotifyEvent)`（并发、失败重试 2 次、5 min 内相同事件去重）

- [ ] 测试：Server酱 body 含 `title` 与 `desp`；企微 body 含 `msgtype=markdown`
- [ ] 测试：GreenMail 收到主题含「抢票成功」的邮件
- [ ] 测试：同一事件 2 s 内发两次只发一次
- [ ] 实现四个 notifier + 分发器；`POST /api/settings/test-notify`
- [ ] 跑测试；提交

### Task 15: 定时开抢

**Files:** Modify `engine/Scheduler.java`、`TaskRunner.java`；Test `SchedulerTimingTest`

**Interfaces:** `Scheduler#computeState(TaskSpec, Instant now):{PENDING, PREHEAT, BURST, STEADY}`

- [ ] 测试：`startAt-31s` → `PREHEAT`；`startAt+10s` → `BURST`；`startAt+90s` → `STEADY`
- [ ] 测试：`BURST` 期间 `RateLimiter` 间隔 300 ms，`STEADY` 回落 1 s + 抖动
- [ ] 实现状态计算（用 `ServerClock`，不用 `Instant.now()`）
- [ ] 实现提频只在 `startAt != null` 时生效
- [ ] 跑测试；提交

### Task 16: 容器化与交付

**Files:** Create `Dockerfile`、`docker-compose.yml`、`.dockerignore`、`README.md`；Test 手工冒烟

- [ ] 写多阶段 `Dockerfile`（构建层用 `maven:3.9-eclipse-temurin-17`，运行层 `eclipse-temurin:17-jre-jammy`，`adduser --uid 1000`，`HEALTHCHECK`）
- [ ] 写 `docker-compose.yml`（端口 8080、`./data:/data`、`JAVA_OPTS`、`ADMIN_PASSWORD`、`restart: unless-stopped`）
- [ ] 写 `README.md`：飞牛 fnOS 步骤、首启密码在哪、通知配置、风险声明、MIT 归属
- [ ] 冒烟：本地 `mvn -q package` → `java -jar` → `/healthz` 200 → 首页 200 → `/api/auth/qr` 返回 base64
- [ ] 提交

### Task 17: 候补（M5）

**Files:** Create `engine/WaitlistService.java`；Test `WaitlistServiceTest`

**Interfaces:** `WaitlistService#submit(TrainRow, String seatCode, List<PassengerRow>):WaitlistResult{CREATED, UNAVAILABLE, RISK, ERROR}`；`task.mode=WAITLIST` 时走此路径，失败退化为普通抢票

- [ ] 测试：`confirmHB` 成功 → `CREATED` 且落 `order(status=PENDING_PAY)`
- [ ] 测试：返回「当前无法候补」→ `UNAVAILABLE`，任务退回 `RUNNING` 抢票模式
- [ ] 实现 `confirmHB` 参数拼装（候补需 `passengerInfo`/`journey`/`train`/`seatType` 四段 JSON 字符串）
- [ ] 跑测试；提交

---

## 自审记录

- **spec 覆盖**：§2 六项目标 → Task 1/6/9/10/12/15/17；§7 D4 → Task 4；D8 → Task 10/12 的 `RISK`；D11 → Task 14；D12 → Task 13；D13 → Task 15；D14 → Task 8；§12 部署 → Task 16；§14 测试策略 1–6 → Task 7/12/13/14/16。
- **未覆盖到实盘的部分**：`queryMyOrderNoComplete`、`confirmHB` 的参数细节需实盘校正，已在 Task 13/17 标注「待实盘确认」，不在单测里假装通过。
- **类型一致性**：`TrainRow.seats` 在 Task 7 定义 `Map<String,SeatInfo>`，Task 12/15/17 引用同一类型；`PassengerRow` 在 Task 8 定义，Task 12/17 使用；`BookingResult` 在 Task 12 定义，Task 13 不直接依赖。
- **比例**：17 个任务对应约 6000 行实现，计划未复写函数体，只固定签名、常量与断言。
