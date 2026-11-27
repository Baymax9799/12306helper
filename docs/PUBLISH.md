# 推到 GitHub

仓库已经整理好（单分支、9 个提交、86 个文件、CI 工作流就绪），你只需要选一种方式把它推上去。三种都行，按你顺手程度挑。

---

## 方式一：网页建仓库 + 命令行推（最通用，不需要装东西）

1. 打开 <https://github.com/new>
2. 仓库名填 `12306Helper`，**不要**勾选 "Add a README / .gitignore / license"（会冲突）
3. 建好后，在本目录执行（把 `<你的用户名>` 换掉）：

```bash
git remote add origin https://github.com/<你的用户名>/12306Helper.git
git push -u origin main
```

第一次推会要求认证：密码不行了，要用 **Personal Access Token**（GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → 勾 `repo` → 生成）。
输入用户名时填 GitHub 用户名，密码处粘贴 Token。

---

## 方式二：装 gh CLI 一条命令搞定

```powershell
winget install --id GitHub.cli
gh auth login          # 选 GitHub.com → HTTPS → 浏览器登录
gh repo create 12306Helper --public --source=. --remote=origin --push
```

私有仓库就把 `--public` 换成 `--private`。

---

## 方式三：先建空仓库再改远端

如果你已经建了仓库但地址不同：

```bash
git remote set-url origin https://github.com/<你的用户名>/12306Helper.git
git push -u origin main
```

---

## 推之前可自查

```bash
git log --oneline          # 9 条提交，M0→M3 的演进过程
git status                 # 应该是 clean
mvn -B test                # 87 个测试全绿（CI 也会跑这个）
docker compose config      # 检查 compose 语法
```

## 推之后建议做的两件事

1. **开启 Actions**：仓库页 → Actions → 允许 workflow。`ci.yml` 会在每次 push 时跑测试 + 冒烟启动。
2. **写仓库描述**：例如
   `12306 抢票服务（无界面 / Docker / NAS），扫码登录 · 定时开抢 · 抢到即停待支付 · 邮件与微信通知`

## 别忘了一件事

`docker-compose.yml` 里的 `ADMIN_PASSWORD: "change-me-please"` 是占位符。**别人 clone 后如果直接 `up`，口令就是它**。README 里已经写了要改，但你也可以在仓库 Settings 里把它设成 template 说明，或者干脆把这一行改成 `ADMIN_PASSWORD: ""`（留空则首次启动随机生成并打印到日志，更安全）。

推荐后一种：留空。

---

# 发布镜像到 Docker Hub

镜像名固定 `baymax9799/12306helper`，与 `.env.example` 的 `IMAGE` 对应。两条路选一条。

## 路线一：本机构建后推

```bash
docker login -u baymax9799
docker build --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public \
  -t baymax9799/12306helper:latest .
docker push baymax9799/12306helper:latest
```

首次 push 会在 Docker Hub 自动建仓库，默认可见性可能是 Private，去 `https://hub.docker.com/r/baymax9799/12306helper/settings` 改成 Public，否则别人（以及不登录的 NAS）拉不下来。

## 路线二：交给 GitHub Actions（默认走这条）

CI 定义在 `.github/workflows/docker-publish.yml`，两种触发：

- **push 到 main**：构建并推 `latest` 和 `main`
- **推 `v*` tag**：构建并推 `latest` 和该 tag

纯文档改动不会触发构建（`paths-ignore` 里排除了 `**.md`、`docs/**` 等）。

配置一次即可：

1. Docker Hub → Account settings → Personal access tokens → Generate new token，权限 Read & Write，复制（只显示一次）。
2. GitHub 仓库 → Settings → Secrets and variables → Actions：
   - **Variables** 页签：`DOCKERHUB_USERNAME` = `baymax9799`
   - **Secrets** 页签：`DOCKERHUB_TOKEN` = 上一步的 token
3. 之后每次 `git push origin main` 都会自动出镜像；要固定一个版本就再打 tag：

```bash
git tag -a v0.2.0 -m "本次改了什么"
git push origin v0.2.0
```

同名 tag 不能重复推：发新版就换号（`v0.2.1`），或先 `git push origin --delete v0.2.0` 再重打。也可以在 Actions 页手动 Run workflow，但那次的版本标签是分支名（如 `main`），`latest` 照常更新。

## 发完之后核对

```bash
docker manifest inspect baymax9799/12306helper:latest   # 能返回 JSON 就是推上去了
```

或直接查 API：

```bash
curl -s https://hub.docker.com/v2/repositories/baymax9799/12306helper/tags/ | head -c 400
```