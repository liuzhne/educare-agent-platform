# GCP 单节点 K3s 部署

> 日期 / 标识：2026-11-20 / GCP-DEPLOY-20261120
> 状态：**当前生产环境**。Kamatera（`resource-profile-sg`）因欠费停用，生产迁到 GCP。17 个工作负载 Ready，受信 HTTPS、三角色鉴权、退出撤销和公网端口边界已验证；RAG 灌库、真实 AgentLoop、PDF 导出、SSE、HPA 扩缩容尚未验收（见 §7）。
> 部署形态沿用 [Kamatera 手册](./KAMATERA_DEPLOYMENT.md) 与 [K3s README](../../k8s/README.md) 的单节点 K3s 路径和 `scripts/k3s-deploy.py` 门禁；本文只记录 GCP 侧差异、实际资源和运维操作。脚本、私有文件和镜像名里的 `kamatera` 字样保持不变（改名会改变镜像 tag / 默认路径），不代表仍运行在 Kamatera。

## 1. 资源与费用

| 项 | 值 |
|---|---|
| 项目 | `educare-prod-2026`（名称 EduCare Prod） |
| 结算账号 | 「我的结算账号」（ID 在 GCP 控制台 Billing 页查看，不写入仓库） |
| VM | `educare-sg`，`asia-southeast1-b`，`e2-highmem-4`（4 vCPU / 32 GB） |
| 系统 | Rocky Linux 9.8（`rocky-linux-cloud/rocky-linux-9-optimized-gcp`），x86_64，SELinux Enforcing，Shielded VM（Secure Boot / vTPM / 完整性监控） |
| 磁盘 | 100 GB `pd-balanced` 启动盘 |
| 外网 IP | 静态 `educare-ip` = `34.87.79.229`（Premium 层） |
| 站点 | `https://educare.34-87-79-229.nip.io`（Caddy + Let's Encrypt，首张证书至 2027-02-18） |

2026-11-20 通过 Cloud Billing Catalog API 查得的新加坡区单价（按需，E2 无持续使用折扣）：

| 项 | 单价 | 月度（730 h） |
|---|---|---|
| E2 vCPU ×4 | $0.02691 / 核·时 | ≈ $78.6 |
| E2 内存 32 GiB | $0.003606 / GiB·时 | ≈ $84.2 |
| Balanced PD 100 GB | $0.11 / GB·月 | $11.0 |
| VM 上的外网 IPv4 | $0.005 / 时 | ≈ $3.7 |
| **合计** | | **≈ US$178 / 月** + 出网流量（亚洲约 $0.12/GB） |

注意：

- **结算账号最多关联 3 个项目**。为挂上本项目，已解绑空项目 My First Project（`project-e4daa64d-90e6-4086-b69`）；需要时可重新关联，但须先腾出名额或向 Google 申请提高配额。
- 停止 VM 只停 vCPU/内存计费；磁盘和静态 IP 仍计费（未挂载的静态 IP 单价更高，$0.011/时）。彻底停止计费见 §8。
- 建议在控制台 Billing → Budgets 给本项目设预算提醒（如 US$200/月）。截至本文未设置。
- Groq / Cloudflare / Langfuse 为外部免费额度，不含在 GCP 账单里。

## 2. 网络与访问

**GCP VPC 防火墙**（`default` 网络）：

| 规则 | 来源 | 放行 | 目标标签 |
|---|---|---|---|
| `educare-allow-web` | 0.0.0.0/0 | tcp:80,443 | `educare` |
| `educare-allow-iap-ssh` | 35.235.240.0/20（IAP） | tcp:22 | `educare` |
| `default-allow-internal` | 10.128.0.0/9 | 全部 | — |
| `default-allow-icmp` | 0.0.0.0/0 | icmp | — |

新项目自带的 `default-allow-ssh`（0.0.0.0/0:22）与 `default-allow-rdp` 已删除。6443 / 10250 / 8472 及所有数据库、MCP、服务端口在 VPC 层不对公网开放。

**VM 内 firewalld**（GCE Rocky 镜像默认 active）：`public` 区加 `http`、`https`；`trusted` 区加 Pod CIDR `10.42.0.0/16` 和 Service CIDR `10.43.0.0/16`，保证 K3s 集群内通信与 hostPort 80/443 转发。

