-- Queryon 示例数据库初始化 · 第 1 步：创建数据库
-- 方言：PostgreSQL（含 pgvector 镜像，见仓库根 docker-compose.yml）
--
-- 连接参数一律由环境变量提供（POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_PORT），
-- 本脚本及同目录其他脚本均不含任何口令、凭据字面量或真实个人信息。
-- 全部数据为假数据，示例主体、邮箱域为 example.invalid，手机号段为示例号段（Q20、Q25）。
--
-- 执行方式（在 psql 之外也可用任何 PostgreSQL 客户端，按文件顺序执行）：
--   psql -h localhost -p "${POSTGRES_PORT:-5432}" -U "${POSTGRES_USER}" -d postgres -v ON_ERROR_STOP=1 -f 01_create_database.sql
--   psql -h localhost -p "${POSTGRES_PORT:-5432}" -U "${POSTGRES_USER}" -d queryon_sample -v ON_ERROR_STOP=1 -f 02_create_tables.sql
--   psql -h localhost -p "${POSTGRES_PORT:-5432}" -U "${POSTGRES_USER}" -d queryon_sample -v ON_ERROR_STOP=1 -f 03_sample_data.sql
--
-- Windows PowerShell 示例（连接参数取自环境变量，密码由 PGPASSWORD 或 .pgpass 提供，不进仓库）：
--   $env:PGPASSWORD = $env:POSTGRES_PASSWORD
--   psql -h localhost -p $env:POSTGRES_PORT -U $env:POSTGRES_USER -d postgres -v ON_ERROR_STOP=1 -f 01_create_database.sql
--   psql -h localhost -p $env:POSTGRES_PORT -U $env:POSTGRES_USER -d queryon_sample -v ON_ERROR_STOP=1 -f 02_create_tables.sql
--   psql -h localhost -p $env:POSTGRES_PORT -U $env:POSTGRES_USER -d queryon_sample -v ON_ERROR_STOP=1 -f 03_sample_data.sql
--
-- 若已通过 docker-compose.yml 的 POSTGRES_DB 创建同名库，请跳过本文件，直接执行 02、03。
-- 库名为示例名，可按部署环境整体替换 queryon_sample；不在脚本内创建角色或设置口令。

CREATE DATABASE queryon_sample;

-- 可选：后续接入向量检索时再启用 pgvector 扩展；当前 5 张示例表不含向量列，
-- 为避免在普通 PostgreSQL 实例上失败，此处不自动执行：
-- CREATE EXTENSION IF NOT EXISTS vector;
