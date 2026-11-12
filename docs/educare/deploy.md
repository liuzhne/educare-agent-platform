# EduCare 部署运维手册

> 适用对象：首次部署、停启服务、排查异常的同学。
> 整套架构参见 [`architecture.md`](./architecture.md)。

---

## 1. 先决条件

| 项 | 版本/要求 |
|---|---|
| 操作系统 | macOS（Apple Silicon 推荐，Metal 加速）/ Linux（CUDA 待评估） |
| Docker | Docker Desktop 4.x，docker-compose v2 |
| Java | JDK 21 |
| Maven | 3.8+ |
| Node.js | 18+，npm 9+ |
| Python | 3.10（仅运行 `scripts/`、`eval/` 下的运维与评测脚本时需要） |
| 磁盘 | ≥ 30GB（Qwen2.5-14B Q5_K_M ≈ 10GB；BGE-large ≈ 1GB；BGE-reranker ≈ 250MB） |
| 内存 | ≥ 24GB（LLM 进程常驻 ~12GB） |

llama.cpp 模型与启动脚本约定放在 `~/edu-ai/`。

---

## 2. 首次部署（按顺序）

> 工作目录：项目根 `/Users/<you>/.../Resource-Profile/.claude/worktrees/cc-dev`

### 2.1 启动基础设施

```bash
docker compose -f docker/docker-compose.yml up -d \
    mysql redis nacos milvus-standalone etcd minio
```

确认就绪：
```bash
docker compose -f docker/docker-compose.yml ps
docker exec edu-redis redis-cli ping       # PONG
curl http://localhost:8848/nacos           # 控制台
curl http://localhost:9091/healthz         # Milvus
```

### 2.2 加载数据库 DDL（首次）

```bash
docker exec -i $(docker compose -f docker/docker-compose.yml ps -q mysql) \
    mysql -uroot -proot edu_portrait < sql/init/03_agent_init.sql
```

> `01_init.sql` / `02_questionnaire_extension.sql` 由 docker-compose 在初始化时自动加载；EduCare 增量是 `03_agent_init.sql`（agent 任务表）+ `04_student_extras.sql`（H-1.2 成绩 / 考勤表）。

加载 H-1.2 学生扩展表：
```bash
docker exec -i $(docker compose -f docker/docker-compose.yml ps -q mysql) \
    mysql -uroot -proot edu_portrait < sql/init/04_student_extras.sql
```

**服务端口对照**（Docker / 本地裸跑通用）：

| 服务 | 端口 | 说明 |
|------|------|------|
| gateway | 8080 | API gateway，外部入口 |
| auth-service | 8081 | 登录 / JWT |
| user-service | 8082 | 用户管理 |
| teacher-service | 8083 | 教师档案 |
| student-service | 8084 | 学生档案 + H-1.2 成绩/考勤端点 |
| mental-service | 8085 | 心理评估 |
| data-service | 8086 | 数据分析 |
| agent-service | 8087 | LLM/Agent 编排 |
| ai-inference-service | 8090 | Java knowledge-service：RAG 检索、知识导入、集合初始化 |
| llama.cpp llm | 8091 | 宿主机 LLM 服务 |
| **mcp-student-data** | **8094** | **H-1.2 + H-1.1.6：student-data MCP server（Streamable HTTP，单端点 `/mcp`）** |
| **knowledge-rag MCP** | **8095** | **knowledge-rag MCP server（Java knowledge-service 的 MCP 实例，Streamable HTTP，单端点 `/mcp`），3 个 search tool（cases/policies/psychology）** |

### 2.3 启动 ai-inference-service（容器）

```bash
docker compose -f docker/docker-compose.yml up -d --build ai-inference-service
docker compose -f docker/docker-compose.yml logs -f ai-inference-service | head -50
```

knowledge-rag MCP 是同一个 Java 镜像的另一个实例（端口 8095，`KNOWLEDGE_MCP_ENABLED=true`、`KNOWLEDGE_API_ENABLED=false`）：