**SSH 只走 IAP 隧道**，公网看不到 22 端口。主机公钥由 VM 的 guest attributes（`enable-guest-attributes=TRUE`）经受信 API 提供，gcloud 写入 `~/.ssh/google_compute_known_hosts` 后严格校验：

```bash
gcloud config set project educare-prod-2026
gcloud config set compute/zone asia-southeast1-b
gcloud compute ssh educare-sg --tunnel-through-iap --strict-host-key-checking=yes
```

```bash
gcloud compute scp --tunnel-through-iap --strict-host-key-checking=yes <本地文件> educare-sg:~/
```

运维账号是登录用户本人（gcloud 自动下发 `~/.ssh/google_compute_engine` 公钥），通过 `sudo` 操作。**sudo 的 PATH 不含 `/usr/local/bin`**，调用 `k3s` 或运行部署脚本时要带上：

```bash
sudo env PATH=/usr/local/bin:$PATH k3s kubectl -n educare get pods
```

## 3. 文件布局

**工作站（本仓库根目录，均被 Git 忽略，0600）**：

| 路径 | 内容 |
|---|---|
| `docker/.env.kamatera` | 私有 env：9 个内部密钥为 2026-11-20 新生成（未复用 Kamatera）；Groq / Cloudflare / Langfuse 参数及其余调优项从旧检出 `agyProject/resource-profile` 迁入；`CLOUD_PUBLIC_IP` / `CLOUD_SITE_ADDRESS` 为 GCP 值 |
| `.local-run/kamatera/seed-credentials.json` | admin / teacher / student 三个账号的当前登录密码（`bootstrap-seeds` 已写入数据库） |
| `.local-run/kamatera/fonts/` | `NotoSansSC-Regular.ttf`（SHA-256 `eeb06b8a…25461`，来源见 Kamatera 手册 §5）与 `OFL.txt` |

**VM（`/opt/resource-profile`，root 0700）**：解压后的源码包、`docker/.env.kamatera`（0600）、`.local-run/kamatera/{seed-credentials.json,fonts/,venv/}`、原始源码包 `source-gcp-3725dd6.tar.gz`。为满足 `ensure_untracked` 已 `git init` 一个空仓库（不跟踪任何文件）。构建日志 `/root/build.log`、部署日志 `/root/deploy.log`。

**K3s**：`/etc/rancher/k3s/config.yaml`（0600）：

```yaml
node-name: educare-sg
selinux: true
secrets-encryption: true
write-kubeconfig-mode: "0600"
disable:
  - traefik
  - servicelb
```

SELinux 持久标签（与 Kamatera 相同的精确路径规则）：`/opt/resource-profile/sql/init(/.*)?` 与 `/opt/resource-profile/.local-run/kamatera/fonts/NotoSansSC-Regular.ttf` 为 `container_file_t:s0`。

## 4. 首次部署记录（2026-11-20，main `3725dd6`）

以下为实际执行顺序，可用于重建环境。所有 gcloud 命令在已 `gcloud auth login` 且项目设为 `educare-prod-2026` 的工作站执行。

### 4.1 项目与资源

```bash
gcloud projects create educare-prod-2026 --name="EduCare Prod"
gcloud billing projects link educare-prod-2026 --billing-account=<BILLING_ACCOUNT_ID>
gcloud services enable compute.googleapis.com iap.googleapis.com
gcloud compute addresses create educare-ip --region=asia-southeast1 --network-tier=PREMIUM
# 重建时新预留的地址不一定是 34.87.79.229，后续一律使用实际分配的值
IP=$(gcloud compute addresses describe educare-ip --region=asia-southeast1 --format='value(address)')
gcloud compute firewall-rules delete default-allow-ssh default-allow-rdp --quiet
gcloud compute firewall-rules create educare-allow-iap-ssh --network=default --direction=INGRESS --allow=tcp:22 --source-ranges=35.235.240.0/20 --target-tags=educare
gcloud compute firewall-rules create educare-allow-web --network=default --direction=INGRESS --allow=tcp:80,tcp:443 --source-ranges=0.0.0.0/0 --target-tags=educare
gcloud compute instances create educare-sg --zone=asia-southeast1-b --machine-type=e2-highmem-4 \
  --image-family=rocky-linux-9-optimized-gcp --image-project=rocky-linux-cloud \
  --boot-disk-size=100GB --boot-disk-type=pd-balanced --address="$IP" --network-tier=PREMIUM \
  --tags=educare --metadata=enable-guest-attributes=TRUE,enable-oslogin=FALSE \
  --shielded-secure-boot --shielded-vtpm --shielded-integrity-monitoring
```

