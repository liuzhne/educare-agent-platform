<div align="center">

# EduCare Agent Platform

**学生风险预警 Agent：读学生数据、查政策与案例、写干预方案；中高风险必须过合规审核才放行。**

[![backend-ci](https://github.com/liuzhne/educare-agent-platform/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/liuzhne/educare-agent-platform/actions/workflows/backend-ci.yml)
[![frontend-ci](https://github.com/liuzhne/educare-agent-platform/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/liuzhne/educare-agent-platform/actions/workflows/frontend-ci.yml)
[![eval-gate](https://github.com/liuzhne/educare-agent-platform/actions/workflows/eval-gate.yml/badge.svg)](https://github.com/liuzhne/educare-agent-platform/actions/workflows/eval-gate.yml)
<!-- 补上 LICENSE 文件后再加 license 徽章 -->

[快速开始](#快速开始) · [工作原理](#工作原理) · [文档](#文档)
<!-- 演示站确认可用时再放：· [在线演示](https://edu-portrait-frontend.onrender.com/) -->

</div>

<!-- 这里放一张“风险画像 + 干预方案”页面截图或 GIF -->

老师发现一个学生成绩下滑、缺勤变多时，需要的不是一个分数，而是一份说得清依据的风险判断和可执行的干预建议。EduCare 用一个 ReAct Agent 把学生档案、心理测评和学校知识库串起来，生成这份报告，并在高风险结论落地前强制加一道审核。

- **Agent，而不是流水线**：AgentLoop 最多 8 轮 ReAct，按需调用 7 个 MCP 工具（4 个只读学生数据工具、3 个知识检索工具）。
- **有出处的建议**：政策、案例与心理学资料走 Milvus 向量检索 + 重排。
- **合规闸门**：MEDIUM / HIGH 结论必须经审核服务放行，审核出错不会静默通过。
- **默认安全**：网关 JWT、服务间内部凭证、越权（IDOR）守卫、字段级权限、送模前脱敏。
- **用评测守住质量**：50 例标注集离线评测；配置模型端点后，CI 的 eval-gate 要求风险等级一致率不低于 0.85。
- **完整业务底座**：师生档案、成绩考勤、心理问卷、数据看板，开箱可用。

## 快速开始

需要 JDK 21、Maven、Node.js 18+、Docker、Python 3.10+。

```bash
docker compose -f docker/docker-compose.yml up -d mysql redis nacos milvus-standalone etcd minio ai-inference-service
cd backend && mvn clean install -DskipTests     # 再按 RUNBOOK §4.4 依次启动各服务
cd frontend && npm install && npm run dev        # http://localhost:5173
```

默认账号 `admin` / `teacher` / `student`，密码同用户名（生产务必改密）。AI 功能需要一个 OpenAI 兼容的模型端点，配置与知识库初始化见 [RUNBOOK](RUNBOOK.md)。

## 工作原理

```text
触发任务 ─► AgentLoop（ReAct，≤8 轮）
              ├─ MCP 学生数据：档案 · 成绩 · 心理指标 · 考勤
              └─ MCP 知识检索：案例 · 政策 · 心理学（Milvus + Reranker）
                        ▼
            风险画像 + 干预方案（结构校验）
                        ▼
      NONE / LOW 直接完成 · MEDIUM / HIGH ─► 合规审核 ─► 放行 / 驳回
```

技术栈：Vue 3 · Spring Boot 3 / Spring Cloud / Spring AI · FastAPI · Milvus · MySQL · Redis · Nacos。

## 文档

| | |
|---|---|
| [架构](ARCHITECTURE.md) | 服务划分、端口、调用链 |
| [设计决策](DECISIONS.md) | 每个取舍选了什么、放弃了什么 |
| [运行手册](RUNBOOK.md) | 启动顺序、健康检查、排错 |
| [部署](docs/DEPLOY.md) | 生产、监控与云端演示 |
| [MCP 设计](docs/educare/MCP_DESIGN.md) | 工具定义与权限边界 |
| [字段权限](docs/educare/FIELD_PERMISSION.md) | 按角色过滤敏感字段 |
| [评测](eval/README.md) | 数据集、指标与 CI 门禁 |
| [执行计划](docs/educare/EXECUTION_PLAN.md) | EduCare 任务进度与下一步 |