```bash
docker compose -f docker/docker-compose.yml up -d --build knowledge-rag-mcp
```

> 两个实例的凭证（`EDUCARE_INTERNAL_TOKEN` / `EDUCARE_ADMIN_TOKEN` / `EDUCARE_MCP_TOKEN`）为空时对应接口返回 503，须在 `docker compose up` 之前导出，见根目录 `RUNBOOK.md` §4.2。

### 2.4 启动宿主机 LLM 三件套

```bash
# 启动后用 ps 确认 8091/8092/8093 都在监听
~/edu-ai/start-llm-server.sh        # Qwen2.5-14B → :8091
~/edu-ai/start-embedding-server.sh  # bge-large-zh → :8092
~/edu-ai/start-reranker-server.sh   # bge-reranker → :8093

# 健康检查
curl -sS http://localhost:8091/v1/models | jq .data[0].id
curl -sS -X POST http://localhost:8092/v1/embeddings \
    -H 'Content-Type: application/json' \
    -d '{"model":"bge-large-zh-v1.5","input":"测试"}' | jq '.data[0].embedding | length'
curl -sS -X POST http://localhost:8093/rerank \
    -H 'Content-Type: application/json' \
    -d '{"model":"bge-reranker-base","query":"q","documents":["a","b"]}' | jq .
```

### 2.5 初始化 Milvus 集合 + 写入种子知识

```bash
# 建立/校验集合（已有集合只校验并加载）
curl --fail -X POST -H "X-Admin-Token: $EDUCARE_ADMIN_TOKEN" http://127.0.0.1:8090/api/v1/rag/initialize
# 导入已审阅的公开语料
python3 scripts/public-rag-corpus.py check
python3 scripts/public-rag-corpus.py apply --expected-sha256 <check 输出的摘要>
```

> 旧的 `init_milvus` / `seed_knowledge` 脚本（含 12 条合成种子知识）已随 Python 服务删除，需要时从标签 `v0.9.0` 取。embedding 端点（8092）不可用时导入返回 503，不再有零向量降级。上述命令在删除后未实跑，待验证。

### 2.5.1 准备 PDF 导出字体（F-1，首次部署）

agent-service 渲染 PDF 时需要中文字体。字体不进 Git，按下面任一方式准备：

```bash
cd backend/agent-service/src/main/resources/fonts
# 方式 A：下载思源黑体 SC Regular（推荐）
curl -L -o NotoSansSC-Regular.ttf \
    https://github.com/notofonts/noto-cjk/raw/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf
# 方式 B：复用系统字体（macOS 示例）
ln -s /System/Library/Fonts/Supplemental/PingFang.ttc NotoSansSC-Regular.ttf
```

也可以用环境变量绕过 classpath：

```bash
export EDUCARE_FONT_PATH=file:/System/Library/Fonts/PingFang.ttc
export EDUCARE_EXPORT_PATH=/var/edu-exports     # 默认 /tmp/edu-exports
```

启动后日志出现 `F-1：中文字体加载就绪 …` 即生效；若是 `未找到中文字体 …，PDF 中文将显示为方块`，说明字体路径不对。

### 2.6 启动 Spring Boot 服务（顺序）

agent-service 依赖 auth/user/student/mental/data 都注册到 Nacos 才能取到画像，先把这些拉起来：

```bash
cd backend
mvn -pl auth-service     spring-boot:run &
mvn -pl user-service     spring-boot:run &
mvn -pl student-service  spring-boot:run &
mvn -pl mental-service   spring-boot:run &
mvn -pl data-service     spring-boot:run &
mvn -pl gateway          spring-boot:run &
# 待 Nacos 控制台显示以上 6 个实例全部 healthy 后：
mvn -pl agent-service    spring-boot:run
```

也可以分多个 terminal / IDE Run 配置。

### 2.7 启动前端

```bash
cd frontend
npm install                  # F-2 后须重新 install（新增 @microsoft/fetch-event-source）
npm run dev                  # http://localhost:5173
```

### 2.8 端到端冒烟