### 4.2 主机初始化（VM 上 root 执行）

Docker 只用于构建镜像；K3s 使用自带 containerd。

```bash
dnf install -y dnf-plugins-core ca-certificates curl git python3 python3-pip container-selinux \
  policycoreutils-python-utils audit tar
dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
echo '{"selinux-enabled": true}' > /etc/docker/daemon.json
dockerd --validate --config-file /etc/docker/daemon.json
systemctl enable --now docker

install -d -m 0755 /etc/rancher/k3s   # 写入 §3 的 config.yaml，chmod 0600
curl -sfL https://get.k3s.io -o /root/k3s-install.sh
INSTALL_K3S_VERSION=v1.36.5+k3s1 sh /root/k3s-install.sh   # 同时安装签名的 k3s-selinux-1.6-1.el9

firewall-cmd --permanent --zone=trusted --add-source=10.42.0.0/16
firewall-cmd --permanent --zone=trusted --add-source=10.43.0.0/16
firewall-cmd --permanent --zone=public --add-service=http --add-service=https
firewall-cmd --reload
```

核验结果：K3s `v1.36.5+k3s1`、containerd `2.3.4-k3s1.36`，节点 Ready，coredns / local-path-provisioner / metrics-server Running；Docker `SecurityOptions` 含 `name=selinux`，Compose v5.8.0；`getenforce` = Enforcing；kubeconfig 0600。

### 4.3 私有配置与源码包（工作站）

```bash
python3 scripts/kamatera-deploy.py init-env      # 新 env + 新种子密码；已存在时拒绝覆盖
# 从旧 env 合并非密钥/供应商参数，填 CLOUD_PUBLIC_IP=$IP、CLOUD_SITE_ADDRESS=educare.<IP 的点换成连字符>.nip.io
# （本次实际为 34.87.79.229 / educare.34-87-79-229.nip.io）
python3 scripts/kamatera-deploy.py check
git worktree add --detach <临时目录> main         # 只打包已合入 main 的代码，不带工作区未提交改动
(cd <临时目录> && python3 scripts/kamatera-deploy.py package-source --output <输出路径>/source-gcp-3725dd6.tar.gz)
git worktree remove <临时目录>
```

源码包 423 个运行文件，SHA-256 `433b5d37e9f076f33fb3adaef8ee46f0643883df5d0e3e273e88601821da30a9`，包含 `scripts/k3s-deploy.py`、`k8s/workloads.json`、`scripts/apply-sql-migrations.py`、`docker/caddy/Caddyfile.cloud`，不含 env、seed、私钥。

### 4.4 上传与暂存

`gcloud compute scp` 把源码包、`.env.kamatera`、`seed-credentials.json`、字体和 OFL 传到 VM 家目录，然后用 root 执行：在 VM 上用 `sha256sum -c` 校验源码包和字体 → 解压到 `/opt/resource-profile` → 按 §3 的权限放置私有文件 → 设置 SQL 目录 0755 / SQL 文件 0644 → `semanage fcontext` + `restorecon` 设置标签 → `git init` → 创建 `.local-run/kamatera/venv` 并安装 `scripts/requirements-cloud.txt` → `shred -u` 删除家目录中的私有文件副本。

### 4.5 构建、导入、启动、发布（VM，root，`cd /opt/resource-profile`）

```bash
export PATH=/usr/local/bin:$PATH
python3 scripts/kamatera-deploy.py build          # 11 个应用镜像，约 15 分钟

images=(edu-portrait-knowledge:cloud)
for s in auth-service user-service teacher-service student-service mental-service data-service mcp-student-data agent-service gateway frontend; do
  images+=("edu-portrait-kamatera-${s}:latest")
done
docker save "${images[@]}" | k3s ctr --namespace k8s.io images import -

python3 scripts/k3s-deploy.py check
python3 scripts/k3s-deploy.py apply-private       # 含 3 个 SQL 迁移，约 4 分钟
.local-run/kamatera/venv/bin/python scripts/k3s-deploy.py bootstrap-seeds
python3 scripts/k3s-deploy.py init-rag
python3 scripts/k3s-deploy.py probe-models        # 真实 Groq + Cloudflare 各一次合成请求
.local-run/kamatera/venv/bin/python scripts/k3s-deploy.py publish
```

