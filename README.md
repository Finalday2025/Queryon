# Queryon

练习 vibe coding 的开源智能 BI 项目：以**语义层／指标中心 + 可信护栏 + 全链路治理**为地基，做自然语言问数，而不是让模型直接猜 SQL。

> 本文件是仓库根 README，面向开源读者。规划调研分册（`docs/`）、决策台账与 4 份开发执行交付文档仅保留在本地工作区，**不随开源仓库发布**；本文件是公开仓库中唯一的项目说明文档。

## 当前状态

工程目录骨架已建立，包含后端 Maven 多模块（`common`／`semantic`／`query`／`ai`／`web`／`evaluation`／`bootstrap`）、前端 Vue 3 骨架与本地数据库编排。**尚未实现业务逻辑、初始化脚本、示例数据文件与评测运行器**；这些是 `M1a` 阶段的范围。

根 `LICENSE` 按 Apache-2.0 在首次发布前添加。

## 技术栈

Java 21 LTS、Spring Boot 3.5.x、Spring AI 1.1.x + Spring AI Alibaba DashScope starter、阿里云百炼、PostgreSQL + pgvector、Vue 3 + TypeScript + Element Plus + ECharts，Maven 多模块构建。

> 版本号为核查日快照。`Boot 3.5.x ↔ Spring AI 1.1.x ↔ Spring AI Alibaba starter` 必须**成对调整**；Spring AI 2.0 需 Boot 4.x，属未来迁移路径，不是当前选型。

## 本地起步（规划，未做依赖可行性验证）

1. 安装 JDK 21、Maven、Node.js 与 Docker。
2. 在环境变量中提供 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`；可选 `POSTGRES_PORT`（默认映射 `5432`）。**不要创建或提交 `.env`**，凭据不写入仓库。
3. 启动数据库：`docker compose up -d postgres`。
4. 后端：`cd queryon-backend && mvn clean verify`。
5. 前端：`cd queryon-frontend && npm install && npm run dev`；首次安装生成的 lockfile 需入库。
6. 在系统环境变量中设置 `DASHSCOPE_API_KEY`（值只留在本机，**不要写入仓库、`.env` 或 `application-local.*`**）。后端 `application.yml` 只用 `${DASHSCOPE_API_KEY}` 占位符引用，没有任何字面量或默认值。当前骨架尚未引入 DashScope starter，该配置在 `M1a` 引入依赖并完成依赖可行性验证后才生效，配置前缀届时按官方文档复核；具体模型名与额度策略尚未确定。

> 依赖可行性验证（框架组合编译启动、百炼调用、PG 端口连通）按计划暂缓，本次未跑构建、未起容器、未调用模型。