```bash
GATEWAY=http://localhost:8080 STUDENT_ID=1 bash scripts/smoke_test_agent.sh
```

通过后再考虑放开定时扫描（见 §4.1）。

### 2.8.1 MCP 双端冒烟（H-1.4）

H-1.4 起新增 `scripts/mcp_smoke_test.sh`，一键回归 student-data(8094) + knowledge-rag(8095) 两个 MCP server 的 Streamable HTTP 握手 + `tools/list` + 7 个 tool 各调一次 happy path：

```bash
# 前置：mcp-student-data (8094) + knowledge-rag (8095) 均已启动
bash scripts/mcp_smoke_test.sh

# 或自定义参数：
STUDENT_ID=1 \
    STUDENT_DATA_URL=http://localhost:8094 \
    KNOWLEDGE_RAG_URL=http://localhost:8095 \
    bash scripts/mcp_smoke_test.sh
```

依赖：

- **bash ≥ 4**（mac 默认是 3.2 → `brew install bash`，并显式用 `/opt/homebrew/bin/bash scripts/mcp_smoke_test.sh`）
- `curl`、`jq`（mac 默认装，缺则 `brew install jq`）

预期输出：两端各打印 `▸ 握手` + `▸ tools/list` + 4/3 个 `✓ tool_name → <preview>` 行；末尾 `✓ H-1.4 smoke 全部通过`，退出码 0。

降级（不 fail，仅 ⚠）：

- student-data 4 tool 返回 `null` / `found=false` → student-service / mental-service / mysql 未起；脚本继续推进
- knowledge-rag 3 tool 返回 `fallback=true && count=0` → Milvus / embedding / reranker 未起；脚本继续推进

失败（exit 1）：

- handshake 失败、`tools/list` 缺失指定 tool、`tools/call` 返回 `error` 字段

取代了原 H-1.2 / H-1.3 文档里"手开 `npx @modelcontextprotocol/inspector` 选 Streamable HTTP 一个个点击"的流程；用作 Spring AI / FastMCP / Milvus 升级的回归 baseline。

---

## 3. 关键配置项

### 3.1 agent-service（`application.yml` + Nacos `agent-service.yml`）

| Key | 默认 | 含义 |
|---|---|---|
| `educare.rate-limit.trigger-qps` | 2 | Sentinel 限流阈值 |
| `educare.idempotency.trigger-window-seconds` | 30 | 触发幂等窗口 |
| `educare.schedule.enabled` | false | 是否开启每日定时扫描（E-1） |
| `educare.schedule.cron` | `0 0 2 * * ?` | 扫描 cron（Spring 6 字段格式） |
| `educare.debug.force-risk-level` | 空 | 调试用，强制 high 走全流水线，验完必须 unset |
| `educare.export.storage-path` | `/tmp/edu-exports` | F-1 PDF 落盘目录（启动时自动创建） |
| `educare.export.font-path` | `classpath:/fonts/NotoSansSC-Regular.ttf` | F-1 中文字体路径，可改为 `file:...` |
| `educare.sse.timeout-millis` | 1800000 | F-2 SSE 单连接最大存活（30 分钟，到期前端自动重连） |
| `educare.sse.heartbeat-millis` | 30000 | F-2 心跳间隔，应低于 Nginx/Gateway idle timeout |
| `spring.cloud.openfeign.circuitbreaker.enabled` | true | F-3 熔断/降级开关；关闭后 `@FeignClient(fallbackFactory=)` 失效，下游异常会直接抛 |
| `spring.ai.openai.base-url` | `http://localhost:8091` | 宿主 LLM；容器内会改成 `host.docker.internal:8091` |
| `ai.inference.url` | `http://localhost:8090` | Feign 目标 |

### 3.2 ai-inference-service（环境变量）