全部步骤一次通过：`apply-private` 应用了 `20261007_01_portrait_contract.sql`、`20261007_02_mental_contract.sql`、`20261007_04_identity_contract.sql`；17 个 Deployment 1/1 Ready；data-service HPA 1–2，CPU 4%/70%。

## 5. 更新发布

镜像使用 `latest` 标签 + `IfNotPresent`，**导入新镜像后必须显式重建 Pod**，否则仍运行旧镜像。

1. 工作站：从要发布的提交（建议 main）用 `git worktree` 干净检出并 `package-source` 到**新文件名**，记录 SHA-256。打包前确认新增的 SQL 迁移会进入包内：`package-source` 的迁移白名单目前只匹配 `sql/migrations/20261007_*.sql`（见 `scripts/cloud_deploy.py`），其他日期的迁移会被静默排除，需先修改白名单。打包后用 `tar -tzf <包> | grep '^sql/migrations/'` 核对。
2. `gcloud compute scp` 上传，VM 上校验 SHA-256。**不要直接解压覆盖 `/opt/resource-profile`**：覆盖解压会留下新提交已删除的文件，而后端 Dockerfile 用 `COPY . .`，残留的旧源码会进入镜像或导致重复类编译失败。应解压到空目录，再同步并删除多余文件，同时排除私有文件：

   ```bash
   dnf install -y rsync   # 首次需要
   REL=/opt/resource-profile-releases/<release>
   install -d -m 0700 "$REL" && tar -xzf /opt/resource-profile/<新包>.tar.gz -C "$REL"
   rsync -a --delete \
     --exclude=/docker/.env.kamatera --exclude=/.local-run/ --exclude=/.git/ --exclude='/*.tar.gz' \
     "$REL"/ /opt/resource-profile/
   chmod 0755 /opt/resource-profile/sql/init && chmod 0644 /opt/resource-profile/sql/init/*.sql
   restorecon -R /opt/resource-profile/sql/init
   ```

3. 只构建受影响服务（服务名同 `docker/docker-compose.cloud.yml`）：

   ```bash
   cd /opt/resource-profile/docker
   docker compose --project-name edu-portrait-kamatera --env-file .env.kamatera -f docker-compose.cloud.yml --profile public build agent-service
   ```

   前端的构建参数来自 env，改 Vite 相关值时在仓库根目录执行：`cd /opt/resource-profile && python3 scripts/k3s-deploy.py build-frontend`。
4. **有新增 SQL 迁移时，先应用迁移再重建服务**，否则依赖新表/列的新版本在 `rollout status` 阶段就会启动失败。迁移须向后兼容（只做加法），保证旧版本 Pod 在迁移后仍可运行：

   ```bash
   cd /opt/resource-profile && python3 scripts/apply-sql-migrations.py --transport k3s --namespace educare
   ```

5. 导入并重建：

   ```bash
   docker save edu-portrait-kamatera-agent-service:latest | k3s ctr --namespace k8s.io images import -
   k3s kubectl -n educare rollout restart deployment/agent-service
   k3s kubectl -n educare rollout status deployment/agent-service --timeout=600s
   ```

   `knowledge` 镜像同时被 `ai-inference-service` 和 `knowledge-rag-mcp` 使用，两者都要重建。
6. 改了 env（模型参数、供应商 key、JWT / 内部 token 等）：更新 VM 上的 `docker/.env.kamatera` 后执行 `apply-private`（也会应用迁移）。模板哈希变化会触发相关 Pod 重建；该命令会先下线 frontend，完成后需再 `publish`。
7. **不能只改 env 来轮换 `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD`**：这两个值只在 MySQL PVC 首次初始化时写入数据库。只改 env 时，MySQL 健康检查会用新的 root 密码登录而数据库仍是旧密码，`apply-private` 卡在 MySQL 未就绪；只改 `MYSQL_PASSWORD` 则业务服务连库认证失败、卡在业务服务 rollout。两种情况下 frontend 都已被下线。正确顺序：
   1. 先备份数据库；
   2. 在 MySQL Pod 内用当前 root 凭据对 `root` 与 `edu` 账号执行 `ALTER USER … IDENTIFIED BY …`，新密码经 stdin 传入，不放在命令行或日志里；
   3. 再把新值写入 VM 的 `docker/.env.kamatera`，然后执行 `apply-private` → `publish`；
   4. 失败恢复：若 MySQL 无法就绪，把 env 中这两项改回数据库当前实际生效的值，重新执行 `apply-private`；确认业务服务可连接后再 `publish`。不要删除 PVC 或重新初始化来"修复"密码。

