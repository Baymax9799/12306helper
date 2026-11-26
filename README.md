# TicketHelper-Linux

把 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)（JavaFX 桌面版 12306 助手）重构为**无界面 Web 服务**，用 Docker 部署在 NAS 上 7×24 跑。

- **无 GUI、无 VNC**：一个容器 + 一个网页，手机也能操作
- **扫码登录**：不落盘 12306 密码，不在无头环境里跟滑块死磕
- **抢到就停**：只做到「订单待支付」，**永不自动付款**；命中后全局暂停，避免把账号额度烧光
- **限速与小黑屋**：全局令牌桶 + 车次级冷却，不刷爆 12306
- **定时开抢**：开抢前 30 秒预热，开售 60 秒提频到 300ms，之后自动退避

> ⚠️ 本项目仅供**个人**抢购**本人**车票。禁止代购、倒票、多账号批量操作。12306 用户协议对自动化工具有限制，使用风险自负。

---

## 功能与真实状态

| 功能 | 状态 | 说明 |
|---|---|---|
| 扫码登录 / 短信登录 / Cookie 导入 | ✅ 已实现 | 单测覆盖调用链；扫码是主路径 |
| 余票查询与解析 | ✅ 已实现 | 列索引用真实响应固化，有回归测试 |
| 乘车人同步 | ✅ 已实现 | 本地**只存掩码**，不存身份证号 |
| 多任务 / 多车次 / 多日期 / 多席别 | ✅ 已实现 | 每任务一个线程，共享限速 |
| 定时开抢 / 预热 / 提频 | ✅ 已实现 | 相位计算有单测 |
| 下单到「待支付」 | ⚠️ 已实现，**待实盘校正** | 六步流程与参数按公开实现写，需用真实账号跑一次确认 |
| 账号闸门 + 每日单量 | ✅ 已实现 | 抢到即全局 HOLD；启动 + 每 60s 对账 |
| 通知（邮件 / 微信 / Webhook） | ✅ 已实现 | Server酱 / PushPlus / 企微机器人三选一 |
| 候补 | ⚠️ 实验性，默认关闭 | 参数格式未实盘验证，`WAITLIST_ENABLED=true` 才启用 |
| 票价显示 | ❌ 未做 | 票价字段是变长拼装，没有稳定文档 |
| 自动支付 | ❌ 永远不做 | 涉及资金与指纹/人脸，且风控必挂 |

## 一分钟部署（NAS / 任何有 Docker 的机器）

```bash
git clone <this-repo> tickethelper
cd tickethelper
mkdir -p data
echo "ADMIN_PASSWORD=$(openssl rand -base64 12)" > .env   # 也可以直接改 docker-compose.yml
docker compose up -d --build
```

打开 `http://<NAS_IP>:8080`，用 `ADMIN_PASSWORD` 登录。

> 不设 `ADMIN_PASSWORD` 时，首次启动会随机生成一个口令并打印在日志里：
> `docker compose logs -f tickethelper | grep 口令`

### 飞牛 fnOS 上的落地步骤

1. 「Docker」→「Compose」→ 新建项目，工作目录选 `/vol1/1000/docker/tickethelper`
2. 把仓库文件放进去，或直接把 `docker-compose.yml` 内容粘进 Compose 编辑器
3. 终端里 `cd /vol1/1000/docker/tickethelper && chown -R 1000:1000 data`
4. 启动 → 浏览器访问 `http://<NAS_IP>:8080`

### 群晖 / 威联通 / unRAID

都一样：能跑 `docker compose` 就能部署。镜像构建需要能访问 Maven 中央仓库（国内建议在 `~/.m2/settings.xml` 配阿里云镜像，或在构建阶段加 `--build-arg`）。

## 首次使用

1. **登录 12306**：点「扫码登录」，用 12306 App 扫码；也可以粘贴浏览器 Cookie 兜底
2. **同步乘车人**：本地只保存掩码姓名与证件后四位
3. **建任务**：出发站、到达站、日期、席别、乘车人；要抢开售就填「开抢时刻」
4. **选择模式**：
   - 监控模式（默认）：命中余票只推送通知，不下单
   - 自动下单：命中即提交，成功后停在待支付 —— 勾选前会二次确认
