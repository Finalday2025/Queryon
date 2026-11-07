# Queryon

练习 vibe coding 的开源智能 BI 项目：以**语义层／指标中心 + 可信护栏 + 全链路治理**为地基，做自然语言问数，而不是让模型直接猜 SQL。

> 本文件是仓库根 README，面向开源读者。规划调研分册、决策台账与 4 份开发执行交付文档仅保留在本地工作区，**不随开源仓库发布**；本文件是公开仓库中唯一的项目说明文档。

## 当前状态

`P0` 可信问数闭环已实现并通过评测：`M1a` 的后端部分（语义层、四道护栏、权限随人、混合解析、REST API、24 条 golden set、纠正样本回流）与 `M1b` 的完整前端界面。

已落地：

- **语义层／指标中心**：5 张示例表的语义模型、中文业务名与同义词、4 个受管指标的唯一认证口径、批准的星型连接路径。
- **四道护栏**：解析门（JSqlParser AST 白名单，严禁用正则判断语句类型）→ Schema 门 → 语义门 → 执行门；顺序执行、任一不过即终止并返回含失败原因与可操作提示的结构化错误。
- **权限随人**：行列级过滤由系统在语义查询层注入，不写进生成侧提示词；敏感列请求直接结构化拒绝。
- **内置电商假数据集**：运行时由 Java 在内存中生成，覆盖连续 12 个自然月并跨年边界，全部为假数据；`queryon-backend/bootstrap/src/main/resources/init/` 另提供经授权的 PostgreSQL 初始化脚本（建库／建表／模拟数据），供后续持久化接入使用，脚本不含任何凭据。
- **问数主链路**：Schema Linking、规则＋可插拔模型端口的混合解析、歧义澄清、多轮上下文、结果表／图表数据／指标口径／SQL 快照／全链路追踪。
- **统一 REST API**：问数、SQL 编辑器、仪表板与开放 API 共用同一语义层、同一查询接口与同一套治理横切。
- **评测**：24 条 golden eval set 一键回归，输出可重复生成的版本报告；`mvn test` 即测试套件。发布用 `mvn -Prelease clean verify`：强制不跳过测试，评测不过即构建失败，并把确定性报告写入 `evaluation/target/evaluation-report.txt` 供归档；`POST /api/evaluations/run` 在存在失败样本时返回 409。
- **反馈闭环**：人工纠正样本回流后可直接进入下一次 golden set 回归，既有 24 条样本的语义与断言不改。
- **审计与可观测**：护栏拦截留记录，全入口可观测统计与按 `traceId` 溯源；不记录 prompt／completion 内容与任何凭据。
- **简单报表与交叉表**：结果表／报表／交叉表／图表统一显示语义层中文业务名与单位；交叉表支持多度量并分别总计（不混算不同单位）；标准视图可点击维度单元格按语义层下钻路径逐层细化；一次取数可保存为数据集供报表复用，按提问者身份隔离且不重新查询。
- **语义翻译、可观测与数据画像**：同一语义查询可按 PG／MYSQL 方言翻译并回归（PG 快照保持历史一致），所有取数入口共用可观测埋点，语义层受管字段支持数据预览与值画像。
- **仪表板与审计台账**：仪表板在全局时间窗下展示受治理卡片，与问数、SQL 编辑器同口径（`02` 第 6.3 节判据 2 的第三条路径）；审计台账可检索成功、拦截、澄清与订阅执行事件。
- **订阅、导出与数据解读**：报表支持 CSV／Excel／PDF／图表 PNG 导出与打印；订阅台账按身份隔离、可立即执行并留痕；问数结果附确定性数据解读与可一键追问的推荐问题。
- **数据源与容器化**：`/api/datasources` 提供数据源与元数据同步；根 `Dockerfile` 多阶段构建后端镜像，`docker-compose.yml` 编排 PostgreSQL、Redis 与应用，凭据只由环境变量注入。
- **异步、分享与可观测**：异步长查询可提交／轮询／取消并按身份隔离；分享链接按创建者身份复用、不扩大权限；订阅支持进程内周期调度；`/api/observability/cache` 提供 Redis 命中率与降级计数，仪表板可视化；问数页提供语义层术语联想补全。

当前未启用：真实模型端点（具体模型名、额度与限流策略属未决项，触发前不拍板）、DashScope starter、Spring Security／OIDC、PostgreSQL 持久化与 Testcontainers、Micrometer／OpenTelemetry 导出器、**定时订阅推送**（调度归属待 `05` 第 4 节再确认）、异步长查询任务化。模型路径默认走离线确定性实现，**不需要任何密钥即可跑通**。`QUERYON_PROFILE=dev` 时按 `application-dev.yml`（已 gitignore）连接 PostgreSQL 取数并用 Redis 缓存查询结果；默认 profile 与全部测试仍走内存示例数据集，保证 `mvn test` 离线可重复。企业版能力（多租户、AI 访问控制与用量配额、对象级权限、Calcite 翻译引擎、Redis／Quartz 调度等）按版本规划留在 `M3`／`M4`，社区版 `M2` 不引入。

根 `LICENSE` 按 Apache-2.0 在首次发布前添加。

## 技术栈

Java 21 LTS、Spring Boot 3.5.x、Spring AI 1.1.x + Spring AI Alibaba DashScope starter、阿里云百炼、PostgreSQL + pgvector、Vue 3 + TypeScript + Element Plus + ECharts，Maven 多模块构建。