回滚：发布前先给旧镜像打备份 tag（如 `docker tag edu-portrait-kamatera-agent-service:latest edu-portrait-kamatera-agent-service:pre-<release>` 后一并导入）。失败时把 `latest` 指回旧镜像，重新导入并 `rollout restart`。不删除 PVC。

## 6. 验证记录（2026-11-20）

从工作站外部实测：

| 检查 | 结果 |
|---|---|
| DNS `educare.34-87-79-229.nip.io` | → 34.87.79.229 |
| `/healthz` / `/login` | 200 / 200，登录页浏览器渲染正常 |
| HTTP → HTTPS | 308 |
| 证书 | Let's Encrypt（YE1），至 2027-02-18 |
| 匿名 `/api/student/list`、`/api/agent/tasks` | 401 |
| `/api/actuator/health`、`/api/agent/_internal/*` | 403 |
| 出厂密码 `admin/admin` | 业务码 401，已拒绝 |
| 三角色新密码登录 | 均成功 |
| admin：userInfo / 仪表盘 / 学生列表 / 用户列表 / Agent 任务 | 全部 200 |
| teacher：用户列表 | 403；其余 200 |
| student：仪表盘 / 学生列表 / 用户列表 / Agent 任务 | 全部 403；userInfo 200 |
| 退出后复用旧 token | 401（Redis 会话撤销生效） |
| 公网端口 22 / 3306 / 6379 / 6443 / 8080 / 8848 / 10250 / 19530 | 全部不可达 |
| `probe-models` | Groq 对话、Cloudflare 1024 维向量均通过 |

## 7. 尚未验收 / 待办

- **知识库为空**：`init-rag` 只建集合，未灌知识，RAG 检索当前无内容。需按 Kamatera 手册 §5 / RUNBOOK 使用授权的非敏感知识源灌库并验收检索。
- **数据库只有三个种子账号**，无演示师生数据。
- 真实 AgentLoop 画像报告生成、中文 PDF 导出、SSE 推送、双 MCP 工具调用、data-service HPA 1→2→1、MySQL/Milvus 备份恢复均未在 GCP 上实测。
- Langfuse 参数已迁入，但未验证 GCP 上的 trace 上报。
- **供应商 key 轮换**：Groq / Cloudflare / Langfuse key 沿用 Kamatera 时期的值，而 Kamatera VM 已不受控，建议轮换后更新 VM env 并 `apply-private` + `publish`。
- 预算提醒未设置；Kamatera 服务器 / 账户需由账号所有者在其后台终止。
- nip.io 仅适合演示；正式长期使用应换自有域名（A 记录指向 34.87.79.229，更新 `CLOUD_SITE_ADDRESS` 后 `publish`）。

## 8. 排错、停机与销毁

- **`k3s: command not found`**：sudo 环境缺 `/usr/local/bin`，见 §2。
- **结算关联失败 `Cloud billing quota exceeded`**：结算账号项目数已满，见 §1。
- **Pod 未就绪**：`k3s kubectl -n educare describe pod …` / `logs`，只在 VM 上私下查看，日志可能含凭据或业务数据，不要复制到聊天或工单。其余排错（SELinux 标签、HPA、TLS）同 [Kamatera 手册 §7](./KAMATERA_DEPLOYMENT.md#7-排错回滚和试用到期停用)。
- **下线公网入口**（保留数据）：`python3 scripts/k3s-deploy.py stop-public`。VM 仍计费。
- **停机省钱**：`gcloud compute instances stop educare-sg`。磁盘和静态 IP 仍计费；`start` 后 K3s 自动拉起，PVC 保留。
- **彻底停止计费**：先备份需要的数据（MySQL dump、导出文件），再 `gcloud projects delete educare-prod-2026`。项目进入 30 天待删除期，期间可恢复；资源立即停止计费。之后可把结算名额还给其他项目。
