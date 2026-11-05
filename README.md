# Queryon

练习 vibe coding 的开源智能 BI 项目：以**语义层／指标中心 + 可信护栏 + 全链路治理**为地基，做自然语言问数，而不是让模型直接猜 SQL。

> 本文件是仓库根 README，面向开源读者，与 `docs/README.md`（调研规划分册索引）不是同一份。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [docs/05-决策记录.md](./docs/05-决策记录.md) | **决策唯一事实源**；每次动手前先读 |
| [docs/README.md](./docs/README.md) | `01`～`05` 分册导航与编号约定 |

冲突一律以 `05` 为准，并同步修正引用处。

> 另有 4 份面向开发执行的本地交付文档（`PRD.md`／`TECH_DESIGN.md`／`AGENTS.md`／`ROADMAP.md`），仅保留在本地工作区供维护者与编码代理查阅，**不随开源仓库发布**（见 `.gitignore`）。本地 clone 后如需查阅，请在项目根目录查看；远程仓库以 `docs/` 分册为准。

## 当前状态

工程目录骨架已建立，包含后端 Maven 多模块（`common`／`semantic`／`query`／`ai`／`web`／`evaluation`／`bootstrap`）、前端 Vue 3 骨架与本地数据库编排。**尚未实现业务逻辑、初始化脚本、示例数据文件与评测运行器**；这些是 `M1a` 的范围，阶段划分见 [docs/03-架构与路线.md](./docs/03-架构与路线.md) 第 8 节。

根 `LICENSE` 按 `Q19`（Apache-2.0）在首次发布前添加，时点属 `05` 第 10 节未决项。

## 本地起步（规划，未做依赖可行性验证）

1. 安装 JDK 21、Maven、Node.js 与 Docker。
2. 在环境变量中提供 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`；可选 `POSTGRES_PORT`（默认映射 `5432`）。**不要创建或提交 `.env`**，凭据不写入仓库。
3. 启动数据库：`docker compose up -d postgres`。
4. 后端：`cd queryon-backend && mvn clean verify`。
5. 前端：`cd queryon-frontend && npm install && npm run dev`；首次安装生成的 lockfile 需入库。
6. 在系统环境变量中设置 `DASHSCOPE_API_KEY`（值只留在本机，**不要写入仓库、`.env` 或 `application-local.*`**）。后端 `application.yml` 只用 `${DASHSCOPE_API_KEY}` 占位符引用，没有任何字面量或默认值。当前骨架尚未引入 DashScope starter，该配置在 `M1a` 引入依赖并完成依赖可行性验证后才生效，配置前缀届时按官方文档复核；具体模型名与额度策略属未决项，见 `05` 第 10 节。

> 版本号为核查日快照，依赖可行性验证（框架组合编译启动、百炼调用、PG 端口连通）按计划暂缓，`Boot 3.5.x ↔ Spring AI 1.1.x ↔ Spring AI Alibaba starter` 必须成对调整。