| Key | 默认 | 含义 |
|---|---|---|
| `LLM_BASE_URL` | `http://host.docker.internal:8091/v1` | 生成 LLM |
| `LLM_MODEL` | `qwen2.5-14b-instruct-q5_k_m` | |
| `EMBEDDING_BASE_URL` | `http://host.docker.internal:8092/v1` | BGE-large |
| `EMBEDDING_DIM` | 1024 | 必须与模型一致 |
| `RERANKER_BASE_URL` | `http://host.docker.internal:8093` | BGE-reranker |
| `RERANKER_ENABLED` | true | 关掉走单路召回 |
| `MILVUS_HOST` | `milvus-standalone` | |
| `RAG_TOP_K` | 5 | |
| `RAG_RECALL_EXPAND` | 3 | 候选倍数 |
| `EMBEDDING_CACHE_TTL` | 86400 | 24h |
| `RAG_CACHE_TTL` | 3600 | 1h |

环境变量在 `docker/docker-compose.yml` 的 `ai-inference-service.environment` 下统一改。

### 3.3 JWT / 数据源

| Env | 默认 | 来源 |
|---|---|---|
| `JWT_SECRET` | 见 common.yml | 各服务共享 |
| `MYSQL_HOST/USER/PASSWORD` | localhost / edu / edu123456 | docker-compose.yml |
| `REDIS_HOST/PORT/PASSWORD` | localhost / 6379 / (空) | 同上 |
| `NACOS_SERVER` | localhost:8848 | 同上 |

---

## 4. 日常运维操作

### 4.1 开关定时扫描

**通过 Nacos**（推荐，不需重启）：
1. Nacos 控制台 → 配置管理 → `agent-service.yml`。
2. 改 `educare.schedule.enabled: true`，可顺手调 `educare.schedule.cron`。
3. 推送后下一次 cron 命中即生效（`Environment.getProperty` 实时取值）。

**通过环境变量**（重启）：
```bash
EDUCARE_SCHEDULE_ENABLED=true mvn -pl agent-service spring-boot:run
```

### 4.2 RAG 知识库重建

知识库 / 向量字段任何变更后：
Java knowledge-service 不提供删除集合的接口（初始化只校验已有集合，维度不匹配时拒绝）。重建步骤：

1. §2.1 的基础设施命令不包含 Attu，先在仓库根目录启动它：

   ```bash
   docker compose -f docker/docker-compose.yml up -d attu
   ```

2. 在 Attu（`http://localhost:8000`）里手工删除旧集合；这一步不可恢复，先确认有语料来源可重灌。
3. 清掉被删集合的导入幂等记录和检索结果缓存。导入接口按 `集合 + doc_id` 记录内容摘要（默认保留 7 天），摘要相同会直接返回 `skipped=true` 而不写向量；`/initialize` 不会清它，不做这一步重灌可能"成功"但集合是空的。只删下面两类键，不要整段删 `edu:rag:*`（里面还有写锁和集合修订号）：

   ```bash
   # 每个被删的集合各执行一次（示例为 edu_cases）；设置了 REDIS_PASSWORD 时给 redis-cli 加 -a
   docker compose -f docker/docker-compose.yml exec redis sh -c \
       "redis-cli --scan --pattern 'edu:rag:upsert:java:v1:edu_cases:*' | xargs -r redis-cli del"
   # 检索结果缓存
   docker compose -f docker/docker-compose.yml exec redis sh -c \
       "redis-cli --scan --pattern 'edu:rag:java:v1:*' | xargs -r redis-cli del"
   ```

4. 调用 `POST /api/v1/rag/initialize` 重新建集合，再按 §2.5 导入语料。
5. 验证：导入响应里 `skipped` 为 `false` 且 `chunks_written` 大于 0；在 Attu 里核对集合行数；再用 `POST /api/v1/rag/retrieve`（带 `X-Internal-Token`）或 MCP 的 search 工具实际检索到刚导入的文档。

> 以上重建步骤是对照 `RagService` 的键名写的，未实跑，待验证。

### 4.3 缓存清理

```bash
# 清 RAG / Embedding 缓存（保守，按前缀）
docker exec edu-redis redis-cli --scan --pattern 'edu:emb:*' | xargs -r docker exec -i edu-redis redis-cli del
docker exec edu-redis redis-cli --scan --pattern 'edu:rag:*' | xargs -r docker exec -i edu-redis redis-cli del
```