5. **收到通知后**：30 分钟内去 12306 付款；然后回到本服务点「我已付款 / 我已取消」解锁闸门

## 为什么抢到就全局暂停

12306 规则：**自然日内未支付或取消订单合计超过 3 次，账号当天线上购票权限被锁**。
所以本服务在抢到第一单后立刻 `HOLD` 所有任务，并且不替你自动取消订单（每取消一次就烧掉 1/3 额度，还可能触发人脸核验）。

每日下单上限默认 1，可在设置里调到 3（不建议）。

## 配置

`docker-compose.yml` 里的环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `ADMIN_PASSWORD` | 空 | 管理员口令；留空则随机生成并打印 |
| `PORT` | `8080` | 监听端口 |
| `DATA_DIR` | `/data` | 数据库 / 会话 / 配置 / 日志目录 |
| `JAVA_OPTS` | `-Xms128m -Xmx384m` | 384MB 堆足够单账号多任务 |
| `WAITLIST_ENABLED` | `false` | 是否启用实验性候补下单 |
| `TZ` | `Asia/Shanghai` | 时区，影响每日额度结算 |

SMTP、微信、Webhook 在网页「设置」里配置，敏感字段用 AES-GCM 加密落盘（密钥在 `/data/secret.key`，权限 600）。

## 架构

```
浏览器 ── HTTP :8080 ──┐
                       ▼
              web (Javalin 6 + 静态页 + 轮询事件)
                       │
              engine (调度 / 状态机 / 闸门 / 小黑屋 / 限速)
                       │
              core   (OkHttp · 扫码登录 · 查询解析 · 站点 · 校时)
                       │                    │
                 SQLite(/data)         kyfw.12306.cn
```

- 事件用**轮询**而不是 SSE：7×24 长连接是最容易泄漏的地方，客户端 2 秒拉一次增量
- 会话 Cookie 原子落盘，容器重启不掉登录；`RAIL_DEVICEID` 单独持久化，避免风控指纹变化
- 12306 的 302 被当作「会话失效/被风控」信号，不会被 OkHttp 悄悄跟过去

## 开发

```bash
mvn test          # 81 个测试，全部用 MockWebServer/GreenMail，不访问真实 12306
mvn package       # 产出 target/tickethelper-linux.jar
java -jar target/tickethelper-linux.jar   # DATA_DIR/PORT/ADMIN_PASSWORD 三个变量
```

目录：

```
src/main/java/com/jactil/tickethelper/
├── app/      进程入口
├── core/     client · auth · query · station · passenger · store · config · security · time
├── engine/   调度 · 状态机 · 下单 · 闸门 · 对账 · 候补
├── notify/   邮件 · 微信 · Webhook
└── web/      API 与装配
```

## 已知限制（老实说）

1. **下单参数待实盘校正**：`submitOrderRequest → initDc → checkOrderInfo → getQueueCount → confirmSingleForQueue → queryOrderWaitTime` 的顺序与参数名按公开实现写，并有 MockWebServer 契约测试，但 12306 改版频繁，第一次真用请盯着日志。
2. **席别列 24/25/26/30 的语义**：公开资料互相矛盾（软座 vs 无座、高级软卧 vs 特等座）。这里沿用上游作者的映射，代码里集中在一张表，实盘发现错位改一处即可。
3. **候补默认关闭**：`confirmHB` 的参数是几段拼装字符串，猜错可能触发风控，所以默认只在页面提示、退化为普通抢票。
4. **单账号**：v1 只支持一个 12306 账号（表结构已留 `account_id`，扩多账号是加法不是改法）。
5. **没有票价**：省掉一块不稳定解析，不影响下单。
6. **仅内网**：服务默认不处理 HTTPS，要公网请自己接反向代理并打开 Cookie 的 `Secure`。

## 许可证

MIT。派生自 [JavaFx-TicketHelper](https://github.com/Jactil777/JavaFx-TicketHelper)，原项目版权归 jactil 所有，详见 [LICENSE](./LICENSE)。
