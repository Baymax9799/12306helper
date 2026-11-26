# 推到 GitHub

仓库已经整理好（单分支、9 个提交、86 个文件、CI 工作流就绪），你只需要选一种方式把它推上去。三种都行，按你顺手程度挑。

---

## 方式一：网页建仓库 + 命令行推（最通用，不需要装东西）

1. 打开 <https://github.com/new>
2. 仓库名填 `tickethelper-linux`，**不要**勾选 "Add a README / .gitignore / license"（会冲突）
3. 建好后，在本目录执行（把 `<你的用户名>` 换掉）：

```bash
git remote add origin https://github.com/<你的用户名>/tickethelper-linux.git
git push -u origin main
```

第一次推会要求认证：密码不行了，要用 **Personal Access Token**（GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → 勾 `repo` → 生成）。
输入用户名时填 GitHub 用户名，密码处粘贴 Token。

---

## 方式二：装 gh CLI 一条命令搞定

```powershell
winget install --id GitHub.cli
gh auth login          # 选 GitHub.com → HTTPS → 浏览器登录
gh repo create tickethelper-linux --public --source=. --remote=origin --push
```

私有仓库就把 `--public` 换成 `--private`。

---

## 方式三：先建空仓库再改远端

如果你已经建了仓库但地址不同：

```bash
git remote set-url origin https://github.com/<你的用户名>/tickethelper-linux.git
git push -u origin main
```

---

## 推之前可自查

```bash
git log --oneline          # 9 条提交，M0→M3 的演进过程
git status                 # 应该是 clean
mvn -B test                # 85 个测试全绿（CI 也会跑这个）
docker compose config      # 检查 compose 语法
```

## 推之后建议做的两件事

1. **开启 Actions**：仓库页 → Actions → 允许 workflow。`ci.yml` 会在每次 push 时跑测试 + 冒烟启动。
2. **写仓库描述**：例如
   `12306 抢票服务（无界面 / Docker / NAS），扫码登录 · 定时开抢 · 抢到即停待支付 · 邮件与微信通知`

## 别忘了一件事

`docker-compose.yml` 里的 `ADMIN_PASSWORD: "change-me-please"` 是占位符。**别人 clone 后如果直接 `up`，口令就是它**。README 里已经写了要改，但你也可以在仓库 Settings 里把它设成 template 说明，或者干脆把这一行改成 `ADMIN_PASSWORD: ""`（留空则首次启动随机生成并打印到日志，更安全）。

推荐后一种：留空。