### 4.4 RAG 检索效果评估（E-2）

原评估脚本 `scripts.eval_retrieval` 属于已删除的 Python 服务，仓库内暂无 Java 版替代；需要复跑历史基线时从标签 `v0.9.0` 取。

阈值参考：Recall@5 ≥ 0.85、MRR ≥ 0.70 视为可用。低于阈值时优先复查 embedding 服务、Milvus 索引参数。

### 4.5 简化压测（E-3）

```bash
bash scripts/bench_agent.sh --n 10 --timeout 240
# 或显式 ids
STUDENT_IDS="1,2,3,4,5" bash scripts/bench_agent.sh
```

观测：耗时 P99、Redis 命中率；命中率第二轮明显高于首轮即代表缓存生效。

---

## 5. 故障排查

| 症状 | 可能原因 | 排查 |
|---|---|---|
| `agent_service` 启动报 `Unable to find Spring AI ChatClient` | parent POM 没拿到 milestone 仓 | 首次 `mvn -U clean install`；确认 `repo.spring.io/milestone` 可达 |
| `ai-inference` 调 8091 超时 | 宿主 llama.cpp 没起 | `lsof -iTCP:8091`；`~/edu-ai/start-llm-server.sh` |
| 任务停在 RISK_ANALYZING 不动 | LLM 推理卡住 / 槽位满 | `docker logs agent-service`；llama.cpp 日志 `--metrics` |
| RAG 返回空 chunks | Milvus 集合为空或 embedding 全零 | `attu` 查 collection row count；`docker logs ai-inference-service` 找"使用零向量降级" |
| 任务全部 REJECTED | 合规 LLM prompt 过严 / fallback 触发 | 看 `compliance_audit` 字段；fallback 默认强制 false |
| 大量 429 | Sentinel 触发 / 30s 幂等被合并 | 临时调高 `educare.rate-limit.trigger-qps`；线上保留即可 |
| PDF 中文显示为方块 | 字体未就位 | 看 §2.5.1；启动日志搜 `F-1：中文字体` |
| PDF 中文显示为 `#` 且 err_msg 含 `_font is null` / `loadMetrics` NPE | 字体是 OpenType (CFF outlines)，PDFBox 2.0.24 的 subset 抛 `Subsetting of OTF based fonts is not supported`，但即便 `subset=false` 走 `PDCIDFontType0Embedder` 全嵌入，noto-cjk 的 SubsetOTF 在 2.0.24 下仍会静默失败 → 字体注册成 null → 渲染回退 Latin-only → 全是 # | **必须换 TrueType outlines（真 TTF）**。当前打包用 LXGW WenKai Lite（`https://github.com/lxgw/LxgwWenKai-Lite/releases/...LXGWWenKaiLite-Regular.ttf`），存为 `resources/fonts/NotoSansSC-Regular.ttf`（family 名沿用以免改 CSS）。`file` 命令应显示 `TrueType Font data` 而非 `OpenType font data`。换字体后 `mvn clean compile` 强刷 `target/classes` 再 `rm -rf /tmp/edu-exports/.fonts/` 清缓存 |
| PDF 导出停在 PROCESSING | 渲染异常 / 进程被杀 | 查 `agent_export_task.err_msg`；重启 agent-service 后 PROCESSING 不会自动重置（MVP 限制），手动 `UPDATE agent_export_task SET status='FAILED' WHERE status='PROCESSING'` 或重新触发 |
| SSE 状态条始终显示「轮询中」 | 后端订阅未启动 / Gateway 缓冲了 SSE / token 失败 | 启动日志搜 `F-2：订阅 Redis 通道`；浏览器 DevTools Network → `warning/stream` 确认 200 + `text/event-stream`；CLI 调试 `curl -N -H "Authorization: Bearer $TOKEN" http://localhost:8080/agent/api/v1/warning/stream` |
| SSE 收不到事件但轮询正常 | Redis Pub/Sub 没起作用 | `docker exec edu-redis redis-cli SUBSCRIBE edu:agent:warning:new` 与触发分析并行观察；若 CLI 收得到而前端收不到 → 看 agent-service 日志「F-2：分发事件」 |
| 任务 RISK_ANALYZING 阶段秒级失败、画像里只剩 `studentId` | 下游 student/mental/data 全挂；Feign FallbackFactory 接管返回降级数据 | agent-service 日志搜 `[fallback]`，找出哪个下游真实异常并修复；若需排查 fallback 是否被加载，临时改 `spring.cloud.openfeign.circuitbreaker.enabled=false`，异常会直接抛栈帧 |
| 容器内 Redis 命中率 0 | Redis 未连接 | `docker logs ai-inference-service` 搜 "redis"；检查 `REDIS_HOST` |
| 定时扫描没触发 | `educare.schedule.enabled=false` 或锁未释放 | Nacos 改 true；检查 `edu:agent:schedule:daily-scan` 是否残留 |