> 版本号为核查日快照。`Boot 3.5.x ↔ Spring AI 1.1.x ↔ Spring AI Alibaba starter` 必须**成对调整**；Spring AI 2.0 需 Boot 4.x，属未来迁移路径，不是当前选型。

## 本地起步

1. 安装 JDK 21 与 Maven（前端另需 Node.js）。
2. 后端：`cd queryon-backend && mvn clean verify`。构建会运行护栏单测、24 条 golden set 回归、发布门禁与 REST 集成测试；发布时改用 `mvn -Prelease clean verify`（强制不跳过测试）。
3. 启动：`java -jar bootstrap/target/queryon-bootstrap-0.1.0-SNAPSHOT.jar`，默认端口 `8080`。
4. 前端骨架：`cd queryon-frontend && npm install && npm run dev`；首次安装生成的 lockfile 需入库。
5. 数据库编排 `docker compose up -d postgres` 为后续持久化准备，当前闭环不依赖它；需要时通过环境变量提供 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`，可选 `POSTGRES_PORT`。示例库表可按 `queryon-backend/bootstrap/src/main/resources/init/` 下 `01_create_database.sql` → `02_create_tables.sql` → `03_sample_data.sql` 顺序初始化（脚本不含凭据，连接参数由环境变量提供）。
6. 容器化运行（可选）：`docker build -t queryon .`，再以 `docker run -p 8080:8080 -e DASHSCOPE_API_KEY queryon` 从宿主环境注入变量；镜像内不含任何凭据。

> **不要创建或提交 `.env`／`application-local.*`**，凭据不写入仓库。模型密钥只在需要接入真实模型端点时以环境变量 `DASHSCOPE_API_KEY` 提供；`application.yml` 只保留 `${...}` 占位符，没有任何字面量或默认值。

## 前端界面

`cd queryon-frontend && npm install && npm run dev`，访问 `http://localhost:5173`（Vite 已将 `/api` 代理到 `8080`）。左侧导航分「分析工作台」与「治理与可信」两组、共十个页面：**智能问数、统一查询、报表、仪表板、多维分析、可视化分析**；**口径中心、评测与回归、反馈闭环、审计台账**。仪表板提供全局时间窗与同口径卡片；多维分析工作台独立提供切片／切块、行列维交换、逐层下钻与数据集复用；可视化分析工作台支持柱／线／饼图切换并一次取数按度量生成多图。统一查询页仍保留「标准视图／交叉表」切换与维度下钻；报表页支持中文列名、分列合计、CSV／Excel／PDF 导出、打印与订阅。顶部身份可在 `admin_bi`（全量）与 `analyst_east`（华东·敏感列屏蔽）之间切换，切换后同一问句的数据范围按行列级权限即时变化。

## 试一次问数

```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -H "X-Queryon-User: admin_bi" \
  -d "{\"question\":\"上月净营收是多少\",\"sessionId\":\"demo\"}"
```

`X-Queryon-User` 取 `admin_bi`（全量）或 `analyst_east`（行级华东、敏感列屏蔽）；更换身份后同一问句的数据范围按行列级权限正确变化。其他入口：

| 用途 | 方法 | 路径 |
| --- | --- | --- |
| 统一查询（SQL 编辑器／仪表板／开放 API） | `POST` | `/api/queries` |
| 指标、维度、表与批准关联路径 | `GET` | `/api/semantic/metrics` 等 |
| 中文列名、指标单位与维度下钻路径 | `GET` | `/api/semantic/display` |
| 数据预览与值画像（受管字段） | `GET` | `/api/semantic/profile` |
| 方言清单与按方言语义查询翻译 | `GET`／`POST` | `/api/semantic/dialects`、`/api/semantic/translate` |
| 仪表板卡片（与统一查询同口径） | `GET` | `/api/dashboard` |
| 数据集保存与复用（按身份隔离） | `POST`／`GET` | `/api/datasets` |
| 订阅台账、立即执行与周期调度 | `GET`／`POST`／`DELETE` | `/api/subscriptions`、`/api/subscriptions/{id}/run`、`/api/subscriptions/{id}/schedule` |
| 异步长查询（提交／轮询／取消） | `POST`／`GET`／`DELETE` | `/api/queries/async` |
| 分享链接 | `GET`／`POST`／`DELETE` | `/api/shares` |
| 缓存命中率 | `GET` | `/api/observability/cache` |
| 内置数据源与元数据同步 | `GET`／`POST` | `/api/datasources`、`/api/datasources/sync` |
| 审计台账 | `GET` | `/api/audit/events` |
| 可观测统计与链路溯源 | `GET` | `/api/observability/metrics`、`/api/observability/traces/{traceId}` |
| 一键回归 24 条样本 | `POST` | `/api/evaluations/run` |
| 可重复生成的评测报告 | `GET` | `/api/evaluations/report` |
| 纠正样本回流 | `POST`／`GET` | `/api/feedback` |

写操作、不存在字段与越权访问会返回结构化拒绝且不执行；24 条样本及其断言见 `queryon-backend/evaluation` 的 golden set。

> 依赖可行性验证中的百炼调用与 PG 端口连通仍按阶段计划暂缓；本次未联网核实模型端点、未起容器、未调用模型。
