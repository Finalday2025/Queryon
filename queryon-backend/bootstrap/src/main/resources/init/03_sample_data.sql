-- Queryon 示例数据库初始化 · 第 3 步：生成模拟数据
-- 需连接到 queryon_sample 且已执行 02_create_tables.sql。
--
-- 与 queryon-backend/query 的 SampleDataGenerator（05 第 8.6 节）保持同一规格：
--   20 个地区 / 60 个客户 / 80 个商品 / 2000 笔订单 / 5000 条明细；
--   5 个销售大区、6 个品类、3 类客户等级；订单状态 paid／refunded／cancelled 约 80／12／8；
--   刻意植入「运动」品类零销量、退款单、取消单、空值（客户等级每 17 个、优惠金额每 23 个）
--   与重复客户名；日期覆盖连续 12 个自然月并跨年边界，随执行当月滚动，便于「上月」口径回归。
-- 数据全部由确定性取模公式生成（不使用 random()），重复执行结果一致；全部为假数据（Q20、Q25）。
-- 数值断言仍以 golden eval set 的可校验语义约束为准，不固化未经核实的数值 KPI（AGENTS 4）。
-- 本文件的 DDL／DML 属部署初始化阶段；问数链路仍严格只读，任何写操作由四道护栏拒绝（G1、D6）。

TRUNCATE TABLE fact_order_item, fact_order, dim_customer, dim_product, dim_region
    RESTART IDENTITY CASCADE;

-- ---------------------------------------------------------------- 地区（20）
INSERT INTO dim_region (region_id, region_name, province, city, sales_territory)
SELECT g,
       '示例地区-' || g,
       '示例省-' || (((g - 1) % 5) + 1),
       '示例市-' || g,
       (ARRAY['华东', '华北', '华南', '华中', '西南'])[((g - 1) % 5) + 1]
FROM generate_series(1, 20) AS g;

-- ---------------------------------------------------------------- 客户（60）
INSERT INTO dim_customer (customer_id, customer_name, region_id, customer_level,
                          register_date, phone, email)
SELECT g,
       '示例客户-' || ((g + 1) / 2),
       ((g * 7) % 20) + 1,
       CASE WHEN g % 17 = 0 THEN NULL
            ELSE (ARRAY['普通', '银卡', '金卡'])[(g % 3) + 1] END,
       (date_trunc('month', current_date)::date - interval '11 months')::date - ((g * 13) % 300),
       '138-0000-' || lpad(g::text, 4, '0'),
       'user' || g || '@example.invalid'
FROM generate_series(1, 60) AS g;

-- ---------------------------------------------------------------- 商品（80）
INSERT INTO dim_product (product_id, product_name, category, brand, list_price, cost)
SELECT g,
       '示例商品-' || lpad(g::text, 3, '0'),
       (ARRAY['数码', '家居', '服饰', '食品', '图书', '运动'])[(g % 6) + 1],
       (ARRAY['示例品牌甲', '示例品牌乙', '示例品牌丙', '示例品牌丁'])[(g % 4) + 1],
       round((20 + (g * 37) % 980 + 0.5)::numeric, 2),
       round(((20 + (g * 37) % 980 + 0.5) * 0.6)::numeric, 2)
FROM generate_series(1, 80) AS g;

-- ---------------------------------------------------------------- 订单（2000）
WITH bounds AS (
    SELECT (date_trunc('month', current_date)::date - interval '11 months')::date AS start_date,
           (current_date + 1) AS end_date
), base AS (
    SELECT g,
           'ORD-' || lpad(g::text, 5, '0') AS order_id,
           c.customer_id,
           c.region_id,
           b.start_date + ((g * 17) % (b.end_date - b.start_date)) AS order_date,
           CASE WHEN g % 100 < 80 THEN 'paid'
                WHEN g % 100 < 92 THEN 'refunded'
                ELSE 'cancelled' END AS order_status,
           CASE WHEN g % 100 >= 92 THEN 0::numeric
                ELSE round((50 + (g * 23) % 1950 + 0.25)::numeric, 2) END AS pay_amount
    FROM generate_series(1, 2000) AS g
    CROSS JOIN bounds b
    JOIN dim_customer c ON c.customer_id = ((g * 11) % 60) + 1
)
INSERT INTO fact_order (order_id, customer_id, region_id, order_date, order_status,
                        pay_amount, refund_amount, freight_amount, discount_amount)
SELECT order_id, customer_id, region_id, order_date, order_status,
       pay_amount,
       CASE WHEN order_status = 'refunded' THEN round((pay_amount * 0.3)::numeric, 2)
            ELSE 0::numeric END,
       round(((g * 7) % 20)::numeric, 2),
       CASE WHEN g % 23 = 0 THEN NULL ELSE round(((g * 3) % 50)::numeric, 2) END
FROM base;

-- ---------------------------------------------------------------- 订单明细（5000，排除零销量品类）
WITH sellable AS (
    SELECT ARRAY(SELECT product_id FROM dim_product WHERE category <> '运动' ORDER BY product_id) AS ids,
           ARRAY(SELECT list_price FROM dim_product WHERE category <> '运动' ORDER BY product_id) AS prices
), orders AS (
    SELECT ARRAY(SELECT order_id FROM fact_order ORDER BY order_id) AS ids
), base AS (
    SELECT g,
           o.ids[((g * 29) % array_length(o.ids, 1)) + 1] AS order_id,
           s.ids[((g * 13) % array_length(s.ids, 1)) + 1] AS product_id,
           1 + (g % 5) AS quantity,
           round((s.prices[((g * 13) % array_length(s.ids, 1)) + 1] * 0.9)::numeric, 2) AS unit_price
    FROM generate_series(1, 5000) AS g
    CROSS JOIN sellable s
    CROSS JOIN orders o
)
INSERT INTO fact_order_item (item_id, order_id, product_id, quantity, unit_price, item_amount)
SELECT g, order_id, product_id, quantity, unit_price,
       round((unit_price * quantity)::numeric, 2)
FROM base;

ANALYZE dim_region;
ANALYZE dim_customer;
ANALYZE dim_product;
ANALYZE fact_order;
ANALYZE fact_order_item;

-- 初始化结果自检（只读）
SELECT 'dim_region' AS table_name, count(*) AS row_count FROM dim_region
UNION ALL SELECT 'dim_customer', count(*) FROM dim_customer
UNION ALL SELECT 'dim_product', count(*) FROM dim_product
UNION ALL SELECT 'fact_order', count(*) FROM fact_order
UNION ALL SELECT 'fact_order_item', count(*) FROM fact_order_item
ORDER BY table_name;