释放残留锁（极端情况）：
```bash
docker exec edu-redis redis-cli del edu:agent:schedule:daily-scan
docker exec edu-redis redis-cli --scan --pattern 'agent:task:lock:*' | xargs -r docker exec -i edu-redis redis-cli del
```

---

## 6. 备份与停服

### 6.1 优雅停服

```bash
# Spring Boot：Ctrl+C / kill <pid>，asyncExecutor 等待 60s
# 容器：
docker compose -f docker/docker-compose.yml stop ai-inference-service
docker compose -f docker/docker-compose.yml stop                # 全量
```

### 6.2 数据卷与备份

| 卷 | 容器 | 内容 |
|---|---|---|
| `edu-mysql-data` | mysql | 业务数据库 |
| `edu-milvus-data` | milvus-standalone | 向量索引 |
| `edu-minio-data` | minio | Milvus 对象存储 |
| `edu-redis-data` | redis | （未启用持久化默认 inmem） |

```bash
# MySQL 逻辑备份
docker exec edu-mysql sh -c 'mysqldump -uroot -proot edu_portrait' > backup_$(date +%F).sql
```

`docker-compose down -v` 会**抹掉**卷，数据库 / 向量库都会清空 — 仅在重置环境时执行。

---

## 7. 升级要点

- **更换 LLM 模型**：改 `LLM_MODEL` 与 `~/edu-ai/start-llm-server.sh` 的模型路径，无需改代码。
- **更换 Embedding 模型**：必须同步更新 `EMBEDDING_DIM`（Milvus 集合 schema 也要重建）；走 §4.2 流程。
- **新增 Milvus 集合**：集合名由 Java `knowledge-service` 的 `RagService` 从 `MILVUS_COLLECTION_*` 环境变量读取，新增集合需要改这里的代码与对应 MCP 工具 → 再调用 `/api/v1/rag/initialize`。
- **Spring AI 升级**：1.0.0-M6 → 正式版后，仍在 `repo.spring.io/milestone` 拉则继续保留仓库声明；切到中央仓时移除即可。H-1.1.6（2026-05-20）已升到 **1.1.6 GA**（Maven Central），同步把 MCP server 传输从 SSE 切到 **Streamable HTTP**（`spring.ai.mcp.server.protocol=STREAMABLE`，单端点 `/mcp`）。1.0→1.1 主线 API 完全兼容。
- **新增 MCP server**（以下为 H-1.3 当时的 Python 实现记录，现由 Java knowledge-service 的 `KnowledgeTools` 提供）：Python 侧（H-1.3）落地后新增依赖 `fastmcp>=2.3,<3.0`，独立进程跑端口 8095，传输 Streamable HTTP 单端点 `/mcp`。环境变量可调 `MCP_KNOWLEDGE_RAG_PORT` / `MCP_KNOWLEDGE_RAG_PATH` / `MCP_KNOWLEDGE_RAG_HOST`；Milvus / embedding / reranker / Redis 沿用既有 `Settings`，零新增连接配置。
